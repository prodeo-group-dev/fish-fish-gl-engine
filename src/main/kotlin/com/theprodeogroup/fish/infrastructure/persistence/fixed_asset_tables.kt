package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

/**
 * Exposed table definition for [com.theprodeogroup.fish.domain.fixedassets.FixedAsset]
 * (`V20__fixed_assets.sql`, docs/DDD_Design.md Section 2.8).
 */
object FixedAssetsTable : Table("fixed_assets") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val name = varchar("name", 255)
    val category = varchar("category", 20)
    val costAmount = decimal("cost_amount", 19, 4)
    val currency = varchar("currency", 3)
    val acquisitionDate = date("acquisition_date")
    val usefulLifeYears = integer("useful_life_years").nullable()
    val identifier = varchar("identifier", 255).nullable()
    val accumulatedDepreciationAmount = decimal("accumulated_depreciation_amount", 19, 4)
    val accumulatedImpairmentAmount = decimal("accumulated_impairment_amount", 19, 4)
    val isDisposed = bool("is_disposed")

    override val primaryKey = PrimaryKey(id)
}
