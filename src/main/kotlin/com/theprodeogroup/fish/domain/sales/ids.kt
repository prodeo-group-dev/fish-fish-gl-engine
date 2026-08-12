package com.theprodeogroup.fish.domain.sales

import java.util.UUID

/**
 * Identity of a Customer - the AR counterparty (docs/DDD_Design.md
 * Section 2.5). Created regardless of credit terms, per the two-layer
 * Customer/Debtor model - a Customer only becomes meaningfully a
 * "Debtor" once its balance is non-zero, but that's a description of
 * state, not a separate class.
 */
@JvmInline
value class CustomerId(val value: UUID) {
    companion object {
        fun generate(): CustomerId = CustomerId(UUID.randomUUID())
    }
}

/**
 * Identity of a SalesOrder aggregate.
 */
@JvmInline
value class SalesOrderId(val value: UUID) {
    companion object {
        fun generate(): SalesOrderId = SalesOrderId(UUID.randomUUID())
    }
}
