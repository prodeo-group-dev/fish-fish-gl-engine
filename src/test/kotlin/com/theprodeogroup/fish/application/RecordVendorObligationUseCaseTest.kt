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
import com.theprodeogroup.fish.domain.purchasing.CreditorId
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
 * The Purchasing mirror of [RecordSaleUseCaseTest] - input VAT instead of
 * output VAT (debits the VAT Control Account instead of crediting it),
 * same atomic rate-resolution-and-computation shape.
 */
class RecordVendorObligationUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordVendorObligationUseCase(periodRepository, accountRepository, journalEntryRepository)

    private val companyId = CompanyId.generate()

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

    private fun request(
        periodId: PeriodId,
        expenseAccountId: AccountId,
        apAccountId: AccountId,
        vatAccountId: AccountId,
        lines: List<RecordVendorObligationUseCase.PurchaseLine> = listOf(RecordVendorObligationUseCase.PurchaseLine(Money(BigDecimal("1000.00"), EUR), VatCategory.STANDARD)),
        vendorId: CreditorId = CreditorId.generate(),
        vatRateSchedule: VatRateSchedule = VatRateSchedule.IRELAND
    ) = RecordVendorObligationUseCase.Request(
        periodId, TODAY, expenseAccountId, apAccountId, vatAccountId, lines, vendorId, vatRateSchedule, "Purchase from supplier"
    )

    @Test
    fun `given a single standard-rated line, when executed, then it debits Expense net, debits VAT, and credits AP gross`() {
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)
        val vendorId = CreditorId.generate()

        val result = useCase.execute(request(period.id, expense.id, ap.id, vat.id, vendorId = vendorId))

        val success = result.shouldBeInstanceOf<RecordVendorObligationResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val expenseLine = success.journalEntry.lines.single { it.accountId == expense.id }
        val apLine = success.journalEntry.lines.single { it.accountId == ap.id }
        val vatLine = success.journalEntry.lines.single { it.accountId == vat.id }
        expenseLine.amount shouldBe Money(BigDecimal("1000.00"), EUR)
        apLine.amount shouldBe Money(BigDecimal("1230.00"), EUR)
        vatLine.amount shouldBe Money(BigDecimal("230.00"), EUR)
        vatLine.dimensions[DimensionType.VAT_CATEGORY] shouldBe "STANDARD"
        apLine.dimensions[DimensionType.VENDOR] shouldBe vendorId.value.toString()
    }

    @Test
    fun `given lines with different VAT categories, when executed, then each category gets its own tagged VAT line`() {
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, expense.id, ap.id, vat.id,
                lines = listOf(
                    RecordVendorObligationUseCase.PurchaseLine(Money(BigDecimal("1000.00"), EUR), VatCategory.STANDARD),
                    RecordVendorObligationUseCase.PurchaseLine(Money(BigDecimal("100.00"), EUR), VatCategory.REDUCED)
                )
            )
        )

        val success = result.shouldBeInstanceOf<RecordVendorObligationResult.Success>()
        val vatLines = success.journalEntry.lines.filter { it.accountId == vat.id }
        vatLines.size shouldBe 2
        vatLines.single { it.dimensions[DimensionType.VAT_CATEGORY] == "STANDARD" }.amount shouldBe Money(BigDecimal("230.00"), EUR)
        vatLines.single { it.dimensions[DimensionType.VAT_CATEGORY] == "REDUCED" }.amount shouldBe Money(BigDecimal("13.50"), EUR)
        val apLine = success.journalEntry.lines.single { it.accountId == ap.id }
        apLine.amount shouldBe Money(BigDecimal("1343.50"), EUR)
    }

    @Test
    fun `given an exempt line, when executed, then no VAT line is posted at all`() {
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, expense.id, ap.id, vat.id,
                lines = listOf(RecordVendorObligationUseCase.PurchaseLine(Money(BigDecimal("500.00"), EUR), VatCategory.EXEMPT))
            )
        )

        val success = result.shouldBeInstanceOf<RecordVendorObligationResult.Success>()
        success.journalEntry.lines.none { it.accountId == vat.id } shouldBe true
        val apLine = success.journalEntry.lines.single { it.accountId == ap.id }
        apLine.amount shouldBe Money(BigDecimal("500.00"), EUR)
    }

    @Test
    fun `given a line dated after 1 Jul 2026, when executed, then the new second-reduced rate applies`() {
        val period = Period.create(companyId, PeriodType.MONTH, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
        period.open()
        periodRepository.save(period)
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val req = RecordVendorObligationUseCase.Request(
            period.id, LocalDate.of(2026, 7, 15), expense.id, ap.id, vat.id,
            listOf(RecordVendorObligationUseCase.PurchaseLine(Money(BigDecimal("100.00"), EUR), VatCategory.SECOND_REDUCED)),
            CreditorId.generate(), VatRateSchedule.IRELAND
        )

        val result = useCase.execute(req)

        val success = result.shouldBeInstanceOf<RecordVendorObligationResult.Success>()
        val vatLine = success.journalEntry.lines.single { it.accountId == vat.id }
        vatLine.amount shouldBe Money(BigDecimal("9.00"), EUR)
    }

    @Test
    fun `given success, then the JournalEntry is saved and every touched Account is marked posted and saved`() {
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, expense.id, ap.id, vat.id))

        val success = result.shouldBeInstanceOf<RecordVendorObligationResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain expense.id
        accountRepository.saveCalls shouldContain ap.id
        accountRepository.saveCalls shouldContain vat.id
    }

    @Test
    fun `given a zero-rated-only purchase, then the VAT Control Account is not touched at all since no VAT line was posted`() {
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)
        val saveCallsBeforeExecute = accountRepository.saveCalls.toList()

        val result = useCase.execute(
            request(
                period.id, expense.id, ap.id, vat.id,
                lines = listOf(RecordVendorObligationUseCase.PurchaseLine(Money(BigDecimal("500.00"), EUR), VatCategory.ZERO_RATED))
            )
        )

        result.shouldBeInstanceOf<RecordVendorObligationResult.Success>()
        val newSaveCalls = accountRepository.saveCalls.drop(saveCallsBeforeExecute.size)
        newSaveCalls shouldContain expense.id
        newSaveCalls shouldContain ap.id
        (vat.id in newSaveCalls) shouldBe false
    }

    @Test
    fun `given no lines, when executed, then it returns InvalidAmount`() {
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, expense.id, ap.id, vat.id, lines = emptyList()))

        result.shouldBeInstanceOf<RecordVendorObligationResult.InvalidAmount>()
    }

    @Test
    fun `given a non-positive line amount, when constructing a PurchaseLine, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            RecordVendorObligationUseCase.PurchaseLine(Money(BigDecimal.ZERO, EUR), VatCategory.STANDARD)
        }
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(PeriodId.generate(), expense.id, ap.id, vat.id))

        result.shouldBeInstanceOf<RecordVendorObligationResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, expense.id, ap.id, vat.id))

        result.shouldBeInstanceOf<RecordVendorObligationResult.PeriodNotOpen>()
    }

    @Test
    fun `given an Expense or Asset Account that does not exist, when executed, then it returns ExpenseOrAssetAccountNotFound`() {
        val period = openPeriod()
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, AccountId.generate(), ap.id, vat.id))

        result.shouldBeInstanceOf<RecordVendorObligationResult.ExpenseOrAssetAccountNotFound>()
    }

    @Test
    fun `given an AP control Account that does not exist, when executed, then it returns ApControlAccountNotFound`() {
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, expense.id, AccountId.generate(), vat.id))

        result.shouldBeInstanceOf<RecordVendorObligationResult.ApControlAccountNotFound>()
    }

    @Test
    fun `given a VAT Control Account that does not exist, when executed, then it returns VatControlAccountNotFound`() {
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, expense.id, ap.id, AccountId.generate()))

        result.shouldBeInstanceOf<RecordVendorObligationResult.VatControlAccountNotFound>()
    }

    @Test
    fun `given a custom VatRateSchedule passed via the Request, when executed, then it uses that schedule instead of IRELAND`() {
        val customSchedule = VatRateSchedule(
            mapOf(VatCategory.STANDARD to listOf(com.theprodeogroup.fish.domain.tax.VatRateEntry(BigDecimal("0.50"), LocalDate.of(2000, 1, 1))))
        )
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, expense.id, ap.id, vat.id,
                lines = listOf(RecordVendorObligationUseCase.PurchaseLine(Money(BigDecimal("100.00"), EUR), VatCategory.STANDARD)),
                vatRateSchedule = customSchedule
            )
        )

        val success = result.shouldBeInstanceOf<RecordVendorObligationResult.Success>()
        success.journalEntry.lines.single { it.accountId == vat.id }.amount shouldBe Money(BigDecimal("50.00"), EUR)
    }

    // --- UK jurisdiction (2026-09-21) - the routing fix's own proof --------

    @Test
    fun `given a UK schedule and a standard-rated line, when executed, then the VAT line reflects 20 percent`() {
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, expense.id, ap.id, vat.id,
                lines = listOf(RecordVendorObligationUseCase.PurchaseLine(Money(BigDecimal("1000.00"), EUR), VatCategory.STANDARD)),
                vatRateSchedule = VatRateSchedule.UK
            )
        )

        val success = result.shouldBeInstanceOf<RecordVendorObligationResult.Success>()
        success.journalEntry.lines.single { it.accountId == vat.id }.amount shouldBe Money(BigDecimal("200.00"), EUR)
    }

    @Test
    fun `given a UK schedule and a second-reduced line, when executed, then it returns VatCategoryNotSupported`() {
        val period = openPeriod()
        val expense = account("5000", AccountType.EXPENSE)
        val ap = account("2000", AccountType.LIABILITY)
        val vat = account("2150", AccountType.LIABILITY)

        val result = useCase.execute(
            request(
                period.id, expense.id, ap.id, vat.id,
                lines = listOf(RecordVendorObligationUseCase.PurchaseLine(Money(BigDecimal("100.00"), EUR), VatCategory.SECOND_REDUCED)),
                vatRateSchedule = VatRateSchedule.UK
            )
        )

        val failure = result.shouldBeInstanceOf<RecordVendorObligationResult.VatCategoryNotSupported>()
        failure.category shouldBe VatCategory.SECOND_REDUCED
    }
}
