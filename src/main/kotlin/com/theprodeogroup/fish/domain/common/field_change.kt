package com.theprodeogroup.fish.domain.common

/**
 * Represents a change to a single field in an entity
 *
 * Used in audit trail to track field-level changes for compliance.
 * Enables detailed change history: "User X changed field Y from A to B"
 *
 * Example usage:
 * ```
 * val changes = mapOf(
 *     "status" to FieldChange("status", "DRAFT", "POSTED", "STRING"),
 *     "amount" to FieldChange("amount", "100.00", "150.00", "DECIMAL")
 * )
 * ```
 */
data class FieldChange(
    /**
     * Name of the field that changed
     */
    val fieldName: String,

    /**
     * Value before the change, serialized as a string.
     * Null if the field was previously unset.
     */
    val oldValue: String?,

    /**
     * Value after the change, serialized as a string.
     * Null if the field was cleared.
     */
    val newValue: String?,

    /**
     * Type of the field's value (e.g. "STRING", "DECIMAL", "BOOLEAN", "DATE"),
     * so consumers of the audit trail can parse oldValue/newValue back correctly.
     */
    val fieldType: String
)
