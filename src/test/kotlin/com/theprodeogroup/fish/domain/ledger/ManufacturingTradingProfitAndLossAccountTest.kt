package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val PERIOD_START = LocalDate.of(2026, 2, 1)
private val PERIOD_END = LocalDate.of(2026, 2, 28)
private val WITHIN_PERIOD = LocalDate.of(2026, 2, 15)

class ManufacturingTradingProfitAndLossAccountTest {

    @Test
    fun `given a manufacturer's classified accounts with no WIP movement, when computed, then the full cascade is correct`() {
        val companyId = CompanyId.generate()
        val period = period(companyId)
        val revenue = revenueAccount(companyId)
        val directMaterial = expenseAccount(companyId, ExpenseClassification.DIRECT_MATERIAL)
        val directLabor = expenseAccount(companyId, ExpenseClassification.DIRECT_LABOR)
        val directExpense = expenseAccount(companyId, ExpenseClassification.DIRECT_EXPENSE)
        val factoryOverhead = expenseAccount(companyId, ExpenseClassification.FACTORY_OVERHEAD)
        val admin = expenseAccount(companyId, ExpenseClassification.ADMINISTRATIVE)
        val sellingDistribution = expenseAccount(companyId, ExpenseClassification.SELLING_DISTRIBUTION)
        val wip = assetAccount(companyId)
        val accounts = listOf(revenue, directMaterial, directLabor, directExpense, factoryOverhead, admin, sellingDistribution, wip)
        val entries = listOf(
            debitCredit(period.id, WITHIN_PERIOD, AccountId.generate(), revenue.id, "50000.00"),
            debitCredit(period.id, WITHIN_PERIOD, directMaterial.id, AccountId.generate(), "10000.00"),
            debitCredit(period.id, WITHIN_PERIOD, directLabor.id, AccountId.generate(), "8000.00"),
            debitCredit(period.id, WITHIN_PERIOD, directExpense.id, AccountId.generate(), "2000.00"),
            debitCredit(period.id, WITHIN_PERIOD, factoryOverhead.id, AccountId.generate(), "5000.00"),
            debitCredit(period.id, WITHIN_PERIOD, admin.id, AccountId.generate(), "6000.00"),
            debitCredit(period.id, WITHIN_PERIOD, sellingDistribution.id, AccountId.generate(), "3000.00")
        )

        val account = ManufacturingTradingProfitAndLossAccount.of(accounts, entries, wip, period, GBP)

        account.primeCost shouldBe Money(BigDecimal("20000.00"), GBP)
        account.totalManufacturingCost shouldBe Money(BigDecimal("25000.00"), GBP)
        account.openingWorkInProgress shouldBe Money(BigDecimal.ZERO, GBP)
        account.closingWorkInProgress shouldBe Money(BigDecimal.ZERO, GBP)
        account.costOfProduction shouldBe Money(BigDecimal("25000.00"), GBP)
        account.revenue shouldBe Money(BigDecimal("50000.00"), GBP)
        account.costOfSales shouldBe Money(BigDecimal("25000.00"), GBP)
        account.grossProfit shouldBe Money(BigDecimal("25000.00"), GBP)
        account.operatingExpenses shouldBe Money(BigDecimal("9000.00"), GBP)
        account.netProfit shouldBe Money(BigDecimal("16000.00"), GBP)
    }

    @Test
    fun `given a pure trader with Cost of Goods Sold and no manufacturing accounts, when computed, then the Manufacturing Account stays zero and Cost of Sales comes from COGS directly`() {
        val companyId = CompanyId.generate()
        val period = period(companyId)
        val revenue = revenueAccount(companyId)
        val cogs = expenseAccount(companyId, ExpenseClassification.COST_OF_GOODS_SOLD)
        val admin = expenseAccount(companyId, ExpenseClassification.ADMINISTRATIVE)
        val wip = assetAccount(companyId)
        val accounts = listOf(revenue, cogs, admin, wip)
        val entries = listOf(
            debitCredit(period.id, WITHIN_PERIOD, AccountId.generate(), revenue.id, "20000.00"),
            debitCredit(period.id, WITHIN_PERIOD, cogs.id, AccountId.generate(), "12000.00"),
            debitCredit(period.id, WITHIN_PERIOD, admin.id, AccountId.generate(), "3000.00")
        )

        val account = ManufacturingTradingProfitAndLossAccount.of(accounts, entries, wip, period, GBP)

        account.primeCost shouldBe Money(BigDecimal.ZERO, GBP)
        account.totalManufacturingCost shouldBe Money(BigDecimal.ZERO, GBP)
        account.costOfProduction shouldBe Money(BigDecimal.ZERO, GBP)
        account.costOfSales shouldBe Money(BigDecimal("12000.00"), GBP)
        account.grossProfit shouldBe Money(BigDecimal("8000.00"), GBP)
        account.operatingExpenses shouldBe Money(BigDecimal("3000.00"), GBP)
        account.netProfit shouldBe Money(BigDecimal("5000.00"), GBP)
    }

    @Test
    fun `given Work in Progress increases during the period, when computed, then Cost of Production is reduced by the increase`() {
        val companyId = CompanyId.generate()
        val period = period(companyId)
        val revenue = revenueAccount(companyId)
        val directMaterial = expenseAccount(companyId, ExpenseClassification.DIRECT_MATERIAL)
        val factoryOverhead = expenseAccount(companyId, ExpenseClassification.FACTORY_OVERHEAD)
        val wip = assetAccount(companyId)
        val accounts = listOf(revenue, directMaterial, factoryOverhead, wip)
        val entries = listOf(
            debitCredit(period.id, PERIOD_START.minusDays(5), wip.id, AccountId.generate(), "1000.00"),
            debitCredit(period.id, WITHIN_PERIOD, AccountId.generate(), revenue.id, "20000.00"),
            debitCredit(period.id, WITHIN_PERIOD, directMaterial.id, AccountId.generate(), "6000.00"),
            debitCredit(period.id, WITHIN_PERIOD, factoryOverhead.id, AccountId.generate(), "2000.00"),
            debitCredit(period.id, WITHIN_PERIOD, wip.id, AccountId.generate(), "3000.00")
        )

        val account = ManufacturingTradingProfitAndLossAccount.of(accounts, entries, wip, period, GBP)

        account.totalManufacturingCost shouldBe Money(BigDecimal("8000.00"), GBP)
        account.openingWorkInProgress shouldBe Money(BigDecimal("1000.00"), GBP)
        account.closingWorkInProgress shouldBe Money(BigDecimal("4000.00"), GBP)
        account.costOfProduction shouldBe Money(BigDecimal("5000.00"), GBP)
        account.grossProfit shouldBe Money(BigDecimal("15000.00"), GBP)
        account.netProfit shouldBe Money(BigDecimal("15000.00"), GBP)
    }

    @Test
    fun `given an unclassified Expense account, when computed, then it still counts toward operating expenses and Net Profit`() {
        val companyId = CompanyId.generate()
        val period = period(companyId)
        val revenue = revenueAccount(companyId)
        val bankCharges = Account.create(companyId, AccountType.EXPENSE, null, "6100", "Bank Charges")
        val wip = assetAccount(companyId)
        val accounts = listOf(revenue, bankCharges, wip)
        val entries = listOf(
            debitCredit(period.id, WITHIN_PERIOD, AccountId.generate(), revenue.id, "1000.00"),
            debitCredit(period.id, WITHIN_PERIOD, bankCharges.id, AccountId.generate(), "50.00")
        )

        val account = ManufacturingTradingProfitAndLossAccount.of(accounts, entries, wip, period, GBP)

        account.operatingExpenses shouldBe Money(BigDecimal("50.00"), GBP)
        account.netProfit shouldBe Money(BigDecimal("950.00"), GBP)
    }

    @Test
    fun `given no accounts, when computed, then it fails`() {
        val companyId = CompanyId.generate()
        val wip = assetAccount(companyId)

        shouldThrow<IllegalArgumentException> {
            ManufacturingTradingProfitAndLossAccount.of(emptyList(), emptyList(), wip, period(companyId), GBP)
        }
    }

    @Test
    fun `given Accounts from two different Companies, when computed, then it fails`() {
        val companyId = CompanyId.generate()
        val otherCompanyId = CompanyId.generate()
        val revenue = revenueAccount(companyId)
        val foreignExpense = expenseAccount(otherCompanyId, ExpenseClassification.ADMINISTRATIVE)
        val wip = assetAccount(companyId)

        shouldThrow<IllegalArgumentException> {
            ManufacturingTradingProfitAndLossAccount.of(listOf(revenue, foreignExpense), emptyList(), wip, period(companyId), GBP)
        }
    }

    private fun period(companyId: CompanyId): Period =
        Period.create(companyId, PeriodType.MONTH, PERIOD_START, PERIOD_END)

    private fun revenueAccount(companyId: CompanyId): Account =
        Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales Revenue")

    private fun expenseAccount(companyId: CompanyId, classification: ExpenseClassification): Account =
        Account.create(companyId, AccountType.EXPENSE, null, "5000", classification.name, classification)

    private fun assetAccount(companyId: CompanyId): Account =
        Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Work in Progress")

    private fun debitCredit(periodId: PeriodId, date: LocalDate, debitAccountId: AccountId, creditAccountId: AccountId, amount: String): JournalEntry {
        val entry = JournalEntry.create(
            periodId, date,
            listOf(
                JournalLine(debitAccountId, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
                JournalLine(creditAccountId, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        entry.post()
        return entry
    }
}
