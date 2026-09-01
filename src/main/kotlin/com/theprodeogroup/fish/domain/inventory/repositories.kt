package com.theprodeogroup.fish.domain.inventory

import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * [StockShortageEscalation] is dormant, not deleted, as of 2026-09-01
 * ("Retire GL's StockItem from its legacy costing") - it's a real
 * audit-trail feature with real historical value, just no longer
 * written to since `CreateSalesInvoiceUseCase` stopped doing its own
 * stock check (IM does that now, before GL is ever called). Its own
 * repository stays for whatever existing records need reading.
 */

/** Persistence contract for [StockShortageEscalation] - append-only, no `findById`/update need identified yet. */
interface StockShortageEscalationRepository {
    fun save(escalation: StockShortageEscalation)
    fun findAllByCompany(companyId: CompanyId): List<StockShortageEscalation>
}
