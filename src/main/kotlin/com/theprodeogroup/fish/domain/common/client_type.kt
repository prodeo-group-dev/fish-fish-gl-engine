package com.theprodeogroup.fish.domain.common

/**
 * Type of business entity
 * 
 * Determines:
 * - Default Chart of Accounts template
 * - Equity structure (Owner's Capital vs Share Capital)
 * - Regulatory requirements
 * - Reporting formats
 */
enum class ClientType {
    /**
     * Individual person tracking personal finances
     * 
     * Chart of Accounts:
     * - Simple structure
     * - Personal assets (Cash, Investments)
     * - Personal liabilities (Loans, Credit Cards)
     * - Income (Salary, Investment Income)
     * - Expenses (Living, Entertainment)
     */
    INDIVIDUAL,
    
    /**
     * Sole trader / Self-employed individual
     * 
     * Chart of Accounts:
     * - Business assets and liabilities
     * - Owner's Capital and Drawings
     * - Revenue and operating expenses
     * - No share capital
     */
    SOLE_TRADER,
    
    /**
     * Partnership (2+ partners)
     * 
     * Chart of Accounts:
     * - Similar to sole trader
     * - Separate capital accounts per partner
     * - Separate drawings accounts per partner
     * - Profit sharing based on partnership agreement
     */
    PARTNERSHIP,
    
    /**
     * Limited company (Ltd, LLC, Corp, etc.)
     * 
     * Chart of Accounts:
     * - Share capital structure
     * - Retained earnings
     * - Dividends instead of drawings
     * - More complex compliance requirements
     * - Full balance sheet and P&L
     */
    COMPANY_LIMITED,
    
    /**
     * Non-profit organization / Charity
     * 
     * Chart of Accounts:
     * - Net assets instead of equity
     * - Restricted and unrestricted funds
     * - Donations and grants as income
     * - Program expenses and admin costs
     * - Different reporting format (Statement of Activities)
     */
    NON_PROFIT
}
