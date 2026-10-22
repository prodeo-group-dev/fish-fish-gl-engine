package com.theprodeogroup.fish.domain.ledger

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The documents an entry can name as its source are a CLOSED set (GL-T-31, standard S6): a service declares a value before GL accepts it, and
 * a reader that decodes the type as text sees exactly these names. This pins the set, so adding or removing a value is a deliberate,
 * reviewed change and never an accident.
 */
class SourceReferenceTypeTest {

    @Test
    fun `the set of source document types is exactly the agreed closed set`() {
        SourceReferenceType.entries.map { it.name } shouldBe listOf(
            "SALES_INVOICE", "SALES_CREDIT_NOTE", "COLLECTION",
            "SUPPLIER_INVOICE", "SUPPLIER_CREDIT_NOTE", "SUPPLIER_PAYMENT",
            "CASH_BOOK_ENTRY", "VAT_SETTLEMENT",
            "STOCK_RECEIPT", "STOCK_ISSUE",
            "PAY_RUN", "LEAVE_ACCRUAL",
            // Payroll's other documents (FR-PAY-38, 2026-10-22): the document that caused a posting, which LEAVE_ACCRUAL (the accrual) does not name.
            "SALARY_ADVANCE", "EXPENSE_CLAIM", "LEAVE_REQUEST",
            "MANUAL_JOURNAL", "CORRECTION"
        )
    }
}
