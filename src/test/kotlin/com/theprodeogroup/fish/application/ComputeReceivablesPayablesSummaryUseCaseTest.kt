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
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

class ComputeReceivablesPayablesSummaryUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private val useCase = ComputeReceivablesPayablesSummaryUseCase(companies, accounts, entries)
    private val asOf = LocalDate.of(2026, 10, 12)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).also { it.open(); periods.save(it) }
    private val counter = Account.create(company.id, AccountType.REVENUE, null, "4000", "Counter").also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun arAccount(forCompany: Company = company) =
        Account.create(forCompany.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "AR").also { accounts.save(it) }

    private fun apAccount(forCompany: Company = company) =
        Account.create(forCompany.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2000", "AP").also { accounts.save(it) }

    private fun post(forPeriod: Period = period, date: LocalDate, vararg lines: JournalLine) =
        JournalEntry.create(forPeriod.id, date, lines.toList(), JournalSource.INTEGRATION).also { it.post(); entries.save(it) }

    private fun sale(ar: Account, customer: UUID, daysOld: Long, amount: String) = post(
        date = asOf.minusDays(daysOld),
        lines = arrayOf(
            JournalLine(ar.id, money(amount), TransactionSide.DEBIT, mapOf(DimensionType.CUSTOMER to customer.toString())),
            JournalLine(counter.id, money(amount), TransactionSide.CREDIT)
        )
    )

    private fun receipt(ar: Account, customer: UUID, daysOld: Long, amount: String) = post(
        date = asOf.minusDays(daysOld),
        lines = arrayOf(
            JournalLine(counter.id, money(amount), TransactionSide.DEBIT),
            JournalLine(ar.id, money(amount), TransactionSide.CREDIT, mapOf(DimensionType.CUSTOMER to customer.toString()))
        )
    )

    private fun purchase(ap: Account, supplier: UUID, daysOld: Long, amount: String) = post(
        date = asOf.minusDays(daysOld),
        lines = arrayOf(
            JournalLine(counter.id, money(amount), TransactionSide.DEBIT),
            JournalLine(ap.id, money(amount), TransactionSide.CREDIT, mapOf(DimensionType.VENDOR to supplier.toString()))
        )
    )

    private fun payment(ap: Account, supplier: UUID, daysOld: Long, amount: String) = post(
        date = asOf.minusDays(daysOld),
        lines = arrayOf(
            JournalLine(ap.id, money(amount), TransactionSide.DEBIT, mapOf(DimensionType.VENDOR to supplier.toString())),
            JournalLine(counter.id, money(amount), TransactionSide.CREDIT)
        )
    )

    private fun summary(forCompany: CompanyId = company.id) =
        useCase.execute(forCompany, asOf).shouldBeInstanceOf<ComputeReceivablesPayablesSummaryUseCase.Result.Success>().summary

    @Test
    fun `given customers and suppliers with part payments, then each side adds up over all of them, with the overdue part and count`() {
        val ar = arAccount(); val ap = apAccount()
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val s = UUID.randomUUID()
        // Customer a: 1000 sold, 250 received: unpaid 50 (70 days), 300 (40 days), 400 (5 days).
        sale(ar, a, 100, "100.00"); sale(ar, a, 70, "200.00"); sale(ar, a, 40, "300.00"); sale(ar, a, 5, "400.00"); receipt(ar, a, 2, "250.00")
        // Customer b: six sales at the bucket edges, no receipts.
        for ((age, amount) in listOf(30L to "10.00", 31L to "20.00", 60L to "30.00", 61L to "40.00", 90L to "50.00", 91L to "60.00")) sale(ar, b, age, amount)
        // Supplier s: 600 bought, 200 paid: unpaid 300 (45 days), 100 (3 days).
        purchase(ap, s, 45, "500.00"); purchase(ap, s, 3, "100.00"); payment(ap, s, 1, "200.00")

        val result = summary()

        result.receivables.total shouldBe money("960.00")
        result.receivables.overdueTotal shouldBe money("550.00")
        result.receivables.overdueCount shouldBe 7
        result.payables.total shouldBe money("400.00")
        result.payables.overdueTotal shouldBe money("300.00")
        result.payables.overdueCount shouldBe 1
        result.currency shouldBe gbp
        result.asOf shouldBe asOf
    }

    @Test
    fun `given a fully paid customer, then it adds nothing`() {
        val ar = arAccount()
        val c = UUID.randomUUID()
        sale(ar, c, 50, "70.00"); receipt(ar, c, 10, "70.00")

        summary().receivables.total shouldBe money("0.00")
        summary().receivables.overdueCount shouldBe 0
    }

    @Test
    fun `given a Company with no receivables or payables control account, then both sides are zero, not an error`() {
        val result = summary()

        result.receivables.total shouldBe money("0.00")
        result.payables.total shouldBe money("0.00")
    }

    @Test
    fun `given only a receivables account, then payables are zero and receivables still count`() {
        val ar = arAccount()
        sale(ar, UUID.randomUUID(), 40, "10.00")

        val result = summary()

        result.receivables.overdueTotal shouldBe money("10.00")
        result.payables.total shouldBe money("0.00")
    }

    @Test
    fun `given postings with no customer tag or a tag that is not an id, then they are counted too, aged by their own date, and cause no error`() {
        val ar = arAccount()
        post(date = asOf.minusDays(50), lines = arrayOf(JournalLine(ar.id, money("99.00"), TransactionSide.DEBIT), JournalLine(counter.id, money("99.00"), TransactionSide.CREDIT)))
        post(
            date = asOf.minusDays(50),
            lines = arrayOf(
                JournalLine(ar.id, money("5.00"), TransactionSide.DEBIT, mapOf(DimensionType.CUSTOMER to "not-an-id")),
                JournalLine(counter.id, money("5.00"), TransactionSide.CREDIT)
            )
        )

        val receivables = summary().receivables
        receivables.total shouldBe money("104.00")
        receivables.overdueTotal shouldBe money("104.00")
        receivables.overdueCount shouldBe 2
    }

    @Test
    fun `given a stock receipt credited to Accounts Payable with no supplier tag, as IM posts it, then the payables total agrees with the ledger balance and the old part is overdue`() {
        val ap = apAccount()
        val tagged = UUID.randomUUID()
        purchase(ap, tagged, 3, "120.00")                                  // POP's tagged purchase, current
        post(date = asOf.minusDays(40), lines = arrayOf(                   // IM receipts, untagged
            JournalLine(counter.id, money("50000.00"), TransactionSide.DEBIT), JournalLine(ap.id, money("50000.00"), TransactionSide.CREDIT)
        ))
        post(date = asOf.minusDays(2), lines = arrayOf(
            JournalLine(counter.id, money("100.00"), TransactionSide.DEBIT), JournalLine(ap.id, money("100.00"), TransactionSide.CREDIT)
        ))

        val payables = summary().payables

        payables.total shouldBe money("50220.00")                          // the ledger's Accounts Payable balance
        payables.overdueTotal shouldBe money("50000.00")
        payables.overdueCount shouldBe 1
    }

    @Test
    fun `given the production shape, a tagged purchase order invoice of 120 and untagged receipts of 50000 and 100, then the total equals the ledger control balance and the overdue part is the old receipt`() {
        val ap = apAccount()
        val supplier = UUID.randomUUID()
        purchase(ap, supplier, 3, "120.00")                                // POP match: tagged, current
        post(date = asOf.minusDays(40), lines = arrayOf(                   // IM receipt, untagged, older than 30 days
            JournalLine(counter.id, money("50000.00"), TransactionSide.DEBIT), JournalLine(ap.id, money("50000.00"), TransactionSide.CREDIT)
        ))
        post(date = asOf.minusDays(2), lines = arrayOf(                    // IM receipt, untagged, recent
            JournalLine(counter.id, money("100.00"), TransactionSide.DEBIT), JournalLine(ap.id, money("100.00"), TransactionSide.CREDIT)
        ))

        // The ledger's own figure, computed from the posted lines on the control account (credits less debits).
        val ledgerBalance = entries.findAllByPeriod(period.id).flatMap { it.lines }.filter { it.accountId == ap.id }
            .fold(BigDecimal.ZERO) { sum, line -> if (line.side == TransactionSide.CREDIT) sum + line.amount.amount else sum - line.amount.amount }
        val payables = summary().payables

        payables.total.amount.compareTo(ledgerBalance) shouldBe 0
        payables.total shouldBe money("50220.00")
        payables.overdueTotal shouldBe money("50000.00")                   // only the 40-day-old receipt is past 30 days
        payables.overdueCount shouldBe 1
    }

    @Test
    fun `given untagged payments against untagged charges, then they allocate among themselves first in first out`() {
        val ap = apAccount()
        post(date = asOf.minusDays(60), lines = arrayOf(JournalLine(counter.id, money("300.00"), TransactionSide.DEBIT), JournalLine(ap.id, money("300.00"), TransactionSide.CREDIT)))
        post(date = asOf.minusDays(5), lines = arrayOf(JournalLine(counter.id, money("200.00"), TransactionSide.DEBIT), JournalLine(ap.id, money("200.00"), TransactionSide.CREDIT)))
        post(date = asOf.minusDays(1), lines = arrayOf(JournalLine(ap.id, money("250.00"), TransactionSide.DEBIT), JournalLine(counter.id, money("250.00"), TransactionSide.CREDIT)))

        val payables = summary().payables

        payables.total shouldBe money("250.00")                            // 300 - 250 left of the old charge, plus the 200
        payables.overdueTotal shouldBe money("50.00")
        payables.overdueCount shouldBe 1
    }

    @Test
    fun `given another Company's postings, then they never enter this Company's figures`() {
        val ar = arAccount()
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val otherPeriod = Period.create(other.id, PeriodType.MONTH, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).also { it.open(); periods.save(it) }
        val otherAr = arAccount(other)
        val otherCounter = Account.create(other.id, AccountType.REVENUE, null, "4000", "Counter").also { accounts.save(it) }
        val customer = UUID.randomUUID()
        sale(ar, customer, 40, "10.00")
        post(
            otherPeriod, asOf.minusDays(40),
            JournalLine(otherAr.id, money("9999.00"), TransactionSide.DEBIT, mapOf(DimensionType.CUSTOMER to customer.toString())),
            JournalLine(otherCounter.id, money("9999.00"), TransactionSide.CREDIT)
        )

        summary().receivables.total shouldBe money("10.00")
        summary(other.id).receivables.total shouldBe money("9999.00")
    }

    @Test
    fun `given an unknown Company, then it is not found`() {
        useCase.execute(CompanyId.generate(), asOf) shouldBe ComputeReceivablesPayablesSummaryUseCase.Result.CompanyNotFound
    }
}
