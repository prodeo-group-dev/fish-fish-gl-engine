package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * Persistence contracts for Sales Order Processing (docs/DDD_Design.md
 * Section 10.4) - the AR mirror of `domain.purchasing.repositories`.
 * `AccountsReceivableAging` has no repository of its own, same reasoning
 * as `AccountsPayableAging` - a derived report, not a persisted aggregate.
 */
interface CustomerRepository {
    fun save(customer: Customer)
    fun findById(id: CustomerId): Customer?
    fun findAllByCompany(companyId: CompanyId): List<Customer>
}

/** Persistence contract for [SalesInvoiceRecord] - append-only, no update path. */
interface SalesInvoiceRecordRepository {
    fun save(record: SalesInvoiceRecord)
    fun findAllByCompany(companyId: CompanyId): List<SalesInvoiceRecord>
}
