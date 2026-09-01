-- Real User + Membership for IM's Cognito service-account identity
-- (im-service@theprodeogroup.com, provisioned via Terraform's
-- aws_cognito_user.im_service) - mirrors V17__sop_service_account.sql
-- exactly, closing IM_GL_ENGINE_BEARER_TOKEN the same way.
--
-- ACCOUNTANT role -> WRITE access level by default (Role.kt), enough
-- for authorizeTenantForWrite (inventory/record-receipt,
-- inventory/record-issue) and authorizeTenantForRead
-- (inventory-posting-context). Prodeo Group's own tenant id, same
-- value as pop_gl_engine_tenant_id/sop_gl_engine_tenant_id in
-- infra/terraform/variables.tf.

INSERT INTO users (id, email, name)
VALUES (gen_random_uuid(), 'im-service@theprodeogroup.com', 'IM Service Account');

INSERT INTO memberships (id, user_id, tenant_id, role, status, access_level)
SELECT gen_random_uuid(), u.id, '9fa2198b-2a6f-467d-97ac-6f6fbce6a9fd', 'ACCOUNTANT', 'ACTIVE', 'WRITE'
FROM users u
WHERE u.email = 'im-service@theprodeogroup.com';

INSERT INTO membership_module_grants (membership_id, module)
SELECT m.id, 'IM'
FROM memberships m
JOIN users u ON u.id = m.user_id
WHERE u.email = 'im-service@theprodeogroup.com';
