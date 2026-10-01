package com.theprodeogroup.fish.domain.opening

interface OpeningImportBatchRepository {
    fun save(batch: OpeningImportBatch)
    fun findById(id: OpeningImportBatchId): OpeningImportBatch?
}

interface OpeningImportRowResultRepository {
    fun saveAll(rowResults: List<OpeningImportRowResult>)
    fun findAllByBatch(batchId: OpeningImportBatchId): List<OpeningImportRowResult>
}
