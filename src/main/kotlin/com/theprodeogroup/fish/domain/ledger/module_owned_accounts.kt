package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.ClientType

/**
 * Which screen to use instead, when an account is not one a person may record money against directly in a cash or
 * bank book. The token is stable so a screen can name and link the right place.
 */
enum class UseInstead {
    /** Money from a customer: Sales, then record the collection. */
    SALES_COLLECTION,

    /** Money to a supplier: Purchases, then record the payment. */
    PURCHASE_PAYMENT,

    /** Stock bought or sold: Stock. */
    INVENTORY,

    /** A fixed asset bought, depreciated or sold: Fixed assets. */
    FIXED_ASSETS,

    /** VAT owed or reclaimed: the VAT return. */
    VAT,

    /** Opening figures: the opening-figures screen. */
    OPENING_FIGURES,

    /** Money between two of the Company's own cash and bank books: Move money. */
    TRANSFER,

    /** Payroll: an entry made by a pay run is reversed in Payroll. (Used when refusing an Undo, not for counter-accounts.) */
    PAYROLL,

    /** An entry that did not start in a cash or bank book: reverse it where it was made. (Used when refusing an Undo.) */
    ORIGINAL_SCREEN
}

/**
 * The accounts that another part of the system owns, so a receipt or payment recorded in a cash or bank book may
 * not post straight against them (docs/GL_Cash_And_Bank_Books_SRS.md, decision D5, confirmed by Femi 2026-10-10):
 * customer and supplier money must go through Sales and Purchases so receivables and payables stay itemised (the
 * DDD design's hard rule), stock and fixed assets keep their own registers, and opening figures keep their own flow.
 *
 * This is the single place that list lives. The posting contexts resolve the same accounts by the same codes
 * ([ChartOfAccountsTemplate]), and the counter-account picker is built from this too, so a screen never offers an
 * account the refusal would reject.
 *
 * The codes mean those accounts only in a business chart: an Individual's chart reuses 1100 for Investments and
 * 2000 for Loans, so for [ClientType.INDIVIDUAL] only the opening-figures accounts and the cash and bank books
 * themselves are reserved.
 */
object ModuleOwnedAccounts {

    /** Why [account] may not be the counter-account of a cash book entry, or null if it may. */
    fun useInstead(account: Account, clientType: ClientType): UseInstead? {
        if (account.cashBookKind != null) return UseInstead.TRANSFER
        if (account.type == AccountType.EQUITY &&
            (account.code == ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE || account.code == ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE)
        ) return UseInstead.OPENING_FIGURES
        if (clientType == ClientType.INDIVIDUAL) return null
        return when {
            account.type == AccountType.ASSET && account.code == ACCOUNTS_RECEIVABLE_CODE -> UseInstead.SALES_COLLECTION
            account.type == AccountType.LIABILITY && account.code == ACCOUNTS_PAYABLE_CODE -> UseInstead.PURCHASE_PAYMENT
            account.type == AccountType.ASSET && account.code == ChartOfAccountsTemplate.INVENTORY_CODE -> UseInstead.INVENTORY
            account.type == AccountType.ASSET && (account.code == FIXED_ASSETS_CODE || account.code == ChartOfAccountsTemplate.ACCUMULATED_DEPRECIATION_CODE) -> UseInstead.FIXED_ASSETS
            account.type == AccountType.LIABILITY && account.code == ChartOfAccountsTemplate.VAT_CONTROL_ACCOUNT_CODE -> UseInstead.VAT
            else -> null
        }
    }

    const val ACCOUNTS_RECEIVABLE_CODE = "1100"
    const val ACCOUNTS_PAYABLE_CODE = "2000"
    const val FIXED_ASSETS_CODE = "1200"
}
