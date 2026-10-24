package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.findOwnedBy
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetId
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRepository
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ModuleOwnedAccounts
import com.theprodeogroup.fish.domain.ledger.UseInstead
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/** Outcome of [DisposeFixedAssetUseCase.execute]. */
sealed class DisposeFixedAssetResult {
    data class Success(
        val journalEntry: JournalEntry,
        val fixedAsset: FixedAsset,
        val events: List<DomainEvent>
    ) : DisposeFixedAssetResult()
    data object FixedAssetNotFound : DisposeFixedAssetResult()
    data object AlreadyDisposed : DisposeFixedAssetResult()
    /** Proceeds cannot be negative: a disposal that costs money is a separate expense, not negative proceeds. */
    data object ProceedsNegative : DisposeFixedAssetResult()
    /** The proceeds are in a different currency from the asset's cost. */
    data object ProceedsCurrencyMismatch : DisposeFixedAssetResult()
    /** [FixedAsset.dispose]'s own guard: a positive [FixedAsset.accumulatedImpairmentLoss] requires [Request.accumulatedImpairmentAccountId]. */
    data object AccumulatedImpairmentAccountRequired : DisposeFixedAssetResult()
    data object PeriodNotFound : DisposeFixedAssetResult()
    data object PeriodNotOpen : DisposeFixedAssetResult()
    data class CashAccountNotFound(val accountId: AccountId) : DisposeFixedAssetResult()
    data class FixedAssetAccountNotFound(val accountId: AccountId) : DisposeFixedAssetResult()
    data class AccumulatedDepreciationAccountNotFound(val accountId: AccountId) : DisposeFixedAssetResult()

    /** The cash account is not an active asset account (FR-FA26): Sales 4000 or any income, expense, liability or equity account is refused. */
    data class CashAccountNotAllowed(val accountId: AccountId, val reason: LineAccountResolver.Reason) : DisposeFixedAssetResult()

    /** The fixed-asset account is not an active asset account that is neither a cash book nor another module's control account (FR-FA26). */
    data class FixedAssetAccountNotAllowed(val accountId: AccountId, val reason: LineAccountResolver.Reason) : DisposeFixedAssetResult()

    /** The accumulated depreciation account is refused for the same reasons as the fixed-asset account (FR-FA26). */
    data class AccumulatedDepreciationAccountNotAllowed(val accountId: AccountId, val reason: LineAccountResolver.Reason) : DisposeFixedAssetResult()
    /** The gain or loss account named is not an account of this Company. */
    data class GainLossAccountNotFound(val accountId: AccountId) : DisposeFixedAssetResult()
    /** The gain account must be income and the loss account an expense (or Suspense); inactive and control accounts are refused (see [LineAccountResolver]). */
    data class GainLossAccountNotAllowed(val accountId: AccountId, val reason: LineAccountResolver.Reason) : DisposeFixedAssetResult()
    /** There is a gain or loss, no account was named, and the Company has no Suspense account to put it on. */
    data object SuspenseAccountNotConfigured : DisposeFixedAssetResult()
    data class AccumulatedImpairmentAccountNotFound(val accountId: AccountId) : DisposeFixedAssetResult()
}

/**
 * Disposes of a single [FixedAsset] register entry - a thin wrapper around
 * [FixedAsset.dispose], same shape as [RecordFixedAssetDepreciationUseCase].
 *
 * **Gain and loss on disposal are separate transaction types with their own chart accounts (Femi, 2026-10-18).**
 * The earlier "Asset Disposal Account" method (docs/DDD_Design.md Section 2.8) routed every leg through one
 * Revenue-type clearing account and left the balance there, so a loss sat as a debit on whatever revenue account the
 * caller picked (tester finding: an 800 loss booked as negative Sales Revenue). Now the difference between
 * [Request.proceeds] and the asset's carrying amount is posted to [Request.gainOnDisposalAccountId] (income) when
 * it is a gain, or [Request.lossOnDisposalAccountId] (expense) when it is a loss; naming none sends it to the
 * Company's Suspense account, never to a default account. [Request.saleOfFixedAssetAccountId] is the legacy field:
 * accepted so old callers still compile and post, but ignored.
 */
class DisposeFixedAssetUseCase(
    private val fixedAssetRepository: FixedAssetRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val companyRepository: CompanyRepository? = null
) {
    private val lineAccountResolver = LineAccountResolver(accountRepository, companyRepository)

    data class Request(
        val fixedAssetId: FixedAssetId,
        val proceeds: Money,
        val cashAccountId: AccountId,
        val fixedAssetAccountId: AccountId,
        val accumulatedDepreciationAccountId: AccountId,
        /** Legacy and ignored since 2026-10-18 (see the class KDoc). */
        val saleOfFixedAssetAccountId: AccountId?,
        val periodId: PeriodId,
        val date: LocalDate,
        val accumulatedImpairmentAccountId: AccountId? = null,
        /** The income account a gain is credited to; `null` means Suspense. */
        val gainOnDisposalAccountId: AccountId? = null,
        /** The expense account a loss is debited to; `null` means Suspense. */
        val lossOnDisposalAccountId: AccountId? = null
    )

    fun execute(request: Request): DisposeFixedAssetResult {
        val fixedAsset = fixedAssetRepository.findById(request.fixedAssetId)
            ?: return DisposeFixedAssetResult.FixedAssetNotFound
        if (fixedAsset.isDisposed) {
            return DisposeFixedAssetResult.AlreadyDisposed
        }
        if (request.proceeds.currency != fixedAsset.cost.currency) return DisposeFixedAssetResult.ProceedsCurrencyMismatch
        if (request.proceeds.amount.signum() < 0) return DisposeFixedAssetResult.ProceedsNegative
        if (fixedAsset.accumulatedImpairmentLoss.amount.signum() > 0 && request.accumulatedImpairmentAccountId == null) {
            return DisposeFixedAssetResult.AccumulatedImpairmentAccountRequired
        }

        val period = periodRepository.findOwnedBy(request.periodId, fixedAsset.companyId)
            ?: return DisposeFixedAssetResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return DisposeFixedAssetResult.PeriodNotOpen
        }

        // Cross-tenant isolation fix (2026-09-23, same gap/fix as PostJournalEntryUseCase):
        // each Account must belong to the FixedAsset's own Company, or it's treated as
        // AccountNotFound - never a distinct "forbidden" case, so this never confirms
        // another Company's Account exists.
        val cashAccount = accountRepository.findById(request.cashAccountId)
            ?.takeIf { it.companyId == fixedAsset.companyId }
            ?: return DisposeFixedAssetResult.CashAccountNotFound(request.cashAccountId)
        val fixedAssetAccount = accountRepository.findById(request.fixedAssetAccountId)
            ?.takeIf { it.companyId == fixedAsset.companyId }
            ?: return DisposeFixedAssetResult.FixedAssetAccountNotFound(request.fixedAssetAccountId)
        val accumulatedDepreciationAccount = accountRepository.findById(request.accumulatedDepreciationAccountId)
            ?.takeIf { it.companyId == fixedAsset.companyId }
            ?: return DisposeFixedAssetResult.AccumulatedDepreciationAccountNotFound(request.accumulatedDepreciationAccountId)
        val accumulatedImpairmentAccount = request.accumulatedImpairmentAccountId?.let { accountId ->
            accountRepository.findById(accountId)?.takeIf { it.companyId == fixedAsset.companyId }
                ?: return DisposeFixedAssetResult.AccumulatedImpairmentAccountNotFound(accountId)
        }

        // FR-FA26 / FS-24 (finding F22-20, 2026-10-24): the TYPE of the accounts a disposal names. Validation only, nothing here is an
        // authorization. The cash side must be an active asset account (Sales 4000 as the cash account is the finding); that it is also a cash or
        // bank book is the settlement-account policy's rule, applied by the route (log-first, like every other settlement account).
        // The fixed-asset and accumulated depreciation accounts must be active asset accounts that are neither a cash book nor another module's
        // control account (receivables, inventory, VAT ...); the owner's own fixed-asset accounts pass.
        val clientType = companyRepository?.findById(fixedAsset.companyId)?.clientType ?: ClientType.COMPANY_LIMITED
        cashAccountRefusal(cashAccount)?.let { return DisposeFixedAssetResult.CashAccountNotAllowed(cashAccount.id, it) }
        fixedAssetAccountRefusal(fixedAssetAccount, clientType)?.let { return DisposeFixedAssetResult.FixedAssetAccountNotAllowed(fixedAssetAccount.id, it) }
        fixedAssetAccountRefusal(accumulatedDepreciationAccount, clientType)?.let {
            return DisposeFixedAssetResult.AccumulatedDepreciationAccountNotAllowed(accumulatedDepreciationAccount.id, it)
        }

        // The account for the difference between proceeds and carrying amount, by which side it falls on.
        val gainOrLoss = request.proceeds - fixedAsset.carryingAmount
        val gainLossAccount: Account? = when {
            gainOrLoss.amount.signum() == 0 -> null
            else -> {
                val gain = gainOrLoss.amount.signum() > 0
                val named = if (gain) request.gainOnDisposalAccountId else request.lossOnDisposalAccountId
                val kind = if (gain) LineAccountResolver.Kind.GAIN_ON_DISPOSAL else LineAccountResolver.Kind.LOSS_ON_DISPOSAL
                val resolved = if (named != null) lineAccountResolver.resolve(fixedAsset.companyId, kind, named) else lineAccountResolver.suspense(fixedAsset.companyId)
                when (resolved) {
                    is LineAccountResolver.Result.Resolved -> resolved.account
                    is LineAccountResolver.Result.NotFound -> return DisposeFixedAssetResult.GainLossAccountNotFound(resolved.accountId)
                    is LineAccountResolver.Result.NotAllowed -> return DisposeFixedAssetResult.GainLossAccountNotAllowed(resolved.accountId, resolved.reason)
                    LineAccountResolver.Result.SuspenseNotConfigured -> return DisposeFixedAssetResult.SuspenseAccountNotConfigured
                }
            }
        }

        val entry = fixedAsset.dispose(
            proceeds = request.proceeds,
            cashAccountId = cashAccount.id,
            fixedAssetAccountId = fixedAssetAccount.id,
            accumulatedDepreciationAccountId = accumulatedDepreciationAccount.id,
            gainOrLossAccountId = gainLossAccount?.id,
            periodId = request.periodId,
            date = request.date,
            accumulatedImpairmentAccountId = accumulatedImpairmentAccount?.id
        ) ?: return DisposeFixedAssetResult.AlreadyDisposed

        val posting = entry.post()
        check(posting.isValid) {
            "DisposeFixedAssetUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOfNotNull(
            cashAccount, fixedAssetAccount, accumulatedDepreciationAccount, accumulatedImpairmentAccount, gainLossAccount
        )
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)
        fixedAssetRepository.save(fixedAsset)

        return DisposeFixedAssetResult.Success(entry, fixedAsset, entry.pullDomainEvents())
    }

    private fun cashAccountRefusal(account: Account): LineAccountResolver.Reason? = when {
        !account.active -> LineAccountResolver.Reason.INACTIVE
        account.type != AccountType.ASSET -> LineAccountResolver.Reason.WRONG_TYPE
        else -> null
    }

    private fun fixedAssetAccountRefusal(account: Account, clientType: ClientType): LineAccountResolver.Reason? = when {
        !account.active -> LineAccountResolver.Reason.INACTIVE
        account.type != AccountType.ASSET -> LineAccountResolver.Reason.WRONG_TYPE
        account.cashBookKind != null -> LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED
        else -> ModuleOwnedAccounts.useInstead(account, clientType)
            ?.takeIf { it != UseInstead.FIXED_ASSETS }
            ?.let { LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED }
    }
}
