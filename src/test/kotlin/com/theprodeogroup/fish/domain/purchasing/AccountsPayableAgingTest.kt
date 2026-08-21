package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val AS_OF = LocalDate.of(2026, 6, 30)

class AccountsPayableAgingTest {

    @Test
    fun `given no entries, when aging is computed, then every bucket is zero`() {
        val aging = AccountsPayableAging.of(CreditorId.generate(), AccountId.generate(), emptyList(), AS_OF, GBP)

        aging.totalOutstanding shouldBe zero()
        aging.buckets.forEach { it.amount shouldBe zero() }
    }

    @Test
    fun `given one unpaid charge within 30 days, when aging is computed, then it falls in the Current bucket`() {
        val creditorId = CreditorId.generate()
        val apAccountId = AccountId.generate()
        val entry = chargeEntry(creditorId, apAccountId, Money(BigDecimal("100.00"), GBP), AS_OF.minusDays(10))

        val aging = AccountsPayableAging.of(creditorId, apAccountId, listOf(entry), AS_OF, GBP)

        aging.buckets.first { it.label == AgingBucketLabel.CURRENT }.amount shouldBe Money(BigDecimal("100.00"), GBP)
        aging.totalOutstanding shouldBe Money(BigDecimal("100.00"), GBP)
    }

    @Test
    fun `given a charge fully offset by a payment, when aging is computed, then it is excluded entirely`() {
        val creditorId = CreditorId.generate()
        val apAccountId = AccountId.generate()
        val charge = chargeEntry(creditorId, apAccountId, Money(BigDecimal("100.00"), GBP), AS_OF.minusDays(10))
        val payment = paymentEntry(creditorId, apAccountId, Money(BigDecimal("100.00"), GBP), AS_OF.minusDays(5))

        val aging = AccountsPayableAging.of(creditorId, apAccountId, listOf(charge, payment), AS_OF, GBP)

        aging.totalOutstanding shouldBe zero()
    }

    @Test
    fun `given a charge partially paid, when aging is computed, then only the remaining unpaid amount appears`() {
        val creditorId = CreditorId.generate()
        val apAccountId = AccountId.generate()
        val charge = chargeEntry(creditorId, apAccountId, Money(BigDecimal("100.00"), GBP), AS_OF.minusDays(10))
        val payment = paymentEntry(creditorId, apAccountId, Money(BigDecimal("40.00"), GBP), AS_OF.minusDays(5))

        val aging = AccountsPayableAging.of(creditorId, apAccountId, listOf(charge, payment), AS_OF, GBP)

        aging.totalOutstanding shouldBe Money(BigDecimal("60.00"), GBP)
        aging.buckets.first { it.label == AgingBucketLabel.CURRENT }.amount shouldBe Money(BigDecimal("60.00"), GBP)
    }

    @Test
    fun `given an older charge fully offset and a newer charge unpaid, when aging is computed, then payments apply oldest-first`() {
        val creditorId = CreditorId.generate()
        val apAccountId = AccountId.generate()
        val olderCharge = chargeEntry(creditorId, apAccountId, Money(BigDecimal("50.00"), GBP), AS_OF.minusDays(100))
        val newerCharge = chargeEntry(creditorId, apAccountId, Money(BigDecimal("50.00"), GBP), AS_OF.minusDays(10))
        val payment = paymentEntry(creditorId, apAccountId, Money(BigDecimal("50.00"), GBP), AS_OF.minusDays(50))

        val aging = AccountsPayableAging.of(creditorId, apAccountId, listOf(olderCharge, newerCharge, payment), AS_OF, GBP)

        aging.buckets.first { it.label == AgingBucketLabel.CURRENT }.amount shouldBe Money(BigDecimal("50.00"), GBP)
        aging.buckets.first { it.label == AgingBucketLabel.OVER_90 }.amount shouldBe zero()
    }

    @Test
    fun `given charges aged into each bucket, when aging is computed, then each bucket has the correct amount`() {
        val creditorId = CreditorId.generate()
        val apAccountId = AccountId.generate()
        val current = chargeEntry(creditorId, apAccountId, Money(BigDecimal("10.00"), GBP), AS_OF.minusDays(10))
        val bucket3160 = chargeEntry(creditorId, apAccountId, Money(BigDecimal("20.00"), GBP), AS_OF.minusDays(45))
        val bucket6190 = chargeEntry(creditorId, apAccountId, Money(BigDecimal("30.00"), GBP), AS_OF.minusDays(75))
        val over90 = chargeEntry(creditorId, apAccountId, Money(BigDecimal("40.00"), GBP), AS_OF.minusDays(120))

        val aging = AccountsPayableAging.of(
            creditorId, apAccountId, listOf(current, bucket3160, bucket6190, over90), AS_OF, GBP
        )

        aging.buckets.first { it.label == AgingBucketLabel.CURRENT }.amount shouldBe Money(BigDecimal("10.00"), GBP)
        aging.buckets.first { it.label == AgingBucketLabel.DAYS_31_TO_60 }.amount shouldBe Money(BigDecimal("20.00"), GBP)
        aging.buckets.first { it.label == AgingBucketLabel.DAYS_61_TO_90 }.amount shouldBe Money(BigDecimal("30.00"), GBP)
        aging.buckets.first { it.label == AgingBucketLabel.OVER_90 }.amount shouldBe Money(BigDecimal("40.00"), GBP)
        aging.totalOutstanding shouldBe Money(BigDecimal("100.00"), GBP)
    }

    @Test
    fun `given a charge belonging to a different Creditor, when aging is computed, then it is excluded`() {
        val creditorId = CreditorId.generate()
        val otherCreditorId = CreditorId.generate()
        val apAccountId = AccountId.generate()
        val entry = chargeEntry(otherCreditorId, apAccountId, Money(BigDecimal("100.00"), GBP), AS_OF.minusDays(10))

        val aging = AccountsPayableAging.of(creditorId, apAccountId, listOf(entry), AS_OF, GBP)

        aging.totalOutstanding shouldBe zero()
    }

    @Test
    fun `given an unposted Draft charge, when aging is computed, then it is excluded`() {
        val creditorId = CreditorId.generate()
        val apAccountId = AccountId.generate()
        val draft = JournalEntry.create(
            PeriodId.generate(), AS_OF.minusDays(10),
            listOf(
                JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
                JournalLine(
                    apAccountId, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT,
                    mapOf(DimensionType.VENDOR to creditorId.value.toString())
                )
            ),
            JournalSource.MANUAL
        )

        val aging = AccountsPayableAging.of(creditorId, apAccountId, listOf(draft), AS_OF, GBP)

        aging.totalOutstanding shouldBe zero()
    }

    private fun chargeEntry(creditorId: CreditorId, apAccountId: AccountId, amount: Money, date: LocalDate): JournalEntry {
        val entry = JournalEntry.create(
            PeriodId.generate(), date,
            listOf(
                JournalLine(AccountId.generate(), amount, TransactionSide.DEBIT),
                JournalLine(
                    apAccountId, amount, TransactionSide.CREDIT,
                    mapOf(DimensionType.VENDOR to creditorId.value.toString())
                )
            ),
            JournalSource.MANUAL
        )
        entry.post()
        return entry
    }

    private fun paymentEntry(creditorId: CreditorId, apAccountId: AccountId, amount: Money, date: LocalDate): JournalEntry {
        val entry = JournalEntry.create(
            PeriodId.generate(), date,
            listOf(
                JournalLine(
                    apAccountId, amount, TransactionSide.DEBIT,
                    mapOf(DimensionType.VENDOR to creditorId.value.toString())
                ),
                JournalLine(AccountId.generate(), amount, TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        entry.post()
        return entry
    }

    private fun zero(): Money = Money(BigDecimal.ZERO, GBP)
}
