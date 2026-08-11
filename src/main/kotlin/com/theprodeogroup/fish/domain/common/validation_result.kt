package com.theprodeogroup.fish.domain.common

/**
 * Result of a validation operation
 * 
 * Contains:
 * - Validation status (pass/fail)
 * - Error messages (blocking issues)
 * - Warning messages (non-blocking issues)
 * 
 * Example usage:
 * ```
 * val result = validateJournal(journal)
 * if (!result.isValid) {
 *     throw ValidationException(result.errors.joinToString())
 * }
 * ```
 */
data class ValidationResult(
    /**
     * True if validation passed, false otherwise
     */
    val isValid: Boolean,
    
    /**
     * List of error messages (blocking issues that prevent processing)
     */
    val errors: List<String> = emptyList(),
    
    /**
     * List of warning messages (non-blocking issues for user awareness)
     */
    val warnings: List<String> = emptyList()
) {
    companion object {
        /**
         * Creates a successful validation result with no errors or warnings
         */
        fun success() = ValidationResult(true)
        
        /**
         * Creates a failed validation result with the given errors
         * 
         * @param errors Error messages
         * @return ValidationResult with isValid = false
         */
        fun failure(vararg errors: String) = ValidationResult(
            isValid = false, 
            errors = errors.toList()
        )
        
        /**
         * Creates a successful validation result with warnings
         * 
         * @param warnings Warning messages
         * @return ValidationResult with isValid = true but with warnings
         */
        fun withWarnings(vararg warnings: String) = ValidationResult(
            isValid = true,
            warnings = warnings.toList()
        )
    }
    
    /**
     * Combines two validation results
     * 
     * Result is valid only if both are valid.
     * Errors and warnings are merged.
     * 
     * @param other Another validation result to combine
     * @return Combined validation result
     */
    fun combine(other: ValidationResult): ValidationResult {
        return ValidationResult(
            isValid = this.isValid && other.isValid,
            errors = this.errors + other.errors,
            warnings = this.warnings + other.warnings
        )
    }
    
    /**
     * Returns true if there are any warnings
     */
    fun hasWarnings(): Boolean = warnings.isNotEmpty()
    
    /**
     * Returns true if there are any errors
     */
    fun hasErrors(): Boolean = errors.isNotEmpty()
}
