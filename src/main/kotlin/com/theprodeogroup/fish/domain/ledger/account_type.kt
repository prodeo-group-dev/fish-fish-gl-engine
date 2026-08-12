package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.TransactionSide

/**
 * The five fundamental account types (docs/DDD_Design.md Section 1/3.1) -
 * a plain-language restatement, confirmed by the user: the GL exists so a
 * Company can "recognise its own income, expenditure, capital, assets and
 * liabilities."
 */
enum class AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE;

    /**
     * The side that increases this account type's balance, per
     * TransactionSide's accounting-equation rule (A = L + E): Assets and
     * Expenses increase with DEBIT, Liabilities/Equity/Revenue with CREDIT.
     */
    fun normalBalance(): TransactionSide = when (this) {
        ASSET, EXPENSE -> TransactionSide.DEBIT
        LIABILITY, EQUITY, REVENUE -> TransactionSide.CREDIT
    }

    /**
     * True for Asset and Liability types, which need a current/non-current
     * classification per IAS 1 (docs/DDD_Design.md Section 3.1, added
     * 2026-08-11) - a balance-sheet presentation requirement that doesn't
     * apply to Equity, Revenue, or Expense accounts.
     */
    fun requiresClassification(): Boolean = this == ASSET || this == LIABILITY
}
