-- Drops GL's own tenants/users/memberships/tenant_companies/
-- tenant_admin_memberships/membership_module_grants tables.
--
-- Confirmed dead, not a live second copy: Tenant/User/Membership moved
-- to EA entirely 2026-09-06 (domain.tenancy/repositories.kt's own
-- comment), and GL's authorizeTenantForWrite/ForAdmin/ForModule/ForRead
-- (Auth.kt) already resolve every Membership check via a live call to
-- EaMembershipGateway, never these local tables. V21's own comment
-- already flagged them as "unreferenced by any Kotlin code" when it
-- dropped companies.tenant_id's FK into tenants(id) - this finishes
-- that cleanup by removing the tables themselves, not just the
-- constraint that once pointed at them.
--
-- Drop order is children-before-parents to satisfy FK constraints
-- without needing CASCADE (which could silently take an unexpected
-- table down with it): membership_module_grants and
-- tenant_admin_memberships both reference memberships; tenant_companies
-- references tenants and companies (companies is NOT dropped here -
-- only the referencing row, tenant_companies, goes); memberships
-- references users and tenants; users and tenants have no remaining
-- incoming references once the above are gone.
DROP TABLE membership_module_grants;
DROP TABLE tenant_admin_memberships;
DROP TABLE tenant_companies;
DROP TABLE memberships;
DROP TABLE users;
DROP TABLE tenants;
