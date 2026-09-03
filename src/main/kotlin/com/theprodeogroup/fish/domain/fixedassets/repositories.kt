package com.theprodeogroup.fish.domain.fixedassets

import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * Persistence contract for [FixedAsset] (docs/DDD_Design.md Section 2.8) -
 * same minimal `save()`/`findById()`/`findAllByCompany()` shape as
 * [com.theprodeogroup.fish.domain.purchasing.CreditorRepository].
 */
interface FixedAssetRepository {
    fun save(fixedAsset: FixedAsset)
    fun findById(id: FixedAssetId): FixedAsset?
    fun findAllByCompany(companyId: CompanyId): List<FixedAsset>
}
