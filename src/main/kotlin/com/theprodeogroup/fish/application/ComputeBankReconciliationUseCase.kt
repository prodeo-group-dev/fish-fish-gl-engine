package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankReconciliationId
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

sealed class ComputeBankReconciliationResult {
    data class Success(val reconciliation: BankReconciliation) : ComputeBankReconciliationResult()
    data object CompanyNotFound : ComputeBankReconciliationResult()
    data object ReconciliationNotFound : ComputeBankReconciliationResult()
}

/**
 * `GET /companies/{companyId}/bank-reconciliations/{id}` (UC-BANKREC-03) -
 * reports a reconciliation's current state: matched/unmatched statement
 * lines, matched/unmatched entries, `isFullyReconciled`.
 */
class ComputeBankReconciliationUseCase(
    private val companyRepository: CompanyRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val bankReconciliationRepository: BankReconciliationRepository
) {
    fun execute(companyId: CompanyId, reconciliationId: BankReconciliationId): ComputeBankReconciliationResult {
        companyRepository.findById(companyId) ?: return ComputeBankReconciliationResult.CompanyNotFound
        val postedEntries = journalEntryRepository.findAllByCompany(companyId)
        val reconciliation = bankReconciliationRepository.findById(reconciliationId, companyId, postedEntries)
            ?: return ComputeBankReconciliationResult.ReconciliationNotFound

        return ComputeBankReconciliationResult.Success(reconciliation)
    }
}
