package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRepository
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
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
import java.util.Currency

/**
 * `docs/Opening_Figures_CSV_Upload_DDD_Design.md` Section 4.2/7 step 2 -
 * the second opening-figures importer, built second specifically
 * because it needs no new use case at all (Section 4.2): every row
 * calls the existing [CreateFixedAssetUseCase] with
 * `funding = FixedAssetFundingMethod.AlreadyOwned(suspenseAccountId)` -
 * already exactly the "this already exists, we're recording it after
 * the fact" shape Suspense was built for.
 *
 * **Unlike [ImportGlBalancesUseCase], [CreateFixedAssetUseCase] needs a
 * real `PeriodId`, not a date** - it doesn't resolve the open Period
 * covering a date itself (confirmed by reading it directly: the caller
 * already has to know the id). This importer resolves it once per batch
 * (every row in one CSV shares the same anchor date), the same
 * open-Period-covering-a-date lookup [RecordOpeningBalanceUseCase]
 * already does internally.
 *
 * **Pre-checks mirror every validation [CreateFixedAssetUseCase]/
 * `FixedAsset.create` would themselves apply** (cost positive, useful
 * life positive-or-absent, Land can't have a useful life, the target and
 * Suspense accounts exist) so that [validate]'s dry-run classification
 * genuinely predicts what [commit] would do - the same "replicate the
 * wrapped use case's own checks, don't just hope" discipline
 * [ImportGlBalancesUseCase] already established for
 * [RecordOpeningBalanceUseCase]'s checks.
 *
 * **Duplicate guard (design doc decision 1) is identifier-based, not
 * account-code-based like the GL balances importer** - "reject duplicate
 * asset code" - checked against both already-persisted Fixed Assets for
 * this Company and other rows earlier in the same batch. A blank/absent
 * identifier never counts as a duplicate of another blank/absent one
 * (per `FixedAsset.create`'s own KDoc, some assets - Land - genuinely
 * have none).
 */
class ImportFixedAssetsUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val fixedAssetRepository: FixedAssetRepository,
    private val createFixedAssetUseCase: CreateFixedAssetUseCase,
    private val batchRepository: OpeningImportBatchRepository,
    private val rowResultRepository: OpeningImportRowResultRepository
) {
    data class Row(
        val rowNumber: Int,
        val assetName: String,
        val category: String,
        val acquisitionDate: LocalDate,
        val cost: BigDecimal,
        val currency: String,
        val usefulLifeYears: Int? = null,
        val identifier: String? = null,
        val fixedAssetAccountCode: String
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
        data object NoOpenPeriod : Result()
    }

    /** Dry run (FR-UP4) - classifies every row exactly as [commit] would, posts and persists nothing. */
    fun validate(request: Request): Result = process(request, commit = false)

    /** Posts every ACCEPTED row via [CreateFixedAssetUseCase] (tagged [JournalSource.IMPORT]) and persists the batch (FR-UP5). */
    fun commit(request: Request): Result = process(request, commit = true)

    private fun process(request: Request, commit: Boolean): Result {
        companyRepository.findById(request.companyId) ?: return Result.CompanyNotFound

        val period = periodRepository.findAllByCompany(request.companyId)
            .filter { it.allowsPosting() }
            .firstOrNull { !request.anchorDate.isBefore(it.startDate) && !request.anchorDate.isAfter(it.endDate) }
            ?: return Result.NoOpenPeriod

        val accounts = accountRepository.findAllByCompany(request.companyId)
        val accountsByCode = accounts.associateBy { it.code }
        val suspenseAccount = accountsByCode[ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE]

        val existingIdentifiers = fixedAssetRepository.findAllByCompany(request.companyId)
            .mapNotNull { it.identifier?.trim()?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

        val batch = OpeningImportBatch.create(
            OpeningImportDomain.FIXED_ASSETS, request.companyId, request.anchorDate, request.filenameHash, request.createdByEmail
        )

        val seenIdentifiers = mutableSetOf<String>()
        val rowResults = request.rows.map { row ->
            val identifier = row.identifier?.trim()?.takeIf { it.isNotEmpty() }
            val category = runCatching { AssetCategory.valueOf(row.category.uppercase()) }.getOrNull()
            val currency = runCatching { Currency.getInstance(row.currency) }.getOrNull()
            val fixedAssetAccount = accountsByCode[row.fixedAssetAccountCode]

            when {
                identifier != null && (identifier in existingIdentifiers || identifier in seenIdentifiers) -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED, listOf("duplicate identifier '$identifier'")
                )
                row.cost.signum() <= 0 -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED, listOf("cost must be positive")
                )
                row.usefulLifeYears != null && row.usefulLifeYears <= 0 -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED, listOf("useful_life_years, if provided, must be positive")
                )
                category == null -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED,
                    listOf("category must be one of ${AssetCategory.entries.joinToString()}")
                )
                category == AssetCategory.LAND && row.usefulLifeYears != null -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED, listOf("Land cannot have a useful_life_years")
                )
                currency == null -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED, listOf("currency is not a valid ISO currency code")
                )
                row.acquisitionDate.isAfter(request.anchorDate) -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED,
                    listOf("acquisition_date must not be after the batch's anchor date")
                )
                fixedAssetAccount == null -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED,
                    listOf("fixed_asset_account_code '${row.fixedAssetAccountCode}' not found")
                )
                suspenseAccount == null -> OpeningImportRowResult(
                    batch.id, row.rowNumber, OpeningImportRowStatus.REJECTED, listOf("Suspense Account not configured for this company")
                )
                else -> {
                    if (identifier != null) seenIdentifiers += identifier
                    val entityIds = if (commit) {
                        val created = createFixedAssetUseCase.execute(
                            CreateFixedAssetUseCase.Request(
                                companyId = request.companyId,
                                name = row.assetName,
                                category = category,
                                cost = Money(row.cost, currency),
                                acquisitionDate = row.acquisitionDate,
                                usefulLifeYears = row.usefulLifeYears,
                                identifier = identifier,
                                periodId = period.id,
                                fixedAssetAccountId = fixedAssetAccount.id,
                                funding = FixedAssetFundingMethod.AlreadyOwned(suspenseAccount.id),
                                journalSource = JournalSource.IMPORT
                            )
                        )
                        listOfNotNull((created as? CreateFixedAssetUseCase.Result.Success)?.fixedAsset?.id?.value?.toString())
                    } else emptyList()
                    OpeningImportRowResult(batch.id, row.rowNumber, OpeningImportRowStatus.ACCEPTED, resultingEntityIds = entityIds)
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
}
