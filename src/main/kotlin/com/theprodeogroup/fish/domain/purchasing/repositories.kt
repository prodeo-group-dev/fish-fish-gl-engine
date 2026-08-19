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
interface CreditorRepository {
    fun save(creditor: Creditor)
    fun findById(id: CreditorId): Creditor?
    fun findAllByCompany(companyId: CompanyId): List<Creditor>
}

interface PurchaseOrderRepository {
    fun save(purchaseOrder: PurchaseOrder)
    fun findById(id: PurchaseOrderId): PurchaseOrder?
    fun findAllByCompany(companyId: CompanyId): List<PurchaseOrder>
}
