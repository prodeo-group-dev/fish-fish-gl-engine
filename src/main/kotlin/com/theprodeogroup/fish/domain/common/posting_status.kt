package com.theprodeogroup.fish.domain.common

/**
 * Status of a journal entry or transaction in the posting lifecycle
 * 
 * Lifecycle:
 * ```
 * DRAFT ──→ PENDING ──→ POSTED ──→ REVERSED
 *    ↑         ↓
 *    └─────────┘
 *        (via REJECTED)
 * ```
 * 
 * SYSTEM follows same rules as POSTED
 */
enum class PostingStatus {
    /**
     * Draft entry - not yet posted
     * - Can be edited
     * - Can be deleted
     * - Not reflected in account balances
     */
    DRAFT,
    
    /**
     * Awaiting approval
     * - Cannot be edited
     * - Awaits approval or rejection
     * - Not reflected in account balances
     */
    PENDING,
    
    /**
     * Posted and final
     * - Immutable (cannot be edited or deleted)
     * - Reflected in account balances
     * - Can only be reversed (never deleted)
     */
    POSTED,
    
    /**
     * Reversed by a reversal journal
     * - Original remains in system (audit trail)
     * - Effect cancelled by reversal
     * - Cannot transition to any other status
     */
    REVERSED,
    
    /**
     * Rejected during approval
     * - Can be edited and resubmitted
     * - Not reflected in account balances
     */
    REJECTED,
    
    /**
     * System-generated entry (depreciation, accruals, etc.)
     * - Immutable like POSTED
     * - Can be reversed
     * - Flagged for reporting purposes
     */
    SYSTEM;
    
    /**
     * Checks if transition to a new status is valid
     * 
     * Valid transitions:
     * - DRAFT → PENDING, POSTED, REJECTED
     * - PENDING → POSTED, REJECTED, DRAFT
     * - POSTED → REVERSED
     * - REVERSED → (none)
     * - REJECTED → DRAFT, PENDING
     * - SYSTEM → REVERSED
     * 
     * @param newStatus Target status
     * @return True if transition is valid
     */
    fun canTransitionTo(newStatus: PostingStatus): Boolean {
        return when (this) {
            DRAFT -> newStatus in setOf(PENDING, POSTED, REJECTED)
            PENDING -> newStatus in setOf(POSTED, REJECTED, DRAFT)
            POSTED -> newStatus == REVERSED
            REVERSED -> false  // Terminal state
            REJECTED -> newStatus in setOf(DRAFT, PENDING)
            SYSTEM -> newStatus == REVERSED
        }
    }
    
    /**
     * Returns true if this status allows editing
     */
    fun isEditable(): Boolean = this in setOf(DRAFT, REJECTED)
    
    /**
     * Returns true if this status affects account balances
     */
    fun affectsBalance(): Boolean = this in setOf(POSTED, SYSTEM)
    
    /**
     * Returns true if this is a final state (immutable)
     */
    fun isFinal(): Boolean = this in setOf(POSTED, REVERSED, SYSTEM)
}
