package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ImportGlBalancesUseCase
import com.theprodeogroup.fish.domain.opening.OpeningImportBatch
import com.theprodeogroup.fish.domain.opening.OpeningImportRowResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.utils.io.core.readText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * `docs/Opening_Figures_CSV_Upload_DDD_Design.md`/`...Requirements_Specification.md`
 * (FR-UP1-UP7) - the HTTP layer for the first opening-figures importer,
 * GL balances. Multipart upload (FR-UP1): one file part named `file`
 * (the CSV) and one form field named `anchor_date` (FR-UP3, ISO-8601).
 *
 * **`/validate` is the dry run (FR-UP4), the plain route is commit
 * (FR-UP5)** - two separate endpoints rather than a `?dryRun=true` query
 * param, matching this codebase's own precedent (no existing route in
 * this file set uses a mode-switching query param - see
 * `ListPurchaseOrdersForFulfillmentUseCase`'s own KDoc in the POP
 * sibling repo for the same "dedicated path, not a mode switch" reasoning).
 *
 * **Wired into [fishModule]'s production instantiation (2026-10-01)** -
 * `productionModule()` now constructs real `ExposedOpeningImportBatchRepository`/
 * `ExposedOpeningImportRowResultRepository` implementations (`V26__opening_import_tables.sql`)
 * and passes a real [ImportGlBalancesUseCase] through. The parameter
 * itself stays nullable in [fishModule] - the same "optional,
 * conditionally registered" shape `vatReturnRepository`/`vatReturnRoutes`
 * already established there - purely so the ~20 existing route-test
 * fixtures that don't exercise this capability keep compiling without
 * every one of them being forced to wire it up too.
 */
fun Route.openingImportRoutes(importGlBalancesUseCase: ImportGlBalancesUseCase, companyRepository: CompanyRepository) {
    post("/companies/{companyId}/opening-imports/gl-balances/validate") {
        call.handleGlBalancesImport(importGlBalancesUseCase, companyRepository, commit = false)
    }
    post("/companies/{companyId}/opening-imports/gl-balances") {
        call.handleGlBalancesImport(importGlBalancesUseCase, companyRepository, commit = true)
    }
}

private suspend fun ApplicationCall.handleGlBalancesImport(
    importGlBalancesUseCase: ImportGlBalancesUseCase,
    companyRepository: CompanyRepository,
    commit: Boolean
) {
    val companyId = parseOpeningImportCompanyId() ?: return
    val tenantId = resolveTenantForCompany(companyId, companyRepository) ?: return
    if (!verifyClaimedTenant(tenantId)) return
    val authorizedCaller = authorizeTenantForWrite(tenantId, companyId) ?: return

    var anchorDateRaw: String? = null
    var filename: String? = null
    var fileContent: String? = null

    receiveMultipart().forEachPart { part ->
        when (part) {
            is PartData.FormItem -> if (part.name == "anchor_date") anchorDateRaw = part.value
            is PartData.FileItem -> {
                filename = part.originalFileName
                fileContent = part.provider().readText(Charsets.UTF_8)
            }
            else -> {}
        }
        part.dispose()
    }

    if (anchorDateRaw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "anchor_date form field is required"))
        return
    }
    val anchorDate = try {
        LocalDate.parse(anchorDateRaw)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "anchor_date must be ISO-8601 (YYYY-MM-DD)"))
        return
    }
    if (fileContent == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "a 'file' part containing the CSV is required"))
        return
    }

    val rows = try {
        CsvParser.parseCsv(fileContent!!).mapIndexed { index, columns ->
            val rowNumber = index + 2 // row 1 is the header
            val accountCode = columns["account_code"]
                ?: throw CsvParseException("row $rowNumber is missing required column 'account_code'")
            val amountRaw = columns["amount"]
                ?: throw CsvParseException("row $rowNumber is missing required column 'amount'")
            val amount = amountRaw.toBigDecimalOrNull()
                ?: throw CsvParseException("row $rowNumber has an invalid amount: '$amountRaw'")
            ImportGlBalancesUseCase.Row(
                rowNumber = rowNumber,
                accountCode = accountCode,
                amount = amount,
                contraAccountCode = columns["contra_account_code"]?.takeIf { it.isNotBlank() }
            )
        }
    } catch (e: CsvParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_csv", e.message))
        return
    }

    val request = ImportGlBalancesUseCase.Request(
        companyId = companyId,
        anchorDate = anchorDate,
        filenameHash = sha256Hex(filename ?: "unknown"),
        createdByEmail = authorizedCaller.email,
        rows = rows
    )

    val result = if (commit) importGlBalancesUseCase.commit(request) else importGlBalancesUseCase.validate(request)
    when (result) {
        is ImportGlBalancesUseCase.Result.CompanyNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        is ImportGlBalancesUseCase.Result.Success ->
            respond(HttpStatusCode.OK, result.toDto())
    }
}

private suspend fun ApplicationCall.parseOpeningImportCompanyId(): CompanyId? {
    val companyIdRaw = parameters["companyId"]
    if (companyIdRaw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
        return null
    }
    val companyUuid = parseUuid(companyIdRaw) ?: return null
    return CompanyId(companyUuid)
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun ImportGlBalancesUseCase.Result.Success.toDto() = OpeningImportBatchResponseDto(
    batchId = batch.id.value.toString(),
    domain = batch.domain.name,
    status = batch.status.name,
    rowCount = batch.rowCount,
    acceptedCount = batch.acceptedCount,
    rejectedCount = batch.rejectedCount,
    needsItemizationCount = batch.needsItemizationCount,
    rows = rowResults.map { it.toDto() }
)

private fun OpeningImportRowResult.toDto() = OpeningImportRowResultDto(
    rowNumber = rowNumber,
    status = status.name,
    errors = errors,
    resultingEntityIds = resultingEntityIds
)

@Serializable
data class OpeningImportRowResultDto(
    val rowNumber: Int,
    val status: String,
    val errors: List<String>,
    val resultingEntityIds: List<String>
)

@Serializable
data class OpeningImportBatchResponseDto(
    val batchId: String,
    val domain: String,
    val status: String,
    val rowCount: Int,
    val acceptedCount: Int,
    val rejectedCount: Int,
    val needsItemizationCount: Int,
    val rows: List<OpeningImportRowResultDto>
)
