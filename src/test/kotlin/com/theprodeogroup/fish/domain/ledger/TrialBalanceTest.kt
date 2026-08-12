package com.theprodeogroup.fish.domain.ledger

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

class TrialBalanceTest {

    @Test
    fun `given a Chart of Accounts with no posted entries, when computed, then every balance is zero and it is balanced`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET)
        val revenue = account(companyId, AccountType.REVENUE)

        val trialBalance = TrialBalance.of(listOf(cash, revenue), emptyList(), GBP)

        trialBalance.lines.first { it.accountId == cash.id }.balance shouldBe zero()
        trialBalance.lines.first { it.accountId == revenue.id }.balance shouldBe zero()
        trialBalance.isBalanced shouldBe true
    }

    @Test
    fun `given a balanced posted sale (debit Cash, credit Revenue), when computed, then both accounts reflect it and it balances`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET)
        val revenue = account(companyId, AccountType.REVENUE)
        val entry = postedEntry(
            JournalLine(cash.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
            JournalLine(revenue.id, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
        )

        val trialBalance = TrialBalance.of(listOf(cash, revenue), listOf(entry), GBP)

        trialBalance.lines.first { it.accountId == cash.id }.balance shouldBe Money(BigDecimal("500.00"), GBP)
        trialBalance.lines.first { it.accountId == revenue.id }.balance shouldBe Money(BigDecimal("500.00"), GBP)
        trialBalance.isBalanced shouldBe true
    }

    @Test
    fun `given postings across all five account types that balance, when computed, then Asset+Expense equals Liability+Equity+Revenue`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET)
        val loan = account(companyId, AccountType.LIABILITY, AccountClassification.NON_CURRENT)
        val capital = account(companyId, AccountType.EQUITY)
        val revenue = account(companyId, AccountType.REVENUE)
        val rent = account(companyId, AccountType.EXPENSE)
        val accounts = listOf(cash, loan, capital, revenue, rent)

        val entries = listOf(
            // Owner injects capital
            postedEntry(
                JournalLine(cash.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(capital.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
            ),
            // Takes out a loan
            postedEntry(
                JournalLine(cash.id, Money(BigDecimal("2000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(loan.id, Money(BigDecimal("2000.00"), GBP), TransactionSide.CREDIT)
            ),
            // Makes a sale
            postedEntry(
                JournalLine(cash.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
            ),
            // Pays rent
            postedEntry(
                JournalLine(rent.id, Money(BigDecimal("300.00"), GBP), TransactionSide.DEBIT),
                JournalLine(cash.id, Money(BigDecimal("300.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val trialBalance = TrialBalance.of(accounts, entries, GBP)

        trialBalance.totalAssetAndExpense shouldBe trialBalance.totalLiabilityEquityRevenue
        trialBalance.isBalanced shouldBe true
    }

    @Test
    fun `given an unposted Draft entry, when computed, then it is excluded from the balance`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET)
        val revenue = account(companyId, AccountType.REVENUE)
        val draft = JournalEntry.create(
            PeriodId.generate(), TODAY,
            listOf(
                JournalLine(cash.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )

        val trialBalance = TrialBalance.of(listOf(cash, revenue), listOf(draft), GBP)

        trialBalance.lines.first { it.accountId == cash.id }.balance shouldBe zero()
    }

    @Test
    fun `given a posted entry and its posted reversal, when computed, then their net effect is zero`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET)
        val revenue = account(companyId, AccountType.REVENUE)
        val entry = postedEntry(
            JournalLine(cash.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
            JournalLine(revenue.id, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
        )
        val reversal = requireNotNull(entry.reverse())

        val trialBalance = TrialBalance.of(listOf(cash, revenue), listOf(entry, reversal), GBP)

        trialBalance.lines.first { it.accountId == cash.id }.balance shouldBe zero()
        trialBalance.lines.first { it.accountId == revenue.id }.balance shouldBe zero()
        trialBalance.isBalanced shouldBe true
    }

    @Test
    fun `given an account with more credits than its normal debit direction, when computed, then its balance is negative`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET)
        val revenue = account(companyId, AccountType.REVENUE)
        val entry = postedEntry(
            JournalLine(revenue.id, Money(BigDecimal("200.00"), GBP), TransactionSide.DEBIT),
            JournalLine(cash.id, Money(BigDecimal("200.00"), GBP), TransactionSide.CREDIT)
        )

        val trialBalance = TrialBalance.of(listOf(cash, revenue), listOf(entry), GBP)

        trialBalance.lines.first { it.accountId == cash.id }.balance shouldBe Money(BigDecimal("-200.00"), GBP)
    }

    @Test
    fun `given a Cash Asset account with a negative balance, when computed, then it is flagged as an overdraft`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET)
        val revenue = account(companyId, AccountType.REVENUE)
        val entry = postedEntry(
            JournalLine(revenue.id, Money(BigDecimal("200.00"), GBP), TransactionSide.DEBIT),
            JournalLine(cash.id, Money(BigDecimal("200.00"), GBP), TransactionSide.CREDIT)
        )

        val trialBalance = TrialBalance.of(listOf(cash, revenue), listOf(entry), GBP)

        trialBalance.overdraftLines.map { it.accountId } shouldBe listOf(cash.id)
    }

    @Test
    fun `given an Asset account with a positive balance, when computed, then it is not flagged as an overdraft`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET)
        val revenue = account(companyId, AccountType.REVENUE)
        val entry = postedEntry(
            JournalLine(cash.id, Money(BigDecimal("200.00"), GBP), TransactionSide.DEBIT),
            JournalLine(revenue.id, Money(BigDecimal("200.00"), GBP), TransactionSide.CREDIT)
        )

        val trialBalance = TrialBalance.of(listOf(cash, revenue), listOf(entry), GBP)

        trialBalance.overdraftLines shouldBe emptyList()
    }

    @Test
    fun `given an overdrawn Cash account, when computed, then totals and isBalanced are unaffected by the reclassification`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET)
        val revenue = account(companyId, AccountType.REVENUE)
        val entry = postedEntry(
            JournalLine(revenue.id, Money(BigDecimal("200.00"), GBP), TransactionSide.DEBIT),
            JournalLine(cash.id, Money(BigDecimal("200.00"), GBP), TransactionSide.CREDIT)
        )

        val trialBalance = TrialBalance.of(listOf(cash, revenue), listOf(entry), GBP)

        trialBalance.totalAssetAndExpense shouldBe trialBalance.totalLiabilityEquityRevenue
        trialBalance.isBalanced shouldBe true
    }

    private fun account(
        companyId: CompanyId,
        type: AccountType,
        classification: AccountClassification? = if (type.requiresClassification()) AccountClassification.CURRENT else null
    ): Account = Account.create(companyId, type, classification, "1000", "Test Account")

    private fun postedEntry(vararg lines: JournalLine): JournalEntry {
        val entry = JournalEntry.create(PeriodId.generate(), TODAY, lines.toList(), JournalSource.MANUAL)
        entry.post()
        return entry
    }

    private fun zero(): Money = Money(BigDecimal.ZERO, GBP)
}
