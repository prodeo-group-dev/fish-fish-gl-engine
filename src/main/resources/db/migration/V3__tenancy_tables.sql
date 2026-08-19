-- Tenancy tables (docs/DDD_Design.md Section 10.3) - Tenant, Company,
-- User, Membership. Unlike the core Ledger tables (V2), these carry real
-- foreign keys throughout: Tenant is the top of this hierarchy and is
-- persisted in the same migration as everything that references it, so
-- there's no forward-reference gap to leave unconstrained.

CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    segment VARCHAR(20) NOT NULL,
    base_currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    kyb_status VARCHAR(20) NOT NULL,
    admin_kyc_status VARCHAR(20) NOT NULL,
    kyb_verification_deadline TIMESTAMP
);

CREATE TABLE companies (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(255) NOT NULL,
    client_type VARCHAR(20) NOT NULL,
    jurisdiction VARCHAR(100) NOT NULL,
    base_currency CHAR(3) NOT NULL,
    going_concern_status VARCHAR(20) NOT NULL
);

CREATE INDEX idx_companies_tenant_id ON companies(tenant_id);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL
);

CREATE TABLE memberships (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    role VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL
);

CREATE INDEX idx_memberships_tenant_id ON memberships(tenant_id);
CREATE INDEX idx_memberships_user_id ON memberships(user_id);

-- Join tables for Tenant's own referenced-ID sets (Tenant._companyIds /
-- _adminMembershipIds, Section 9.2 steps 3-4) - persisted as their own
-- rows rather than derived by querying companies.tenant_id /
-- memberships.tenant_id, since the aggregate itself tracks these as
-- explicit state: a Company/Membership only counts as "under" a Tenant
-- once addCompany/addAdminMembership is actually called.
CREATE TABLE tenant_companies (
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    company_id UUID NOT NULL REFERENCES companies(id),
    PRIMARY KEY (tenant_id, company_id)
);

CREATE TABLE tenant_admin_memberships (
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    membership_id UUID NOT NULL REFERENCES memberships(id),
    PRIMARY KEY (tenant_id, membership_id)
);
