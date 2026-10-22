package com.theprodeogroup.fish.domain.ledger

import java.time.LocalDate

/**
 * The documents a journal entry can name as its source (standard S6, GL-T-31). A closed set, declared by the consumers before GL
 * accepts a new value; stored as its name. An entry the Accountant keys in by hand names [MANUAL_JOURNAL]; a correction names the
 * document it corrects through [CORRECTION] or the document's own type.
 */
enum class SourceReferenceType {
    SALES_INVOICE,
    SALES_CREDIT_NOTE,
    COLLECTION,
    SUPPLIER_INVOICE,
    SUPPLIER_CREDIT_NOTE,
    SUPPLIER_PAYMENT,
    CASH_BOOK_ENTRY,
    VAT_SETTLEMENT,
    STOCK_RECEIPT,
    STOCK_ISSUE,
    PAY_RUN,
    LEAVE_ACCRUAL,
    MANUAL_JOURNAL,
    CORRECTION
}

/** The document an entry came from: [type], the owning service's own [id] for it, and the human [number] printed on it (an invoice number), if any. */
data class SourceReference(val type: SourceReferenceType, val id: String, val number: String?) {
    init {
        require(id.isNotBlank() && id.length <= MAX_LENGTH) { "A source reference id must be 1 to $MAX_LENGTH characters" }
        require(number == null || (number.isNotBlank() && number.length <= MAX_LENGTH)) { "A source reference number must be 1 to $MAX_LENGTH characters" }
    }

    companion object {
        const val MAX_LENGTH = 100
    }
}

/**
 * The rate row a caller used when it worked the VAT out itself (GL does not compute it, F22-18), so a later correction to that rate can find the
 * postings made under it (Tax's impact query). All fields are what the caller says it read; [ratePercent] is an exact decimal percent string.
 */
data class VatBasis(val ratePercent: String, val rulebookVersion: Int?, val effectiveFrom: LocalDate?) {
    init {
        require(ratePercent.length <= 20 && ratePercent.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true) { "A VAT basis rate must be a plain decimal percent" }
    }
}

/**
 * Who, why and what document, recorded once when an entry is stored (standards S4 and S6; GL-FR-AUD-4, TAX-10). Never edited: the entry's
 * accounting facts and this record are written together at insert and no path updates them. [recordedBy] is the credential that posted
 * (`USER:<EA user id>` or `SERVICE:<name>`); [actingUser] is the EA user id a service posted on behalf of, as that service said, so it is as
 * reliable as the service. [reason] is why, mandatory on a correction. Everything is null for an entry stored before V36.
 */
data class EntryProvenance(
    val recordedBy: String?,
    val actingUser: String?,
    val reason: String?,
    val sourceReference: SourceReference?,
    val vatBasis: VatBasis?
) {
    val isEmpty: Boolean get() = recordedBy == null && actingUser == null && reason == null && sourceReference == null && vatBasis == null
}
