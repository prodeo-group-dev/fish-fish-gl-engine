package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRepository
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/** How a Fixed Asset acquisition was funded - each variant supplies whatever [CreateFixedAssetUseCase] needs to build the credit side of the acquisition entry. */
sealed class FixedAssetFundingMethod {
    /** Dr [cashAccountId]'s counterpart credited - tagged `CASH_FLOW_ACTIVITY = INVESTING`, matching [FixedAsset.dispose]'s own convention for a Fixed-Asset-related cash line, so this correctly shows as an investing outflow, not operating. */
    data class Cash(val cashAccountId: AccountId) : FixedAssetFundingMethod()

    /**
     * Credits the AP control account instead, tagged `VENDOR` with
     * [vendorReference] - a free-text audit note, **not** a validated
     * `Creditor` record. GL has no HTTP-exposed Creditor listing or
     * creation route today, and this codebase's own convention is "POP
     * owns Supplier/Creditor creation, never GL" - the same "opaque
     * tag, no referential check" treatment `RecordVendorObligationUseCase`
     * already gives its own `vendorId`. No cash-flow tag here - no cash
     * moves until the obligation is later settled.
     */
    data class OnAccount(val apControlAccountId: AccountId, val vendorReference: String) : FixedAssetFundingMethod()
}

/**
 * Adds one entry to a Company's Fixed Asset Register **and posts its
 * acquisition to the Ledger** (2026-09-12, "I posted into the Fixed
 * asset register and it did not carry through into the ledgers and
 * the balance sheet" / "There appears to be no process for fixed
 * asset Purchases"). Previously this use case only created the
 * register entry - acquiring the asset was deliberately left as "a
 * separate posting via `RecordVendorObligationUseCase` or
 * `PostJournalEntryUseCase`" (2026-09-03), but that second step was
 * never actually wired into any route or UI, so in production adding
 * an asset never touched Account balances or the Balance Sheet at all.
 *
 * **Posts before saving the register entry, not after** - if
 * [postJournalEntryUseCase] fails for any reason (closed Period, an
 * unknown account), no `FixedAsset` is ever saved. This is what closes
 * the reported gap for good: there is no code path left where a
 * register entry exists without its acquisition already having posted
 * successfully.
 *
 * Reuses [PostJournalEntryUseCase] rather than re-deriving its own
 * period-open-check/account-existence-check/balance-validation/
 * `Account.recordActivity()` wiring - the same boilerplate
 * `RecordVendorObligationUseCase`/`DisposeFixedAssetUseCase` each
 * already repeat once more.
 */
class CreateFixedAssetUseCase(
    private val companyRepository: CompanyRepository,
    private val fixedAssetRepository: FixedAssetRepository,
    private val postJournalEntryUseCase: PostJournalEntryUseCase
) {
    data class Request(
        val companyId: CompanyId,
        val name: String,
        val category: AssetCategory,
        val cost: Money,
        val acquisitionDate: LocalDate,
        val usefulLifeYears: Int? = null,
        /** A serial number, vehicle registration, deed reference, or similar - optional, some assets genuinely have none (e.g. Land). */
        val identifier: String? = null,
        val periodId: PeriodId,
        val fixedAssetAccountId: AccountId,
        val funding: FixedAssetFundingMethod
    )

    sealed class Result {
        data class Success(val fixedAsset: FixedAsset, val journalEntry: JournalEntry) : Result()
        data object CompanyNotFound : Result()
        data class InvalidFixedAsset(val message: String?) : Result()
        data object PeriodNotFound : Result()
        data object PeriodNotOpen : Result()
        data class AccountNotFound(val accountId: AccountId) : Result()
        data class InvalidPosting(val errors: List<String>) : Result()
    }

    fun execute(request: Request): Result {
        companyRepository.findById(request.companyId) ?: return Result.CompanyNotFound

        val fixedAsset = try {
            FixedAsset.create(
                companyId = request.companyId,
                name = request.name,
                category = request.category,
                cost = request.cost,
                acquisitionDate = request.acquisitionDate,
                usefulLifeYears = request.usefulLifeYears,
                identifier = request.identifier
            )
        } catch (e: IllegalArgumentException) {
            return Result.InvalidFixedAsset(e.message)
        }

        val creditLine = when (val funding = request.funding) {
            is FixedAssetFundingMethod.Cash -> JournalLine(
                funding.cashAccountId, request.cost, TransactionSide.CREDIT,
                mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.INVESTING.name)
            )
            is FixedAssetFundingMethod.OnAccount -> JournalLine(
                funding.apControlAccountId, request.cost, TransactionSide.CREDIT,
                mapOf(DimensionType.VENDOR to funding.vendorReference)
            )
        }
        val lines = listOf(
            JournalLine(request.fixedAssetAccountId, request.cost, TransactionSide.DEBIT),
            creditLine
        )

        val posting = postJournalEntryUseCase.execute(
            PostJournalEntryUseCase.Request(
                periodId = request.periodId,
                date = request.acquisitionDate,
                lines = lines,
                source = JournalSource.MANUAL,
                description = "Acquisition - ${request.name}"
            )
        )

        return when (posting) {
            is PostJournalEntryResult.Success -> {
                fixedAssetRepository.save(fixedAsset)
                Result.Success(fixedAsset, posting.entry)
            }
            is PostJournalEntryResult.PeriodNotFound -> Result.PeriodNotFound
            is PostJournalEntryResult.PeriodNotOpen -> Result.PeriodNotOpen
            is PostJournalEntryResult.AccountNotFound -> Result.AccountNotFound(posting.accountId)
            is PostJournalEntryResult.InvalidLines -> Result.InvalidPosting(posting.errors)
        }
    }
}
