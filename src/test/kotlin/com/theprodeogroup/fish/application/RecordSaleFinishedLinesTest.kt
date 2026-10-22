package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tax.TestVatRates
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val DAY = LocalDate.of(2026, 10, 12)

/**
 * `record-sale` with finished VAT amounts (GL-T-26, GL-FR-TAX-7, GL-UC-2; Femi 2026-10-22, F22-18: "VAT is associated with the
 * product or service, so SOP calculates it. GL does not compute or preview VAT. It posts the balanced journal SOP sends"). A line
 * may carry [RecordSaleUseCase.SaleLine.vatAmount]; when every line does, GL posts the amounts as given and reads no rate. When none
 * does, GL computes as before (retired later, GL-T-26b).
 */
class RecordSaleFinishedLinesTest {

    private val periods = FakePeriodRepository()
    private val accounts = FakeAccountRepository()
    private val entries = FakeJournalEntryRepository()
    private val useCase = RecordSaleUseCase(periods, accounts, entries)

    private val companyId = CompanyId.generate()
    private val period: Period = Period.create(companyId, PeriodType.MONTH, DAY, DAY.plusDays(30)).also { it.open(); periods.save(it) }
    private val ar = account("1100", AccountType.ASSET)
    private val sales = account("4000", AccountType.REVENUE)
    private val services = account("4100", AccountType.REVENUE)
    private val vat = account("2150", AccountType.LIABILITY)

    private fun account(code: String, type: AccountType) =
        Account.create(companyId, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, "Account $code")
            .also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), GBP)

    private fun line(net: String, category: VatCategory, vatAmount: String? = null, account: Account? = null) =
        RecordSaleUseCase.SaleLine(money(net), category, account?.id, vatAmount?.let { money(it) })

    private fun request(lines: List<RecordSaleUseCase.SaleLine>, schedule: com.theprodeogroup.fish.domain.tax.VatRateSchedule? = TestVatRates.UK) =
        RecordSaleUseCase.Request(companyId, period.id, DAY, ar.id, null, vat.id, lines, CustomerId.generate(), schedule)

    private fun success(result: RecordSaleResult) = result.shouldBeInstanceOf<RecordSaleResult.Success>()

    private fun shape(entry: com.theprodeogroup.fish.domain.ledger.JournalEntry) =
        entry.lines.map { Triple(it.accountId, it.side, it.amount.amount.toPlainString()) }

    @Test
    fun `given finished VAT amounts equal to what GL would compute, then the entry is line for line the entry GL computes itself`() {
        val computed = success(
            useCase.execute(request(listOf(line("1000.00", VatCategory.STANDARD, account = sales), line("400.00", VatCategory.REDUCED, account = services))))
        )
        val finished = success(
            useCase.execute(
                request(listOf(line("1000.00", VatCategory.STANDARD, "200.00", sales), line("400.00", VatCategory.REDUCED, "20.00", services)))
            )
        )

        shape(finished.journalEntry) shouldBe shape(computed.journalEntry)
        finished.journalEntry.lines.map { it.dimensions.filterKeys { k -> k != DimensionType.CUSTOMER } } shouldBe
            computed.journalEntry.lines.map { it.dimensions.filterKeys { k -> k != DimensionType.CUSTOMER } }
        finished.grossTotal shouldBe computed.grossTotal
        finished.vatTotal shouldBe computed.vatTotal
        finished.grossTotal shouldBe money("1620.00")
        finished.vatTotal shouldBe money("220.00")
    }

    @Test
    fun `given finished amounts and no rate schedule at all, then the sale posts, because no rate is read`() {
        val result = success(useCase.execute(request(listOf(line("1000.00", VatCategory.STANDARD, "200.00", sales)), schedule = null)))

        result.vatTotal shouldBe money("200.00")
        result.journalEntry.lines.first { it.accountId == vat.id }.dimensions shouldBe
            mapOf(DimensionType.VAT_CATEGORY to "STANDARD", DimensionType.VAT_DIRECTION to "OUTPUT")
    }

    @Test
    fun `given a VAT amount that differs from the schedule, then GL posts the amount it was given`() {
        val result = success(useCase.execute(request(listOf(line("1000.00", VatCategory.STANDARD, "150.00", sales)))))

        result.journalEntry.lines.first { it.accountId == vat.id }.amount shouldBe money("150.00")
        result.grossTotal shouldBe money("1150.00")
        result.journalEntry.lines.first { it.accountId == ar.id }.amount shouldBe money("1150.00")
    }

    @Test
    fun `given two lines of one category, then their given VAT is summed into one VAT line, never recomputed on the total`() {
        // 0.25 at 10% is 0.02 each by the per-line rule; the sum 0.04 is posted, not 0.05 (10% of the 0.50 total).
        val result = success(useCase.execute(request(listOf(line("0.25", VatCategory.STANDARD, "0.02", sales), line("0.25", VatCategory.STANDARD, "0.02", sales)))))

        val vatLines = result.journalEntry.lines.filter { it.accountId == vat.id }
        vatLines.size shouldBe 1
        vatLines.single().amount shouldBe money("0.04")
    }

    @Test
    fun `given a category whose given VAT is zero, then no VAT line is posted for it`() {
        val result = success(
            useCase.execute(request(listOf(line("1000.00", VatCategory.STANDARD, "200.00", sales), line("300.00", VatCategory.ZERO_RATED, "0.00", services))))
        )

        result.journalEntry.lines.filter { it.accountId == vat.id }.map { it.dimensions[DimensionType.VAT_CATEGORY] } shouldBe listOf("STANDARD")
        result.grossTotal shouldBe money("1500.00")
    }

    @Test
    fun `given some lines with a VAT amount and some without, then it is refused and nothing is saved`() {
        val before = entries.saveCalls.size

        useCase.execute(request(listOf(line("1000.00", VatCategory.STANDARD, "200.00", sales), line("400.00", VatCategory.REDUCED, null, services)))) shouldBe
            RecordSaleResult.VatAmountOnEveryLineRequired
        entries.saveCalls.size shouldBe before
    }

    @Test
    fun `given lines with no VAT amount and no schedule, then GL cannot compute and says so`() {
        shouldThrow<IllegalArgumentException> { useCase.execute(request(listOf(line("1000.00", VatCategory.STANDARD, null, sales)), schedule = null)) }
    }

    @Test
    fun `given a negative VAT amount or one in another currency, then the line cannot be built`() {
        shouldThrow<IllegalArgumentException> { line("100.00", VatCategory.STANDARD, "-1.00") }
        shouldThrow<IllegalArgumentException> {
            RecordSaleUseCase.SaleLine(money("100.00"), VatCategory.STANDARD, null, Money(BigDecimal("20.00"), Currency.getInstance("EUR")))
        }
    }

    @Test
    fun `given finished amounts, then the unrelated refusals still hold`() {
        val unknownVat = AccountId.generate()

        useCase.execute(request(listOf(line("100.00", VatCategory.STANDARD, "20.00", sales))).copy(vatControlAccountId = unknownVat)) shouldBe
            RecordSaleResult.VatControlAccountNotFound(unknownVat)
        useCase.execute(request(listOf(line("100.00", VatCategory.STANDARD, "20.00", sales))).copy(periodId = PeriodId.generate())) shouldBe
            RecordSaleResult.PeriodNotFound
    }
}
