package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * Profit and Loss for a date range, `Revenue - Expense` for entries dated from [from] to [to],
 * both ends inclusive (UAT v2.2: month-end and year-end packs). Unlike [ProfitAndLoss], which is
 * scoped to one `Period`, a range may span several Periods or cover part of one. Same query
 * over already-posted entries, no new domain invariants; Draft, Pending and Rejected entries are
 * excluded exactly as in [ProfitAndLoss].
 */
class ProfitAndLossForDates private constructor(
    val companyId: CompanyId,
    val from: LocalDate,
    val to: LocalDate,
    val currency: Currency,
    val totalRevenue: Money,
    val totalExpense: Money
) {
    val netIncome: Money
        get() = totalRevenue - totalExpense

    companion object {
        /** [accounts] should all belong to one Company; only Revenue/Expense accounts contribute to the totals. */
        fun of(
            accounts: List<Account>,
            postedEntries: List<JournalEntry>,
            from: LocalDate,
            to: LocalDate,
            currency: Currency
        ): ProfitAndLossForDates {
            require(accounts.isNotEmpty()) { "A ProfitAndLossForDates report needs at least one Account" }
            require(!to.isBefore(from)) { "to cannot be before from" }
            val companyId = accounts.first().companyId
            require(accounts.all { it.companyId == companyId }) {
                "All Accounts in a ProfitAndLossForDates report must belong to the same Company"
            }
            val rangeEntries = postedEntries.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
            val balances = accountBalances(accounts, rangeEntries, currency)

            val zero = Money(BigDecimal.ZERO, currency)
            val totalRevenue = accounts.filter { it.type == AccountType.REVENUE }
                .fold(zero) { sum, account -> sum + balances.getValue(account.id) }
            val totalExpense = accounts.filter { it.type == AccountType.EXPENSE }
                .fold(zero) { sum, account -> sum + balances.getValue(account.id) }

            return ProfitAndLossForDates(companyId, from, to, currency, totalRevenue, totalExpense)
        }
    }
}
