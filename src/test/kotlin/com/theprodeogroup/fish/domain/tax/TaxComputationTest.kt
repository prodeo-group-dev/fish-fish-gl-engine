package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 15)

class TaxComputationTest {

    @Test
    fun `given a profitable Period, when computed, then taxDue is rate times net income`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val revenue = account(companyId, AccountType.REVENUE)
        val expense = account(companyId, AccountType.EXPENSE)
        val entries = listOf(
            postedEntry(
                periodId,
                JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
            ),
            postedEntry(
                periodId,
                JournalLine(expense.id, Money(BigDecimal("400.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("400.00"), GBP), TransactionSide.CREDIT)
            )
        )
        val taxRule = TaxRule.create("Sierra Leone", TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.30"))

        val computation = TaxComputation.of(taxRule, listOf(revenue, expense), entries, periodId, GBP)

        computation.taxableProfit shouldBe Money(BigDecimal("600.00"), GBP)
        computation.taxDue shouldBe Money(BigDecimal("180.00"), GBP)
    }

    @Test
    fun `given a Period with a net loss, when computed, then taxableProfit is negative but taxDue is zero`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val expense = account(companyId, AccountType.EXPENSE)
        val entry = postedEntry(
            periodId,
            JournalLine(expense.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
        )
        val taxRule = TaxRule.create("Sierra Leone", TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.30"))

        val computation = TaxComputation.of(taxRule, listOf(expense), listOf(entry), periodId, GBP)

        computation.taxableProfit shouldBe Money(BigDecimal("-500.00"), GBP)
        computation.taxDue shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a zero rate, when computed, then taxDue is zero regardless of profit`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val revenue = account(companyId, AccountType.REVENUE)
        val entry = postedEntry(
            periodId,
            JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
            JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
        )
        val taxRule = TaxRule.create("Sierra Leone", TaxType.CORPORATE_INCOME_TAX, BigDecimal.ZERO)

        val computation = TaxComputation.of(taxRule, listOf(revenue), listOf(entry), periodId, GBP)

        computation.taxDue shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given Accounts from two different Companies, when computed, then it fails - delegated to ProfitAndLoss's own validation`() {
        val revenueA = account(CompanyId.generate(), AccountType.REVENUE)
        val revenueB = account(CompanyId.generate(), AccountType.REVENUE)
        val taxRule = TaxRule.create("Sierra Leone", TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.30"))

        shouldThrow<IllegalArgumentException> {
            TaxComputation.of(taxRule, listOf(revenueA, revenueB), emptyList(), PeriodId.generate(), GBP)
        }
    }

    @Test
    fun `given the computation succeeds, then it carries the same TaxRule id and companyId used`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val revenue = account(companyId, AccountType.REVENUE)
        val taxRule = TaxRule.create("Sierra Leone", TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.30"))

        val computation = TaxComputation.of(taxRule, listOf(revenue), emptyList(), periodId, GBP)

        computation.companyId shouldBe companyId
        computation.periodId shouldBe periodId
        computation.taxRuleId shouldBe taxRule.id
    }

    @Test
    fun `given a TaxRule with a CategorySplit RateStructure, when computed with a category, then taxDue uses that category's rate`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val revenue = account(companyId, AccountType.REVENUE)
        val entry = postedEntry(
            periodId,
            JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
            JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
        )
        val irishCorporationTax = RateStructure.CategorySplit(
            mapOf("trading" to BigDecimal("0.125"), "passive" to BigDecimal("0.25"))
        )
        val taxRule = TaxRule.create("Ireland", TaxType.CORPORATE_INCOME_TAX, irishCorporationTax)

        val computation = TaxComputation.of(
            taxRule, listOf(revenue), listOf(entry), periodId, GBP, TaxComputationInputs(category = "trading")
        )

        computation.taxableProfit shouldBe Money(BigDecimal("1000.00"), GBP)
        computation.taxDue shouldBe Money(BigDecimal("125.00"), GBP)
    }

    @Test
    fun `given a TaxRule with a CategorySplit RateStructure, when computed without a category, then it fails`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val revenue = account(companyId, AccountType.REVENUE)
        val entry = postedEntry(
            periodId,
            JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
            JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
        )
        val irishCorporationTax = RateStructure.CategorySplit(
            mapOf("trading" to BigDecimal("0.125"), "passive" to BigDecimal("0.25"))
        )
        val taxRule = TaxRule.create("Ireland", TaxType.CORPORATE_INCOME_TAX, irishCorporationTax)

        shouldThrow<IllegalArgumentException> {
            TaxComputation.of(taxRule, listOf(revenue), listOf(entry), periodId, GBP)
        }
    }

    private fun account(companyId: CompanyId, type: AccountType): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        return Account.create(companyId, type, classification, "4000", "Test Account")
    }

    private fun postedEntry(periodId: PeriodId, vararg lines: JournalLine): JournalEntry {
        val entry = JournalEntry.create(periodId, TODAY, lines.toList(), JournalSource.MANUAL)
        entry.post()
        return entry
    }
}
