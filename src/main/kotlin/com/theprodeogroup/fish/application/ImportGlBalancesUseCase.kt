package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.opening.OpeningImportBatch
import com.theprodeogroup.fish.domain.opening.OpeningImportBatchRepository
import com.theprodeogroup.fish.domain.opening.OpeningImportDomain
import com.theprodeogroup.fish.domain.opening.OpeningImportRowResult
import com.theprodeogroup.fish.domain.opening.OpeningImportRowResultRepository
import com.theprodeogroup.fish.domain.opening.OpeningImportRowStatus
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.math.BigDecimal
import java.time.LocalDate

/**
 * `docs/Opening_Figures_CSV_Upload_DDD_Design.md` Section 4.4/7 step 1 -
 * the first of the five opening-figures importers to be built, built
 * first specifically because it exercises the Suspense-redirect
 * mechanism (Section 2) every other domain's reject-vs-redirect
 * reasoning leans on. Operates on already-parsed [Row]s, not raw CSV
 * text - parsing a file into rows is a web/infrastructure concern, kept
 * out of this framework-agnostic use case the same way every other
 * use case in this codebase stays `domain`-only.
 *
 * **[validate]/[commit] share one code path ([process]), not two**
 * (design doc decision 5: "no separate code path - the row-processing
 * logic is identical either way"). [validate] is a true dry run (FR-UP4):
 * every row is classified exactly as [commit] would classify it, but
 * nothing is posted and nothing is persisted. This deliberately departs
 * from the design doc's own `commit(batchId)` signature (resume a
 * previously-validated, persisted batch by id) - doing that would need
 * a way to persist and retrieve the *original, unparsed* rows between
 * two separate calls, a concept this design doc never actually
 * specifies the shape of. For the synchronous path (<=2,000 rows,
 * decision 5's own working default - the only path this increment
 * builds), the design doc itself says a caller never observes the
 * intermediate `VALIDATING`/`COMMITTING` states anyway, so re-running
 * the identical classification logic with the same rows in hand across
 * one validate-then-commit round trip is a faithful, honest reading of
 * that decision, not a shortcut around it. The >2,000-row async path
 * ([com.theprodeogroup.fish.domain.opening.OpeningImportBatchStatus]'s
 * own KDoc) stays explicitly unbuilt.
 *
 * **The Suspense-redirect (Section 2) covers all four "itemized-elsewhere"
 * account categories the design doc names, plus one contra account** -
 * AR control (code "1100"), AP control ("2000"), Fixed Assets ("1200"),
 * and, since 2026-10-09, Inventory ("1300") and Accumulated Depreciation
 * ("1210"), all resolved the same fixed-code-lookup way
 * [ComputePurchasePostingContextUseCase]/[ComputeInventoryPostingContextUseCase]
 * already resolve their own control accounts.
 *
 * Inventory used to be a flagged gap: the design doc's Section 2.1 claims it's
 * "already referenced by `CreateItemUseCase.Request.inventoryAssetAccountId`,"
 * but that id is supplied per-`Item` on IM's own side, and the default chart had
 * no company-wide inventory account for GL to recognise. The default chart now
 * seeds one (code "1300", UAT v2.2 W-L4), so without this a lump opening balance
 * imported to it was silently accepted instead of itemized through IM. Accumulated
 * Depreciation ("1210", same change) is the contra to Fixed Assets: its detail is
 * the per-asset register's depreciation, so it is redirected for the same reason.
 * A Company created before the 2026-10-08 chart change has neither code unless it
 * made them itself, in which case nothing is redirected for it, as before.
 */
class ImportGlBalancesUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val recordOpeningBalanceUseCase: RecordOpeningBalanceUseCase,
    private val batchRepository: OpeningImportBatchRepository,
    private val rowResultRepository: OpeningImportRowResultRepository
) {
    /** [contraAccountCode] omitted defaults to [ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE], per the design doc's Section 6 CSV schema. */
    data class Row(
        val rowNumber: Int,
        val accountCode: String,
        val amount: BigDecimal,
        val contraAccountCode: String? = null
    )

    data class Request(
        val companyId: CompanyId,
        val anchorDate: LocalDate,
        val filenameHash: String,
        val createdByEmail: String,
        val rows: List<Row>
    )

    sealed class Result {
        data class Success(val batch: OpeningImportBatch, val rowResults: List<OpeningImportRowResult>) : Result()
        data object CompanyNotFound : Result()
    }

    /** Dry run (FR-UP4) - classifies every row exactly as [commit] would, posts and persists nothing. */
    fun validate(request: Request): Result = process(request, commit = false)

    /** Posts every ACCEPTED/NEEDS_ITEMIZATION row via [RecordOpeningBalanceUseCase] (tagged [JournalSource.IMPORT]) and persists the batch (FR-UP5). */
    fun commit(request: Request): Result = process(request, commit = true)

    private fun process(request: Request, commit: Boolean): Result {
        companyRepository.findById(request.companyId) ?: return Result.CompanyNotFound

        val accounts = accountRepository.findAllByCompany(request.companyId)
        val accountsByCode = accounts.associateBy { it.code }
        val defaultContraAccount = accountsByCode[ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE]
        val suspenseAccount = accountsByCode[ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE]

        // The "itemized elsewhere" categories GL can resolve on its own, by fixed code - see this class's own KDoc.
        val itemizedElsewhereIds = setOfNotNull(
            accounts.firstOrNull { it.type == AccountType.ASSET && it.code == "1100" }?.id,
            accounts.firstOrNull { it.type == AccountType.LIABILITY && it.code == "2000" }?.id,
            accounts.firstOrNull { it.type == AccountType.ASSET && it.code == "1200" }?.id,
            accounts.firstOrNull { it.type == AccountType.ASSET && it.code == ChartOfAccountsTemplate.INVENTORY_CODE }?.id,
            accounts.firstOrNull { it.type == AccountType.ASSET && it.code == ChartOfAccountsTemplate.ACCUMULATED_DEPRECIATION_CODE }?.id
        )

        val batch = OpeningImportBatch.create(
            OpeningImportDomain.GL_BALANCES, request.companyId, request.anchorDate, request.filenameHash, request.createdByEmail
        )

        val seenAccountCodes = mutableSetOf<String>()
        val rowResults = request.rows.map { row ->
            val targetAccount = accountsByCode[row.accountCode]
            val contraAccount = row.contraAccountCode?.let { accountsByCode[it] } ?: defaultContraAccount

            when {
                row.accountCode in seenAccountCodes -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED,
                    listOf("duplicate account_code '${row.accountCode}' within this batch")
                )
                row.amount.signum() <= 0 -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED, listOf("amount must be positive")
                )
                targetAccount == null -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED, listOf("account_code '${row.accountCode}' not found")
                )
                contraAccount == null -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED,
                    listOf("contra account '${row.contraAccountCode ?: ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE}' not found")
                )
                targetAccount.id in itemizedElsewhereIds && suspenseAccount == null -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED, listOf("Suspense Account not configured for this company")
                )
                else -> {
                    seenAccountCodes += row.accountCode
                    if (targetAccount.id in itemizedElsewhereIds) {
                        val entityIds = postIfCommitting(commit, request, suspenseAccount!!.id, contraAccount.id, row)
                        OpeningImportRowResult(
                            batch.id, row.rowNumber, OpeningImportRowStatus.NEEDS_ITEMIZATION,
                            listOf("account '${row.accountCode}' is itemized elsewhere - posted to Suspense instead, needs itemizing"),
                            entityIds
                        )
                    } else {
                        val entityIds = postIfCommitting(commit, request, targetAccount.id, contraAccount.id, row)
                        OpeningImportRowResult(batch.id, row.rowNumber, OpeningImportRowStatus.ACCEPTED, resultingEntityIds = entityIds)
                    }
                }
            }
        }

        batch.markValidated(rowResults)
        if (commit) {
            batch.markCommitted()
            batchRepository.save(batch)
            rowResultRepository.saveAll(rowResults)
        }
        return Result.Success(batch, rowResults)
    }

    private fun postIfCommitting(
        commit: Boolean,
        request: Request,
        accountId: com.theprodeogroup.fish.domain.ledger.AccountId,
        contraAccountId: com.theprodeogroup.fish.domain.ledger.AccountId,
        row: Row
    ): List<String> {
        if (!commit) return emptyList()
        val posted = recordOpeningBalanceUseCase.execute(
            RecordOpeningBalanceUseCase.Request(
                companyId = request.companyId,
                accountId = accountId,
                contraAccountId = contraAccountId,
                amount = row.amount,
                date = request.anchorDate,
                journalSource = JournalSource.IMPORT
            )
        )
        return listOfNotNull((posted as? RecordOpeningBalanceUseCase.Result.Success)?.journalEntry?.id?.value?.toString())
    }
}
