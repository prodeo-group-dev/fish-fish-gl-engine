package com.theprodeogroup.fish.domain.common

import java.time.Instant
import java.util.UUID

/**
 * Context information for a request/operation
 * 
 * Contains security and audit information about who is performing
 * an operation and from where.
 * 
 * Used for:
 * - Audit logging (who did what, when, from where)
 * - Security checks (verify user permissions)
 * - Request tracing (correlate logs across services)
 * - Analytics (user behavior, geographic analysis)
 * 
 * Example usage:
 * ```
 * val context = RequestContext(
 *     clientId = client.id,
 *     userId = currentUser.id,
 *     sessionId = session.id,
 *     requestId = UUID.randomUUID().toString(),
 *     ipAddress = request.remoteAddress,
 *     userAgent = request.header("User-Agent")
 * )
 * 
 * auditService.log(
 *     action = AuditAction.POSTED,
 *     context = context,
 *     // ...
 * )
 * ```
 */
data class RequestContext(
    /**
     * ID of the client/tenant making the request
     * Null for system-level operations
     */
    val clientId: UUID?,
    
    /**
     * ID of the user performing the action
     * Required for all operations (even system uses system user ID)
     */
    val userId: UUID,
    
    /**
     * ID of the user's current session
     * Used for:
     * - Session validation
     * - Tracking user activity
     * - Concurrent session management
     */
    val sessionId: String?,
    
    /**
     * Unique ID for this specific request
     * Used for:
     * - Distributed tracing
     * - Correlating logs across services
     * - Debugging
     * 
     * Example: "req_7h3jk2m4n5p8"
     */
    val requestId: String?,
    
    /**
     * IP address of the client
     * Used for:
     * - Security monitoring
     * - Geographic analysis
     * - Fraud detection
     * - Login anomaly detection
     * 
     * Examples: "192.168.1.1", "2001:0db8:85a3::8a2e:0370:7334"
     */
    val ipAddress: String?,
    
    /**
     * User agent string (browser/client info)
     * Used for:
     * - Security analysis
     * - Client compatibility
     * - Bot detection
     * 
     * Example: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)..."
     */
    val userAgent: String?,
    
    /**
     * Timestamp when this context was created
     * Defaults to current time
     */
    val timestamp: Instant = Instant.now()
) {
    /**
     * Returns true if this is a system-level operation (no client)
     */
    fun isSystemOperation(): Boolean = clientId == null
    
    /**
     * Returns true if this request has a valid session
     */
    fun hasSession(): Boolean = sessionId != null
    
    /**
     * Returns true if this request can be traced (has requestId)
     */
    fun isTraceable(): Boolean = requestId != null
    
    /**
     * Creates a sanitized copy for logging (removes sensitive info if needed)
     */
    fun sanitize(): RequestContext {
        return this.copy(
            // Keep all fields - nothing sensitive here
            // If you add sensitive fields later, redact them here
        )
    }
    
    companion object {
        /**
         * Creates a system context (for automated operations)
         */
        fun system(userId: UUID): RequestContext {
            return RequestContext(
                clientId = null,
                userId = userId,
                sessionId = null,
                requestId = "system-${UUID.randomUUID()}",
                ipAddress = "127.0.0.1",
                userAgent = "System/1.0"
            )
        }
    }
}
