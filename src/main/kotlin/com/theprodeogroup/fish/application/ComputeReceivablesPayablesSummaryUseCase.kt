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
 * on that side, not an error. **Postings to a control account that carry no customer or supplier tag are counted too**
 * (2026-10-15, found by the dashboard test: an inventory receipt credits Accounts Payable with no supplier tag, so the
 * tile read 120 against a ledger balance of 50,220): they form one anonymous party, allocated first in first out among
 * themselves and aged by their own dates, so `total` agrees with the ledger's control balance (gross owed: a customer or
 * supplier in credit is not netted off). The aging routes themselves are unchanged. Read-only.
 *
 * **Known limit, accepted by CM 2026-10-15, until GRNI lands:** a tagged payment that exceeds its own supplier's tagged
 * charges (for example, paying a receipt-origin liability with a payment tagged to the supplier) leaves the anonymous
 * party's untagged credit unreduced, because tagged lines never allocate against untagged ones. Until then `total` can
 * exceed the ledger control balance by that amount. This is gross owed by design, not a netting error.
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
            val customers = taggedIds(entries, account.id, DimensionType.CUSTOMER)
            val known = customers.map { it.toString() }.toSet()
            customers.flatMap { AccountsReceivableAging.of(CustomerId(it), account.id, entries, asOf, currency).unpaidItems } +
                AccountsReceivableAging.unattributedUnpaidItems(account.id, entries, asOf, currency) { it in known }
        } ?: emptyList()
        val payableItems: List<UnpaidItem> = apAccount?.let { account ->
            val suppliers = taggedIds(entries, account.id, DimensionType.VENDOR)
            val known = suppliers.map { it.toString() }.toSet()
            suppliers.flatMap { AccountsPayableAging.of(SupplierId(it), account.id, entries, asOf, currency).unpaidItems } +
                AccountsPayableAging.unattributedUnpaidItems(account.id, entries, asOf, currency) { it in known }
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
