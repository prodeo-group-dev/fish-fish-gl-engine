package com.theprodeogroup.fish.domain.ledger

import java.util.UUID

/**
 * Identity of an Account aggregate.
 *
 * A typed wrapper around UUID so the compiler rejects an AccountId passed
 * where a CompanyId (or any other ID type) is expected.
 */
@JvmInline
value class AccountId(val value: UUID) {
    companion object {
        fun generate(): AccountId = AccountId(UUID.randomUUID())
    }
}
