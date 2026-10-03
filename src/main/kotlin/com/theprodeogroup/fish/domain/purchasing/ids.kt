package com.theprodeogroup.fish.domain.purchasing

import java.util.UUID

/**
 * Identity of a Supplier - the AP counterparty (docs/DDD_Design.md
 * Section 2.5). Not the same as `DimensionType.VENDOR` (a journal-line
 * tag) - a Supplier is a first-class record with its own running balance.
 */
@JvmInline
value class SupplierId(val value: UUID) {
    companion object {
        fun generate(): SupplierId = SupplierId(UUID.randomUUID())
    }
}
