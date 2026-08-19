package com.theprodeogroup.fish.domain.inventory

import com.theprodeogroup.fish.domain.tenancy.CompanyId

/** Persistence contract for Inventory Management (docs/DDD_Design.md Section 10.4). Same minimal shape as every other repository in this codebase. */
interface StockItemRepository {
    fun save(stockItem: StockItem)
    fun findById(id: StockItemId): StockItem?
    fun findAllByCompany(companyId: CompanyId): List<StockItem>
}
