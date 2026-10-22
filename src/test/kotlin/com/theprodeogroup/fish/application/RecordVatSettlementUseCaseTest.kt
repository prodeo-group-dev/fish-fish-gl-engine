package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.UseInstead
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatFilingPeriod
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * Paying VAT to the authority, or receiving a refund from it (GL-T-30, GL-FR-TAX-9, GL-UC-20; Femi 2026-10-22: at the
 * quarter end VAT is paid to the Government, Dr VAT, Cr Bank). The only door that may post to the VAT control account from a
 * cash or bank book; the line is tagged SETTLEMENT so the VAT return does not count it.
 */
class RecordVatSettlementUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val today = LocalDate.of(2026, 10, 12)
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private val post = PostJournalEntryUseCase(periods, accounts, entries)
    private val cashBookEntry = RecordCashBookEntryUseCase(companies, accounts, periods, entries, post)
    private val useCase = RecordVatSettlementUseCase(accounts, cashBookEntry)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }
    private val bank = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK).also { accounts.save(it) }
    private val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }
    private val vatControl = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2150", "VAT Control Account").also { accounts.save(it) }

    private fun request(direction: CashDirection = CashDirection.PAID, amount: String = "1000.00", entryId: JournalEntryId = JournalEntryId.generate(), book: Account = bank) =
        RecordVatSettlementUseCase.Request(company.id, book.id, direction, BigDecimal(amount), today, "Q3 VAT", entryId)

    private fun posted(result: RecordCashBookEntryUseCase.Result) = result.shouldBeInstanceOf<RecordCashBookEntryUseCase.Result.Posted>()

    @Test
    fun `given VAT paid to the authority, then the VAT control account is debited, the bank credited, and the VAT line is tagged SETTLEMENT`() {
        val result = posted(useCase.execute(request(CashDirection.PAID, "1000.00")))

        result.entry.source shouldBe JournalSource.CASH_BOOK
        result.entry.lines.map { Triple(it.accountId, it.side, it.amount.amount.toPlainString()) } shouldBe listOf(
            Triple(bank.id, TransactionSide.CREDIT, "1000.00"),
            Triple(vatControl.id, TransactionSide.DEBIT, "1000.00")
        )
        result.entry.lines.first { it.accountId == vatControl.id }.dimensions shouldBe mapOf(DimensionType.VAT_DIRECTION to "SETTLEMENT")
        result.entry.lines.first { it.accountId == bank.id }.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.OPERATING.name
        result.balanceAfter shouldBe Money(BigDecimal("-1000.00"), gbp)
        result.warnings.map { it.code } shouldBe listOf("bank_overdrawn")
    }

    @Test
    fun `given a VAT refund received, then the bank is debited and the VAT control account credited, tagged SETTLEMENT`() {
        val result = posted(useCase.execute(request(CashDirection.RECEIVED, "250.00")))

        result.entry.lines.map { Triple(it.accountId, it.side, it.amount.amount.toPlainString()) } shouldBe listOf(
            Triple(bank.id, TransactionSide.DEBIT, "250.00"),
            Triple(vatControl.id, TransactionSide.CREDIT, "250.00")
        )
        result.entry.lines.first { it.accountId == vatControl.id }.dimensions[DimensionType.VAT_DIRECTION] shouldBe "SETTLEMENT"
        result.balanceAfter shouldBe Money(BigDecimal("250.00"), gbp)
    }

    @Test
    fun `given the same settlement again with the same entry id, then it is a replay, and a different amount is key reuse`() {
        val id = JournalEntryId.generate()
        posted(useCase.execute(request(entryId = id)))

        useCase.execute(request(entryId = id)).shouldBeInstanceOf<RecordCashBookEntryUseCase.Result.Replay>()
        useCase.execute(request(amount = "999.00", entryId = id)) shouldBe RecordCashBookEntryUseCase.Result.KeyReused
        entries.findAllByAccount(vatControl.id).size shouldBe 1
    }

    @Test
    fun `given a Company with no VAT control account, then it is refused and nothing posts`() {
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        Period.create(other.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }
        val otherBank = Account.create(other.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK).also { accounts.save(it) }

        useCase.execute(request(book = otherBank).copy(companyId = other.id)) shouldBe RecordCashBookEntryUseCase.Result.VatControlAccountNotConfigured
        entries.findAllByAccount(otherBank.id).size shouldBe 0
    }

    @Test
    fun `given an account that is not a cash or bank book, a bad amount or no open Period, then each is refused as for any cash book entry`() {
        useCase.execute(request(book = sales)) shouldBe RecordCashBookEntryUseCase.Result.NotACashBook
        useCase.execute(request(amount = "0.00")) shouldBe RecordCashBookEntryUseCase.Result.InvalidAmount
        useCase.execute(request(amount = "1.005")) shouldBe RecordCashBookEntryUseCase.Result.InvalidAmount
        val closed = Company.create(TenantId.generate(), "Closed Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val closedBank = Account.create(closed.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK).also { accounts.save(it) }
        Account.create(closed.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2150", "VAT Control Account").also { accounts.save(it) }
        useCase.execute(request(book = closedBank).copy(companyId = closed.id)) shouldBe RecordCashBookEntryUseCase.Result.NoOpenPeriod
    }

    @Test
    fun `given the ordinary receipt and payment door, then the VAT control account is still refused as a counter-account`() {
        val general = cashBookEntry.execute(
            RecordCashBookEntryUseCase.Request(company.id, bank.id, CashDirection.PAID, BigDecimal("10.00"), today, vatControl.id, null, CashFlowActivity.OPERATING, JournalEntryId.generate())
        )

        general shouldBe RecordCashBookEntryUseCase.Result.CounterAccountNotAllowed(UseInstead.VAT)
        entries.findAllByAccount(vatControl.id).size shouldBe 0
    }

    @Test
    fun `given the settlement flag on an account that is not the VAT control account, then it is refused`() {
        val flagged = cashBookEntry.execute(
            RecordCashBookEntryUseCase.Request(company.id, bank.id, CashDirection.PAID, BigDecimal("10.00"), today, sales.id, null, CashFlowActivity.OPERATING, JournalEntryId.generate(), settlesVat = true)
        )

        flagged shouldBe RecordCashBookEntryUseCase.Result.CounterAccountNotAllowed(UseInstead.VAT)
        entries.findAllByAccount(sales.id).size shouldBe 0
    }

    @Test
    fun `given VAT charged and suffered in a quarter, then paying the net due leaves the return unchanged and the VAT control account at zero`() {
        val ar = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable").also { accounts.save(it) }
        val rent = Account.create(company.id, AccountType.EXPENSE, null, "5000", "Rent").also { accounts.save(it) }
        fun line(account: Account, amount: String, side: TransactionSide, vararg tags: Pair<DimensionType, String>) =
            JournalLine(account.id, Money(BigDecimal(amount), gbp), side, mapOf(*tags))
        val outputTags = arrayOf(DimensionType.VAT_CATEGORY to VatCategory.STANDARD.name, DimensionType.VAT_DIRECTION to "OUTPUT")
        val inputTags = arrayOf(DimensionType.VAT_CATEGORY to VatCategory.STANDARD.name, DimensionType.VAT_DIRECTION to "INPUT")
        post.execute(
            PostJournalEntryUseCase.Request(
                company.id, period.id, today,
                listOf(line(ar, "1200.00", TransactionSide.DEBIT), line(sales, "1000.00", TransactionSide.CREDIT), line(vatControl, "200.00", TransactionSide.CREDIT, *outputTags)),
                JournalSource.INTEGRATION, null
            )
        ).shouldBeInstanceOf<PostJournalEntryResult.Success>()
        post.execute(
            PostJournalEntryUseCase.Request(
                company.id, period.id, today,
                listOf(line(rent, "250.00", TransactionSide.DEBIT), line(vatControl, "50.00", TransactionSide.DEBIT, *inputTags), line(bank, "300.00", TransactionSide.CREDIT)),
                JournalSource.INTEGRATION, null
            )
        ).shouldBeInstanceOf<PostJournalEntryResult.Success>()
        val compute = ComputeVatReturnUseCase(accounts, entries, FakeVatReturnRepository())
        val quarter = VatFilingPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31))
        fun vatReturn() = compute.execute(ComputeVatReturnUseCase.Request(company.id, quarter, vatControl.id, gbp))
            .shouldBeInstanceOf<ComputeVatReturnResult.Success>().vatReturn
        val due = vatReturn().netVatDue
        due shouldBe Money(BigDecimal("150.00"), gbp)

        posted(useCase.execute(request(CashDirection.PAID, due.amount.toPlainString())))

        val after = vatReturn()
        after.netVatDue shouldBe due
        after.outputVat shouldBe Money(BigDecimal("200.00"), gbp)
        after.inputVat shouldBe Money(BigDecimal("50.00"), gbp)
        val credits = entries.findAllByAccount(vatControl.id).flatMap { it.lines }.filter { it.accountId == vatControl.id }
            .fold(BigDecimal.ZERO) { sum, l -> if (l.side == TransactionSide.CREDIT) sum + l.amount.amount else sum - l.amount.amount }
        credits.compareTo(BigDecimal.ZERO) shouldBe 0
    }
}
