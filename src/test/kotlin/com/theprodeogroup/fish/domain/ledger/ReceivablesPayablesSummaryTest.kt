package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.purchasing.AccountsPayableAging
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.sales.AccountsReceivableAging
import com.theprodeogroup.fish.domain.sales.CustomerId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/** The unpaid items the aging exposes, and the summary the dashboard shows, built from them. */
class ReceivablesPayablesSummaryTest {

    private val gbp = Currency.getInstance("GBP")
    private val asOf = LocalDate.of(2026, 10, 12)
    private val periodId = PeriodId.generate()
    private val ar = AccountId.generate()
    private val ap = AccountId.generate()
    private val other = AccountId.generate()
    private val customer = CustomerId.generate()
    private val supplier = SupplierId.generate()

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun entry(date: LocalDate, vararg lines: JournalLine) =
        JournalEntry.create(periodId, date, lines.toList(), JournalSource.INTEGRATION, "x").also { it.post() }

    private fun sale(daysOld: Long, amount: String) = entry(
        asOf.minusDays(daysOld),
        JournalLine(ar, money(amount), TransactionSide.DEBIT, mapOf(DimensionType.CUSTOMER to customer.value.toString())),
        JournalLine(other, money(amount), TransactionSide.CREDIT)
    )

    private fun receipt(daysOld: Long, amount: String) = entry(
        asOf.minusDays(daysOld),
        JournalLine(other, money(amount), TransactionSide.DEBIT),
        JournalLine(ar, money(amount), TransactionSide.CREDIT, mapOf(DimensionType.CUSTOMER to customer.value.toString()))
    )

    private fun purchase(daysOld: Long, amount: String) = entry(
        asOf.minusDays(daysOld),
        JournalLine(other, money(amount), TransactionSide.DEBIT),
        JournalLine(ap, money(amount), TransactionSide.CREDIT, mapOf(DimensionType.VENDOR to supplier.value.toString()))
    )

    private fun payment(daysOld: Long, amount: String) = entry(
        asOf.minusDays(daysOld),
        JournalLine(ap, money(amount), TransactionSide.DEBIT, mapOf(DimensionType.VENDOR to supplier.value.toString())),
        JournalLine(other, money(amount), TransactionSide.CREDIT)
    )

    @Test
    fun `given receipts that part-pay the oldest sales, then the unpaid items are the remainders, oldest first, and they add up to the buckets`() {
        val entries = listOf(sale(100, "100.00"), sale(70, "200.00"), sale(40, "300.00"), sale(5, "400.00"), receipt(2, "250.00"))

        val aging = AccountsReceivableAging.of(customer, ar, entries, asOf, gbp)

        aging.unpaidItems.map { it.date } shouldBe listOf(asOf.minusDays(70), asOf.minusDays(40), asOf.minusDays(5))
        aging.unpaidItems.map { it.amount } shouldBe listOf(money("50.00"), money("300.00"), money("400.00"))
        aging.unpaidItems.map { it.bucket } shouldBe listOf(AgingBucketLabel.DAYS_61_TO_90, AgingBucketLabel.DAYS_31_TO_60, AgingBucketLabel.CURRENT)
        aging.unpaidItems.fold(money("0.00")) { sum, item -> sum + item.amount } shouldBe aging.totalOutstanding
        AgingBucketLabel.entries.forEach { label ->
            aging.unpaidItems.filter { it.bucket == label }.fold(money("0.00")) { sum, item -> sum + item.amount } shouldBe
                aging.buckets.first { it.label == label }.amount
        }
    }

    @Test
    fun `given payables, then the same items are exposed for a supplier`() {
        val aging = AccountsPayableAging.of(supplier, ap, listOf(purchase(45, "500.00"), purchase(3, "100.00"), payment(1, "200.00")), asOf, gbp)

        aging.unpaidItems.map { it.amount } shouldBe listOf(money("300.00"), money("100.00"))
        aging.unpaidItems.map { it.bucket } shouldBe listOf(AgingBucketLabel.DAYS_31_TO_60, AgingBucketLabel.CURRENT)
    }

    @Test
    fun `the bucket edges are 30, 60 and 90 days`() {
        AgingBucketLabel.forDaysOld(0) shouldBe AgingBucketLabel.CURRENT
        AgingBucketLabel.forDaysOld(30) shouldBe AgingBucketLabel.CURRENT
        AgingBucketLabel.forDaysOld(31) shouldBe AgingBucketLabel.DAYS_31_TO_60
        AgingBucketLabel.forDaysOld(60) shouldBe AgingBucketLabel.DAYS_31_TO_60
        AgingBucketLabel.forDaysOld(61) shouldBe AgingBucketLabel.DAYS_61_TO_90
        AgingBucketLabel.forDaysOld(90) shouldBe AgingBucketLabel.DAYS_61_TO_90
        AgingBucketLabel.forDaysOld(91) shouldBe AgingBucketLabel.OVER_90
    }

    @Test
    fun `given unpaid items, then total is everything, overdue is everything outside CURRENT, and the count is the number of older items`() {
        val items = listOf(
            UnpaidItem(asOf.minusDays(5), money("400.00"), AgingBucketLabel.CURRENT),
            UnpaidItem(asOf.minusDays(30), money("10.00"), AgingBucketLabel.CURRENT),
            UnpaidItem(asOf.minusDays(40), money("300.00"), AgingBucketLabel.DAYS_31_TO_60),
            UnpaidItem(asOf.minusDays(70), money("50.00"), AgingBucketLabel.DAYS_61_TO_90),
            UnpaidItem(asOf.minusDays(100), money("7.25"), AgingBucketLabel.OVER_90)
        )

        val side = ReceivablesPayablesSummary.Side.of(items, gbp)

        side.total shouldBe money("767.25")
        side.overdueTotal shouldBe money("357.25")
        side.overdueCount shouldBe 3
    }

    @Test
    fun `given nothing unpaid, then every figure is zero`() {
        val side = ReceivablesPayablesSummary.Side.of(emptyList(), gbp)

        side.total shouldBe money("0.00")
        side.overdueTotal shouldBe money("0.00")
        side.overdueCount shouldBe 0
    }

    @Test
    fun `the two sides are independent and the summary carries the currency and the date`() {
        val summary = ReceivablesPayablesSummary.of(
            listOf(UnpaidItem(asOf.minusDays(40), money("100.00"), AgingBucketLabel.DAYS_31_TO_60)),
            listOf(UnpaidItem(asOf.minusDays(1), money("20.00"), AgingBucketLabel.CURRENT)),
            asOf, gbp
        )

        summary.receivables.overdueTotal shouldBe money("100.00")
        summary.payables.overdueTotal shouldBe money("0.00")
        summary.payables.total shouldBe money("20.00")
        summary.currency shouldBe gbp
        summary.asOf shouldBe asOf
    }
}
