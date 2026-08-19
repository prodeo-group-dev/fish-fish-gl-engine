package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
import com.theprodeogroup.fish.domain.purchasing.Creditor
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.purchasing.CreditorRepository
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrder
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderId
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * In-memory stand-ins for the "ecosystem" repository interfaces
 * (Purchase Order Processing, Inventory Management - docs/DDD_Design.md
 * Section 10.4), same discipline as `LedgerRepositoryFakes.kt`/
 * `TenancyRepositoryFakes.kt`.
 */
class FakeCreditorRepository : CreditorRepository {
    val saveCalls = mutableListOf<CreditorId>()
    private val store = mutableMapOf<CreditorId, Creditor>()
    override fun save(creditor: Creditor) {
        saveCalls.add(creditor.id)
        store[creditor.id] = creditor
    }
    override fun findById(id: CreditorId): Creditor? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<Creditor> = store.values.filter { it.companyId == companyId }
}

class FakePurchaseOrderRepository : PurchaseOrderRepository {
    val saveCalls = mutableListOf<PurchaseOrderId>()
    private val store = mutableMapOf<PurchaseOrderId, PurchaseOrder>()
    override fun save(purchaseOrder: PurchaseOrder) {
        saveCalls.add(purchaseOrder.id)
        store[purchaseOrder.id] = purchaseOrder
    }
    override fun findById(id: PurchaseOrderId): PurchaseOrder? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<PurchaseOrder> = store.values.filter { it.companyId == companyId }
}

class FakeStockItemRepository : StockItemRepository {
    val saveCalls = mutableListOf<StockItemId>()
    private val store = mutableMapOf<StockItemId, StockItem>()
    override fun save(stockItem: StockItem) {
        saveCalls.add(stockItem.id)
        store[stockItem.id] = stockItem
    }
    override fun findById(id: StockItemId): StockItem? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<StockItem> = store.values.filter { it.companyId == companyId }
}
