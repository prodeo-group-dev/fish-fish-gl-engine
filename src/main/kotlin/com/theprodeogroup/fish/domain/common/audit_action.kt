package com.theprodeogroup.fish.domain.common

/**
 * Types of auditable actions in the system
 * 
 * Every action logged in the audit trail must have one of these types.
 * Used for:
 * - Audit reporting
 * - Compliance (SOX, GDPR)
 * - Security monitoring
 * - User activity tracking
 */
enum class AuditAction {
    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================
    
    /** Entity created */
    CREATED,
    
    /** Entity updated/modified */
    UPDATED,
    
    /** Entity deleted (should be rare - prefer soft delete) */
    DELETED,
    
    // ========================================================================
    // STATE TRANSITIONS (Journal/Transaction lifecycle)
    // ========================================================================
    
    /** Journal/transaction posted to ledger */
    POSTED,
    
    /** Journal/transaction reversed */
    REVERSED,
    
    /** Journal approved (approval workflow) */
    APPROVED,
    
    /** Journal rejected (approval workflow) */
    REJECTED,
    
    /** Journal submitted for approval */
    SUBMITTED,
    
    // ========================================================================
    // PERIOD OPERATIONS
    // ========================================================================
    
    /** Fiscal period opened for posting */
    OPENED,
    
    /** Fiscal period closed (no more posting) */
    CLOSED,
    
    /** Fiscal period permanently locked */
    LOCKED,
    
    /** Fiscal period reopened */
    REOPENED,
    
    // ========================================================================
    // TENANT OPERATIONS
    // ========================================================================

    /** Tenant activated (transitioned to Active) */
    ACTIVATED,

    /** Tenant suspended (Active -> Suspended) */
    SUSPENDED,

    /** Suspended tenant reactivated (Suspended -> Active) */
    REACTIVATED,

    // ========================================================================
    // SECURITY EVENTS
    // ========================================================================
    
    /** Successful user login */
    LOGIN,
    
    /** User logout */
    LOGOUT,
    
    /** Failed login attempt */
    LOGIN_FAILED,
    
    /** User password changed */
    PASSWORD_CHANGED,
    
    /** Permission granted to user */
    PERMISSION_GRANTED,
    
    /** Permission revoked from user */
    PERMISSION_REVOKED,
    
    // ========================================================================
    // DATA OPERATIONS
    // ========================================================================
    
    /** Data exported (report, CSV, etc.) */
    EXPORTED,
    
    /** Data imported (bulk upload, migration) */
    IMPORTED,
    
    /** Bulk update operation */
    BULK_UPDATE,
    
    // ========================================================================
    // SYSTEM OPERATIONS
    // ========================================================================
    
    /** System-generated action (depreciation, etc.) */
    SYSTEM_GENERATED,
    
    /** Database migration or system upgrade */
    MIGRATION,
    
    // ========================================================================
    // ACCESS TRACKING
    // ========================================================================
    
    /** Entity viewed/accessed */
    VIEWED,
    
    /** Document/report downloaded */
    DOWNLOADED,
    
    /** Document/report printed */
    PRINTED
}
