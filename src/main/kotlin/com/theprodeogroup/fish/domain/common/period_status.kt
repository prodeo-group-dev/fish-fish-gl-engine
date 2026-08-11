package com.theprodeogroup.fish.domain.common

/**
 * Status of a fiscal period in its lifecycle
 * 
 * Lifecycle:
 * ```
 * DRAFT ──→ OPEN ──→ CLOSED ──→ LOCKED
 *              ↑        ↓
 *              └────────┘
 *            (can reopen)
 * ```
 * 
 * LOCKED is terminal - cannot be reopened
 */
enum class PeriodStatus {
    /**
     * Draft period - not yet active
     * - Created but not open for posting
     * - Can be deleted
     * - Used for period setup
     */
    DRAFT,
    
    /**
     * Active period - open for posting
     * - Transactions can be posted
     * - Most operations allowed
     * - Current working period
     */
    OPEN,
    
    /**
     * Closed period - no new transactions
     * - No new transactions allowed
     * - Can be reopened if needed
     * - Used for period-end review
     * - Balances finalized but not permanent
     */
    CLOSED,
    
    /**
     * Locked period - permanently sealed
     * - Cannot be reopened
     * - No modifications allowed
     * - Terminal state
     * - Used for:
     *   * Tax filing
     *   * Audit compliance
     *   * Legal requirements
     */
    LOCKED;
    
    /**
     * Checks if transition to new status is valid
     * 
     * Valid transitions:
     * - DRAFT → OPEN
     * - OPEN → CLOSED
     * - CLOSED → OPEN (reopen)
     * - CLOSED → LOCKED
     * - LOCKED → (none - terminal)
     * 
     * @param from Source status
     * @return True if transition is valid
     */
    fun isValidTransition(from: PeriodStatus): Boolean {
        return when (this) {
            DRAFT -> from == DRAFT
            OPEN -> from in setOf(DRAFT, CLOSED)
            CLOSED -> from == OPEN
            LOCKED -> from == CLOSED
        }
    }
    
    /**
     * Returns true if posting is allowed in this period
     */
    fun allowsPosting(): Boolean = this == OPEN
    
    /**
     * Returns true if period can be reopened
     */
    fun canReopen(): Boolean = this == CLOSED
    
    /**
     * Returns true if period is finalized
     */
    fun isFinalized(): Boolean = this in setOf(CLOSED, LOCKED)
}
