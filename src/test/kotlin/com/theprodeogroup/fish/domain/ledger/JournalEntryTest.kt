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
private val USD: Currency = Currency.getInstance("USD")
private val TODAY = LocalDate.of(2026, 1, 15)

class JournalEntryTest {

    @Test
    fun `given lines where total debits equal total credits in the same currency, when validated, then it succeeds`() {
        val lines = listOf(
            JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
        )

        JournalEntry.validateLines(lines).isValid shouldBe true
    }

    @Test
    fun `given lines where debits do not equal credits, when validated, then it fails with a clear error`() {
        val lines = listOf(
            JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("50.00"), GBP), TransactionSide.CREDIT)
        )

        val result = JournalEntry.validateLines(lines)

        result.isValid shouldBe false
        result.errors shouldHaveSize 1
    }

    @Test
    fun `given lines balanced independently in two currencies, when validated, then it succeeds`() {
        val lines = listOf(
            JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("50.00"), USD), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("50.00"), USD), TransactionSide.CREDIT)
        )

        JournalEntry.validateLines(lines).isValid shouldBe true
    }

    @Test
    fun `given one currency balances but another does not, when validated, then it fails`() {
        val lines = listOf(
            JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("50.00"), USD), TransactionSide.DEBIT)
        )

        JournalEntry.validateLines(lines).isValid shouldBe false
    }

    @Test
    fun `given no lines, when validated, then it fails`() {
        JournalEntry.validateLines(emptyList()).isValid shouldBe false
    }

    @Test
    fun `given unbalanced lines, when create is called directly, then it throws`() {
        val lines = listOf(
            JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT)
        )

        shouldThrow<IllegalArgumentException> {
            JournalEntry.create(PeriodId.generate(), TODAY, lines, JournalSource.MANUAL)
        }
    }

    @Test
    fun `given a Draft entry, when posted, then status becomes Posted, an event is recorded, and it is no longer editable`() {
        val entry = readyEntry()
        entry.isEditable() shouldBe true

        val result = entry.post()

        result.isValid shouldBe true
        entry.status shouldBe PostingStatus.POSTED
        entry.isEditable() shouldBe false
        val events = entry.pullDomainEvents()
        events shouldHaveSize 1
        (events.first() as JournalEntryPosted).journalEntryId shouldBe entry.id
    }

    @Test
    fun `given a Posted entry, when posted again, then it is rejected`() {
        val entry = readyEntry()
        entry.post()

        val result = entry.post()

        result.isValid shouldBe false
        entry.status shouldBe PostingStatus.POSTED
    }

    @Test
    fun `given a Posted entry, when reversed, then a new Reversed-sourced entry is created referencing the original, and the original becomes Reversed`() {
        val entry = readyEntry()
        entry.post()
        entry.pullDomainEvents()

        val reversal = requireNotNull(entry.reverse())

        entry.status shouldBe PostingStatus.REVERSED
        reversal.reversalOfEntryId shouldBe entry.id
        reversal.source shouldBe JournalSource.REVERSAL
        reversal.status shouldBe PostingStatus.POSTED
    }

    @Test
    fun `given a Posted entry, when reversed, then the reversal lines are the opposite side of the original, cancelling it out`() {
        val entry = readyEntry()
        entry.post()

        val reversal = requireNotNull(entry.reverse())

        reversal.lines shouldHaveSize entry.lines.size
        entry.lines.zip(reversal.lines).forEach { (original, reversed) ->
            reversed.accountId shouldBe original.accountId
            reversed.amount shouldBe original.amount
            reversed.side shouldBe original.side.opposite()
        }
    }

    @Test
    fun `given a Draft entry, when reversed, then it fails - only Posted or System entries can reverse`() {
        val entry = readyEntry()

        entry.reverse() shouldBe null
        entry.status shouldBe PostingStatus.DRAFT
    }

    @Test
    fun `given a Posted entry, when reversed twice, then the second attempt fails - Reversed is terminal`() {
        val entry = readyEntry()
        entry.post()
        entry.reverse()

        entry.reverse() shouldBe null
    }

    private fun readyEntry(): JournalEntry {
        val lines = listOf(
            JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
        )
        return JournalEntry.create(PeriodId.generate(), TODAY, lines, JournalSource.MANUAL)
    }
}
