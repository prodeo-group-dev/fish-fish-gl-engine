package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.findOwnedBy
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/**
 * What a payroll journal line is, which decides the accounts it may post to (the same resolver rule as purchase and sale
 * lines): [EXPENSE] an earning or an employer cost (an expense account), [LIABILITY] net pay, PAYE, social insurance,
 * pension, deductions or accrued leave payable (a liability account, never a control or module-owned one), [SETTLEMENT]
 * the cash or bank side of a payment (one of the Company's cash or bank books, never Suspense).
 */
enum class PayRunJournalKind { EXPENSE, LIABILITY, SETTLEMENT }

sealed class RecordPayRunJournalResult {
    data class Success(val journalEntry: JournalEntry, val events: List<DomainEvent>, val total: Money) : RecordPayRunJournalResult()

    /** No lines, a non-positive amount, mixed currencies, or a blank or over-long element. */
    data class InvalidLines(val reason: String) : RecordPayRunJournalResult()
    data class Unbalanced(val debits: Money, val credits: Money) : RecordPayRunJournalResult()
    data object PeriodNotFound : RecordPayRunJournalResult()
    data object PeriodNotOpen : RecordPayRunJournalResult()
    data class LineAccountNotFound(val accountId: AccountId) : RecordPayRunJournalResult()
    data class LineAccountNotAllowed(val accountId: AccountId, val reason: LineAccountResolver.Reason) : RecordPayRunJournalResult()
    data object SuspenseAccountNotConfigured : RecordPayRunJournalResult()

    /** A settlement line names no account: the cash or bank side of a payment is never defaulted to Suspense. */
    data object SettlementAccountRequired : RecordPayRunJournalResult()
}

/**
 * The *Record pay run journal* thin posting interface (Femi, 2026-10-19: Payroll becomes its own service; the line list
 * is the one HR and GL agreed on 2026-10-18, HR's `docs/Payroll_Subledger_And_Posting_Accounts_Move2_Note.md` section 9):
 * one balanced journal made of lines the caller builds, each line posting to an account from the Company's **own** chart.
 * It serves the liability-first pay run (debit each earning and employer cost, credit net pay payable, PAYE, social
 * insurance, pension and deductions payable), the disbursement (debit net pay payable, credit the bank book) and a
 * remittance (debit the statutory payable, credit the bank book).
 *
 * Each line carries a [Line.kind] (which accounts it may use), a [Line.side], an amount, an optional account and an
 * **opaque** [Line.element] code that GL stores on the journal line as an audit tag (`PAYROLL_ELEMENT`) and never
 * validates against a list: GL does not learn Payroll's catalogue. A line with no account goes to the Company's
 * Suspense account (Femi 2026-10-18), except a settlement line, which must name a cash or bank book. Lines are posted
 * as given (aggregated by the caller by account, never by employee). The generic journal route stays free-form; this
 * is the service door with the account rules.
 */
class RecordPayRunJournalUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    companyRepository: CompanyRepository? = null
) {
    private val lineAccountResolver = LineAccountResolver(accountRepository, companyRepository)

    data class Line(
        val element: String,
        val kind: PayRunJournalKind,
        val side: TransactionSide,
        val amount: Money,
        val accountId: AccountId? = null
    )

    data class Request(
        val companyId: CompanyId,
        val periodId: PeriodId,
        val date: LocalDate,
        val lines: List<Line>,
        val description: String? = null,
        /** An opaque reference from Payroll (the run id), written into the description for tracing; not validated. */
        val reference: String? = null
    )

    fun execute(request: Request): RecordPayRunJournalResult {
        if (request.lines.isEmpty()) return RecordPayRunJournalResult.InvalidLines("lines must not be empty")
        request.lines.forEach { line ->
            if (line.amount.amount.signum() <= 0) return RecordPayRunJournalResult.InvalidLines("every amount must be positive")
            if (line.element.isBlank() || line.element.length > MAX_ELEMENT_LENGTH) {
                return RecordPayRunJournalResult.InvalidLines("element must be 1 to $MAX_ELEMENT_LENGTH characters")
            }
        }
        val currency = request.lines.first().amount.currency
        if (request.lines.any { it.amount.currency != currency }) return RecordPayRunJournalResult.InvalidLines("all lines must be in one currency")

        val zero = Money(java.math.BigDecimal.ZERO, currency)
        val debits = request.lines.filter { it.side == TransactionSide.DEBIT }.fold(zero) { sum, line -> sum + line.amount }
        val credits = request.lines.filter { it.side == TransactionSide.CREDIT }.fold(zero) { sum, line -> sum + line.amount }
        if (debits != credits) return RecordPayRunJournalResult.Unbalanced(debits, credits)

        val period = periodRepository.findOwnedBy(request.periodId, request.companyId) ?: return RecordPayRunJournalResult.PeriodNotFound
        if (!period.allowsPosting()) return RecordPayRunJournalResult.PeriodNotOpen

        // Every account is resolved before anything is built or saved: one bad line refuses the whole journal.
        val resolved = mutableListOf<Account>()
        for (line in request.lines) {
            val kind = when (line.kind) {
                PayRunJournalKind.EXPENSE -> LineAccountResolver.Kind.PAYROLL_EXPENSE
                PayRunJournalKind.LIABILITY -> LineAccountResolver.Kind.PAYROLL_LIABILITY
                PayRunJournalKind.SETTLEMENT -> LineAccountResolver.Kind.SETTLEMENT
            }
            val result = when {
                line.accountId != null -> lineAccountResolver.resolve(period.companyId, kind, line.accountId)
                line.kind == PayRunJournalKind.SETTLEMENT -> return RecordPayRunJournalResult.SettlementAccountRequired
                else -> lineAccountResolver.suspense(period.companyId)
            }
            when (result) {
                is LineAccountResolver.Result.Resolved -> resolved.add(result.account)
                is LineAccountResolver.Result.NotFound -> return RecordPayRunJournalResult.LineAccountNotFound(result.accountId)
                is LineAccountResolver.Result.NotAllowed -> return RecordPayRunJournalResult.LineAccountNotAllowed(result.accountId, result.reason)
                LineAccountResolver.Result.SuspenseNotConfigured -> return RecordPayRunJournalResult.SuspenseAccountNotConfigured
            }
        }

        val journalLines = request.lines.zip(resolved).map { (line, account) ->
            val dimensions = buildMap {
                put(DimensionType.PAYROLL_ELEMENT, line.element)
                if (line.kind == PayRunJournalKind.SETTLEMENT) put(DimensionType.CASH_FLOW_ACTIVITY, CashFlowActivity.OPERATING.name)
            }
            JournalLine(account.id, line.amount, line.side, dimensions)
        }
        val description = listOfNotNull(request.description, request.reference?.let { "run $it" }).joinToString(" - ").ifBlank { null }
        val entry = JournalEntry.create(request.periodId, request.date, journalLines, JournalSource.INTEGRATION, description)
        val posting = entry.post()
        check(posting.isValid) {
            "RecordPayRunJournalUseCase built a JournalEntry that failed its own post() precondition: " + posting.errors.joinToString()
        }

        for (account in resolved.distinctBy { it.id }) {
            account.recordActivity()
            accountRepository.save(account)
        }
        journalEntryRepository.save(entry)
        return RecordPayRunJournalResult.Success(entry, entry.pullDomainEvents(), debits)
    }

    private companion object {
        const val MAX_ELEMENT_LENGTH = 64
    }
}
