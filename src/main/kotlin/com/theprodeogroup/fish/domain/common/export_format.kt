package com.theprodeogroup.fish.domain.common

/**
 * Supported export formats for reports and data
 * 
 * Used when exporting:
 * - Audit logs
 * - Financial reports
 * - Transaction lists
 * - Account data
 * 
 * Example usage:
 * ```
 * val data = reportService.generateTrialBalance(clientId, periodId)
 * val exported = exportService.export(data, ExportFormat.PDF)
 * ```
 */
enum class ExportFormat {
    /**
     * Comma-Separated Values format
     * 
     * Best for:
     * - Excel import
     * - Data analysis
     * - Simple data transfer
     * - Large datasets
     * 
     * Characteristics:
     * - Text-based
     * - Universal compatibility
     * - No formatting
     * - Lightweight
     * 
     * MIME type: text/csv
     * Extension: .csv
     */
    CSV,
    
    /**
     * JavaScript Object Notation format
     * 
     * Best for:
     * - API responses
     * - System integration
     * - Structured data
     * - Web applications
     * 
     * Characteristics:
     * - Text-based
     * - Nested structures
     * - Type information preserved
     * - Human-readable
     * 
     * MIME type: application/json
     * Extension: .json
     */
    JSON,
    
    /**
     * Portable Document Format
     * 
     * Best for:
     * - Printed reports
     * - Official documents
     * - Presentation
     * - Archival
     * 
     * Characteristics:
     * - Binary format
     * - Formatted layout
     * - Print-ready
     * - Not editable
     * 
     * MIME type: application/pdf
     * Extension: .pdf
     */
    PDF,
    
    /**
     * Microsoft Excel format (Office Open XML)
     * 
     * Best for:
     * - Spreadsheet analysis
     * - Complex reports
     * - Multi-sheet documents
     * - Formatted data
     * 
     * Characteristics:
     * - Binary format
     * - Multiple sheets
     * - Formulas supported
     * - Formatting preserved
     * 
     * MIME type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
     * Extension: .xlsx
     */
    XLSX;
    
    /**
     * Returns the MIME type for this format
     */
    fun getMimeType(): String {
        return when (this) {
            CSV -> "text/csv"
            JSON -> "application/json"
            PDF -> "application/pdf"
            XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
    }
    
    /**
     * Returns the file extension for this format (with dot)
     */
    fun getExtension(): String {
        return when (this) {
            CSV -> ".csv"
            JSON -> ".json"
            PDF -> ".pdf"
            XLSX -> ".xlsx"
        }
    }
    
    /**
     * Returns true if this is a text-based format
     */
    fun isTextBased(): Boolean {
        return this in setOf(CSV, JSON)
    }
    
    /**
     * Returns true if this format supports formatting/styling
     */
    fun supportsFormatting(): Boolean {
        return this in setOf(PDF, XLSX)
    }
    
    /**
     * Returns suggested filename with this format's extension
     */
    fun filename(base: String): String {
        return base + getExtension()
    }
}
