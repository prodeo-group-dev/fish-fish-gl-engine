package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

/** Exposed table definition for [com.theprodeogroup.fish.domain.tax.VatRateRow] (`V30__vat_rates.sql`). */
object VatRatesTable : Table("vat_rates") {
    val jurisdiction = char("jurisdiction", 2)
    val category = varchar("category", 30)
    val rate = decimal("rate", 8, 6)
    val effectiveFrom = date("effective_from")
    val verified = bool("verified")

    override val primaryKey = PrimaryKey(jurisdiction, category, effectiveFrom)
}
