-- Real User + Membership for SOP's Cognito service-account identity
-- (sop-service@theprodeogroup.com, provisioned via Terraform's
-- aws_cognito_user.sop_service, 2026-09-01) - closes
-- SOP_GL_ENGINE_BEARER_TOKEN: SOP authenticates to GL as this real,
-- ordinary Membership, exactly like any invited staff member. Auth.kt
-- needs zero special-casing for a "service" identity - it's just
-- another email/Membership pair joined at login time, same as every
-- other caller.
--
-- ACCOUNTANT role -> WRITE access level by default (Role.kt), enough
-- for authorizeTenantForWrite (issue-for-sale, sales/record-sale,
-- sales/record-collection) and authorizeTenantForRead
-- (sales-posting-context). Prodeo Group's own tenant id, same value
-- as sop_gl_engine_tenant_id/pop_gl_engine_tenant_id in
-- infra/terraform/variables.tf.

INSERT INTO users (id, email, name)
VALUES (gen_random_uuid(), 'sop-service@theprodeogroup.com', 'SOP Service Account');

INSERT INTO memberships (id, user_id, tenant_id, role, status, access_level)
SELECT gen_random_uuid(), u.id, '9fa2198b-2a6f-467d-97ac-6f6fbce6a9fd', 'ACCOUNTANT', 'ACTIVE', 'WRITE'
FROM users u
WHERE u.email = 'sop-service@theprodeogroup.com';

INSERT INTO membership_module_grants (membership_id, module)
SELECT m.id, 'SOP'
FROM memberships m
JOIN users u ON u.id = m.user_id
WHERE u.email = 'sop-service@theprodeogroup.com';
