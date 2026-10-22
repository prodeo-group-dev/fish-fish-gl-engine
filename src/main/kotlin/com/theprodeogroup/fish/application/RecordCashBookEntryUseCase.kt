package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookEntry
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.ModuleOwnedAccounts
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.UseInstead
import com.theprodeogroup.fish.domain.ledger.hasHistoricalEffect
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatLineDirection
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * Money in or money out, recorded in a cash or bank book (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB20 to CB26,
 * Femi 2026-10-10: the books are books of original entry). It builds the existing [CashBookEntry] command and posts
 * it through [PostJournalEntryUseCase], so every posting rule (the open Period, every account in the Company,
 * balanced lines) is the one the rest of the ledger uses, and the entry carries `JournalSource.CASH_BOOK`.
 *
 * **Idempotency is the entry id.** The caller derives [Request.entryId] from the Idempotency-Key (and the Tenant,
 * Company, book and direction), and the entry is stored only if no entry has that id
 * ([PostJournalEntryUseCase.executeOnce]). So:
 * - a retry of the same request finds the entry already there and gets a [Result.Replay], posting nothing;
 * - two simultaneous identical requests cannot both post, because the database lets only one insert win;
 * - the same key with a different request is [Result.KeyReused];
 * - a refused request stores nothing, so the key is not spent: after the cause is fixed the same key posts.
 * An existing entry is checked before any other validation, so a replay still answers after, say, the counter
 * account has since been deactivated.
 *
 * The posting goes into the Company's open Period (the first one that allows posting, as the sales, purchase and
 * payroll posting contexts choose it); the entry [Request.date] is not checked against the Period's dates, as for
 * every other posting route today (see docs/GL_Period_Management_Scoping.md).
 *
 * **VAT (GL-FR-TAX-8, Femi 2026-10-22, option A).** A request may carry [Request.vat], a category tag and an amount that is
 * already inside [Request.amount]. GL does not look up or check a rate (the caller worked it out from the rate it read); it posts
 * the net to the counter account, the VAT to the Company's VAT control account and the gross to the book. A receipt must name an
 * income account and a payment an expense account. A VAT of zero posts no VAT line: it is the plain two-line entry.
 *
 * A payment that takes the book below zero still posts, with a [Warning]: a cash book below zero means more
 * spending was recorded than cash was, a bank book below zero is an overdraft. Nothing is ever silently overdrawn.
 */
class RecordCashBookEntryUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val periodRepository: PeriodRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val postJournalEntryUseCase: PostJournalEntryUseCase
) {
    data class Request(
        val companyId: CompanyId,
        val bookAccountId: AccountId,
        val direction: CashDirection,
        val amount: BigDecimal,
        val date: LocalDate,
        val counterAccountId: AccountId,
        val description: String?,
        val cashFlowActivity: CashFlowActivity,
        val entryId: JournalEntryId,
        val vat: Vat? = null,
        /** True only from [RecordVatSettlementUseCase]: the counter account must be the VAT control account, which the ordinary doors refuse (GL-T-30). */
        val settlesVat: Boolean = false
    ) {
        /** The VAT inside the gross amount: the category it is tagged with for the VAT return, and how much. */
        data class Vat(val category: VatCategory, val amount: BigDecimal)
    }

    data class Warning(val code: String, val accountId: AccountId, val balanceAfter: Money)

    sealed class Result {
        data class Posted(val entry: JournalEntry, val balanceAfter: Money, val warnings: List<Warning>) : Result()

        /** An identical request was already recorded under this key; nothing was posted. */
        data class Replay(val entry: JournalEntry, val balanceAfter: Money) : Result()

        /** The key was already used for a different request. */
        data object KeyReused : Result()
        data object CompanyNotFound : Result()
        data object AccountNotFound : Result()
        data object NotACashBook : Result()
        data object CounterAccountNotFound : Result()
        data object CounterAccountInactive : Result()
        data class CounterAccountNotAllowed(val useInstead: UseInstead) : Result()
        data object InvalidAmount : Result()
        data object NoOpenPeriod : Result()

        /** VAT is not less than the amount, so the net would be nothing. */
        data object VatExceedsAmount : Result()

        /** VAT was sent for a counter account that is not income (receipt) or expense (payment). */
        data object VatNotApplicable : Result()

        /** VAT was sent and the Company has no VAT control account. */
        data object VatControlAccountNotConfigured : Result()
    }

    fun execute(request: Request): Result {
        val company = companyRepository.findById(request.companyId) ?: return Result.CompanyNotFound
        val book = accountRepository.findById(request.bookAccountId)?.takeIf { it.companyId == request.companyId }
            ?: return Result.AccountNotFound
        if (book.cashBookKind == null) return Result.NotACashBook
        val currency = company.baseCurrency

        // A retry is answered from the entry that is already there, before anything else is checked.
        journalEntryRepository.findById(request.entryId)?.let { return replayOrReuse(it, request, book, currency) }

        if (request.counterAccountId == book.id) return Result.CounterAccountNotAllowed(UseInstead.TRANSFER)
        val counter = accountRepository.findById(request.counterAccountId)?.takeIf { it.companyId == request.companyId }
            ?: return Result.CounterAccountNotFound
        if (!counter.active) return Result.CounterAccountInactive
        if (request.settlesVat) {
            // The one door that may post to the VAT control account from a book: the line is a payment of VAT, not VAT.
            if (counter.type != AccountType.LIABILITY || counter.code != ChartOfAccountsTemplate.VAT_CONTROL_ACCOUNT_CODE) {
                return Result.CounterAccountNotAllowed(UseInstead.VAT)
            }
        } else {
            ModuleOwnedAccounts.useInstead(counter, company.clientType)?.let { return Result.CounterAccountNotAllowed(it) }
        }

        if (request.amount.signum() <= 0 || request.amount.stripTrailingZeros().scale() > currency.defaultFractionDigits) {
            return Result.InvalidAmount
        }
        val amount = Money(request.amount.setScale(currency.defaultFractionDigits), currency)

        var vatLine: CashBookEntry.VatLine? = null
        request.vat?.let { vat ->
            if (vat.amount.signum() < 0 || vat.amount.stripTrailingZeros().scale() > currency.defaultFractionDigits) return Result.InvalidAmount
            val vatMoney = Money(vat.amount.setScale(currency.defaultFractionDigits), currency)
            if (vatMoney >= amount) return Result.VatExceedsAmount
            val expectedType = if (request.direction == CashDirection.RECEIVED) AccountType.REVENUE else AccountType.EXPENSE
            if (counter.type != expectedType) return Result.VatNotApplicable
            if (vatMoney.amount.signum() > 0) {
                val control = accountRepository.findAllByCompany(request.companyId)
                    .firstOrNull { it.type == AccountType.LIABILITY && it.code == ChartOfAccountsTemplate.VAT_CONTROL_ACCOUNT_CODE }
                    ?: return Result.VatControlAccountNotConfigured
                val direction = if (request.direction == CashDirection.RECEIVED) VatLineDirection.OUTPUT else VatLineDirection.INPUT
                vatLine = CashBookEntry.VatLine(
                    control.id, vatMoney,
                    mapOf(DimensionType.VAT_CATEGORY to vat.category.name, DimensionType.VAT_DIRECTION to direction.name)
                )
            }
        }

        val period = periodRepository.findAllByCompany(request.companyId).firstOrNull { it.allowsPosting() }
            ?: return Result.NoOpenPeriod

        val draft = CashBookEntry(
            accountId = book.id, direction = request.direction, amount = amount, counterAccountId = counter.id,
            date = request.date, cashFlowActivity = request.cashFlowActivity, description = request.description,
            vatLine = vatLine,
            counterDimensions = if (request.settlesVat) mapOf(DimensionType.VAT_DIRECTION to VatLineDirection.SETTLEMENT.name) else emptyMap()
        ).toJournalEntry(period.id, request.entryId)

        return when (
            val outcome = postJournalEntryUseCase.executeOnce(
                PostJournalEntryUseCase.Request(request.companyId, period.id, request.date, draft.lines, JournalSource.CASH_BOOK, request.description),
                request.entryId
            )
        ) {
            is PostJournalEntryUseCase.OnceResult.Posted -> {
                val balanceAfter = balanceOf(book, currency)
                Result.Posted(outcome.entry, balanceAfter, warningsFor(book, request.direction, balanceAfter))
            }
            // Lost the race to an identical request that posted first.
            is PostJournalEntryUseCase.OnceResult.AlreadyExists -> replayOrReuse(outcome.entry, request, book, currency)
            is PostJournalEntryUseCase.OnceResult.Refused -> when (outcome.result) {
                is PostJournalEntryResult.PeriodNotFound, is PostJournalEntryResult.PeriodNotOpen -> Result.NoOpenPeriod
                is PostJournalEntryResult.AccountNotFound -> Result.CounterAccountNotFound
                is PostJournalEntryResult.InvalidLines -> Result.InvalidAmount
                is PostJournalEntryResult.Success -> error("executeOnce never refuses with a Success")
            }
        }
    }

    private fun replayOrReuse(existing: JournalEntry, request: Request, book: Account, currency: Currency): Result {
        val sameRequest = existing.matches(request, currency)
        return if (sameRequest) Result.Replay(existing, balanceOf(book, currency)) else Result.KeyReused
    }

    /** Whether this existing entry is exactly what [request] would have posted: same book line, counter line, date and description. */
    private fun JournalEntry.matches(request: Request, currency: Currency): Boolean {
        val bookSide = if (request.direction == CashDirection.RECEIVED) TransactionSide.DEBIT else TransactionSide.CREDIT
        val bookLine = lines.singleOrNull { it.accountId == request.bookAccountId } ?: return false
        val counterLine = lines.singleOrNull { it.accountId == request.counterAccountId } ?: return false
        // A VAT of zero posts no VAT line, so it is the same entry as no VAT at all.
        val vatAmount = request.vat?.amount?.takeIf { it.signum() > 0 }
        val vatLineMatches = if (vatAmount == null) lines.size == 2 else {
            val vatLine = lines.firstOrNull { it.accountId != request.bookAccountId && it.accountId != request.counterAccountId }
            lines.size == 3 && vatLine != null && vatLine.side == bookSide.opposite() && vatLine.amount.amount.compareTo(vatAmount) == 0 &&
                vatLine.dimensions[DimensionType.VAT_CATEGORY] == request.vat!!.category.name
        }
        val counterAmount = if (vatAmount == null) request.amount else request.amount - vatAmount
        return vatLineMatches &&
            bookLine.side == bookSide && bookLine.amount.amount.compareTo(request.amount) == 0 && bookLine.amount.currency == currency &&
            counterLine.side == bookSide.opposite() && counterLine.amount.amount.compareTo(counterAmount) == 0 &&
            date == request.date &&
            (description?.takeIf { it.isNotBlank() }) == (request.description?.takeIf { it.isNotBlank() })
    }

    private fun balanceOf(book: Account, currency: Currency): Money = journalEntryRepository.balanceOfBook(book, currency)

    private fun warningsFor(book: Account, direction: CashDirection, balanceAfter: Money): List<Warning> {
        if (direction != CashDirection.PAID || balanceAfter.amount.signum() >= 0) return emptyList()
        val code = if (book.cashBookKind == CashBookKind.BANK) "bank_overdrawn" else "cash_below_zero"
        return listOf(Warning(code, book.id, balanceAfter))
    }
}
