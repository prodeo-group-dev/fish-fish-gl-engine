package com.theprodeogroup.fish.domain.opening

import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `docs/Opening_Figures_CSV_Upload_DDD_Design.md` Section 3 - which
 * domain-specific import a batch belongs to. Only [GL_BALANCES] has a
 * real use case wired up so far ([com.theprodeogroup.fish.application.ImportGlBalancesUseCase]) -
 * the design doc's own build order (Section 7) does GL balances first
 * specifically because it exercises the Suspense-redirect mechanism
 * every other domain's reasoning leans on.
 */
enum class OpeningImportDomain {
    GL_BALANCES,
    FIXED_ASSETS,
    STOCK,
    AR,
    AP
}

/**
 * [DRAFT]/[VALIDATED]/[COMMITTED] (and [FAILED]) are the only states a
 * synchronous batch (<=2,000 rows, design doc decision 5) ever
 * observes - a caller's `validate` call moves `DRAFT -> VALIDATED` and
 * returns, `commit` moves `VALIDATED -> COMMITTED` and returns.
 * [VALIDATING]/[COMMITTING] exist for the design doc's own future
 * async (>2,000 rows) path, **not yet built** - no background-job
 * mechanism exists anywhere in this codebase today, and inventing one
 * for this alone would be scope well beyond "start the next backlog
 * item." Flagged here rather than silently built half-complete.
 */
enum class OpeningImportBatchStatus {
    DRAFT,
    VALIDATING,
    VALIDATED,
    COMMITTING,
    COMMITTED,
    FAILED
}

enum class OpeningImportRowStatus {
    ACCEPTED,
    REJECTED,
    NEEDS_ITEMIZATION
}

@JvmInline
value class OpeningImportBatchId(val value: UUID) {
    companion object {
        fun generate(): OpeningImportBatchId = OpeningImportBatchId(UUID.randomUUID())
    }
}

/**
 * One upload. [createdByEmail] rather than the design doc's own
 * placeholder `UserId` - GL has no `User` aggregate of its own (EA
 * owns identity), so this records the authenticated caller's email
 * claim, the same identifier GL's own auth layer already keys on
 * elsewhere.
 */
class OpeningImportBatch private constructor(
    val id: OpeningImportBatchId,
    val domain: OpeningImportDomain,
    val companyId: CompanyId,
    val anchorDate: LocalDate,
    val filenameHash: String,
    var status: OpeningImportBatchStatus,
    var rowCount: Int,
    var acceptedCount: Int,
    var rejectedCount: Int,
    var needsItemizationCount: Int,
    val createdByEmail: String,
    val createdAt: Instant,
    var committedAt: Instant?
) {
    companion object {
        fun create(
            domain: OpeningImportDomain,
            companyId: CompanyId,
            anchorDate: LocalDate,
            filenameHash: String,
            createdByEmail: String,
            now: Instant = Instant.now()
        ): OpeningImportBatch = OpeningImportBatch(
            id = OpeningImportBatchId.generate(),
            domain = domain,
            companyId = companyId,
            anchorDate = anchorDate,
            filenameHash = filenameHash,
            status = OpeningImportBatchStatus.DRAFT,
            rowCount = 0,
            acceptedCount = 0,
            rejectedCount = 0,
            needsItemizationCount = 0,
            createdByEmail = createdByEmail,
            createdAt = now,
            committedAt = null
        )

        internal fun reconstitute(
            id: OpeningImportBatchId,
            domain: OpeningImportDomain,
            companyId: CompanyId,
            anchorDate: LocalDate,
            filenameHash: String,
            status: OpeningImportBatchStatus,
            rowCount: Int,
            acceptedCount: Int,
            rejectedCount: Int,
            needsItemizationCount: Int,
            createdByEmail: String,
            createdAt: Instant,
            committedAt: Instant?
        ): OpeningImportBatch = OpeningImportBatch(
            id, domain, companyId, anchorDate, filenameHash, status,
            rowCount, acceptedCount, rejectedCount, needsItemizationCount,
            createdByEmail, createdAt, committedAt
        )
    }

    /** Records the outcome of a synchronous validate pass (decision 5 - no intermediate VALIDATING state observed). */
    fun markValidated(rowResults: List<OpeningImportRowResult>) {
        rowCount = rowResults.size
        acceptedCount = rowResults.count { it.status == OpeningImportRowStatus.ACCEPTED }
        rejectedCount = rowResults.count { it.status == OpeningImportRowStatus.REJECTED }
        needsItemizationCount = rowResults.count { it.status == OpeningImportRowStatus.NEEDS_ITEMIZATION }
        status = OpeningImportBatchStatus.VALIDATED
    }

    fun markCommitted(now: Instant = Instant.now()) {
        status = OpeningImportBatchStatus.COMMITTED
        committedAt = now
    }

    fun markFailed() {
        status = OpeningImportBatchStatus.FAILED
    }
}

/** [resultingEntityIds] holds e.g. a posted `JournalEntry`'s id - empty unless [status] is ACCEPTED or NEEDS_ITEMIZATION. */
data class OpeningImportRowResult(
    val batchId: OpeningImportBatchId,
    val rowNumber: Int,
    val status: OpeningImportRowStatus,
    val errors: List<String> = emptyList(),
    val resultingEntityIds: List<String> = emptyList()
)
