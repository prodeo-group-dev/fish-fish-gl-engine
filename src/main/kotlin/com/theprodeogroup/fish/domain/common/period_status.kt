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
     * Checks if transition to a new status is valid
     *
     * Valid transitions:
     * - DRAFT → OPEN
     * - OPEN → CLOSED
     * - CLOSED → OPEN (reopen)
     * - CLOSED → LOCKED
     * - LOCKED → (none - terminal)
     *
     * Calling convention aligned with `PostingStatus.canTransitionTo()`
     * (docs/DDD_Design.md Section 0, gap 6) - this used to be
     * `isValidTransition(from)`, asking "can I go FROM here," while
     * PostingStatus asked "can I go TO there." Flipped when Period
     * (the first real consumer of this enum) was built, so the
     * ubiquitous language ("can transition to") is uniform across both.
     *
     * @param newStatus Target status
     * @return True if transition is valid
     */
    fun canTransitionTo(newStatus: PeriodStatus): Boolean {
        return when (this) {
            DRAFT -> newStatus == OPEN
            OPEN -> newStatus == CLOSED
            CLOSED -> newStatus in setOf(OPEN, LOCKED)
            LOCKED -> false
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
