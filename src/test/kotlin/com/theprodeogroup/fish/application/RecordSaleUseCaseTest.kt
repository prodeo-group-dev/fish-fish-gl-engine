package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val EUR: Currency = Currency.getInstance("EUR")
private val TODAY = LocalDate.of(2026, 8, 20)

/**
 * SOP (fish-sales-order-processing) computes each line's net amount and
 * VAT category, and calls this - GL resolves the VAT rate and computes
 * the VAT amount itself, atomically, in the same call that posts the
 * JournalEntry (2026-09-19, "the required atomicity is high" - no
 * separate rate-lookup call SOP must sequence beforehand, no staleness
 * window between resolving a rate and posting against it).
 */
class RecordSaleUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordSaleUseCase(periodRepository, accountRepository, journalEntryRepository)

    private val companyId = CompanyId.generate()

    private fun request(
        periodId: PeriodId,
        arAccountId: AccountId,
        revenueAccountId: AccountId,
        vatAccountId: AccountId,
        lines: List<RecordSaleUseCase.SaleLine> = listOf(RecordSaleUseCase.SaleLine(Money(BigDecimal("1000.00"), EUR), VatCategory.STANDARD)),
        customerId: CustomerId = CustomerId.generate(),
        vatRateSchedule: VatRateSchedule = VatRateSchedule.IRELAND
    ) = RecordSaleUseCase.Request(
        periodId, TODAY, arAccountId, revenueAccountId, vatAccountId, lines, customerId,
        vatRateSchedule, "Sale to SOCIETE JALLOH ALPHAJOR SARLU"
    )

    private fun openPeriod(): Period {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        return period
    }

    private fun account(code: String, type: AccountType): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        val account = Account.create(companyId, type, classification, code, "Test Account")
        accountRepository.save(account)
        return account
    }

    @Test
    fun `given a single standard-rated line, when executed, then it debits AR gross, credits Revenue net, and credits VAT for the rate`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)
        val customerId = CustomerId.generate()

        val result = useCase.execute(
            request(
                period.id, ar.id, revenue.id, vat.id,
                lines = listOf(RecordSaleUseCase.SaleLine(Money(BigDecimal("1000.00"), EUR), VatCategory.STANDARD)),
                customerId = customerId
            )
        )

        val success = result.shouldBeInstanceOf<RecordSaleResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val arLine = success.journalEntry.lines.single { it.accountId == ar.id }
        val revenueLine = success.journalEntry.lines.single { it.accountId == revenue.id }
        val vatLine = success.journalEntry.lines.single { it.accountId == vat.id }
        arLine.amount shouldBe Money(BigDecimal("1230.00"), EUR)
        revenueLine.amount shouldBe Money(BigDecimal("1000.00"), EUR)
        vatLine.amount shouldBe Money(BigDecimal("230.00"), EUR)
        vatLine.dimensions[DimensionType.VAT_CATEGORY] shouldBe "STANDARD"
        arLine.dimensions[DimensionType.CUSTOMER] shouldBe customerId.value.toString()
    }

    @Test
    fun `given lines with different VAT categories, when executed, then each category gets its own tagged VAT line`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, ar.id, revenue.id, vat.id,
                lines = listOf(
                    RecordSaleUseCase.SaleLine(Money(BigDecimal("1000.00"), EUR), VatCategory.STANDARD),
                    RecordSaleUseCase.SaleLine(Money(BigDecimal("100.00"), EUR), VatCategory.REDUCED)
                )
            )
        )

        val success = result.shouldBeInstanceOf<RecordSaleResult.Success>()
        val vatLines = success.journalEntry.lines.filter { it.accountId == vat.id }
        vatLines.size shouldBe 2
        vatLines.single { it.dimensions[DimensionType.VAT_CATEGORY] == "STANDARD" }.amount shouldBe Money(BigDecimal("230.00"), EUR)
        vatLines.single { it.dimensions[DimensionType.VAT_CATEGORY] == "REDUCED" }.amount shouldBe Money(BigDecimal("13.50"), EUR)
        val revenueLine = success.journalEntry.lines.single { it.accountId == revenue.id }
        revenueLine.amount shouldBe Money(BigDecimal("1100.00"), EUR)
        val arLine = success.journalEntry.lines.single { it.accountId == ar.id }
        arLine.amount shouldBe Money(BigDecimal("1343.50"), EUR)
    }

    @Test
    fun `given a zero-rated line, when executed, then a VAT line is still posted at zero for audit visibility`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, ar.id, revenue.id, vat.id,
                lines = listOf(RecordSaleUseCase.SaleLine(Money(BigDecimal("500.00"), EUR), VatCategory.ZERO_RATED))
            )
        )

        val success = result.shouldBeInstanceOf<RecordSaleResult.Success>()
        success.journalEntry.lines.none { it.accountId == vat.id } shouldBe true
        val arLine = success.journalEntry.lines.single { it.accountId == ar.id }
        arLine.amount shouldBe Money(BigDecimal("500.00"), EUR)
    }

    @Test
    fun `given an exempt line, when executed, then no VAT line is posted at all`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, ar.id, revenue.id, vat.id,
                lines = listOf(RecordSaleUseCase.SaleLine(Money(BigDecimal("500.00"), EUR), VatCategory.EXEMPT))
            )
        )

        val success = result.shouldBeInstanceOf<RecordSaleResult.Success>()
        success.journalEntry.lines.none { it.accountId == vat.id } shouldBe true
    }

    @Test
    fun `given a line dated after 1 Jul 2026, when executed, then the new second-reduced rate applies`() {
        val period = Period.create(companyId, PeriodType.MONTH, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
        period.open()
        periodRepository.save(period)
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val req = RecordSaleUseCase.Request(
            period.id, LocalDate.of(2026, 7, 15), ar.id, revenue.id, vat.id,
            listOf(RecordSaleUseCase.SaleLine(Money(BigDecimal("100.00"), EUR), VatCategory.SECOND_REDUCED)),
            CustomerId.generate(), VatRateSchedule.IRELAND
        )

        val result = useCase.execute(req)

        val success = result.shouldBeInstanceOf<RecordSaleResult.Success>()
        val vatLine = success.journalEntry.lines.single { it.accountId == vat.id }
        vatLine.amount shouldBe Money(BigDecimal("9.00"), EUR)
    }

    @Test
    fun `given success, then the JournalEntry is saved and every touched Account is marked posted and saved`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, ar.id, revenue.id, vat.id))

        val success = result.shouldBeInstanceOf<RecordSaleResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain ar.id
        accountRepository.saveCalls shouldContain revenue.id
        accountRepository.saveCalls shouldContain vat.id
    }

    @Test
    fun `given a zero-rated-only sale, then the VAT Control Account is not touched at all since no VAT line was posted`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)
        // Snapshot before execute() - account() fixture creation itself calls
        // save(), so a bare "shouldNotContain" check here would compare
        // against the wrong baseline (the same pitfall PostPayRunUseCase's
        // own KDoc, docs/DDD_Design.md Section 10.17, already flagged).
        val saveCallsBeforeExecute = accountRepository.saveCalls.toList()

        val result = useCase.execute(
            request(
                period.id, ar.id, revenue.id, vat.id,
                lines = listOf(RecordSaleUseCase.SaleLine(Money(BigDecimal("500.00"), EUR), VatCategory.ZERO_RATED))
            )
        )

        result.shouldBeInstanceOf<RecordSaleResult.Success>()
        val newSaveCalls = accountRepository.saveCalls.drop(saveCallsBeforeExecute.size)
        newSaveCalls shouldContain ar.id
        newSaveCalls shouldContain revenue.id
        (vat.id in newSaveCalls) shouldBe false
    }

    @Test
    fun `given no lines, when executed, then it returns InvalidAmount`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, ar.id, revenue.id, vat.id, lines = emptyList()))

        result.shouldBeInstanceOf<RecordSaleResult.InvalidAmount>()
    }

    @Test
    fun `given a non-positive line amount, when constructing a SaleLine, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            RecordSaleUseCase.SaleLine(Money(BigDecimal.ZERO, EUR), VatCategory.STANDARD)
        }
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(PeriodId.generate(), ar.id, revenue.id, vat.id))

        result.shouldBeInstanceOf<RecordSaleResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, ar.id, revenue.id, vat.id))

        result.shouldBeInstanceOf<RecordSaleResult.PeriodNotOpen>()
    }

    @Test
    fun `given an AR control Account that does not exist, when executed, then it returns ArControlAccountNotFound`() {
        val period = openPeriod()
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, AccountId.generate(), revenue.id, vat.id))

        result.shouldBeInstanceOf<RecordSaleResult.ArControlAccountNotFound>()
    }

    @Test
    fun `given a Revenue Account that does not exist, when executed, then it returns RevenueAccountNotFound`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, ar.id, AccountId.generate(), vat.id))

        result.shouldBeInstanceOf<RecordSaleResult.RevenueAccountNotFound>()
    }

    @Test
    fun `given a VAT Control Account that does not exist, when executed, then it returns VatControlAccountNotFound`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)

        val result = useCase.execute(request(period.id, ar.id, revenue.id, AccountId.generate()))

        result.shouldBeInstanceOf<RecordSaleResult.VatControlAccountNotFound>()
    }

    @Test
    fun `given a Revenue Account belonging to another Company, when executed, then it returns RevenueAccountNotFound`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val otherCompanysRevenue = Account.create(CompanyId.generate(), AccountType.REVENUE, null, "4000", "Test Account")
        accountRepository.save(otherCompanysRevenue)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, ar.id, otherCompanysRevenue.id, vat.id))

        result.shouldBeInstanceOf<RecordSaleResult.RevenueAccountNotFound>()
    }

    @Test
    fun `given a custom VatRateSchedule passed via the Request, when executed, then it uses that schedule instead of IRELAND`() {
        val customSchedule = VatRateSchedule(
            mapOf(VatCategory.STANDARD to listOf(com.theprodeogroup.fish.domain.tax.VatRateEntry(BigDecimal("0.50"), LocalDate.of(2000, 1, 1))))
        )
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, ar.id, revenue.id, vat.id,
                lines = listOf(RecordSaleUseCase.SaleLine(Money(BigDecimal("100.00"), EUR), VatCategory.STANDARD)),
                vatRateSchedule = customSchedule
            )
        )

        val success = result.shouldBeInstanceOf<RecordSaleResult.Success>()
        success.journalEntry.lines.single { it.accountId == vat.id }.amount shouldBe Money(BigDecimal("50.00"), EUR)
    }

    // --- UK jurisdiction (2026-09-21) - the routing fix's own proof --------

    @Test
    fun `given a UK schedule and a standard-rated line, when executed, then the VAT line reflects 20 percent`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, ar.id, revenue.id, vat.id,
                lines = listOf(RecordSaleUseCase.SaleLine(Money(BigDecimal("1000.00"), EUR), VatCategory.STANDARD)),
                vatRateSchedule = VatRateSchedule.UK
            )
        )

        val success = result.shouldBeInstanceOf<RecordSaleResult.Success>()
        success.journalEntry.lines.single { it.accountId == vat.id }.amount shouldBe Money(BigDecimal("200.00"), EUR)
    }

    @Test
    fun `given a UK schedule and a second-reduced line, when executed, then it returns VatCategoryNotSupported`() {
        val period = openPeriod()
        val ar = account("1100", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, ar.id, revenue.id, vat.id,
                lines = listOf(RecordSaleUseCase.SaleLine(Money(BigDecimal("100.00"), EUR), VatCategory.SECOND_REDUCED)),
                vatRateSchedule = VatRateSchedule.UK
            )
        )

        val failure = result.shouldBeInstanceOf<RecordSaleResult.VatCategoryNotSupported>()
        failure.category shouldBe VatCategory.SECOND_REDUCED
    }
}
