package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.opening.OpeningImportBatch
import com.theprodeogroup.fish.domain.opening.OpeningImportBatchId
import com.theprodeogroup.fish.domain.opening.OpeningImportBatchRepository
import com.theprodeogroup.fish.domain.opening.OpeningImportBatchStatus
import com.theprodeogroup.fish.domain.opening.OpeningImportDomain
import com.theprodeogroup.fish.domain.opening.OpeningImportRowResult
import com.theprodeogroup.fish.domain.opening.OpeningImportRowResultRepository
import com.theprodeogroup.fish.domain.opening.OpeningImportRowStatus
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Exposed-backed `OpeningImportBatchRepository`. Same existence-check-then-insert-or-update shape as [ExposedFixedAssetRepository]. */
class ExposedOpeningImportBatchRepository : OpeningImportBatchRepository {

    override fun save(batch: OpeningImportBatch): Unit = transaction {
        val exists = OpeningImportBatchesTable.selectAll().where { OpeningImportBatchesTable.id eq batch.id.value }.count() > 0
        if (exists) {
            OpeningImportBatchesTable.update({ OpeningImportBatchesTable.id eq batch.id.value }) { statement ->
                populate(statement, batch)
            }
        } else {
            OpeningImportBatchesTable.insert { statement ->
                statement[id] = batch.id.value
                populate(statement, batch)
            }
        }
        Unit
    }

    override fun findById(id: OpeningImportBatchId): OpeningImportBatch? = transaction {
        OpeningImportBatchesTable.selectAll().where { OpeningImportBatchesTable.id eq id.value }
            .map { it.toOpeningImportBatch() }
            .singleOrNull()
    }

    private fun populate(statement: UpdateBuilder<*>, batch: OpeningImportBatch) {
        statement[OpeningImportBatchesTable.domain] = batch.domain.name
        statement[OpeningImportBatchesTable.companyId] = batch.companyId.value
        statement[OpeningImportBatchesTable.anchorDate] = batch.anchorDate
        statement[OpeningImportBatchesTable.filenameHash] = batch.filenameHash
        statement[OpeningImportBatchesTable.status] = batch.status.name
        statement[OpeningImportBatchesTable.rowCount] = batch.rowCount
        statement[OpeningImportBatchesTable.acceptedCount] = batch.acceptedCount
        statement[OpeningImportBatchesTable.rejectedCount] = batch.rejectedCount
        statement[OpeningImportBatchesTable.needsItemizationCount] = batch.needsItemizationCount
        statement[OpeningImportBatchesTable.createdByEmail] = batch.createdByEmail
        statement[OpeningImportBatchesTable.createdAt] = batch.createdAt
        statement[OpeningImportBatchesTable.committedAt] = batch.committedAt
    }

    private fun ResultRow.toOpeningImportBatch(): OpeningImportBatch = OpeningImportBatch.reconstitute(
        id = OpeningImportBatchId(this[OpeningImportBatchesTable.id]),
        domain = OpeningImportDomain.valueOf(this[OpeningImportBatchesTable.domain]),
        companyId = CompanyId(this[OpeningImportBatchesTable.companyId]),
        anchorDate = this[OpeningImportBatchesTable.anchorDate],
        filenameHash = this[OpeningImportBatchesTable.filenameHash],
        status = OpeningImportBatchStatus.valueOf(this[OpeningImportBatchesTable.status]),
        rowCount = this[OpeningImportBatchesTable.rowCount],
        acceptedCount = this[OpeningImportBatchesTable.acceptedCount],
        rejectedCount = this[OpeningImportBatchesTable.rejectedCount],
        needsItemizationCount = this[OpeningImportBatchesTable.needsItemizationCount],
        createdByEmail = this[OpeningImportBatchesTable.createdByEmail],
        createdAt = this[OpeningImportBatchesTable.createdAt],
        committedAt = this[OpeningImportBatchesTable.committedAt]
    )
}

/** Exposed-backed `OpeningImportRowResultRepository` - append-only (a row result is never updated once written), so [saveAll] is a plain batch insert, not an upsert. */
class ExposedOpeningImportRowResultRepository : OpeningImportRowResultRepository {

    override fun saveAll(rowResults: List<OpeningImportRowResult>): Unit = transaction {
        if (rowResults.isEmpty()) return@transaction
        OpeningImportRowResultsTable.batchInsert(rowResults) { rowResult ->
            this[OpeningImportRowResultsTable.batchId] = rowResult.batchId.value
            this[OpeningImportRowResultsTable.rowNumber] = rowResult.rowNumber
            this[OpeningImportRowResultsTable.status] = rowResult.status.name
            this[OpeningImportRowResultsTable.errors] = rowResult.errors.joinToString(ROW_RESULT_LIST_SEPARATOR)
            this[OpeningImportRowResultsTable.resultingEntityIds] = rowResult.resultingEntityIds.joinToString(ROW_RESULT_LIST_SEPARATOR)
        }
        Unit
    }

    override fun findAllByBatch(batchId: OpeningImportBatchId): List<OpeningImportRowResult> = transaction {
        OpeningImportRowResultsTable.selectAll().where { OpeningImportRowResultsTable.batchId eq batchId.value }
            .map { it.toOpeningImportRowResult() }
    }

    private fun ResultRow.toOpeningImportRowResult(): OpeningImportRowResult = OpeningImportRowResult(
        batchId = OpeningImportBatchId(this[OpeningImportRowResultsTable.batchId]),
        rowNumber = this[OpeningImportRowResultsTable.rowNumber],
        status = OpeningImportRowStatus.valueOf(this[OpeningImportRowResultsTable.status]),
        errors = splitRowResultList(this[OpeningImportRowResultsTable.errors]),
        resultingEntityIds = splitRowResultList(this[OpeningImportRowResultsTable.resultingEntityIds])
    )

    private fun splitRowResultList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(ROW_RESULT_LIST_SEPARATOR)
}
