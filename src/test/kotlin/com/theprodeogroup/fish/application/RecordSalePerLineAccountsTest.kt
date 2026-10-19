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
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tax.TestVatRates
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val SALE_DATE = LocalDate.of(2026, 8, 20)

/**
 * The sales mirror of [RecordSupplierObligationPerLineAccountsTest]: the revenue account of a sale is chosen by the
 * type of sale (goods, services, fees, interest), one account per line from the Company's chart; none set means
 * Suspense (Femi, 2026-10-18). docs/GL_Per_Line_Account_Routing_Audit_And_Design.md.
 */
class RecordSalePerLineAccountsTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordSaleUseCase(periodRepository, accountRepository, journalEntryRepository)

    private val companyId = CompanyId.generate()
    private val period = Period.create(companyId, PeriodType.MONTH, SALE_DATE, SALE_DATE.plusDays(30)).also {
        it.open()
        periodRepository.save(it)
    }

    private val ar = account("1100", AccountType.ASSET, "Accounts Receivable")
    private val vat = account("2150", AccountType.LIABILITY, "VAT Control Account")
    private val goods = account("4010", AccountType.REVENUE, "Sales of goods")
    private val fees = account("4020", AccountType.REVENUE, "Fee income")
    private val customerId = CustomerId.generate()

    private fun account(code: String, type: AccountType, name: String, companyId: CompanyId = this.companyId): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        return Account.create(companyId, type, classification, code, name).also { accountRepository.save(it) }
    }

    private fun suspense() = account("3910", AccountType.EQUITY, "Suspense Account")

    private fun line(net: String, category: VatCategory = VatCategory.STANDARD, accountId: AccountId? = null) =
        RecordSaleUseCase.SaleLine(Money(BigDecimal(net), GBP), category, accountId)

    private fun request(lines: List<RecordSaleUseCase.SaleLine>, legacyAccount: AccountId? = null) =
        RecordSaleUseCase.Request(companyId, period.id, SALE_DATE, ar.id, legacyAccount, vat.id, lines, customerId, TestVatRates.UK, "Sale")

    private fun gbp(amount: String) = Money(BigDecimal(amount), GBP)

    private fun RecordSaleResult.entryLines() = shouldBeInstanceOf<RecordSaleResult.Success>().journalEntry.lines

    @Test
    fun `given goods and fees on different accounts, when executed, then each credits its own revenue account, VAT per category tagged OUTPUT, one AR debit for the gross`() {
        val lines = useCase.execute(
            request(listOf(line("1000.00", VatCategory.STANDARD, goods.id), line("200.00", VatCategory.ZERO_RATED, fees.id)))
        ).entryLines()

        lines.filter { it.side == TransactionSide.CREDIT && it.accountId != vat.id }.associate { it.accountId to it.amount } shouldBe
            mapOf(goods.id to gbp("1000.00"), fees.id to gbp("200.00"))
        lines.single { it.accountId == vat.id && it.dimensions[DimensionType.VAT_CATEGORY] == "STANDARD" }.let {
            it.side shouldBe TransactionSide.CREDIT
            it.amount shouldBe gbp("200.00")
            it.dimensions[DimensionType.VAT_DIRECTION] shouldBe "OUTPUT"
        }
        lines.single { it.accountId == ar.id }.let {
            it.side shouldBe TransactionSide.DEBIT
            it.amount shouldBe gbp("1400.00")
            it.dimensions shouldBe mapOf(DimensionType.CUSTOMER to customerId.value.toString())
        }
        lines.filter { it.side == TransactionSide.DEBIT }.sumOf { it.amount.amount } shouldBe
            lines.filter { it.side == TransactionSide.CREDIT }.sumOf { it.amount.amount }
    }

    @Test
    fun `given two lines on the same revenue account, when executed, then they are summed into one credit line`() {
        val lines = useCase.execute(request(listOf(line("600.00", accountId = goods.id), line("400.00", accountId = goods.id)))).entryLines()

        lines.filter { it.accountId == goods.id }.map { it.amount } shouldBe listOf(gbp("1000.00"))
    }

    @Test
    fun `given a line with no account and no request-level account, when executed, then the net credits Suspense`() {
        val suspense = suspense()

        val lines = useCase.execute(request(listOf(line("1000.00")))).entryLines()

        lines.single { it.accountId == suspense.id }.let {
            it.side shouldBe TransactionSide.CREDIT
            it.amount shouldBe gbp("1000.00")
        }
    }

    @Test
    fun `given no account anywhere and no Suspense account, when executed, then it is refused and nothing is posted`() {
        useCase.execute(request(listOf(line("1000.00")))).shouldBeInstanceOf<RecordSaleResult.SuspenseAccountNotConfigured>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a legacy caller with only the request-level revenue account, when executed, then every line credits it as before`() {
        val legacy = account("4000", AccountType.REVENUE, "Sales Revenue")

        val lines = useCase.execute(request(listOf(line("700.00"), line("300.00")), legacy.id)).entryLines()

        lines.filter { it.accountId == legacy.id }.map { it.amount } shouldBe listOf(gbp("1000.00"))
    }

    @Test
    fun `given a foreign, wrong-type, control or inactive account on a sale line, when executed, then it is refused and nothing is posted`() {
        val foreign = account("4010", AccountType.REVENUE, "Sales", CompanyId.generate())
        val expense = account("5000", AccountType.EXPENSE, "Operating Expenses")
        val discountsReceived = account("4920", AccountType.REVENUE, "Discounts Received")
        val dormant = account("4030", AccountType.REVENUE, "Old income").also { it.deactivate(); accountRepository.save(it) }

        useCase.execute(request(listOf(line("100.00", accountId = foreign.id)))).shouldBeInstanceOf<RecordSaleResult.LineAccountNotFound>()
        useCase.execute(request(listOf(line("100.00", accountId = expense.id)))).shouldBeInstanceOf<RecordSaleResult.LineAccountNotAllowed>()
            .reason shouldBe LineAccountResolver.Reason.WRONG_TYPE
        useCase.execute(request(listOf(line("100.00", accountId = discountsReceived.id)))).shouldBeInstanceOf<RecordSaleResult.LineAccountNotAllowed>()
            .reason shouldBe LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED
        useCase.execute(request(listOf(line("100.00", accountId = dormant.id)))).shouldBeInstanceOf<RecordSaleResult.LineAccountNotAllowed>()
            .reason shouldBe LineAccountResolver.Reason.INACTIVE
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given an explicit Suspense line account, when executed, then it is accepted`() {
        val suspense = suspense()

        useCase.execute(request(listOf(line("100.00", accountId = suspense.id)))).entryLines()
            .single { it.accountId == suspense.id }.amount shouldBe gbp("100.00")
    }
}
