package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.util.Currency

/**
 * The full three-stage **Manufacturing Account -> Trading Account ->
 * Profit & Loss Account** cascade (docs/DDD_Design.md Section 2.1/2.6),
 * confirmed 2026-08-14 as the follow-up to `ExpenseClassification`/
 * `InventoryStage`/`OverheadAllocation`. A query/report layer over
 * already-posted `JournalEntry` data, same shape as `TrialBalance`/
 * `ProfitAndLoss`/`WorkingCapital` - no new domain invariants, just a
 * richer grouping of the same Expense accounts `ProfitAndLoss` already
 * sums as one flat total.
 *
 * **Manufacturing Account** (only meaningful for a manufacturer -
 * these are all zero for a pure trader with no classified accounts):
 * - [primeCost] = `DIRECT_MATERIAL` + `DIRECT_LABOR` + `DIRECT_EXPENSE`
 * - [totalManufacturingCost] = [primeCost] + `FACTORY_OVERHEAD`
 * - [openingWorkInProgress]/[closingWorkInProgress] - [workInProgressAccount]'s
 *   balance before/through [period], same opening/closing mechanism
 *   `StatementOfCashFlows` already uses for cash.
 * - [costOfProduction] = [totalManufacturingCost] + [openingWorkInProgress] − [closingWorkInProgress]
 *
 * **Trading Account:**
 * - [revenue] - all Revenue accounts, same as `ProfitAndLoss.totalRevenue`.
 * - [costOfSales] = [costOfProduction] + `COST_OF_GOODS_SOLD` - the
 *   *sum* of both paths into Cost of Sales: a manufacturer's Cost of
 *   Production (built up through the Manufacturing Account above) and
 *   a pure trader's direct Cost of Goods Sold (goods resold as-is,
 *   never touching a Manufacturing Account at all). A company using
 *   only one of the two simply has zero in the other.
 * - [grossProfit] = [revenue] − [costOfSales]
 *
 * **Profit & Loss Account:**
 * - [operatingExpenses] = every Expense account *not* already counted
 *   in [costOfSales]'s two paths (`ADMINISTRATIVE`/`SELLING_DISTRIBUTION`,
 *   and critically any *unclassified* Expense account too - computed
 *   as `totalExpense - manufacturingAndCogsExpense` rather than
 *   enumerating classifications, so an Expense account nobody got
 *   around to classifying still counts toward Net Profit instead of
 *   silently vanishing).
 * - [netProfit] = [grossProfit] − [operatingExpenses]
 *
 * Algebraically, [netProfit] always equals [revenue] − (every Expense
 * account's balance) ± the WIP timing adjustment - the three-stage
 * grouping never changes the bottom line versus `ProfitAndLoss`'s
 * simpler `Revenue - Expense`, it only shows the intermediate Gross
 * Profit / Cost of Production figures a manufacturer's accounts need.
 */
class ManufacturingTradingProfitAndLossAccount private constructor(
    val companyId: CompanyId,
    val periodId: PeriodId,
    val currency: Currency,
    val primeCost: Money,
    val totalManufacturingCost: Money,
    val openingWorkInProgress: Money,
    val closingWorkInProgress: Money,
    val costOfProduction: Money,
    val revenue: Money,
    val costOfSales: Money,
    val grossProfit: Money,
    val operatingExpenses: Money,
    val netProfit: Money
) {
    companion object {
        /** [accounts] should all belong to one Company; [workInProgressAccount] is that Company's WIP control Account. */
        fun of(
            accounts: List<Account>,
            postedEntries: List<JournalEntry>,
            workInProgressAccount: Account,
            period: Period,
            currency: Currency
        ): ManufacturingTradingProfitAndLossAccount {
            require(accounts.isNotEmpty()) { "A ManufacturingTradingProfitAndLossAccount report needs at least one Account" }
            val companyId = accounts.first().companyId
            require(accounts.all { it.companyId == companyId }) {
                "All Accounts in a ManufacturingTradingProfitAndLossAccount report must belong to the same Company"
            }

            val zero = Money(BigDecimal.ZERO, currency)
            val periodEntries = postedEntries.filter { it.periodId == period.id }
            val balances = accountBalances(accounts, periodEntries, currency)

            fun sumBy(classification: ExpenseClassification): Money = accounts
                .filter { it.type == AccountType.EXPENSE && it.expenseClassification == classification }
                .fold(zero) { sum, account -> sum + balances.getValue(account.id) }

            val primeCost = sumBy(ExpenseClassification.DIRECT_MATERIAL) +
                sumBy(ExpenseClassification.DIRECT_LABOR) +
                sumBy(ExpenseClassification.DIRECT_EXPENSE)
            val totalManufacturingCost = primeCost + sumBy(ExpenseClassification.FACTORY_OVERHEAD)

            val openingEntries = postedEntries.filter { it.date.isBefore(period.startDate) }
            val closingEntries = postedEntries.filter { !it.date.isAfter(period.endDate) }
            val openingWorkInProgress = accountBalances(listOf(workInProgressAccount), openingEntries, currency)
                .getValue(workInProgressAccount.id)
            val closingWorkInProgress = accountBalances(listOf(workInProgressAccount), closingEntries, currency)
                .getValue(workInProgressAccount.id)
            val costOfProduction = totalManufacturingCost + openingWorkInProgress - closingWorkInProgress

            val revenue = accounts.filter { it.type == AccountType.REVENUE }
                .fold(zero) { sum, account -> sum + balances.getValue(account.id) }
            val costOfGoodsSold = sumBy(ExpenseClassification.COST_OF_GOODS_SOLD)
            val costOfSales = costOfProduction + costOfGoodsSold
            val grossProfit = revenue - costOfSales

            val totalExpense = accounts.filter { it.type == AccountType.EXPENSE }
                .fold(zero) { sum, account -> sum + balances.getValue(account.id) }
            val manufacturingAndCogsExpense = totalManufacturingCost + costOfGoodsSold
            val operatingExpenses = totalExpense - manufacturingAndCogsExpense
            val netProfit = grossProfit - operatingExpenses

            return ManufacturingTradingProfitAndLossAccount(
                companyId, period.id, currency,
                primeCost, totalManufacturingCost, openingWorkInProgress, closingWorkInProgress, costOfProduction,
                revenue, costOfSales, grossProfit,
                operatingExpenses, netProfit
            )
        }
    }
}
