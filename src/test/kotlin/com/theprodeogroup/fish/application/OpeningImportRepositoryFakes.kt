package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.opening.OpeningImportBatch
import com.theprodeogroup.fish.domain.opening.OpeningImportBatchId
import com.theprodeogroup.fish.domain.opening.OpeningImportBatchRepository
import com.theprodeogroup.fish.domain.opening.OpeningImportRowResult
import com.theprodeogroup.fish.domain.opening.OpeningImportRowResultRepository

class FakeOpeningImportBatchRepository : OpeningImportBatchRepository {
    private val store = mutableMapOf<OpeningImportBatchId, OpeningImportBatch>()
    val saveCalls = mutableListOf<OpeningImportBatch>()
    override fun save(batch: OpeningImportBatch) {
        saveCalls.add(batch)
        store[batch.id] = batch
    }
    override fun findById(id: OpeningImportBatchId): OpeningImportBatch? = store[id]
}

class FakeOpeningImportRowResultRepository : OpeningImportRowResultRepository {
    private val store = mutableMapOf<OpeningImportBatchId, MutableList<OpeningImportRowResult>>()
    override fun saveAll(rowResults: List<OpeningImportRowResult>) {
        rowResults.forEach { store.getOrPut(it.batchId) { mutableListOf() }.add(it) }
    }
    override fun findAllByBatch(batchId: OpeningImportBatchId): List<OpeningImportRowResult> = store[batchId] ?: emptyList()
}
