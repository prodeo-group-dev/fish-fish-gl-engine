package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.PeriodId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/** How the books and the journal list word an entry's description (display only: the stored entry never changes). */
class HumanDescriptionTest {

    private val gbp = Currency.getInstance("GBP")
    private val day = LocalDate.of(2026, 10, 3)

    private fun original(description: String?) = JournalEntry.create(
        PeriodId.generate(), day,
        listOf(
            JournalLine(AccountId.generate(), Money(BigDecimal("10.00"), gbp), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("10.00"), gbp), TransactionSide.CREDIT)
        ),
        JournalSource.MANUAL, description
    )

    private fun lookup(vararg entries: JournalEntry): (JournalEntryId) -> JournalEntry? {
        val byId = entries.associateBy { it.id }
        return { id -> byId[id] }
    }

    @Test
    fun `an old reversal that names the original by its plain id is worded from the original when it can be found`() {
        val takings = original("Takings")

        humanDescription("Reversal of ${takings.id.value}", lookup(takings)) shouldBe "Reversal of Takings (2026-10-03)"
    }

    @Test
    fun `an old reversal with no description on the original reads Reversal of an earlier entry`() {
        val bare = original(null)

        humanDescription("Reversal of ${bare.id.value}", lookup(bare)) shouldBe "Reversal of an earlier entry (2026-10-03)"
    }

    @Test
    fun `the older still form with the typed id printed raw is worded the same way`() {
        val takings = original("Takings")

        humanDescription("Reversal of JournalEntryId(value=${takings.id.value})", lookup(takings)) shouldBe "Reversal of Takings (2026-10-03)"
    }

    @Test
    fun `when the original cannot be found, the plain id is left as stored - nothing is guessed`() {
        val id = "3f2c8a52-1b6e-4c0e-9d3a-7a1d5b9c0e11"

        humanDescription("Reversal of $id", lookup()) shouldBe "Reversal of $id"
        humanDescription("Reversal of $id") shouldBe "Reversal of $id"
    }

    @Test
    fun `a description that merely contains an id, or is already in words, is not rewritten`() {
        val takings = original("Takings")

        humanDescription("Paid ${takings.id.value} in full", lookup(takings)) shouldBe "Paid ${takings.id.value} in full"
        humanDescription("Reversal of Takings (2026-10-03)", lookup(takings)) shouldBe "Reversal of Takings (2026-10-03)"
        humanDescription("Undo of Takings", lookup(takings)) shouldBe "Undo of Takings"
        humanDescription(null, lookup(takings)) shouldBe null
    }

    @Test
    fun `the original's own old typed id is unwrapped inside the new wording`() {
        val raw = "Pay run - PayRunId(value=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee)"
        val payRun = original(raw)

        humanDescription("Reversal of ${payRun.id.value}", lookup(payRun)) shouldBe
            "Reversal of Pay run - aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee (2026-10-03)"
    }
}
