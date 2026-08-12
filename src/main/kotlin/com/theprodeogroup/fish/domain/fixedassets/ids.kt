package com.theprodeogroup.fish.domain.fixedassets

import java.util.UUID

/**
 * Identity of a FixedAsset aggregate.
 */
@JvmInline
value class FixedAssetId(val value: UUID) {
    companion object {
        fun generate(): FixedAssetId = FixedAssetId(UUID.randomUUID())
    }
}
