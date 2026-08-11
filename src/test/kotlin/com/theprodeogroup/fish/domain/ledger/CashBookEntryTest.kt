package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 15)

class CashBookEntryTest {

    @Test
    fun `given money received, when converted to a JournalEntry, then the Cash Bank Account is debited and the counter-account is credited`() {
        val cashAccountId = AccountId.generate()
        val counterAccountId = AccountId.generate()
        val entry = CashBookEntry(
            accountId = cashAccountId,
            direction = CashDirection.RECEIVED,
            amount = Money(BigDecimal("250.00"), GBP),
            counterAccountId = counterAccountId,
            date = TODAY,
            description = "Sale of goods"
        )

        val journalEntry = entry.toJournalEntry(PeriodId.generate())

        val cashLine = journalEntry.lines.first { it.accountId == cashAccountId }
        val counterLine = journalEntry.lines.first { it.accountId == counterAccountId }
        cashLine.side shouldBe TransactionSide.DEBIT
        counterLine.side shouldBe TransactionSide.CREDIT
    }

    @Test
    fun `given money paid, when converted to a JournalEntry, then the Cash Bank Account is credited and the counter-account is debited`() {
        val cashAccountId = AccountId.generate()
        val counterAccountId = AccountId.generate()
        val entry = CashBookEntry(
            accountId = cashAccountId,
            direction = CashDirection.PAID,
            amount = Money(BigDecimal("75.50"), GBP),
            counterAccountId = counterAccountId,
            date = TODAY,
            description = "Office rent"
        )

        val journalEntry = entry.toJournalEntry(PeriodId.generate())

        val cashLine = journalEntry.lines.first { it.accountId == cashAccountId }
        val counterLine = journalEntry.lines.first { it.accountId == counterAccountId }
        cashLine.side shouldBe TransactionSide.CREDIT
        counterLine.side shouldBe TransactionSide.DEBIT
    }

    @Test
    fun `given a CashBookEntry, when converted, then the resulting JournalEntry is balanced, in Draft, and sourced as Manual`() {
        val entry = readyEntry()

        val journalEntry = entry.toJournalEntry(PeriodId.generate())

        JournalEntry.validateLines(journalEntry.lines).isValid shouldBe true
        journalEntry.lines shouldHaveSize 2
        journalEntry.status shouldBe PostingStatus.DRAFT
        journalEntry.source shouldBe JournalSource.MANUAL
    }

    @Test
    fun `given a CashBookEntry, when converted, then the JournalEntry's date, period, and description are carried over`() {
        val entry = readyEntry()
        val periodId = PeriodId.generate()

        val journalEntry = entry.toJournalEntry(periodId)

        journalEntry.date shouldBe TODAY
        journalEntry.periodId shouldBe periodId
        journalEntry.description shouldBe "Sale of goods"
    }

    @Test
    fun `given a CashBookEntry, when converted, then both lines carry the same amount`() {
        val entry = readyEntry()

        val journalEntry = entry.toJournalEntry(PeriodId.generate())

        journalEntry.lines[0].amount shouldBe journalEntry.lines[1].amount
        journalEntry.lines[0].amount shouldBe Money(BigDecimal("250.00"), GBP)
    }

    @Test
    fun `given a zero amount, when a CashBookEntry is created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            CashBookEntry(
                accountId = AccountId.generate(),
                direction = CashDirection.RECEIVED,
                amount = Money(BigDecimal.ZERO, GBP),
                counterAccountId = AccountId.generate(),
                date = TODAY
            )
        }
    }

    @Test
    fun `given a negative amount, when a CashBookEntry is created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            CashBookEntry(
                accountId = AccountId.generate(),
                direction = CashDirection.PAID,
                amount = Money(BigDecimal("-10.00"), GBP),
                counterAccountId = AccountId.generate(),
                date = TODAY
            )
        }
    }

    private fun readyEntry(): CashBookEntry = CashBookEntry(
        accountId = AccountId.generate(),
        direction = CashDirection.RECEIVED,
        amount = Money(BigDecimal("250.00"), GBP),
        counterAccountId = AccountId.generate(),
        date = TODAY,
        description = "Sale of goods"
    )
}
