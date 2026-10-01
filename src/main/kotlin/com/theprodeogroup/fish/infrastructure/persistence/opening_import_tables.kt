package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table definitions for [com.theprodeogroup.fish.domain.opening.OpeningImportBatch]/
 * [com.theprodeogroup.fish.domain.opening.OpeningImportRowResult] (`V26__opening_import_tables.sql`).
 */
object OpeningImportBatchesTable : Table("opening_import_batches") {
    val id = uuid("id")
    val domain = varchar("domain", 20)
    val companyId = uuid("company_id")
    val anchorDate = date("anchor_date")
    val filenameHash = varchar("filename_hash", 64)
    val status = varchar("status", 20)
    val rowCount = integer("row_count")
    val acceptedCount = integer("accepted_count")
    val rejectedCount = integer("rejected_count")
    val needsItemizationCount = integer("needs_itemization_count")
    val createdByEmail = varchar("created_by_email", 255)
    val createdAt = timestamp("created_at")
    val committedAt = timestamp("committed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** [OpeningImportRowResultsTable.errors]/[OpeningImportRowResultsTable.resultingEntityIds] are joined on [ROW_RESULT_LIST_SEPARATOR] - see the migration's own comment for why. */
object OpeningImportRowResultsTable : Table("opening_import_row_results") {
    val batchId = uuid("batch_id")
    val rowNumber = integer("row_number")
    val status = varchar("status", 20)
    val errors = text("errors")
    val resultingEntityIds = text("resulting_entity_ids")

    override val primaryKey = PrimaryKey(batchId, rowNumber)
}

/** ASCII Unit Separator - chosen specifically because it can't appear in a normal error message or UUID string. */
const val ROW_RESULT_LIST_SEPARATOR = ""
