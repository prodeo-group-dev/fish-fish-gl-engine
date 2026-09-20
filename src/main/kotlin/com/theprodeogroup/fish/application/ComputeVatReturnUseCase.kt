package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.tax.VatFilingPeriod
import com.theprodeogroup.fish.domain.tax.VatReturn
import com.theprodeogroup.fish.domain.tax.VatReturnRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.util.Currency

/**
 * Outcome of [ComputeVatReturnUseCase.execute].
 */
sealed class ComputeVatReturnResult {
    data class Success(val vatReturn: VatReturn) : ComputeVatReturnResult()
    data class VatControlAccountNotFound(val accountId: AccountId) : ComputeVatReturnResult()
}

/**
 * The VAT MVP's actual deliverable (docs/IE/IE_VAT_MVP_Design.md #5) - a
 * real, correct VAT liability (or refund) figure for an RoI Company,
 * derived from actual recorded sales and purchases. Mirrors
 * [ComputeTaxUseCase]'s own shape exactly: this use case's entire job is
 * assembling the inputs [VatReturn.of] needs (every posted entry for the
 * Company - [VatFilingPeriod]'s own date-range filtering happens inside
 * [VatReturn.of], not here) and persisting the result. No new calculation
 * logic lives here, same restraint `ComputeTaxUseCase`'s own KDoc states.
 *
 * **No `CompanyRepository` dependency** - [currency] is caller-supplied
 * in [Request], the same choice `ComputeTaxUseCase.Request` already made
 * for the identical reason: the caller (the HTTP route) already resolved
 * the Company to get here, so re-resolving it a second time inside this
 * use case would be redundant.
 */
class ComputeVatReturnUseCase(
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val vatReturnRepository: VatReturnRepository
) {
    data class Request(
        val companyId: CompanyId,
        val filingPeriod: VatFilingPeriod,
        val vatControlAccountId: AccountId,
        val currency: Currency
    )

    fun execute(request: Request): ComputeVatReturnResult {
        val vatAccount = accountRepository.findById(request.vatControlAccountId)
            ?: return ComputeVatReturnResult.VatControlAccountNotFound(request.vatControlAccountId)

        val postedEntries = journalEntryRepository.findAllByCompany(request.companyId)
        val vatReturn = VatReturn.of(request.companyId, request.filingPeriod, vatAccount.id, postedEntries, request.currency)
        vatReturnRepository.save(vatReturn)

        return ComputeVatReturnResult.Success(vatReturn)
    }
}
