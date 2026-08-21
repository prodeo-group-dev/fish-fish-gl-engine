package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 15)

class BankReconciliationTest {

    @Test
    fun `given a deposit on the statement matching a posted receipt, when matched, then both are matched and it is fully reconciled`() {
        val companyId = CompanyId.generate()
        val cashAccountId = AccountId.generate()
        val revenueAccountId = AccountId.generate()
        val statementLine = BankStatementLine(
            date = TODAY, amount = Money(BigDecimal("500.00"), GBP),
            direction = CashDirection.RECEIVED, description = "Card settlement"
        )
        val entry = postedEntry(
            JournalLine(cashAccountId, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
            JournalLine(revenueAccountId, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
        )
        val reconciliation = BankReconciliation.create(
            cashAccountId, TODAY, Money(BigDecimal("500.00"), GBP),
            listOf(statementLine), listOf(entry), GBP
        )

        val result = reconciliation.match(statementLine.id, entry.id)

        result.isValid shouldBe true
        reconciliation.isFullyReconciled shouldBe true
        reconciliation.unmatchedStatementLines shouldBe emptyList()
        reconciliation.unmatchedEntries shouldBe emptyList()
    }

    @Test
    fun `given a withdrawal on the statement matching a posted payment, when matched, then it succeeds`() {
        val companyId = CompanyId.generate()
        val cashAccountId = AccountId.generate()
        val statementLine = BankStatementLine(
            date = TODAY, amount = Money(BigDecimal("120.00"), GBP),
            direction = CashDirection.PAID, description = "Supplier payment"
        )
        val entry = postedEntry(
            JournalLine(AccountId.generate(), Money(BigDecimal("120.00"), GBP), TransactionSide.DEBIT),
            JournalLine(cashAccountId, Money(BigDecimal("120.00"), GBP), TransactionSide.CREDIT)
        )
        val reconciliation = BankReconciliation.create(
            cashAccountId, TODAY, Money(BigDecimal("-120.00"), GBP),
            listOf(statementLine), listOf(entry), GBP
        )

        val result = reconciliation.match(statementLine.id, entry.id)

        result.isValid shouldBe true
        reconciliation.isFullyReconciled shouldBe true
    }

    @Test
    fun `given a statement line and entry with mismatched amounts, when matched, then it fails`() {
        val cashAccountId = AccountId.generate()
        val statementLine = BankStatementLine(
            date = TODAY, amount = Money(BigDecimal("500.00"), GBP),
            direction = CashDirection.RECEIVED, description = "Card settlement"
        )
        val entry = postedEntry(
            JournalLine(cashAccountId, Money(BigDecimal("450.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("450.00"), GBP), TransactionSide.CREDIT)
        )
        val reconciliation = BankReconciliation.create(
            cashAccountId, TODAY, Money(BigDecimal("500.00"), GBP),
            listOf(statementLine), listOf(entry), GBP
        )

        val result = reconciliation.match(statementLine.id, entry.id)

        result.isValid shouldBe false
        reconciliation.isFullyReconciled shouldBe false
    }

    @Test
    fun `given a statement line marked RECEIVED but the entry credits the account, when matched, then it fails`() {
        val cashAccountId = AccountId.generate()
        val statementLine = BankStatementLine(
            date = TODAY, amount = Money(BigDecimal("500.00"), GBP),
            direction = CashDirection.RECEIVED, description = "Card settlement"
        )
        val entry = postedEntry(
            JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
            JournalLine(cashAccountId, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
        )
        val reconciliation = BankReconciliation.create(
            cashAccountId, TODAY, Money(BigDecimal("500.00"), GBP),
            listOf(statementLine), listOf(entry), GBP
        )

        val result = reconciliation.match(statementLine.id, entry.id)

        result.isValid shouldBe false
    }

    @Test
    fun `given an already-matched statement line, when matched again, then it fails`() {
        val cashAccountId = AccountId.generate()
        val statementLine = BankStatementLine(
            date = TODAY, amount = Money(BigDecimal("500.00"), GBP),
            direction = CashDirection.RECEIVED, description = "Card settlement"
        )
        val entry = postedEntry(
            JournalLine(cashAccountId, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
        )
        val otherEntry = postedEntry(
            JournalLine(cashAccountId, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
        )
        val reconciliation = BankReconciliation.create(
            cashAccountId, TODAY, Money(BigDecimal("1000.00"), GBP),
            listOf(statementLine), listOf(entry, otherEntry), GBP
        )
        reconciliation.match(statementLine.id, entry.id)

        val result = reconciliation.match(statementLine.id, otherEntry.id)

        result.isValid shouldBe false
    }

    @Test
    fun `given an already-matched JournalEntry, when matched again, then it fails`() {
        val cashAccountId = AccountId.generate()
        val statementLine = BankStatementLine(
            date = TODAY, amount = Money(BigDecimal("500.00"), GBP),
            direction = CashDirection.RECEIVED, description = "Card settlement"
        )
        val otherStatementLine = BankStatementLine(
            date = TODAY, amount = Money(BigDecimal("500.00"), GBP),
            direction = CashDirection.RECEIVED, description = "Duplicate?"
        )
        val entry = postedEntry(
            JournalLine(cashAccountId, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
        )
        val reconciliation = BankReconciliation.create(
            cashAccountId, TODAY, Money(BigDecimal("500.00"), GBP),
            listOf(statementLine, otherStatementLine), listOf(entry), GBP
        )
        reconciliation.match(statementLine.id, entry.id)

        val result = reconciliation.match(otherStatementLine.id, entry.id)

        result.isValid shouldBe false
    }

    @Test
    fun `given an unposted Draft entry, when the reconciliation is created, then it is not eligible to match`() {
        val cashAccountId = AccountId.generate()
        val statementLine = BankStatementLine(
            date = TODAY, amount = Money(BigDecimal("500.00"), GBP),
            direction = CashDirection.RECEIVED, description = "Card settlement"
        )
        val draft = JournalEntry.create(
            PeriodId.generate(), TODAY,
            listOf(
                JournalLine(cashAccountId, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        val reconciliation = BankReconciliation.create(
            cashAccountId, TODAY, Money(BigDecimal("500.00"), GBP),
            listOf(statementLine), listOf(draft), GBP
        )

        val result = reconciliation.match(statementLine.id, draft.id)

        result.isValid shouldBe false
        reconciliation.unmatchedEntries shouldBe emptyList()
    }

    @Test
    fun `given a posted entry against a different Account, when the reconciliation is created, then it is excluded`() {
        val cashAccountId = AccountId.generate()
        val otherAccountId = AccountId.generate()
        val entry = postedEntry(
            JournalLine(otherAccountId, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
        )

        val reconciliation = BankReconciliation.create(
            cashAccountId, TODAY, Money(BigDecimal("0.00"), GBP),
            emptyList(), listOf(entry), GBP
        )

        reconciliation.unmatchedEntries shouldBe emptyList()
    }

    @Test
    fun `given outstanding items on both sides, when checked before matching, then it is not fully reconciled`() {
        val cashAccountId = AccountId.generate()
        val statementLine = BankStatementLine(
            date = TODAY, amount = Money(BigDecimal("500.00"), GBP),
            direction = CashDirection.RECEIVED, description = "Card settlement"
        )
        val entry = postedEntry(
            JournalLine(cashAccountId, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
        )

        val reconciliation = BankReconciliation.create(
            cashAccountId, TODAY, Money(BigDecimal("500.00"), GBP),
            listOf(statementLine), listOf(entry), GBP
        )

        reconciliation.isFullyReconciled shouldBe false
        reconciliation.unmatchedStatementLines shouldBe listOf(statementLine)
        reconciliation.unmatchedEntries shouldBe listOf(entry)
    }

    private fun postedEntry(vararg lines: JournalLine): JournalEntry {
        val entry = JournalEntry.create(PeriodId.generate(), TODAY, lines.toList(), JournalSource.MANUAL)
        entry.post()
        return entry
    }
}
