-- Idempotency keys (docs/GL_Production_Readiness_Plan.md - the
-- idempotency-key gap flagged in the fresh production-readiness
-- review, 2026-08-21) - lets a caller retrying a posting request after
-- a timeout/network failure get back the exact original outcome
-- instead of posting the same financial fact twice.
--
-- Scoped by (tenant_id, endpoint, idempotency_key), not by
-- idempotency_key alone: two different Tenants (or two different
-- endpoints within the same Tenant) reusing the same key string is a
-- real possibility, not a bug to guard against with a single global
-- key space. The unique index doubles as the concurrency guard - a
-- second concurrent request racing to insert the same key loses to
-- Postgres's own uniqueness enforcement, not to application-level
-- locking.
CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    endpoint VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    response_status_code INT NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_idempotency_keys_tenant_endpoint_key
    ON idempotency_keys (tenant_id, endpoint, idempotency_key);
