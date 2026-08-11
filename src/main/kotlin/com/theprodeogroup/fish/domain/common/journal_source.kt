package com.theprodeogroup.fish.domain.common

/**
 * Origin or source of a journal entry
 * 
 * Used for:
 * - Audit trail (who/what created the entry)
 * - Reporting (analyze entries by source)
 * - Filtering (show only manual entries, etc.)
 */
enum class JournalSource {
    /**
     * Manually entered by a user through the UI
     * 
     * Example: Accountant creates journal entry for correction
     */
    MANUAL,
    
    /**
     * System-generated reversal of another journal
     * 
     * Example: Reversing an incorrect invoice posting
     */
    REVERSAL,
    
    /**
     * System-generated entry (depreciation, accruals, etc.)
     * 
     * Examples:
     * - Monthly depreciation entry
     * - Automatic accruals
     * - Bank reconciliation adjustments
     */
    SYSTEM,
    
    /**
     * Created via external API
     * 
     * Example: Third-party app posts entries via REST API
     */
    API,
    
    /**
     * Bulk imported from file (CSV, Excel, etc.)
     * 
     * Example: Importing historical data during migration
     */
    IMPORT,
    
    /**
     * Created by integration with external system
     * 
     * Examples:
     * - Stripe payment processor
     * - Xero accounting sync
     * - Bank feed integration
     */
    INTEGRATION,
    
    /**
     * Period closing entry
     * 
     * Example: Closing nominal accounts to retained earnings
     */
    CLOSING
}
