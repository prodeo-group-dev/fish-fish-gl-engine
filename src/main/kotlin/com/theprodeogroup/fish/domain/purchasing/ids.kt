package com.theprodeogroup.fish.domain.purchasing

import java.util.UUID

/**
 * Identity of a Creditor - the AP counterparty (docs/DDD_Design.md
 * Section 2.5). Not the same as `DimensionType.VENDOR` (a journal-line
 * tag) - a Creditor is a first-class record with its own running balance.
 */
@JvmInline
value class CreditorId(val value: UUID) {
    companion object {
        fun generate(): CreditorId = CreditorId(UUID.randomUUID())
    }
}
