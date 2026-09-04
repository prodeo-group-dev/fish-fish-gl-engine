package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.sales.AccountsReceivableAging
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

data class CustomerBalance(val customerId: CustomerId, val balance: Money)

sealed class ComputeCustomerBalancesResult {
    data class Success(val balances: List<CustomerBalance>) : ComputeCustomerBalancesResult()
    data object CompanyNotFound : ComputeCustomerBalancesResult()
    data object ArControlAccountNotConfigured : ComputeCustomerBalancesResult()
}

/**
 * `POST /companies/{companyId}/customer-balances` (2026-09-04, "Why do
 * we have 'Schedule of Customers' and 'Customers'. They are the same
 * thing. Some customers can have a balance of zero.") - the real,
 * ledger-derived per-customer AR balance that was missing entirely:
 * GL's own legacy `domain.sales.Customer.balance` (the old "Schedule of
 * Customers" source) is a *different*, disconnected identity from the
 * `CustomerId`s SOP's own `Customer` aggregate actually tags onto every
 * posted `RecordSaleUseCase`/`RecordCollectionUseCase` JournalLine
 * (`DimensionType.CUSTOMER`). This use case answers "what does each of
 * *these* (SOP-supplied) customers owe" directly from posted Ledger
 * data, reusing [AccountsReceivableAging] (already built, never exposed
 * as its own route before this).
 *
 * Takes a caller-supplied [CustomerId] list rather than discovering
 * customers itself - GL has no visibility into SOP's Customer master
 * data, only the ids SOP happens to have tagged onto Ledger lines so
 * far. Every requested id gets a balance back, including zero for one
 * with no posted activity yet - "some customers can have a balance of
 * zero," not omitted from the list.
 */
class ComputeCustomerBalancesUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    fun execute(companyId: CompanyId, customerIds: List<CustomerId>, asOfDate: LocalDate = LocalDate.now()): ComputeCustomerBalancesResult {
        val company = companyRepository.findById(companyId) ?: return ComputeCustomerBalancesResult.CompanyNotFound

        val accounts = accountRepository.findAllByCompany(companyId)
        val arAccount = accounts.firstOrNull { it.type == AccountType.ASSET && it.code == "1100" }
            ?: return ComputeCustomerBalancesResult.ArControlAccountNotConfigured

        val entries = journalEntryRepository.findAllByCompany(companyId)
        val balances = customerIds.map { customerId ->
            val aging = AccountsReceivableAging.of(customerId, arAccount.id, entries, asOfDate, company.baseCurrency)
            CustomerBalance(customerId, aging.totalOutstanding)
        }

        return ComputeCustomerBalancesResult.Success(balances)
    }
}
