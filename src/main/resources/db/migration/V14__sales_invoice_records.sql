-- SalesInvoiceRecord (2026-08-29) - a persisted, queryable log of every
-- CreateSalesInvoiceUseCase success, powering the SOP dashboard tab's
-- "listing of sales (each timestamped)." Append-only; the JournalEntry
-- it points to (journal_entry_id) remains the actual source of
-- financial truth.

CREATE TABLE sales_invoice_records (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    journal_entry_id UUID NOT NULL,
    invoice_number VARCHAR(32) NOT NULL,
    customer_id UUID NOT NULL,
    customer_name VARCHAR(255) NOT NULL,
    sale_type VARCHAR(10) NOT NULL,
    sale_method VARCHAR(10) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    paid BOOLEAN NOT NULL,
    description VARCHAR(500),
    recorded_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_sales_invoice_records_company_id ON sales_invoice_records (company_id);
