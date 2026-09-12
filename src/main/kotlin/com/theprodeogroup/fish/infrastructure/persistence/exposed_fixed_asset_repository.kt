package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetId
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/** Exposed-backed `FixedAssetRepository` (docs/DDD_Design.md Section 2.8). Same existence-check-then-insert-or-update shape as every other repository in this codebase. */
class ExposedFixedAssetRepository : FixedAssetRepository {

    override fun save(fixedAsset: FixedAsset): Unit = transaction {
        val exists = FixedAssetsTable.selectAll().where { FixedAssetsTable.id eq fixedAsset.id.value }.count() > 0
        if (exists) {
            FixedAssetsTable.update({ FixedAssetsTable.id eq fixedAsset.id.value }) { statement ->
                populate(statement, fixedAsset)
            }
        } else {
            FixedAssetsTable.insert { statement ->
                statement[id] = fixedAsset.id.value
                populate(statement, fixedAsset)
            }
        }
        Unit
    }

    override fun findById(id: FixedAssetId): FixedAsset? = transaction {
        FixedAssetsTable.selectAll().where { FixedAssetsTable.id eq id.value }
            .map { it.toFixedAsset() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<FixedAsset> = transaction {
        FixedAssetsTable.selectAll().where { FixedAssetsTable.companyId eq companyId.value }
            .map { it.toFixedAsset() }
    }

    private fun populate(statement: UpdateBuilder<*>, fixedAsset: FixedAsset) {
        statement[FixedAssetsTable.companyId] = fixedAsset.companyId.value
        statement[FixedAssetsTable.name] = fixedAsset.name
        statement[FixedAssetsTable.category] = fixedAsset.category.name
        statement[FixedAssetsTable.costAmount] = fixedAsset.cost.amount
        statement[FixedAssetsTable.currency] = fixedAsset.cost.currency.currencyCode
        statement[FixedAssetsTable.acquisitionDate] = fixedAsset.acquisitionDate
        statement[FixedAssetsTable.usefulLifeYears] = fixedAsset.usefulLifeYears
        statement[FixedAssetsTable.identifier] = fixedAsset.identifier
        statement[FixedAssetsTable.accumulatedDepreciationAmount] = fixedAsset.accumulatedDepreciation.amount
        statement[FixedAssetsTable.accumulatedImpairmentAmount] = fixedAsset.accumulatedImpairmentLoss.amount
        statement[FixedAssetsTable.isDisposed] = fixedAsset.isDisposed
    }

    private fun ResultRow.toFixedAsset(): FixedAsset {
        val currency = Currency.getInstance(this[FixedAssetsTable.currency])
        return FixedAsset.reconstitute(
            id = FixedAssetId(this[FixedAssetsTable.id]),
            companyId = CompanyId(this[FixedAssetsTable.companyId]),
            name = this[FixedAssetsTable.name],
            category = AssetCategory.valueOf(this[FixedAssetsTable.category]),
            cost = Money(this[FixedAssetsTable.costAmount], currency),
            acquisitionDate = this[FixedAssetsTable.acquisitionDate],
            usefulLifeYears = this[FixedAssetsTable.usefulLifeYears],
            identifier = this[FixedAssetsTable.identifier],
            accumulatedDepreciation = Money(this[FixedAssetsTable.accumulatedDepreciationAmount], currency),
            accumulatedImpairmentLoss = Money(this[FixedAssetsTable.accumulatedImpairmentAmount], currency),
            isDisposed = this[FixedAssetsTable.isDisposed]
        )
    }
}
