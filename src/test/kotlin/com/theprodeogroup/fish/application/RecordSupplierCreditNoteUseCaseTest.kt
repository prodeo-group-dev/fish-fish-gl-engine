package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.tax.TestVatRates
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * A credit note from a supplier for a discount or a rebate (POP's Discounts Received design, task T-D5; the supplier half
 * of GL's S8): the buyer owes less, the difference is Discounts Received, and the input VAT falls by the VAT on the credit
 * note. Worked example, UK: a 100.00 net price allowance at 20 percent is a credit note of 120.00. Dr Accounts Payable
 * 120.00 / Cr Discounts Received 100.00 / Cr VAT control 20.00 (tagged INPUT).
 */
class RecordSupplierCreditNoteUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordSupplierCreditNoteUseCase(periodRepository, accountRepository, journalEntryRepository)

    private val companyId = CompanyId.generate()
    private val today = LocalDate.of(2026, 10, 16)
    private val period = Period.create(companyId, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periodRepository.save(it) }

    private fun account(code: String, type: AccountType, forCompany: CompanyId = companyId): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        return Account.create(forCompany, type, classification, code, "Account $code").also { accountRepository.save(it) }
    }

    private val ap = account("2000", AccountType.LIABILITY)
    private val vat = account("2150", AccountType.LIABILITY)
    private val discounts = account("4920", AccountType.REVENUE)

    private val ukSchedule = VatRateSchedule.of(TestVatRates.UK_ROWS)!!
    private val ieSchedule = VatRateSchedule.of(TestVatRates.IRELAND_ROWS)!!

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun line(net: String, category: VatCategory) = RecordSupplierCreditNoteUseCase.CreditNoteLine(money(net), category)

    private fun request(
        vararg lines: RecordSupplierCreditNoteUseCase.CreditNoteLine,
        kind: RecordSupplierCreditNoteUseCase.Kind = RecordSupplierCreditNoteUseCase.Kind.DISCOUNT,
        schedule: VatRateSchedule = ukSchedule,
        supplyDate: LocalDate? = null,
        supplier: SupplierId = SupplierId.generate(),
        description: String? = null,
        periodId: PeriodId = period.id,
        apAccount: AccountId = ap.id,
        discountsAccount: AccountId = discounts.id,
        vatAccount: AccountId = vat.id
    ) = RecordSupplierCreditNoteUseCase.Request(
        companyId, periodId, today, apAccount, discountsAccount, vatAccount, lines.toList(), supplier, kind, schedule, supplyDate, description
    )

    @Test
    fun `given a 100 net UK price allowance, then Dr AP 120, Cr Discounts Received 100 and Cr VAT 20 tagged INPUT`() {
        val supplier = SupplierId.generate()

        val result = useCase.execute(request(line("100.00", VatCategory.STANDARD), supplier = supplier))

        val success = result.shouldBeInstanceOf<RecordSupplierCreditNoteResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.journalEntry.source shouldBe JournalSource.INTEGRATION
        val lines = success.journalEntry.lines
        lines.size shouldBe 3
        lines.single { it.accountId == ap.id }.let {
            it.side shouldBe TransactionSide.DEBIT
            it.amount shouldBe money("120.00")
            it.dimensions[DimensionType.VENDOR] shouldBe supplier.value.toString()
        }
        lines.single { it.accountId == discounts.id }.let {
            it.side shouldBe TransactionSide.CREDIT
            it.amount shouldBe money("100.00")
        }
        lines.single { it.accountId == vat.id }.let {
            it.side shouldBe TransactionSide.CREDIT
            it.amount shouldBe money("20.00")
            it.dimensions[DimensionType.VAT_CATEGORY] shouldBe "STANDARD"
            it.dimensions[DimensionType.VAT_DIRECTION] shouldBe "INPUT"
        }
        success.creditNoteTotal shouldBe money("120.00")
        success.vat shouldBe money("20.00")
    }

    @Test
    fun `given lines of several categories, then VAT is computed per category and grouped, and zero-rated and exempt add none`() {
        val result = useCase.execute(
            request(line("100.00", VatCategory.STANDARD), line("50.00", VatCategory.STANDARD), line("40.00", VatCategory.REDUCED), line("10.00", VatCategory.ZERO_RATED), line("5.00", VatCategory.EXEMPT))
        )

        val success = result.shouldBeInstanceOf<RecordSupplierCreditNoteResult.Success>()
        success.journalEntry.lines.filter { it.accountId == vat.id }.associate { it.dimensions[DimensionType.VAT_CATEGORY] to it.amount } shouldBe
            mapOf("STANDARD" to money("30.00"), "REDUCED" to money("2.00"))
        success.journalEntry.lines.single { it.accountId == discounts.id }.amount shouldBe money("205.00")
        success.vat shouldBe money("32.00")
        success.creditNoteTotal shouldBe money("237.00")
        success.journalEntry.lines.single { it.accountId == ap.id }.amount shouldBe money("237.00")
    }

    @Test
    fun `given only exempt or zero-rated lines, then there is no VAT leg`() {
        val result = useCase.execute(request(line("80.00", VatCategory.EXEMPT), line("20.00", VatCategory.ZERO_RATED)))

        val success = result.shouldBeInstanceOf<RecordSupplierCreditNoteResult.Success>()
        success.journalEntry.lines.none { it.accountId == vat.id } shouldBe true
        success.vat shouldBe money("0.00")
        success.creditNoteTotal shouldBe money("100.00")
    }

    @Test
    fun `given the rate changed after the supply date, then the VAT uses the rate on the supply date, and by default the credit note date`() {
        // Ireland's SECOND_REDUCED fell from 13.5 percent to 9 percent on 2026-07-01.
        val onSupplyDate = useCase.execute(request(line("100.00", VatCategory.SECOND_REDUCED), schedule = ieSchedule, supplyDate = LocalDate.of(2026, 6, 30)))
            .shouldBeInstanceOf<RecordSupplierCreditNoteResult.Success>()
        val byDefault = useCase.execute(request(line("100.00", VatCategory.SECOND_REDUCED), schedule = ieSchedule))
            .shouldBeInstanceOf<RecordSupplierCreditNoteResult.Success>()

        onSupplyDate.vat shouldBe money("13.50")
        byDefault.vat shouldBe money("9.00")
    }

    @Test
    fun `given a DISCOUNT and a REBATE, then both post the same accounts and the kind is in the description`() {
        val discount = useCase.execute(request(line("10.00", VatCategory.EXEMPT), kind = RecordSupplierCreditNoteUseCase.Kind.DISCOUNT, description = "damaged goods"))
            .shouldBeInstanceOf<RecordSupplierCreditNoteResult.Success>()
        val rebate = useCase.execute(request(line("10.00", VatCategory.EXEMPT), kind = RecordSupplierCreditNoteUseCase.Kind.REBATE))
            .shouldBeInstanceOf<RecordSupplierCreditNoteResult.Success>()

        discount.journalEntry.lines.map { it.accountId }.toSet() shouldBe rebate.journalEntry.lines.map { it.accountId }.toSet()
        discount.journalEntry.description shouldBe "DISCOUNT: damaged goods"
        rebate.journalEntry.description shouldBe "REBATE"
    }

    @Test
    fun `given a credit note larger than anything owed, then it posts, there is no balance check`() {
        val result = useCase.execute(request(line("1000000.00", VatCategory.EXEMPT)))

        result.shouldBeInstanceOf<RecordSupplierCreditNoteResult.Success>()
    }

    @Test
    fun `given a category the jurisdiction has no rate for, then it is refused and nothing is posted`() {
        val result = useCase.execute(request(line("20.00", VatCategory.SECOND_REDUCED)))

        result.shouldBeInstanceOf<RecordSupplierCreditNoteResult.VatCategoryNotSupported>().category shouldBe VatCategory.SECOND_REDUCED
        journalEntryRepository.saveCalls shouldBe emptyList()
        accountRepository.findAllByCompany(companyId).any { it.hasPostedActivity } shouldBe false
    }

    @Test
    fun `given a supply date before the first rate in force, then it is refused cleanly`() {
        val result = useCase.execute(request(line("20.00", VatCategory.STANDARD), supplyDate = LocalDate.of(1990, 1, 1)))

        result.shouldBeInstanceOf<RecordSupplierCreditNoteResult.VatRateNotInForce>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given no lines or a non-positive net amount, then it is an invalid amount`() {
        useCase.execute(request()).shouldBeInstanceOf<RecordSupplierCreditNoteResult.InvalidAmount>()
        useCase.execute(request(line("0.00", VatCategory.STANDARD))).shouldBeInstanceOf<RecordSupplierCreditNoteResult.InvalidAmount>()
        useCase.execute(request(line("-5.00", VatCategory.STANDARD))).shouldBeInstanceOf<RecordSupplierCreditNoteResult.InvalidAmount>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a Period of another Company, an unknown Period or one not open, then it is refused`() {
        val otherCompany = CompanyId.generate()
        val foreignPeriod = Period.create(otherCompany, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periodRepository.save(it) }
        val closedPeriod = Period.create(companyId, PeriodType.MONTH, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).also { periodRepository.save(it) }

        useCase.execute(request(line("10.00", VatCategory.EXEMPT), periodId = foreignPeriod.id)).shouldBeInstanceOf<RecordSupplierCreditNoteResult.PeriodNotFound>()
        useCase.execute(request(line("10.00", VatCategory.EXEMPT), periodId = PeriodId.generate())).shouldBeInstanceOf<RecordSupplierCreditNoteResult.PeriodNotFound>()
        useCase.execute(request(line("10.00", VatCategory.EXEMPT), periodId = closedPeriod.id)).shouldBeInstanceOf<RecordSupplierCreditNoteResult.PeriodNotOpen>()
    }

    @Test
    fun `given an account of another Company, then it is not found and nothing is posted`() {
        val other = CompanyId.generate()
        val foreignAp = account("2000", AccountType.LIABILITY, other)
        val foreignDiscounts = account("4920", AccountType.REVENUE, other)
        val foreignVat = account("2150", AccountType.LIABILITY, other)

        useCase.execute(request(line("10.00", VatCategory.EXEMPT), apAccount = foreignAp.id)).shouldBeInstanceOf<RecordSupplierCreditNoteResult.ApControlAccountNotFound>()
        useCase.execute(request(line("10.00", VatCategory.EXEMPT), discountsAccount = foreignDiscounts.id)).shouldBeInstanceOf<RecordSupplierCreditNoteResult.DiscountsReceivedAccountNotFound>()
        useCase.execute(request(line("10.00", VatCategory.STANDARD), vatAccount = foreignVat.id)).shouldBeInstanceOf<RecordSupplierCreditNoteResult.VatControlAccountNotFound>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a Discounts Received account that is not a revenue account, then it is refused`() {
        val notRevenue = account("5000", AccountType.EXPENSE)

        val result = useCase.execute(request(line("10.00", VatCategory.EXEMPT), discountsAccount = notRevenue.id))

        result.shouldBeInstanceOf<RecordSupplierCreditNoteResult.DiscountsReceivedAccountNotAllowed>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }
}
