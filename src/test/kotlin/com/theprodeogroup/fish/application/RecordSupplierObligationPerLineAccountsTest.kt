package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.tax.TestVatRates
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val PURCHASE_DATE = LocalDate.of(2026, 8, 20)

/**
 * The account a purchase line posts to is chosen by the type of purchase (Femi, 2026-10-18: rent, electricity and
 * consultants each its own account from the Company's chart; none set means Suspense, never a default account).
 * docs/GL_Per_Line_Account_Routing_Audit_And_Design.md. The reference posting is the one Femi approved: rent net
 * 1,000.00 plus 20% VAT.
 */
class RecordSupplierObligationPerLineAccountsTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordSupplierObligationUseCase(periodRepository, accountRepository, journalEntryRepository)

    private val companyId = CompanyId.generate()
    private val period = Period.create(companyId, PeriodType.MONTH, PURCHASE_DATE, PURCHASE_DATE.plusDays(30)).also {
        it.open()
        periodRepository.save(it)
    }

    private val ap = account("2000", AccountType.LIABILITY, "Accounts Payable")
    private val vat = account("2150", AccountType.LIABILITY, "VAT Control Account")
    private val rent = account("5100", AccountType.EXPENSE, "Rent")
    private val electricity = account("5110", AccountType.EXPENSE, "Electricity")
    private val supplierId = SupplierId.generate()

    private fun account(code: String, type: AccountType, name: String, companyId: CompanyId = this.companyId): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        return Account.create(companyId, type, classification, code, name).also { accountRepository.save(it) }
    }

    private fun suspense() = account("3910", AccountType.EQUITY, "Suspense Account")

    private fun line(net: String, category: VatCategory = VatCategory.STANDARD, accountId: AccountId? = null) =
        RecordSupplierObligationUseCase.PurchaseLine(Money(BigDecimal(net), GBP), category, accountId)

    private fun request(lines: List<RecordSupplierObligationUseCase.PurchaseLine>, legacyAccount: AccountId? = null) =
        RecordSupplierObligationUseCase.Request(
            companyId, period.id, PURCHASE_DATE, legacyAccount, ap.id, vat.id, lines, supplierId, TestVatRates.UK, "Rent for August"
        )

    private fun gbp(amount: String) = Money(BigDecimal(amount), GBP)

    private fun RecordSupplierObligationResult.entryLines() =
        shouldBeInstanceOf<RecordSupplierObligationResult.Success>().journalEntry.lines

    @Test
    fun `given a rent line with its own account, when executed, then Dr Rent net, Dr VAT Control input VAT tagged INPUT, Cr AP gross tagged to the supplier`() {
        val lines = useCase.execute(request(listOf(line("1000.00", accountId = rent.id)))).entryLines()

        lines.single { it.accountId == rent.id }.let {
            it.side shouldBe TransactionSide.DEBIT
            it.amount shouldBe gbp("1000.00")
        }
        lines.single { it.accountId == vat.id }.let {
            it.side shouldBe TransactionSide.DEBIT
            it.amount shouldBe gbp("200.00")
            it.dimensions shouldBe mapOf(DimensionType.VAT_CATEGORY to "STANDARD", DimensionType.VAT_DIRECTION to "INPUT")
        }
        lines.single { it.accountId == ap.id }.let {
            it.side shouldBe TransactionSide.CREDIT
            it.amount shouldBe gbp("1200.00")
            it.dimensions shouldBe mapOf(DimensionType.VENDOR to supplierId.value.toString())
        }
        lines.size shouldBe 3
    }

    @Test
    fun `given rent and electricity at different VAT rates, when executed, then each lands on its own account, VAT per category, one AP credit for the total`() {
        val lines = useCase.execute(
            request(
                listOf(
                    line("1000.00", VatCategory.STANDARD, rent.id),
                    line("400.00", VatCategory.REDUCED, electricity.id)
                )
            )
        ).entryLines()

        lines.filter { it.side == TransactionSide.DEBIT && it.accountId != vat.id }
            .associate { it.accountId to it.amount } shouldBe mapOf(rent.id to gbp("1000.00"), electricity.id to gbp("400.00"))
        lines.filter { it.accountId == vat.id }.associate { it.dimensions[DimensionType.VAT_CATEGORY] to it.amount } shouldBe
            mapOf("STANDARD" to gbp("200.00"), "REDUCED" to gbp("20.00"))
        lines.filter { it.accountId == ap.id }.single().amount shouldBe gbp("1620.00")
        lines.filter { it.side == TransactionSide.DEBIT }.sumOf { it.amount.amount } shouldBe
            lines.filter { it.side == TransactionSide.CREDIT }.sumOf { it.amount.amount }
    }

    @Test
    fun `given two lines on the same account, when executed, then they are summed into one debit line`() {
        val lines = useCase.execute(request(listOf(line("600.00", accountId = rent.id), line("400.00", accountId = rent.id)))).entryLines()

        lines.filter { it.accountId == rent.id }.map { it.amount } shouldBe listOf(gbp("1000.00"))
    }

    @Test
    fun `given a line with no account and no request-level account, when executed, then the net goes to Suspense and nothing else changes`() {
        val suspense = suspense()

        val lines = useCase.execute(request(listOf(line("1000.00")))).entryLines()

        lines.single { it.accountId == suspense.id }.let {
            it.side shouldBe TransactionSide.DEBIT
            it.amount shouldBe gbp("1000.00")
        }
        lines.single { it.accountId == vat.id }.amount shouldBe gbp("200.00")
        lines.single { it.accountId == ap.id }.amount shouldBe gbp("1200.00")
    }

    @Test
    fun `given no account anywhere and the Company has no Suspense account, when executed, then it is refused and nothing is posted`() {
        val result = useCase.execute(request(listOf(line("1000.00"))))

        result.shouldBeInstanceOf<RecordSupplierObligationResult.SuspenseAccountNotConfigured>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a legacy caller with only the request-level account, when executed, then every line posts to it as before`() {
        val legacy = account("5000", AccountType.EXPENSE, "Operating Expenses")

        val lines = useCase.execute(request(listOf(line("700.00"), line("300.00")), legacy.id)).entryLines()

        lines.filter { it.accountId == legacy.id }.map { it.amount } shouldBe listOf(gbp("1000.00"))
    }

    @Test
    fun `given a line account and a request-level account, when executed, then the line account wins for its line and the other lines use the request-level one`() {
        val legacy = account("5000", AccountType.EXPENSE, "Operating Expenses")

        val lines = useCase.execute(request(listOf(line("1000.00", accountId = rent.id), line("100.00")), legacy.id)).entryLines()

        lines.filter { it.side == TransactionSide.DEBIT && it.accountId != vat.id }.associate { it.accountId to it.amount } shouldBe
            mapOf(rent.id to gbp("1000.00"), legacy.id to gbp("100.00"))
    }

    @Test
    fun `given a line account that is explicitly Suspense, when executed, then it is accepted`() {
        val suspense = suspense()

        val lines = useCase.execute(request(listOf(line("1000.00", accountId = suspense.id)))).entryLines()

        lines.map { it.accountId } shouldContainExactlyInAnyOrder listOf(suspense.id, vat.id, ap.id)
    }

    @Test
    fun `given a line account of another Company, when executed, then it is not found and nothing is posted`() {
        val foreign = account("5100", AccountType.EXPENSE, "Rent", CompanyId.generate())

        val result = useCase.execute(request(listOf(line("1000.00", accountId = foreign.id))))

        result.shouldBeInstanceOf<RecordSupplierObligationResult.LineAccountNotFound>().accountId shouldBe foreign.id
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a line account that does not exist, when executed, then it is not found`() {
        val missing = AccountId.generate()

        useCase.execute(request(listOf(line("1000.00", accountId = missing))))
            .shouldBeInstanceOf<RecordSupplierObligationResult.LineAccountNotFound>().accountId shouldBe missing
    }

    @Test
    fun `given a revenue account on a purchase line, when executed, then it is refused as the wrong type`() {
        val sales = account("4000", AccountType.REVENUE, "Sales Revenue")

        val refused = useCase.execute(request(listOf(line("1000.00", accountId = sales.id))))
            .shouldBeInstanceOf<RecordSupplierObligationResult.LineAccountNotAllowed>()

        refused.accountId shouldBe sales.id
        refused.reason shouldBe LineAccountResolver.Reason.WRONG_TYPE
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a control or module-owned account on a purchase line, when executed, then it is refused`() {
        val inventory = account("1300", AccountType.ASSET, "Inventory")
        val receivable = account("1100", AccountType.ASSET, "Accounts Receivable")
        val fixedAssets = account("1200", AccountType.ASSET, "Fixed Assets")

        for (blocked in listOf(inventory, receivable, fixedAssets)) {
            useCase.execute(request(listOf(line("1000.00", accountId = blocked.id))))
                .shouldBeInstanceOf<RecordSupplierObligationResult.LineAccountNotAllowed>()
                .reason shouldBe LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED
        }
        // The payable and VAT control accounts are liabilities, so they are refused as the wrong type.
        for (blocked in listOf(ap, vat)) {
            useCase.execute(request(listOf(line("1000.00", accountId = blocked.id))))
                .shouldBeInstanceOf<RecordSupplierObligationResult.LineAccountNotAllowed>()
        }
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given an inactive account on a purchase line, when executed, then it is refused`() {
        rent.deactivate()
        accountRepository.save(rent)

        useCase.execute(request(listOf(line("1000.00", accountId = rent.id))))
            .shouldBeInstanceOf<RecordSupplierObligationResult.LineAccountNotAllowed>()
            .reason shouldBe LineAccountResolver.Reason.INACTIVE
    }

    @Test
    fun `given a prepayment-style asset account on a purchase line, when executed, then it is accepted`() {
        val prepayments = account("1150", AccountType.ASSET, "Prepayments")

        useCase.execute(request(listOf(line("1000.00", accountId = prepayments.id)))).entryLines()
            .single { it.accountId == prepayments.id }.amount shouldBe gbp("1000.00")
    }

    @Test
    fun `given one bad line among good ones, when executed, then the whole purchase is refused`() {
        val sales = account("4000", AccountType.REVENUE, "Sales Revenue")

        useCase.execute(request(listOf(line("1000.00", accountId = rent.id), line("100.00", accountId = sales.id))))
            .shouldBeInstanceOf<RecordSupplierObligationResult.LineAccountNotAllowed>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }
}
