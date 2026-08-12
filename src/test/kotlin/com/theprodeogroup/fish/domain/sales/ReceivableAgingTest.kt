package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val AS_OF = LocalDate.of(2026, 6, 30)

class ReceivableAgingTest {

    @Test
    fun `given no entries, when aging is computed, then every bucket is zero`() {
        val aging = ReceivableAging.of(CustomerId.generate(), AccountId.generate(), emptyList(), AS_OF, GBP)

        aging.totalOutstanding shouldBe zero()
        aging.buckets.forEach { it.amount shouldBe zero() }
    }

    @Test
    fun `given one unpaid sale within 30 days, when aging is computed, then it falls in the Current bucket`() {
        val customerId = CustomerId.generate()
        val arAccountId = AccountId.generate()
        val entry = saleEntry(customerId, arAccountId, Money(BigDecimal("100.00"), GBP), AS_OF.minusDays(10))

        val aging = ReceivableAging.of(customerId, arAccountId, listOf(entry), AS_OF, GBP)

        aging.buckets.first { it.label == AgingBucketLabel.CURRENT }.amount shouldBe Money(BigDecimal("100.00"), GBP)
        aging.totalOutstanding shouldBe Money(BigDecimal("100.00"), GBP)
    }

    @Test
    fun `given a sale fully offset by a receipt, when aging is computed, then it is excluded entirely`() {
        val customerId = CustomerId.generate()
        val arAccountId = AccountId.generate()
        val sale = saleEntry(customerId, arAccountId, Money(BigDecimal("100.00"), GBP), AS_OF.minusDays(10))
        val receipt = receiptEntry(customerId, arAccountId, Money(BigDecimal("100.00"), GBP), AS_OF.minusDays(5))

        val aging = ReceivableAging.of(customerId, arAccountId, listOf(sale, receipt), AS_OF, GBP)

        aging.totalOutstanding shouldBe zero()
    }

    @Test
    fun `given a sale partially paid, when aging is computed, then only the remaining unpaid amount appears`() {
        val customerId = CustomerId.generate()
        val arAccountId = AccountId.generate()
        val sale = saleEntry(customerId, arAccountId, Money(BigDecimal("100.00"), GBP), AS_OF.minusDays(10))
        val receipt = receiptEntry(customerId, arAccountId, Money(BigDecimal("40.00"), GBP), AS_OF.minusDays(5))

        val aging = ReceivableAging.of(customerId, arAccountId, listOf(sale, receipt), AS_OF, GBP)

        aging.totalOutstanding shouldBe Money(BigDecimal("60.00"), GBP)
        aging.buckets.first { it.label == AgingBucketLabel.CURRENT }.amount shouldBe Money(BigDecimal("60.00"), GBP)
    }

    @Test
    fun `given an older sale fully offset and a newer sale unpaid, when aging is computed, then receipts apply oldest-first`() {
        val customerId = CustomerId.generate()
        val arAccountId = AccountId.generate()
        val olderSale = saleEntry(customerId, arAccountId, Money(BigDecimal("50.00"), GBP), AS_OF.minusDays(100))
        val newerSale = saleEntry(customerId, arAccountId, Money(BigDecimal("50.00"), GBP), AS_OF.minusDays(10))
        val receipt = receiptEntry(customerId, arAccountId, Money(BigDecimal("50.00"), GBP), AS_OF.minusDays(50))

        val aging = ReceivableAging.of(customerId, arAccountId, listOf(olderSale, newerSale, receipt), AS_OF, GBP)

        // the receipt should have paid off the OLDER sale first, leaving the newer (recent) sale unpaid
        aging.buckets.first { it.label == AgingBucketLabel.CURRENT }.amount shouldBe Money(BigDecimal("50.00"), GBP)
        aging.buckets.first { it.label == AgingBucketLabel.OVER_90 }.amount shouldBe zero()
    }

    @Test
    fun `given sales aged into each bucket, when aging is computed, then each bucket has the correct amount`() {
        val customerId = CustomerId.generate()
        val arAccountId = AccountId.generate()
        val current = saleEntry(customerId, arAccountId, Money(BigDecimal("10.00"), GBP), AS_OF.minusDays(10))
        val bucket3160 = saleEntry(customerId, arAccountId, Money(BigDecimal("20.00"), GBP), AS_OF.minusDays(45))
        val bucket6190 = saleEntry(customerId, arAccountId, Money(BigDecimal("30.00"), GBP), AS_OF.minusDays(75))
        val over90 = saleEntry(customerId, arAccountId, Money(BigDecimal("40.00"), GBP), AS_OF.minusDays(120))

        val aging = ReceivableAging.of(
            customerId, arAccountId, listOf(current, bucket3160, bucket6190, over90), AS_OF, GBP
        )

        aging.buckets.first { it.label == AgingBucketLabel.CURRENT }.amount shouldBe Money(BigDecimal("10.00"), GBP)
        aging.buckets.first { it.label == AgingBucketLabel.DAYS_31_TO_60 }.amount shouldBe Money(BigDecimal("20.00"), GBP)
        aging.buckets.first { it.label == AgingBucketLabel.DAYS_61_TO_90 }.amount shouldBe Money(BigDecimal("30.00"), GBP)
        aging.buckets.first { it.label == AgingBucketLabel.OVER_90 }.amount shouldBe Money(BigDecimal("40.00"), GBP)
        aging.totalOutstanding shouldBe Money(BigDecimal("100.00"), GBP)
    }

    @Test
    fun `given a sale belonging to a different Customer, when aging is computed, then it is excluded`() {
        val customerId = CustomerId.generate()
        val otherCustomerId = CustomerId.generate()
        val arAccountId = AccountId.generate()
        val entry = saleEntry(otherCustomerId, arAccountId, Money(BigDecimal("100.00"), GBP), AS_OF.minusDays(10))

        val aging = ReceivableAging.of(customerId, arAccountId, listOf(entry), AS_OF, GBP)

        aging.totalOutstanding shouldBe zero()
    }

    @Test
    fun `given an unposted Draft sale, when aging is computed, then it is excluded`() {
        val customerId = CustomerId.generate()
        val arAccountId = AccountId.generate()
        val draft = JournalEntry.create(
            PeriodId.generate(), AS_OF.minusDays(10),
            listOf(
                JournalLine(
                    arAccountId, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT,
                    mapOf(DimensionType.CUSTOMER to customerId.value.toString())
                ),
                JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )

        val aging = ReceivableAging.of(customerId, arAccountId, listOf(draft), AS_OF, GBP)

        aging.totalOutstanding shouldBe zero()
    }

    private fun saleEntry(customerId: CustomerId, arAccountId: AccountId, amount: Money, date: LocalDate): JournalEntry {
        val entry = JournalEntry.create(
            PeriodId.generate(), date,
            listOf(
                JournalLine(
                    arAccountId, amount, TransactionSide.DEBIT,
                    mapOf(DimensionType.CUSTOMER to customerId.value.toString())
                ),
                JournalLine(AccountId.generate(), amount, TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        entry.post()
        return entry
    }

    private fun receiptEntry(customerId: CustomerId, arAccountId: AccountId, amount: Money, date: LocalDate): JournalEntry {
        val entry = JournalEntry.create(
            PeriodId.generate(), date,
            listOf(
                JournalLine(AccountId.generate(), amount, TransactionSide.DEBIT),
                JournalLine(
                    arAccountId, amount, TransactionSide.CREDIT,
                    mapOf(DimensionType.CUSTOMER to customerId.value.toString())
                )
            ),
            JournalSource.MANUAL
        )
        entry.post()
        return entry
    }

    private fun zero(): Money = Money(BigDecimal.ZERO, GBP)
}
