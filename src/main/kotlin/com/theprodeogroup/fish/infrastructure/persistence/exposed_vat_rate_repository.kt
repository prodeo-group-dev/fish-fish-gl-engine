package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatRateRepository
import com.theprodeogroup.fish.domain.tax.VatRateRow
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/** Exposed-backed [VatRateRepository]. Only VERIFIED rows ever form a schedule. */
class ExposedVatRateRepository : VatRateRepository {

    override fun save(row: VatRateRow): Unit = transaction {
        VatRatesTable.upsert { statement ->
            statement[jurisdiction] = row.jurisdiction.code
            statement[category] = row.category.name
            statement[rate] = row.rate
            statement[effectiveFrom] = row.effectiveFrom
            statement[verified] = row.verified
        }
        Unit
    }

    override fun findVerifiedScheduleFor(jurisdiction: Jurisdiction): VatRateSchedule? = transaction {
        VatRatesTable.selectAll()
            .where { (VatRatesTable.jurisdiction eq jurisdiction.code) and (VatRatesTable.verified eq true) }
            .map {
                VatRateRow(
                    jurisdiction = Jurisdiction(it[VatRatesTable.jurisdiction]),
                    category = VatCategory.valueOf(it[VatRatesTable.category]),
                    rate = it[VatRatesTable.rate],
                    effectiveFrom = it[VatRatesTable.effectiveFrom],
                    verified = it[VatRatesTable.verified]
                )
            }
            .let { VatRateSchedule.of(it) }
    }
}
