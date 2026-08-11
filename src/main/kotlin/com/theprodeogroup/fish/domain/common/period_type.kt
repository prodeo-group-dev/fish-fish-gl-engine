package com.theprodeogroup.fish.domain.common

/**
 * Type of fiscal period
 * 
 * Determines the duration and number of periods in a fiscal year
 */
enum class PeriodType {
    /**
     * Monthly periods (12 per year)
     * 
     * Standard for most businesses:
     * - January, February, March, ..., December
     * - Or based on fiscal year start date
     * 
     * Example: FY starting April 1
     * - Period 1: Apr 1 - Apr 30
     * - Period 2: May 1 - May 31
     * - ...
     * - Period 12: Mar 1 - Mar 31
     */
    MONTH,
    
    /**
     * Quarterly periods (4 per year)
     * 
     * Common for:
     * - Quarterly reporting requirements
     * - VAT/GST returns
     * - Simplified bookkeeping
     * 
     * Example: Calendar year
     * - Q1: Jan 1 - Mar 31
     * - Q2: Apr 1 - Jun 30
     * - Q3: Jul 1 - Sep 30
     * - Q4: Oct 1 - Dec 31
     */
    QUARTER,
    
    /**
     * Annual period (1 per year)
     * 
     * Used for:
     * - Simple entities with low transaction volume
     * - Annual-only reporting
     * 
     * Example: Calendar year
     * - Period 1: Jan 1 - Dec 31
     */
    YEAR,
    
    /**
     * Custom period definition
     * 
     * Used for:
     * - Non-standard period lengths
     * - 4-4-5 calendar (retail)
     * - 13-period year
     * - Project-based periods
     * 
     * Must be manually configured
     */
    CUSTOM
}
