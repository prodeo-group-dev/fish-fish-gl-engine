package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.AgingBucketAmount
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.purchasing.AccountsPayableAging
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

data class VendorAging(val creditorId: CreditorId, val buckets: List<AgingBucketAmount>)

sealed class ComputeAccountsPayableAgingResult {
    data class Success(val aging: List<VendorAging>) : ComputeAccountsPayableAgingResult()
    data object CompanyNotFound : ComputeAccountsPayableAgingResult()
    data object ApControlAccountNotConfigured : ComputeAccountsPayableAgingResult()
}

/**
 * `POST /companies/{companyId}/accounts-payable-aging` - the AP mirror of
 * [ComputeAccountsReceivableAgingUseCase], exposing [AccountsPayableAging]'s
 * own bucketed breakdown (CURRENT/31-60/61-90/OVER_90), which
 * [ComputeVendorBalancesUseCase] reuses internally but only ever returns
 * as the scalar `totalOutstanding`. Flagged alongside the AR gap
 * (`docs/GL_POP_IM_SOP_Backlog.md`) and built as its own follow-up pass.
 * Additive - does not change [ComputeVendorBalancesUseCase]'s existing
 * response shape.
 *
 * Same caller-supplied-id-list constraint as [ComputeVendorBalancesUseCase]:
 * GL has no visibility into POP's Creditor master data, only the ids POP
 * has tagged onto Ledger lines so far.
 */
class ComputeAccountsPayableAgingUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    fun execute(companyId: CompanyId, creditorIds: List<CreditorId>, asOfDate: LocalDate = LocalDate.now()): ComputeAccountsPayableAgingResult {
        val company = companyRepository.findById(companyId) ?: return ComputeAccountsPayableAgingResult.CompanyNotFound

        val accounts = accountRepository.findAllByCompany(companyId)
        val apAccount = accounts.firstOrNull { it.type == AccountType.LIABILITY && it.code == "2000" }
            ?: return ComputeAccountsPayableAgingResult.ApControlAccountNotConfigured

        val entries = journalEntryRepository.findAllByCompany(companyId)
        val aging = creditorIds.map { creditorId ->
            val result = AccountsPayableAging.of(creditorId, apAccount.id, entries, asOfDate, company.baseCurrency)
            VendorAging(creditorId, result.buckets)
        }

        return ComputeAccountsPayableAgingResult.Success(aging)
    }
}
