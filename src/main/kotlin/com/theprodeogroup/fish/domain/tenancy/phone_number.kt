package com.theprodeogroup.fish.domain.tenancy

/**
 * The founding admin's mobile number (docs/DDD_Design.md Section 9.4's
 * KYB grace period, extended 2026-08-27 to also require a verified
 * phone number within 14 days of activation - see [Tenant.recordAdminPhoneNumber]).
 *
 * E.164 only ("+" then 8-15 digits, no spaces/hyphens) - the format
 * Cognito's own `phone_number` attribute requires, since that's where
 * this number is actually verified (an SMS code sent via Cognito's SMS
 * configuration, not anything FiSH sends itself). Rejecting a
 * non-E.164 string here rather than normalizing it avoids silently
 * storing a number Cognito would have rejected outright.
 */
@JvmInline
value class PhoneNumber(val value: String) {
    init {
        require(E164_PATTERN.matches(value)) {
            "'$value' is not a valid E.164 phone number (expected format: +<8-15 digits>)"
        }
    }

    override fun toString(): String = value

    companion object {
        private val E164_PATTERN = Regex("^\\+[1-9]\\d{7,14}$")
    }
}
