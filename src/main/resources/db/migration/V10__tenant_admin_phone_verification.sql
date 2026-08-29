-- Admin phone number as part of KYB (docs/DDD_Design.md Section 9.4,
-- extended 2026-08-27): the founding admin's mobile number must be
-- supplied and verified within 14 days of Tenant activation, same
-- consequence (automated suspension) as the existing 180-day KYB/KYC
-- grace period - see Tenant.isPhoneVerificationOverdue/suspendForExpiredKyb.
--
-- admin_phone_verification_status defaults to 'PENDING' (matching
-- kyb_status/admin_kyc_status's own NOT NULL, no-default-needed shape at
-- table-creation time) so existing rows backfill correctly without a
-- separate UPDATE statement.

ALTER TABLE tenants
    ADD COLUMN admin_phone_number VARCHAR(20),
    ADD COLUMN admin_phone_verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN phone_verification_deadline TIMESTAMP;

ALTER TABLE tenants ALTER COLUMN admin_phone_verification_status DROP DEFAULT;
