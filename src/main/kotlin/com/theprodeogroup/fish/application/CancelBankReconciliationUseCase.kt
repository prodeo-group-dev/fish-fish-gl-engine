package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankReconciliationId
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

sealed class CancelBankReconciliationResult {
    data class Success(val reconciliation: BankReconciliation) : CancelBankReconciliationResult()
    data object CompanyNotFound : CancelBankReconciliationResult()
    data object ReconciliationNotFound : CancelBankReconciliationResult()
    /** Already COMPLETED or CANCELLED - both are final. */
    data object NotOpen : CancelBankReconciliationResult()
}

/** Abandons an OPEN Bank Reconciliation (UAT v2.2 W-M2); final. Its statement lines and matches stay readable. */
class CancelBankReconciliationUseCase(
    private val companyRepository: CompanyRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val bankReconciliationRepository: BankReconciliationRepository
) {
    fun execute(companyId: CompanyId, reconciliationId: BankReconciliationId): CancelBankReconciliationResult {
        companyRepository.findById(companyId) ?: return CancelBankReconciliationResult.CompanyNotFound
        val postedEntries = journalEntryRepository.findAllByCompany(companyId)
        val reconciliation = bankReconciliationRepository.findById(reconciliationId, companyId, postedEntries)
            ?: return CancelBankReconciliationResult.ReconciliationNotFound

        if (!reconciliation.cancel().isValid) return CancelBankReconciliationResult.NotOpen
        bankReconciliationRepository.save(reconciliation, companyId)
        return CancelBankReconciliationResult.Success(reconciliation)
    }
}
