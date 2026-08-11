package com.theprodeogroup.fish.domain.common

/**
 * Ordering options for transaction queries
 * 
 * Used in repository methods to specify sort order
 */
enum class OrderBy {
    /**
     * Order by transaction date (chronological)
     * 
     * SQL: ORDER BY value_date ASC, posted_at ASC
     * 
     * Use for:
     * - General ledger reports
     * - Account activity
     * - Transaction history
     * 
     * Results in chronological sequence of transactions
     */
    BY_DATE,
    
    /**
     * Order by transaction reference number
     * 
     * SQL: ORDER BY txn_number ASC
     * 
     * Use for:
     * - Transaction lookups by reference
     * - Sequential transaction listing
     * - Audit trails by transaction number
     * 
     * Results in sequential order: TXN-001, TXN-002, TXN-003, ...
     */
    BY_NUMBER,
    
    /**
     * Order by transaction amount (largest first)
     * 
     * SQL: ORDER BY (debit + credit) DESC
     * 
     * Use for:
     * - Finding largest transactions
     * - Exception reporting
     * - Materiality analysis
     * 
     * Results in descending order by transaction size
     */
    BY_AMOUNT
}
