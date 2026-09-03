package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * Default Chart of Accounts, keyed to [ClientType] (docs/FiSH_GL_Engine_Spec.md
 * Section 7.1, docs/DDD_Design.md Section 9.2 step 3 - "ClientType selects
 * the default COA template," previously a confirmed-but-unbuilt requirement).
 * A newly onboarded Company had no Accounts and no Period at all until this
 * existed - it could be created, but nothing could ever be posted to it.
 *
 * Each [ClientType]'s account list is a direct translation of the structural
 * guidance already sitting in that enum's own KDoc (personal vs. sole-trader
 * vs. company vs. non-profit) - this file is where that guidance actually
 * becomes real `Account` rows for the first time, not a new design.
 *
 * **Standard numeric ranges, not arbitrary**: 1000s Asset, 2000s Liability,
 * 3000s Equity, 4000s Revenue, 5000s Expense - matches the numbering already
 * used ad hoc in this codebase's own test fixtures (e.g. "5000" for an
 * Expense account, "2100" for Accounts Payable).
 *
 * **`expenseClassification` deliberately left `null` on every seeded Expense
 * account** - it's optional even for Expense accounts
 * ([AccountType.requiresExpenseClassification]'s own KDoc), and a generic
 * template has no basis for guessing Manufacturing vs. Trading vs. P&L for
 * an account that doesn't exist in a real business yet.
 *
 * **`NON_PROFIT`'s template is generic, not Purse's credit-union-specific
 * one** - already flagged as a separate, deferred piece of work (member
 * deposits, loan receivables, interest income/expense have no place in a
 * generic non-profit template and need real credit-union domain input, not
 * a guess made here).
 *
 * **`PARTNERSHIP`'s per-partner capital/drawings accounts are approximated
 * as one shared pair**, not one pair per partner - there is no `Partner`
 * concept anywhere in this domain model to seed sub-accounts for. Revisit
 * if/when partners become a modeled concept; a single combined pair is an
 * honest starting point, not a silent shortcut around a real requirement.
 *
 * **Every template also seeds a dedicated "Opening Balance Equity" account**
 * ([OPENING_BALANCE_EQUITY_CODE]) - most users are expected to have
 * incomplete records, not a full opening trial balance, so the one anchor
 * value the onboarding flow actually asks for and posts is the starting
 * cash/bank figure as of the opening Period's start date (see
 * `OnboardTenantUseCase`). That entry's offsetting side needs its own
 * account, kept separate from a template's "real" equity accounts (Share
 * Capital, Owner's Capital, etc.) rather than posted into one of those
 * directly - standard practice (matches how QuickBooks and similar
 * systems isolate this), so a rough opening figure never gets silently
 * blended into an account meant to track something more precise.
 */
object ChartOfAccountsTemplate {
    const val CASH_CODE = "1000"
    const val OPENING_BALANCE_EQUITY_CODE = "3900"

    /**
     * Payroll accounts (added 2026-09-02, UC-HR15/[ComputePayrollPostingContextUseCase]) -
     * seeded on every template except [ClientType.INDIVIDUAL] (personal
     * finance has no employees). `ACCRUED_LEAVE_LIABILITY_CODE` sits in
     * the 2000s Liability range next to Accounts Payable (2000), not
     * the 5000s alongside the expense accounts - it's IAS 19's
     * accumulating-leave *liability* `LeaveAccrual` already carries.
     */
    const val WAGES_EXPENSE_CODE = "5200"
    const val SALARIES_EXPENSE_CODE = "5300"
    const val LEAVE_EXPENSE_CODE = "5400"
    const val ACCRUED_LEAVE_LIABILITY_CODE = "2200"

    /**
     * Trade finance facility liability - closes the FR-PO06 gap
     * (docs/Purchase_Order_Processing_DDD_Design.md): when a Bank
     * executes a supplier payment on the financing structure's behalf
     * ([com.theprodeogroup.fish.domain.purchasing.CreditorId] paid via
     * POP's `ExecutingParty.BANK`), the settlement side of that posting
     * is this liability, not Cash - the cash didn't come from the
     * Company's own account. Distinct from `Loans Payable` (2100,
     * already seeded) rather than reusing it: a revolving trade
     * facility drawdown is a specific, short-term (hence `CURRENT`)
     * obligation, not the same thing as a term loan. Seeded on every
     * business template, same scoping [payrollAccounts] already uses -
     * personal finance ([ClientType.INDIVIDUAL]) never runs a trade
     * finance facility.
     */
    const val FACILITY_LIABILITY_CODE = "2300"

    fun accountsFor(clientType: ClientType, companyId: CompanyId): List<Account> =
        when (clientType) {
            ClientType.INDIVIDUAL -> individualAccounts(companyId)
            ClientType.SOLE_TRADER -> soleTraderAccounts(companyId)
            ClientType.PARTNERSHIP -> partnershipAccounts(companyId)
            ClientType.COMPANY_LIMITED -> companyLimitedAccounts(companyId)
            ClientType.NON_PROFIT -> nonProfitAccounts(companyId)
        }

    private fun individualAccounts(companyId: CompanyId): List<Account> = listOf(
        asset(companyId, CASH_CODE, "Cash", AccountClassification.CURRENT),
        asset(companyId, "1100", "Investments", AccountClassification.NON_CURRENT),
        liability(companyId, "2000", "Loans", AccountClassification.NON_CURRENT),
        liability(companyId, "2100", "Credit Cards", AccountClassification.CURRENT),
        equity(companyId, "3000", "Personal Equity"),
        openingBalanceEquity(companyId),
        revenue(companyId, "4000", "Salary Income"),
        revenue(companyId, "4100", "Investment Income"),
        expense(companyId, "5000", "Living Expenses"),
        expense(companyId, "5100", "Entertainment"),
    )

    private fun soleTraderAccounts(companyId: CompanyId): List<Account> = listOf(
        listOf(
            asset(companyId, CASH_CODE, "Cash", AccountClassification.CURRENT),
            asset(companyId, "1100", "Accounts Receivable", AccountClassification.CURRENT),
            asset(companyId, "1200", "Fixed Assets", AccountClassification.NON_CURRENT),
            liability(companyId, "2000", "Accounts Payable", AccountClassification.CURRENT),
            liability(companyId, "2100", "Loans Payable", AccountClassification.NON_CURRENT),
            equity(companyId, "3000", "Owner's Capital"),
            equity(companyId, "3100", "Owner's Drawings"),
            openingBalanceEquity(companyId),
            revenue(companyId, "4000", "Sales Revenue"),
            expense(companyId, "5000", "Operating Expenses"),
        ),
        payrollAccounts(companyId),
        facilityLiabilityAccounts(companyId),
    ).flatten()

    private fun partnershipAccounts(companyId: CompanyId): List<Account> = listOf(
        listOf(
            asset(companyId, CASH_CODE, "Cash", AccountClassification.CURRENT),
            asset(companyId, "1100", "Accounts Receivable", AccountClassification.CURRENT),
            asset(companyId, "1200", "Fixed Assets", AccountClassification.NON_CURRENT),
            liability(companyId, "2000", "Accounts Payable", AccountClassification.CURRENT),
            liability(companyId, "2100", "Loans Payable", AccountClassification.NON_CURRENT),
            equity(companyId, "3000", "Partners' Capital"),
            equity(companyId, "3100", "Partners' Drawings"),
            openingBalanceEquity(companyId),
            revenue(companyId, "4000", "Sales Revenue"),
            expense(companyId, "5000", "Operating Expenses"),
        ),
        payrollAccounts(companyId),
        facilityLiabilityAccounts(companyId),
    ).flatten()

    private fun companyLimitedAccounts(companyId: CompanyId): List<Account> = listOf(
        listOf(
            asset(companyId, CASH_CODE, "Cash", AccountClassification.CURRENT),
            asset(companyId, "1100", "Accounts Receivable", AccountClassification.CURRENT),
            asset(companyId, "1200", "Fixed Assets", AccountClassification.NON_CURRENT),
            liability(companyId, "2000", "Accounts Payable", AccountClassification.CURRENT),
            liability(companyId, "2100", "Loans Payable", AccountClassification.NON_CURRENT),
            equity(companyId, "3000", "Share Capital"),
            equity(companyId, "3100", "Retained Earnings"),
            equity(companyId, "3200", "Dividends"),
            openingBalanceEquity(companyId),
            revenue(companyId, "4000", "Sales Revenue"),
            expense(companyId, "5000", "Operating Expenses"),
        ),
        payrollAccounts(companyId),
        facilityLiabilityAccounts(companyId),
    ).flatten()

    private fun nonProfitAccounts(companyId: CompanyId): List<Account> = listOf(
        listOf(
            asset(companyId, CASH_CODE, "Cash", AccountClassification.CURRENT),
            asset(companyId, "1100", "Accounts Receivable", AccountClassification.CURRENT),
            asset(companyId, "1200", "Fixed Assets", AccountClassification.NON_CURRENT),
            liability(companyId, "2000", "Accounts Payable", AccountClassification.CURRENT),
            equity(companyId, "3000", "Unrestricted Net Assets"),
            equity(companyId, "3100", "Restricted Net Assets"),
            openingBalanceEquity(companyId),
            revenue(companyId, "4000", "Donations Income"),
            revenue(companyId, "4100", "Grants Income"),
            expense(companyId, "5000", "Program Expenses"),
            expense(companyId, "5100", "Administrative Expenses"),
        ),
        payrollAccounts(companyId),
        facilityLiabilityAccounts(companyId),
    ).flatten()

    private fun facilityLiabilityAccounts(companyId: CompanyId): List<Account> = listOf(
        liability(companyId, FACILITY_LIABILITY_CODE, "Trade Finance Facility Payable", AccountClassification.CURRENT),
    )

    private fun payrollAccounts(companyId: CompanyId): List<Account> = listOf(
        expense(companyId, WAGES_EXPENSE_CODE, "Wages Expense"),
        expense(companyId, SALARIES_EXPENSE_CODE, "Salaries Expense"),
        expense(companyId, LEAVE_EXPENSE_CODE, "Leave Expense"),
        liability(companyId, ACCRUED_LEAVE_LIABILITY_CODE, "Accrued Leave Liability", AccountClassification.CURRENT),
    )

    private fun asset(companyId: CompanyId, code: String, name: String, classification: AccountClassification) =
        Account.create(companyId, AccountType.ASSET, classification, code, name)

    private fun liability(companyId: CompanyId, code: String, name: String, classification: AccountClassification) =
        Account.create(companyId, AccountType.LIABILITY, classification, code, name)

    private fun equity(companyId: CompanyId, code: String, name: String) =
        Account.create(companyId, AccountType.EQUITY, null, code, name)

    private fun openingBalanceEquity(companyId: CompanyId) =
        equity(companyId, OPENING_BALANCE_EQUITY_CODE, "Opening Balance Equity")

    private fun revenue(companyId: CompanyId, code: String, name: String) =
        Account.create(companyId, AccountType.REVENUE, null, code, name)

    private fun expense(companyId: CompanyId, code: String, name: String) =
        Account.create(companyId, AccountType.EXPENSE, null, code, name)
}
