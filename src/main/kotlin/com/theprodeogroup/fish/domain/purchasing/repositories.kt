package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * Persistence contracts for Purchase Order Processing (docs/DDD_Design.md
 * Section 10.4) - same minimal `save()`/`findById()`/`findAllByCompany()`
 * shape as the Ledger and Tenancy repositories. `AccountsPayableAging`
 * has no repository of its own - it's a derived report computed from
 * already-posted `JournalEntry` data (`domain.ledger`), the same
 * treatment as `TrialBalance`/`ProfitAndLoss`, not a persisted aggregate.
 */
interface SupplierRepository {
    fun save(supplier: Supplier)
    fun findById(id: SupplierId): Supplier?
    fun findAllByCompany(companyId: CompanyId): List<Supplier>
}
