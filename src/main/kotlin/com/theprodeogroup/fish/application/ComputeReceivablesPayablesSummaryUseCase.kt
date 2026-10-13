package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.ReceivablesPayablesSummary
import com.theprodeogroup.fish.domain.ledger.UnpaidItem
import com.theprodeogroup.fish.domain.ledger.hasHistoricalEffect
import com.theprodeogroup.fish.domain.purchasing.AccountsPayableAging
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.sales.AccountsReceivableAging
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate
import java.util.UUID

/**
 * `GET /companies/{companyId}/receivables-payables-summary`: one total per side plus the overdue part and count, cheap
 * to call on every dashboard load. GL finds the customers and suppliers itself, from the CUSTOMER and VENDOR tags on
 * the AR (1100) and AP (2000) control lines, and applies the existing first-in-first-out aging
 * ([AccountsReceivableAging], [AccountsPayableAging], unchanged). A Company with no AR or AP control account gets zeros
 * on that side, not an error. Postings to a control account that carry no customer or supplier tag are outside the
 * aging, as they are in the aging routes and the customer balances. Read-only.
 */
class ComputeReceivablesPayablesSummaryUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    /** The source of "today"; a test (the contract fixtures) fixes it so a response with dates is reproducible. */
    private val clock: java.time.Clock = java.time.Clock.systemUTC()
) {
    sealed class Result {
        data class Success(val summary: ReceivablesPayablesSummary) : Result()
        data object CompanyNotFound : Result()
    }

    fun execute(companyId: CompanyId, asOf: LocalDate = LocalDate.now(clock)): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val currency = company.baseCurrency
        val accounts = accountRepository.findAllByCompany(companyId)
        val arAccount = accounts.firstOrNull { it.type == AccountType.ASSET && it.code == "1100" }
        val apAccount = accounts.firstOrNull { it.type == AccountType.LIABILITY && it.code == "2000" }
        if (arAccount == null && apAccount == null) {
            return Result.Success(ReceivablesPayablesSummary.of(emptyList(), emptyList(), asOf, currency))
        }

        val entries = journalEntryRepository.findAllByCompany(companyId)
        val receivableItems: List<UnpaidItem> = arAccount?.let { account ->
            taggedIds(entries, account.id, DimensionType.CUSTOMER).flatMap {
                AccountsReceivableAging.of(CustomerId(it), account.id, entries, asOf, currency).unpaidItems
            }
        } ?: emptyList()
        val payableItems: List<UnpaidItem> = apAccount?.let { account ->
            taggedIds(entries, account.id, DimensionType.VENDOR).flatMap {
                AccountsPayableAging.of(SupplierId(it), account.id, entries, asOf, currency).unpaidItems
            }
        } ?: emptyList()
        return Result.Success(ReceivablesPayablesSummary.of(receivableItems, payableItems, asOf, currency))
    }

    /** The distinct customer (or supplier) ids tagged on lines of [accountId]; a tag that is not an id is skipped. */
    private fun taggedIds(entries: List<JournalEntry>, accountId: AccountId, dimension: DimensionType): Set<UUID> =
        entries.filter { it.status.hasHistoricalEffect() }
            .flatMap { it.lines }
            .filter { it.accountId == accountId }
            .mapNotNull { line -> line.dimensions[dimension]?.let { runCatching { UUID.fromString(it) }.getOrNull() } }
            .toSet()
}
