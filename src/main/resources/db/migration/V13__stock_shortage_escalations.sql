-- StockShortageEscalation (2026-08-29) - a real, queryable record of
-- every time CreateSalesInvoiceUseCase found insufficient stock for a
-- GOODS sale, per the user's explicit instruction: "the request for
-- the item is logged and escalated to the owner." Append-only, no
-- update path - overridden distinguishes a hard-blocked WRITE-level
-- attempt (false) from an APPROVE-level caller pushing the sale
-- through anyway (true).

CREATE TABLE stock_shortage_escalations (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    stock_item_id UUID NOT NULL,
    requested_quantity DECIMAL(19, 4) NOT NULL,
    quantity_on_hand_at_request DECIMAL(19, 4) NOT NULL,
    requested_by_email VARCHAR(255) NOT NULL,
    overridden BOOLEAN NOT NULL,
    requested_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_stock_shortage_escalations_company_id ON stock_shortage_escalations (company_id);
