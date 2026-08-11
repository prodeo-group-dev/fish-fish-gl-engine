package com.theprodeogroup.fish.domain.common

/**
 * Side of a transaction in double-entry bookkeeping
 * 
 * Every transaction must be either a DEBIT or CREDIT.
 * In the accounting equation (A = L + E):
 * - Assets and Expenses increase with DEBIT
 * - Liabilities, Equity, and Income increase with CREDIT
 */
enum class TransactionSide {
    /**
     * Left side of the accounting equation
     * Increases: Assets, Expenses
     * Decreases: Liabilities, Equity, Income
     */
    DEBIT,
    
    /**
     * Right side of the accounting equation
     * Increases: Liabilities, Equity, Income
     * Decreases: Assets, Expenses
     */
    CREDIT
}
