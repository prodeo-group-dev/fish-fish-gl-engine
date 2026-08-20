CREATE TABLE leave_accruals (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    employee_id UUID NOT NULL,
    provision_id UUID NOT NULL,
    provision_description TEXT NOT NULL,
    balance_amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL
);

CREATE INDEX idx_leave_accruals_company_id ON leave_accruals(company_id);
