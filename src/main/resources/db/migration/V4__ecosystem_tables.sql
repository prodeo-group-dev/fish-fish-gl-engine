-- Ecosystem tables (docs/DDD_Design.md Section 10.4) - Purchase Order
-- Processing (Increment 1), Sales Order Processing (Increment 2),
-- Inventory Management (Increment 3), and Payroll's GL-facing posting
-- interface (Increment 4). AccountsPayableAging/AccountsReceivableAging
-- have no tables of their own - both are derived reports computed from
-- already-posted journal_entries/journal_lines (V2), the same treatment
-- as TrialBalance/ProfitAndLoss.
--
-- company_id columns carry real foreign keys to companies(id), unlike
-- V2's accounts/periods.company_id - Company is persisted as of V3, so
-- there's no forward-reference gap to leave open the way there was when
-- V2 was written before Company had a table at all. V2 is left as-is,
-- not retrofitted here - out of this migration's scope.

CREATE TABLE creditors (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    name VARCHAR(255) NOT NULL,
    currency CHAR(3) NOT NULL,
    balance_amount NUMERIC(19, 4) NOT NULL
);

CREATE INDEX idx_creditors_company_id ON creditors(company_id);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    name VARCHAR(255) NOT NULL,
    currency CHAR(3) NOT NULL,
    balance_amount NUMERIC(19, 4) NOT NULL,
    allowance_for_expected_credit_loss_amount NUMERIC(19, 4) NOT NULL
);

CREATE INDEX idx_customers_company_id ON customers(company_id);

CREATE TABLE stock_items (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    name VARCHAR(255) NOT NULL,
    currency CHAR(3) NOT NULL,
    stage VARCHAR(20) NOT NULL,
    quantity_on_hand NUMERIC(19, 4) NOT NULL,
    unit_cost_amount NUMERIC(19, 4) NOT NULL,
    nrv_write_down_per_unit_amount NUMERIC(19, 4) NOT NULL
);

CREATE INDEX idx_stock_items_company_id ON stock_items(company_id);

CREATE TABLE purchase_orders (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    creditor_id UUID NOT NULL REFERENCES creditors(id),
    order_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL
);

CREATE INDEX idx_purchase_orders_company_id ON purchase_orders(company_id);

-- One row per PurchaseOrderLine - a plain data class with no identity of
-- its own in the domain model, same (purchase_order_id, line_index)
-- synthetic-key treatment as journal_lines (V2). stock_item_id is
-- nullable - only a GOODS line references a StockItem, a SERVICE line
-- never does (PurchaseOrderLine's own init block enforces this).
CREATE TABLE purchase_order_lines (
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    line_index INT NOT NULL,
    description VARCHAR(255) NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    item_type VARCHAR(10) NOT NULL,
    quantity NUMERIC(19, 4),
    stock_item_id UUID REFERENCES stock_items(id),
    PRIMARY KEY (purchase_order_id, line_index)
);

CREATE TABLE sales_orders (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    order_date DATE NOT NULL
);

CREATE INDEX idx_sales_orders_company_id ON sales_orders(company_id);

CREATE TABLE sales_order_lines (
    sales_order_id UUID NOT NULL REFERENCES sales_orders(id) ON DELETE CASCADE,
    line_index INT NOT NULL,
    description VARCHAR(255) NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    item_type VARCHAR(10) NOT NULL,
    quantity NUMERIC(19, 4),
    stock_item_id UUID REFERENCES stock_items(id),
    PRIMARY KEY (sales_order_id, line_index)
);

-- Persists SalesOrder's private deliveredLineIndices set - SalesOrder has
-- no stored `status` field of its own, it's computed from this set (see
-- SalesOrder's KDoc), so this table is the entirety of what needs
-- persisting beyond the order's own lines.
CREATE TABLE sales_order_delivered_lines (
    sales_order_id UUID NOT NULL REFERENCES sales_orders(id) ON DELETE CASCADE,
    line_index INT NOT NULL,
    PRIMARY KEY (sales_order_id, line_index)
);

-- PayRun has no mutable state after creation (post() produces a
-- JournalEntry but changes nothing on PayRun itself) - same shape as
-- User in V3, no separate status/state column needed.
CREATE TABLE pay_runs (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    pay_date DATE NOT NULL,
    total_wages_amount NUMERIC(19, 4) NOT NULL,
    total_salaries_amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL
);

CREATE INDEX idx_pay_runs_company_id ON pay_runs(company_id);
