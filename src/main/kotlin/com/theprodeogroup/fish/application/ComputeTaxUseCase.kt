package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tax.TaxComputation
import com.theprodeogroup.fish.domain.tax.TaxComputationInputs
import com.theprodeogroup.fish.domain.tax.TaxComputationRepository
import com.theprodeogroup.fish.domain.tax.TaxRule
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.util.Currency

/**
 * Outcome of [ComputeTaxUseCase.execute] - a sealed `Result`, same
 * reasoning as `PostJournalEntryResult`/`ClosePeriodResult` (Sections
 * 10.8/10.9): several genuinely distinct failure reasons, not the
 * domain layer's usual "return null" case.
 */
sealed class ComputeTaxResult {
    data class Success(val computation: TaxComputation) : ComputeTaxResult()
    data object PeriodNotFound : ComputeTaxResult()
    data object PeriodBelongsToDifferentCompany : ComputeTaxResult()
    data object NoAccountsForCompany : ComputeTaxResult()
}

/**
 * Section 5's third named example use case (`PostJournalEntry,
 * ClosePeriod, ComputeTax, ...`), docs/DDD_Design.md Section 10.10.
 *
 * Confirmed scope before building (2026-08-20) - Corporate Income Tax
 * only, since the spec itself (Section 7.12) leaves open which tax type
 * to build first and VAT/GST and PAYROLL_PAYE each have a genuinely
 * different, currently-unsupported computation shape. See `TaxType`'s
 * own KDoc for the full reasoning.
 *
 * This use case's entire job is assembling the inputs `TaxComputation.of()`
 * needs (a Company's `Account`s, a Period's posted `JournalEntry`s) - the
 * actual computation is delegated entirely to `TaxComputation`, which in
 * turn delegates to the already-built `ProfitAndLoss.of()`. No new
 * calculation logic lives here.
 *
 * **Persists the resulting `TaxComputation`** (docs/DDD_Design.md
 * Section 10.11) - `ComputeTaxUseCase` still never posts back into the
 * Ledger (matches spec 7.12/Section 2.4's "does not post back into the
 * Ledger in v1" - tax computation is a report, not a Ledger-mutating
 * operation), but the computed figure itself is now saved via
 * `TaxComputationRepository`, for the audit/compliance reasons
 * `TaxComputation`'s own KDoc explains. This use case deliberately does
 * **not** take a `TaxRuleRepository` - the caller is still expected to
 * supply an already-resolved `TaxRule` directly in the `Request` (an
 * ad-hoc TaxRule value passed in for one computation shouldn't silently
 * get written to the global reference-data table as a side effect of
 * just running a computation - that would conflate "administering tax
 * rules" with "running a report").
 */
class ComputeTaxUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val taxComputationRepository: TaxComputationRepository
) {
    data class Request(
        val companyId: CompanyId,
        val periodId: PeriodId,
        val taxRule: TaxRule,
        val currency: Currency,
        val inputs: TaxComputationInputs = TaxComputationInputs.NONE
    )

    fun execute(request: Request): ComputeTaxResult {
        val period = periodRepository.findById(request.periodId)
            ?: return ComputeTaxResult.PeriodNotFound
        if (period.companyId != request.companyId) {
            return ComputeTaxResult.PeriodBelongsToDifferentCompany
        }

        val accounts = accountRepository.findAllByCompany(request.companyId)
        if (accounts.isEmpty()) {
            return ComputeTaxResult.NoAccountsForCompany
        }

        val postedEntries = journalEntryRepository.findAllByPeriod(period.id)
        val computation = TaxComputation.of(
            request.taxRule, accounts, postedEntries, period.id, request.currency, request.inputs
        )
        taxComputationRepository.save(computation)

        return ComputeTaxResult.Success(computation)
    }
}
