package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 15)

class WorkingCapitalTest {

    @Test
    fun `given no posted entries, when computed, then everything is zero`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET, AccountClassification.CURRENT)
        val payable = account(companyId, AccountType.LIABILITY, AccountClassification.CURRENT)

        val workingCapital = WorkingCapital.of(listOf(cash, payable), emptyList(), GBP)

        workingCapital.totalCurrentAssets shouldBe zero()
        workingCapital.totalCurrentLiabilities shouldBe zero()
        workingCapital.workingCapital shouldBe zero()
    }

    @Test
    fun `given only current asset activity, when computed, then working capital equals current assets`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET, AccountClassification.CURRENT)
        val capital = account(companyId, AccountType.EQUITY)
        val entry = postedEntry(
            JournalLine(cash.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
            JournalLine(capital.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
        )

        val workingCapital = WorkingCapital.of(listOf(cash, capital), listOf(entry), GBP)

        workingCapital.totalCurrentAssets shouldBe Money(BigDecimal("1000.00"), GBP)
        workingCapital.workingCapital shouldBe Money(BigDecimal("1000.00"), GBP)
    }

    @Test
    fun `given equal current asset and current liability activity, when computed, then working capital is zero`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET, AccountClassification.CURRENT)
        val shortTermLoan = account(companyId, AccountType.LIABILITY, AccountClassification.CURRENT)
        val entry = postedEntry(
            JournalLine(cash.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
            JournalLine(shortTermLoan.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
        )

        val workingCapital = WorkingCapital.of(listOf(cash, shortTermLoan), listOf(entry), GBP)

        workingCapital.totalCurrentAssets shouldBe Money(BigDecimal("1000.00"), GBP)
        workingCapital.totalCurrentLiabilities shouldBe Money(BigDecimal("1000.00"), GBP)
        workingCapital.workingCapital shouldBe zero()
    }

    @Test
    fun `given current liabilities exceeding current assets, when computed, then working capital is negative`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET, AccountClassification.CURRENT)
        val payable = account(companyId, AccountType.LIABILITY, AccountClassification.CURRENT)
        val entries = listOf(
            postedEntry(
                JournalLine(cash.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
                JournalLine(payable.id, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
            ),
            postedEntry(
                JournalLine(AccountId.generate(), Money(BigDecimal("300.00"), GBP), TransactionSide.DEBIT),
                JournalLine(payable.id, Money(BigDecimal("300.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val workingCapital = WorkingCapital.of(listOf(cash, payable), entries, GBP)

        workingCapital.workingCapital shouldBe Money(BigDecimal("-300.00"), GBP)
    }

    @Test
    fun `given a non-current asset and non-current liability, when computed, then they are excluded`() {
        val companyId = CompanyId.generate()
        val building = account(companyId, AccountType.ASSET, AccountClassification.NON_CURRENT)
        val longTermLoan = account(companyId, AccountType.LIABILITY, AccountClassification.NON_CURRENT)
        val entry = postedEntry(
            JournalLine(building.id, Money(BigDecimal("50000.00"), GBP), TransactionSide.DEBIT),
            JournalLine(longTermLoan.id, Money(BigDecimal("50000.00"), GBP), TransactionSide.CREDIT)
        )

        val workingCapital = WorkingCapital.of(listOf(building, longTermLoan), listOf(entry), GBP)

        workingCapital.totalCurrentAssets shouldBe zero()
        workingCapital.totalCurrentLiabilities shouldBe zero()
    }

    @Test
    fun `given an unposted Draft entry, when computed, then it is excluded`() {
        val companyId = CompanyId.generate()
        val cash = account(companyId, AccountType.ASSET, AccountClassification.CURRENT)
        val capital = account(companyId, AccountType.EQUITY)
        val draft = JournalEntry.create(
            PeriodId.generate(), TODAY,
            listOf(
                JournalLine(cash.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(capital.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )

        val workingCapital = WorkingCapital.of(listOf(cash, capital), listOf(draft), GBP)

        workingCapital.totalCurrentAssets shouldBe zero()
    }

    @Test
    fun `given Accounts from different Companies, when computed, then it fails`() {
        val cash = account(CompanyId.generate(), AccountType.ASSET, AccountClassification.CURRENT)
        val payable = account(CompanyId.generate(), AccountType.LIABILITY, AccountClassification.CURRENT)

        shouldThrow<IllegalArgumentException> {
            WorkingCapital.of(listOf(cash, payable), emptyList(), GBP)
        }
    }

    private fun account(
        companyId: CompanyId,
        type: AccountType,
        classification: AccountClassification? = null
    ): Account = Account.create(companyId, type, classification, "1000", "Test Account")

    private fun postedEntry(vararg lines: JournalLine): JournalEntry {
        val entry = JournalEntry.create(PeriodId.generate(), TODAY, lines.toList(), JournalSource.MANUAL)
        entry.post()
        return entry
    }

    private fun zero(): Money = Money(BigDecimal.ZERO, GBP)
}
