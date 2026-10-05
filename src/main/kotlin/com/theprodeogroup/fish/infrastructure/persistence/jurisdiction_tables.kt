package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table

/** Exposed table definition for [com.theprodeogroup.fish.domain.common.JurisdictionEntry] (`V29__jurisdictions.sql`). */
object JurisdictionsTable : Table("jurisdictions") {
    val code = char("code", 2)
    val name = varchar("name", 100)
    val enabled = bool("enabled")

    override val primaryKey = PrimaryKey(code)
}
