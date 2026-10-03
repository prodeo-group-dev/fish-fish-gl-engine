package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetId
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRepository
import com.theprodeogroup.fish.domain.purchasing.Supplier
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.purchasing.SupplierRepository
import com.theprodeogroup.fish.domain.sales.Customer
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.sales.CustomerRepository
import com.theprodeogroup.fish.domain.sales.SalesInvoiceRecord
import com.theprodeogroup.fish.domain.sales.SalesInvoiceRecordRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * In-memory stand-ins for the "ecosystem" repository interfaces
 * (Purchase Order Processing, Inventory Management - docs/DDD_Design.md
 * Section 10.4), same discipline as `LedgerRepositoryFakes.kt`/
 * `TenancyRepositoryFakes.kt`.
 *
 * `FakePurchaseOrderRepository`/`FakeStockItemRepository`/
 * `FakeSalesOrderRepository`/`FakeStockShortageEscalationRepository`
 * were removed from this file 2026-09-01 ("Retire GL's StockItem from
 * its legacy costing") alongside the domain interfaces they backed.
 */
class FakeSupplierRepository : SupplierRepository {
    val saveCalls = mutableListOf<SupplierId>()
    private val store = mutableMapOf<SupplierId, Supplier>()
    override fun save(supplier: Supplier) {
        saveCalls.add(supplier.id)
        store[supplier.id] = supplier
    }
    override fun findById(id: SupplierId): Supplier? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<Supplier> = store.values.filter { it.companyId == companyId }
}

class FakeCustomerRepository : CustomerRepository {
    val saveCalls = mutableListOf<CustomerId>()
    private val store = mutableMapOf<CustomerId, Customer>()
    override fun save(customer: Customer) {
        saveCalls.add(customer.id)
        store[customer.id] = customer
    }
    override fun findById(id: CustomerId): Customer? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<Customer> = store.values.filter { it.companyId == companyId }
}

class FakeSalesInvoiceRecordRepository : SalesInvoiceRecordRepository {
    val saveCalls = mutableListOf<SalesInvoiceRecord>()
    override fun save(record: SalesInvoiceRecord) {
        saveCalls.add(record)
    }
    override fun findAllByCompany(companyId: CompanyId): List<SalesInvoiceRecord> =
        saveCalls.filter { it.companyId == companyId }.sortedByDescending { it.recordedAt }
}

class FakeFixedAssetRepository : FixedAssetRepository {
    val saveCalls = mutableListOf<FixedAssetId>()
    private val store = mutableMapOf<FixedAssetId, FixedAsset>()
    override fun save(fixedAsset: FixedAsset) {
        saveCalls.add(fixedAsset.id)
        store[fixedAsset.id] = fixedAsset
    }
    override fun findById(id: FixedAssetId): FixedAsset? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<FixedAsset> = store.values.filter { it.companyId == companyId }
}
