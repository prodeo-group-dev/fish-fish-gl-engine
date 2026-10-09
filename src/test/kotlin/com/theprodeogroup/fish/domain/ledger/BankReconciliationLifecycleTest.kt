package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * UAT v2.2 W-M2: a bank reconciliation could be started, matched and unmatched but never finished or
 * abandoned. It now has a status; matching is allowed only while OPEN; cancel and complete are final.
 * Completing needs every statement line matched, and - when the tie-out is switched on - the statement
 * ending balance, adjusted for the book entries still outstanding, to agree with the ledger.
 */
class BankReconciliationLifecycleTest {

    private val gbp: Currency = Currency.getInstance("GBP")
    private val statementDay = LocalDate.of(2026, 1, 31)
    private val cash = AccountId.generate()
    private val other = AccountId.generate()

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun entry(date: LocalDate, vararg lines: JournalLine): JournalEntry =
        JournalEntry.create(PeriodId.generate(), date, lines.toList(), JournalSource.MANUAL).also { it.post() }

    private fun receipt(amount: String, date: LocalDate = statementDay.minusDays(3)) = entry(
        date, JournalLine(cash, money(amount), TransactionSide.DEBIT), JournalLine(other, money(amount), TransactionSide.CREDIT)
    )

    private fun payment(amount: String, date: LocalDate = statementDay.minusDays(3)) = entry(
        date, JournalLine(other, money(amount), TransactionSide.DEBIT), JournalLine(cash, money(amount), TransactionSide.CREDIT)
    )

    private fun line(amount: String, direction: CashDirection) =
        BankStatementLine(date = statementDay.minusDays(3), amount = money(amount), direction = direction, description = "line")

    private fun reconciliation(endingBalance: String, lines: List<BankStatementLine>, entries: List<JournalEntry>) =
        BankReconciliation.create(cash, statementDay, money(endingBalance), lines, entries, gbp)

    @Test
    fun `a new reconciliation is OPEN`() {
        reconciliation("0.00", emptyList(), emptyList()).status shouldBe BankReconciliationStatus.OPEN
    }

    @Test
    fun `cancel ends an OPEN reconciliation and is final`() {
        val r = reconciliation("0.00", emptyList(), emptyList())

        r.cancel().isValid shouldBe true
        r.status shouldBe BankReconciliationStatus.CANCELLED
        r.cancel().isValid shouldBe false
        r.complete().shouldBeInstanceOf<BankReconciliationCompletion.NotOpen>()
    }

    @Test
    fun `match and unmatch are refused once the reconciliation is cancelled or completed`() {
        val received = line("100.00", CashDirection.RECEIVED)
        val book = receipt("100.00")
        val cancelled = reconciliation("100.00", listOf(received), listOf(book)).also { it.cancel() }
        cancelled.match(received.id, book.id).isValid shouldBe false

        val received2 = line("100.00", CashDirection.RECEIVED)
        val book2 = receipt("100.00")
        val completed = reconciliation("100.00", listOf(received2), listOf(book2))
        completed.match(received2.id, book2.id).isValid shouldBe true
        completed.complete().shouldBeInstanceOf<BankReconciliationCompletion.Completed>()
        completed.unmatch(received2.id, book2.id).isValid shouldBe false
        completed.currentMatches.size shouldBe 1
    }

    @Test
    fun `complete is refused while a statement line is unmatched, and names the lines`() {
        val matched = line("100.00", CashDirection.RECEIVED)
        val unmatched = line("40.00", CashDirection.PAID)
        val book = receipt("100.00")
        val r = reconciliation("60.00", listOf(matched, unmatched), listOf(book))
        r.match(matched.id, book.id)

        val result = r.complete()

        result.shouldBeInstanceOf<BankReconciliationCompletion.NotFullyMatched>()
            .unmatchedStatementLineIds shouldBe listOf(unmatched.id)
        r.status shouldBe BankReconciliationStatus.OPEN
    }

    @Test
    fun `with every line matched complete succeeds even when a book entry is outstanding, if the tie-out is off`() {
        val received = line("100.00", CashDirection.RECEIVED)
        val book = receipt("100.00")
        val outstandingCheque = payment("30.00")
        val r = reconciliation("100.00", listOf(received), listOf(book, outstandingCheque))
        r.match(received.id, book.id)

        r.complete(enforceBalanceTieOut = false).shouldBeInstanceOf<BankReconciliationCompletion.Completed>()
        r.status shouldBe BankReconciliationStatus.COMPLETED
    }

    @Test
    fun `the tie-out agrees when the statement balance plus outstanding book items equals the ledger`() {
        // Ledger: +100 receipt, -30 cheque not yet cashed = 70. Bank shows 100 (cheque outstanding).
        val received = line("100.00", CashDirection.RECEIVED)
        val book = receipt("100.00")
        val outstandingCheque = payment("30.00")
        val r = reconciliation("100.00", listOf(received), listOf(book, outstandingCheque))
        r.match(received.id, book.id)

        val tieOut = r.balanceTieOut()

        tieOut.ledgerBalance shouldBe money("70.00")
        tieOut.outstandingNet shouldBe money("-30.00")
        tieOut.difference shouldBe money("0.00")
        r.complete(enforceBalanceTieOut = true).shouldBeInstanceOf<BankReconciliationCompletion.Completed>()
    }

    @Test
    fun `a deposit in transit adds to the statement balance in the tie-out`() {
        // Ledger: +100 matched, +50 deposit not on the statement yet = 150. Bank shows 100.
        val received = line("100.00", CashDirection.RECEIVED)
        val book = receipt("100.00")
        val inTransit = receipt("50.00")
        val r = reconciliation("100.00", listOf(received), listOf(book, inTransit))
        r.match(received.id, book.id)

        r.balanceTieOut().outstandingNet shouldBe money("50.00")
        r.balanceTieOut().difference shouldBe money("0.00")
    }

    @Test
    fun `a wrong statement ending balance is a balance difference when the tie-out is on, and the figures are reported`() {
        val received = line("100.00", CashDirection.RECEIVED)
        val book = receipt("100.00")
        val r = reconciliation("90.00", listOf(received), listOf(book)) // statement says 90, ledger says 100
        r.match(received.id, book.id)

        val result = r.complete(enforceBalanceTieOut = true)

        val diff = result.shouldBeInstanceOf<BankReconciliationCompletion.BalanceDifference>().tieOut
        diff.statementEndingBalance shouldBe money("90.00")
        diff.ledgerBalance shouldBe money("100.00")
        diff.difference shouldBe money("-10.00")
        r.status shouldBe BankReconciliationStatus.OPEN
    }

    @Test
    fun `book entries dated after the statement date are not part of the tie-out`() {
        val received = line("100.00", CashDirection.RECEIVED)
        val book = receipt("100.00")
        val later = receipt("999.00", statementDay.plusDays(5))
        val r = reconciliation("100.00", listOf(received), listOf(book, later))
        r.match(received.id, book.id)

        r.balanceTieOut().ledgerBalance shouldBe money("100.00")
        r.balanceTieOut().difference shouldBe money("0.00")
    }
}
