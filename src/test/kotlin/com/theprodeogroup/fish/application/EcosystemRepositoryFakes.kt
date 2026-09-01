package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.purchasing.Creditor
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.purchasing.CreditorRepository
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
