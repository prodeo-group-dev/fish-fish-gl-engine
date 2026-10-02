package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.AgingBucketAmount
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.sales.AccountsReceivableAging
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

data class CustomerAging(val customerId: CustomerId, val buckets: List<AgingBucketAmount>)

sealed class ComputeAccountsReceivableAgingResult {
    data class Success(val aging: List<CustomerAging>) : ComputeAccountsReceivableAgingResult()
    data object CompanyNotFound : ComputeAccountsReceivableAgingResult()
    data object ArControlAccountNotConfigured : ComputeAccountsReceivableAgingResult()
}

/**
 * `POST /companies/{companyId}/accounts-receivable-aging` - exposes
 * [AccountsReceivableAging]'s own bucketed breakdown (CURRENT/31-60/
 * 61-90/OVER_90), which was already fully built and tested but never
 * surfaced over HTTP: [ComputeCustomerBalancesUseCase] reuses the same
 * domain type but only ever returns `totalOutstanding`, collapsing the
 * buckets into a single scalar. Flagged by SOP (`docs/GL_POP_IM_SOP_Backlog.md`
 * "not yet waved" table, 2026-10-01/02) while scoping its own sales-
 * performance report. Additive - does not change
 * [ComputeCustomerBalancesUseCase]'s existing response shape.
 *
 * Same caller-supplied-id-list constraint as [ComputeCustomerBalancesUseCase]:
 * GL has no visibility into SOP's Customer master data, only the ids SOP
 * has tagged onto Ledger lines so far.
 */
class ComputeAccountsReceivableAgingUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    fun execute(companyId: CompanyId, customerIds: List<CustomerId>, asOfDate: LocalDate = LocalDate.now()): ComputeAccountsReceivableAgingResult {
        val company = companyRepository.findById(companyId) ?: return ComputeAccountsReceivableAgingResult.CompanyNotFound

        val accounts = accountRepository.findAllByCompany(companyId)
        val arAccount = accounts.firstOrNull { it.type == AccountType.ASSET && it.code == "1100" }
            ?: return ComputeAccountsReceivableAgingResult.ArControlAccountNotConfigured

        val entries = journalEntryRepository.findAllByCompany(companyId)
        val aging = customerIds.map { customerId ->
            val result = AccountsReceivableAging.of(customerId, arAccount.id, entries, asOfDate, company.baseCurrency)
            CustomerAging(customerId, result.buckets)
        }

        return ComputeAccountsReceivableAgingResult.Success(aging)
    }
}
