package com.theprodeogroup.fish.domain.fixedassets

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A single capital item's per-asset detail (docs/DDD_Design.md Section
 * 2.8) - the subsidiary-ledger side of the Fixed Assets control account,
 * same pattern as AP/AR (Section 2.5). Confirmed scope 2026-08-12:
 * **straight-line depreciation only** (no pluggable method yet) and
 * **acquisition + periodic depreciation only** - disposal/write-off is
 * deferred to a later increment.
 *
 * [usefulLifeYears] is `null` for [AssetCategory.LAND] (enforced at
 * construction) - land has an indefinite useful life and is never
 * depreciated, the confirmed policy from Section 3.1's depreciation note.
 *
 * One [recordDepreciation] call represents *one year's* charge, not a
 * period-fraction proration - matches the existing boundary discipline
 * of `JournalEntry` never inspecting `Period`'s own type/duration
 * (Section 3.1's design note); callers decide how often to call this
 * based on their own reporting cadence.
 *
 * [dispose] closes the original "disposal deferred" scope note, built
 * 2026-08-12.
 */
class FixedAsset private constructor(
    val id: FixedAssetId,
    val companyId: CompanyId,
    val name: String,
    val category: AssetCategory,
    val cost: Money,
    val acquisitionDate: LocalDate,
    val usefulLifeYears: Int?
) {
    var accumulatedDepreciation: Money = Money(BigDecimal.ZERO, cost.currency)
        private set

    var isDisposed: Boolean = false
        private set

    val netBookValue: Money
        get() = cost - accumulatedDepreciation

    /** Straight-line: cost / usefulLifeYears. `null` if this asset isn't depreciated (e.g. Land). */
    val annualDepreciationCharge: Money?
        get() = usefulLifeYears?.let { cost / BigDecimal(it) }

    /**
     * Posts one year's straight-line depreciation: debits
     * [depreciationExpenseAccountId], credits
     * [accumulatedDepreciationAccountId] (a contra-asset control
     * account, shared across all assets rather than one per category -
     * the simplest default given Section 2.8 left this genuinely open).
     * Uses `JournalSource.SYSTEM`, matching that enum's own original
     * "monthly depreciation entry" example.
     *
     * The charge is capped at the asset's remaining [netBookValue], so
     * it never depreciates past cost - a rounding remainder left after
     * `usefulLifeYears` full charges (straight-line division doesn't
     * always divide evenly) is captured in full by whichever call
     * crosses it. Returns `null` if this asset isn't depreciated at all
     * (no [usefulLifeYears]) or is already fully depreciated - matches
     * `PurchaseOrder.send()`/`SalesOrder.deliverLine()`'s precedent for
     * a method producing a new object only on success.
     */
    fun recordDepreciation(
        depreciationExpenseAccountId: AccountId,
        accumulatedDepreciationAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (isDisposed) return null
        val annualCharge = annualDepreciationCharge ?: return null
        val remaining = netBookValue
        if (remaining.amount.signum() <= 0) return null
        val charge = if (annualCharge > remaining) remaining else annualCharge

        accumulatedDepreciation = accumulatedDepreciation + charge

        val lines = listOf(
            JournalLine(depreciationExpenseAccountId, charge, TransactionSide.DEBIT),
            JournalLine(accumulatedDepreciationAccountId, charge, TransactionSide.CREDIT)
        )
        return JournalEntry.create(
            periodId, date, lines, JournalSource.SYSTEM,
            "Depreciation - $name ($id)", journalEntryId
        )
    }

    /**
     * Disposes of the asset (sale or scrap) using the standard Asset
     * Disposal Account method (docs/DDD_Design.md Section 2.8, confirmed
     * 2026-08-12 - a real correction to an earlier compound-entry design
     * that computed gain/loss directly instead of letting the ledger
     * derive it). [saleOfFixedAssetAccountId] ("Sale of Fixed Asset," a
     * Revenue-type account) is the clearing account everything routes
     * through, **not** a separate gain-only target:
     *
     * 1. Debit [saleOfFixedAssetAccountId], credit [fixedAssetAccountId]
     *    for [cost] - removes the asset from the Fixed Asset register.
     * 2. Debit [accumulatedDepreciationAccountId], credit
     *    [saleOfFixedAssetAccountId] for [accumulatedDepreciation] -
     *    clears the accumulated depreciation. After steps 1-2 alone,
     *    [saleOfFixedAssetAccountId]'s net balance is exactly
     *    [netBookValue] (debit).
     * 3. Debit [cashAccountId], credit [saleOfFixedAssetAccountId] for
     *    [proceeds]. The cash line is tagged `CashFlowActivity.INVESTING`
     *    (IAS 7, confirmed 2026-08-12) - unlike `Customer.receivePayment()`/
     *    `Creditor.makePayment()`'s Operating tag, proceeds from selling
     *    a capital asset are always Investing, regardless of
     *    [saleOfFixedAssetAccountId]'s own Revenue account type.
     *
     * No separate gain/loss computation or account - whatever balance
     * remains on [saleOfFixedAssetAccountId] after all three postings
     * *is* the gain (a credit balance, matching its Revenue type) or
     * loss (an abnormal debit balance). The accumulated-depreciation and
     * proceeds legs are omitted entirely when exactly zero (matching
     * `Customer.assessExpectedCreditLoss()`'s "nothing to post"
     * precedent) - e.g. disposing an asset with no depreciation recorded
     * yet, or a scrapped asset with zero proceeds.
     *
     * Marks the asset [isDisposed] - `recordDepreciation()` and a second
     * `dispose()` call both fail afterward. Returns `null` if already
     * disposed, matching every other "already in that state" precedent
     * in this codebase.
     */
    fun dispose(
        proceeds: Money,
        cashAccountId: AccountId,
        fixedAssetAccountId: AccountId,
        accumulatedDepreciationAccountId: AccountId,
        saleOfFixedAssetAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (isDisposed) return null

        val lines = mutableListOf(
            JournalLine(saleOfFixedAssetAccountId, cost, TransactionSide.DEBIT),
            JournalLine(fixedAssetAccountId, cost, TransactionSide.CREDIT)
        )
        if (accumulatedDepreciation.amount.signum() > 0) {
            lines.add(JournalLine(accumulatedDepreciationAccountId, accumulatedDepreciation, TransactionSide.DEBIT))
            lines.add(JournalLine(saleOfFixedAssetAccountId, accumulatedDepreciation, TransactionSide.CREDIT))
        }
        if (proceeds.amount.signum() > 0) {
            lines.add(
                JournalLine(
                    cashAccountId, proceeds, TransactionSide.DEBIT,
                    mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.INVESTING.name)
                )
            )
            lines.add(JournalLine(saleOfFixedAssetAccountId, proceeds, TransactionSide.CREDIT))
        }

        isDisposed = true
        return JournalEntry.create(
            periodId, date, lines, JournalSource.MANUAL,
            "Disposal - $name ($id)", journalEntryId
        )
    }

    companion object {
        fun create(
            companyId: CompanyId,
            name: String,
            category: AssetCategory,
            cost: Money,
            acquisitionDate: LocalDate,
            usefulLifeYears: Int? = null,
            id: FixedAssetId = FixedAssetId.generate()
        ): FixedAsset {
            require(cost.amount.signum() > 0) { "Cost must be positive" }
            require(usefulLifeYears == null || usefulLifeYears > 0) {
                "Useful life, if provided, must be positive"
            }
            require(category != AssetCategory.LAND || usefulLifeYears == null) {
                "Land is not depreciated and cannot have a useful life"
            }
            return FixedAsset(id, companyId, name, category, cost, acquisitionDate, usefulLifeYears)
        }
    }
}
