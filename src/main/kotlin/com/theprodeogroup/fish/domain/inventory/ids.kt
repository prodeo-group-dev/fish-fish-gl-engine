package com.theprodeogroup.fish.domain.inventory

import java.util.UUID

/**
 * Identity of a StockItem aggregate.
 */
@JvmInline
value class StockItemId(val value: UUID) {
    companion object {
        fun generate(): StockItemId = StockItemId(UUID.randomUUID())
    }
}
