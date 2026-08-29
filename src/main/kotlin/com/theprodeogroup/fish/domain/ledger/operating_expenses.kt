package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.util.Currency

/**
 * "The rate at which money is being expensed - not direct purchases
 * for resale" (2026-08-27, the expense-velocity dashboard KPI's
 * counterpart to [ProfitAndLoss]'s money-velocity). Every Expense
 * account **except** the ones that are Cost of Sales by either path
 * into it - a manufacturer's Manufacturing Account
 * ([ExpenseClassification.DIRECT_MATERIAL]/[ExpenseClassification.DIRECT_LABOR]/
 * [ExpenseClassification.DIRECT_EXPENSE]/[ExpenseClassification.FACTORY_OVERHEAD])
 * or a pure trader's direct [ExpenseClassification.COST_OF_GOODS_SOLD] -
 * matches [ManufacturingTradingProfitAndLossAccount.operatingExpenses]'s
 * own definition exactly, but computed directly rather than through
 * that class, since this figure alone needs none of the Work-in-Progress
 * timing adjustment that only affects Cost of Sales/Gross Profit/Net
 * Profit - most Companies (anything that isn't a manufacturer) have no
 * WIP control Account at all, which [ManufacturingTradingProfitAndLossAccount.of]
 * requires as a parameter.
 *
 * An unclassified Expense account (`expenseClassification == null` -
 * the norm for [ChartOfAccountsTemplate]'s own seeded accounts) counts
 * as an operating expense, not Cost of Sales - the same "don't silently
 * drop what nobody classified" reasoning [ManufacturingTradingProfitAndLossAccount]'s
 * own KDoc already documents.
 */
class OperatingExpenses private constructor(
    val companyId: CompanyId,
    val periodId: PeriodId,
    val currency: Currency,
    val total: Money
) {
    companion object {
        private val COST_OF_SALES_CLASSIFICATIONS = setOf(
            ExpenseClassification.DIRECT_MATERIAL,
            ExpenseClassification.DIRECT_LABOR,
            ExpenseClassification.DIRECT_EXPENSE,
            ExpenseClassification.FACTORY_OVERHEAD,
            ExpenseClassification.COST_OF_GOODS_SOLD
        )

        /** [accounts] should all belong to one Company. */
        fun of(
            accounts: List<Account>,
            postedEntries: List<JournalEntry>,
            periodId: PeriodId,
            currency: Currency
        ): OperatingExpenses {
            require(accounts.isNotEmpty()) { "An OperatingExpenses report needs at least one Account" }
            val companyId = accounts.first().companyId
            require(accounts.all { it.companyId == companyId }) {
                "All Accounts in an OperatingExpenses report must belong to the same Company"
            }
            val periodEntries = postedEntries.filter { it.periodId == periodId }
            val balances = accountBalances(accounts, periodEntries, currency)

            val zero = Money(BigDecimal.ZERO, currency)
            val total = accounts
                .filter { it.type == AccountType.EXPENSE && it.expenseClassification !in COST_OF_SALES_CLASSIFICATIONS }
                .fold(zero) { sum, account -> sum + balances.getValue(account.id) }

            return OperatingExpenses(companyId, periodId, currency, total)
        }
    }
}
