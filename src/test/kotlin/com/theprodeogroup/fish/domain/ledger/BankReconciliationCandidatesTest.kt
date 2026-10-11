package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

/**
 * Which journal entries a bank reconciliation offers to match (CM item 6, 2026-10-11): only POSTED entries with a
 * line on THAT bank account that are not already matched in a COMPLETED reconciliation; another account's entries never.
 */
class BankReconciliationCandidatesTest {

    private val gbp = Currency.getInstance("GBP")
    private val bank = AccountId.generate()
    private val otherBank = AccountId.generate()
    private val sales = AccountId.generate()
    private val statementDay = LocalDate.of(2026, 10, 31)
    private val periodId = PeriodId.generate()

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun entry(on: AccountId, amount: String, date: LocalDate = LocalDate.of(2026, 10, 10), post: Boolean = true): JournalEntry =
        JournalEntry.create(
            periodId, date,
            listOf(JournalLine(on, money(amount), TransactionSide.DEBIT), JournalLine(sales, money(amount), TransactionSide.CREDIT)),
            JournalSource.MANUAL, "Receipt"
        ).also { if (post) it.post() }

    private fun received(amount: String) = BankStatementLine(date = LocalDate.of(2026, 10, 12), amount = money(amount), direction = CashDirection.RECEIVED, description = "line")

    private fun reconstitute(
        entries: List<JournalEntry>,
        lines: List<BankStatementLine> = emptyList(),
        matches: Set<Pair<BankStatementLineId, JournalEntryId>> = emptySet(),
        alreadyReconciled: Set<JournalEntryId> = emptySet(),
        ending: String = "0.00"
    ) = BankReconciliation.reconstitute(
        BankReconciliationId.generate(), bank, statementDay, money(ending), lines, entries, gbp, matches,
        alreadyReconciledEntryIds = alreadyReconciled
    )

    @Test
    fun `given the whole Company's entries, when a saved reconciliation is rebuilt, then only entries with a line on its own account are offered`() {
        val mine = entry(bank, "100.00")
        val theirs = entry(otherBank, "40.00")

        val rec = reconstitute(listOf(mine, theirs))

        rec.unmatchedEntries.map { it.id } shouldBe listOf(mine.id)
    }

    @Test
    fun `given every statement line matched and only another account's entries left over, when rebuilt, then it is fully reconciled`() {
        val mine = entry(bank, "100.00")
        val theirs = entry(otherBank, "40.00")
        val line = received("100.00")

        val rec = reconstitute(listOf(mine, theirs), listOf(line), setOf(line.id to mine.id))

        rec.isFullyReconciled shouldBe true
    }

    @Test
    fun `given entries that are not posted, then they are not offered`() {
        val draft = entry(bank, "10.00", post = false)
        val posted = entry(bank, "20.00")

        reconstitute(listOf(draft, posted)).unmatchedEntries.map { it.id } shouldBe listOf(posted.id)
    }

    @Test
    fun `given an entry already matched in a completed reconciliation, then it is not offered and cannot be matched again`() {
        val reconciledBefore = entry(bank, "100.00")
        val fresh = entry(bank, "100.00")
        val line = received("100.00")
        val rec = reconstitute(listOf(reconciledBefore, fresh), listOf(line), alreadyReconciled = setOf(reconciledBefore.id))

        rec.unmatchedEntries.map { it.id } shouldBe listOf(fresh.id)
        rec.match(line.id, reconciledBefore.id).isValid shouldBe false
        rec.match(line.id, fresh.id).isValid shouldBe true
    }

    @Test
    fun `given a new reconciliation created with already-reconciled ids, then those entries are not offered either`() {
        val reconciledBefore = entry(bank, "100.00")
        val fresh = entry(bank, "30.00")

        val rec = BankReconciliation.create(
            bank, statementDay, money("0.00"), emptyList(), listOf(reconciledBefore, fresh), gbp,
            alreadyReconciledEntryIds = setOf(reconciledBefore.id)
        )

        rec.unmatchedEntries.map { it.id } shouldBe listOf(fresh.id)
    }

    @Test
    fun `given an earlier reconciliation cleared some entries, then the tie-out counts them in the ledger balance but not as outstanding`() {
        val clearedBefore = entry(bank, "100.00")
        val matchedNow = entry(bank, "50.00")
        val line = received("50.00")
        val rec = reconstitute(
            listOf(clearedBefore, matchedNow), listOf(line), setOf(line.id to matchedNow.id),
            alreadyReconciled = setOf(clearedBefore.id), ending = "150.00"
        )

        val tieOut = rec.balanceTieOut()

        tieOut.ledgerBalance shouldBe money("150.00")
        tieOut.outstandingNet shouldBe money("0.00")
        tieOut.difference.amount.signum() shouldBe 0
    }

    @Test
    fun `given an entry that was undone and its reversal, when neither was ever on a statement, then neither is offered`() {
        val original = entry(bank, "75.00")
        val reversal = requireNotNull(original.reverse(Instant.parse("2026-10-11T10:00:00Z")))
        val other = entry(bank, "20.00")

        val rec = reconstitute(listOf(original, reversal, other))

        rec.unmatchedEntries.map { it.id } shouldBe listOf(other.id)
    }

    @Test
    fun `given an entry already cleared by a completed reconciliation and then undone, then its reversal is still offered`() {
        val original = entry(bank, "75.00")
        val reversal = requireNotNull(original.reverse(Instant.parse("2026-10-11T10:00:00Z")))

        val rec = reconstitute(listOf(original, reversal), alreadyReconciled = setOf(original.id))

        rec.unmatchedEntries.map { it.id } shouldBe listOf(reversal.id)
    }
}
