package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ImportFixedAssetsUseCase
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.core.readText
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * `docs/Opening_Figures_CSV_Upload_DDD_Design.md`/`...Requirements_Specification.md`
 * (FR-UP1-UP7) - the HTTP layer for the opening-figures importers. Each
 * domain gets its own `POST /companies/{companyId}/opening-imports/{domain}[/validate]`
 * pair, sharing one multipart-upload-and-CSV-parse helper
 * ([readMultipartCsvUpload]) since FR-UP1-UP3 (multipart file + an
 * `anchor_date` form field, CSV grammar) are identical across every
 * domain - only the per-row column shape and the use case called differ.
 *
 * **`/validate` is the dry run (FR-UP4), the plain route is commit
 * (FR-UP5)** - two separate endpoints rather than a `?dryRun=true` query
 * param, matching this codebase's own precedent (no existing route in
 * this file set uses a mode-switching query param).
 *
 * **GL balances wired into [fishModule]'s production instantiation
 * (2026-10-01)**; **Fixed Assets added the same day** - both share the
 * same nullable, conditionally-registered `fishModule` shape
 * `vatReturnRepository`/`vatReturnRoutes` already established, so the
 * ~20 existing route-test fixtures that don't exercise this capability
 * keep compiling unchanged.
 */
fun Route.openingImportRoutes(
    importGlBalancesUseCase: ImportGlBalancesUseCase,
    importFixedAssetsUseCase: ImportFixedAssetsUseCase,
    companyRepository: CompanyRepository
) {
    post("/companies/{companyId}/opening-imports/gl-balances/validate") {
        call.handleGlBalancesImport(importGlBalancesUseCase, companyRepository, commit = false)
    }
    post("/companies/{companyId}/opening-imports/gl-balances") {
        call.handleGlBalancesImport(importGlBalancesUseCase, companyRepository, commit = true)
    }
    post("/companies/{companyId}/opening-imports/fixed-assets/validate") {
        call.handleFixedAssetsImport(importFixedAssetsUseCase, companyRepository, commit = false)
    }
    post("/companies/{companyId}/opening-imports/fixed-assets") {
        call.handleFixedAssetsImport(importFixedAssetsUseCase, companyRepository, commit = true)
    }
}

/** One parsed multipart upload: the batch-level `anchor_date` (FR-UP3) and the CSV's data rows, already header-mapped. Responds and returns `null` itself on any failure, so callers just need to bail on `null`. */
private class MultipartCsvUpload(val anchorDate: LocalDate, val filenameHash: String, val csvRows: List<Map<String, String>>)

private suspend fun ApplicationCall.readMultipartCsvUpload(): MultipartCsvUpload? {
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
        return null
    }
    val anchorDate = try {
        LocalDate.parse(anchorDateRaw)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "anchor_date must be ISO-8601 (YYYY-MM-DD)"))
        return null
    }
    val content = fileContent
    if (content == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "a 'file' part containing the CSV is required"))
        return null
    }

    val csvRows = try {
        CsvParser.parseCsv(content)
    } catch (e: CsvParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_csv", e.message))
        return null
    }

    return MultipartCsvUpload(anchorDate, sha256Hex(filename ?: "unknown"), csvRows)
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
    val upload = readMultipartCsvUpload() ?: return

    val rows = try {
        upload.csvRows.mapIndexed { index, columns ->
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
        anchorDate = upload.anchorDate,
        filenameHash = upload.filenameHash,
        createdByEmail = authorizedCaller.email,
        rows = rows
    )

    val result = if (commit) importGlBalancesUseCase.commit(request) else importGlBalancesUseCase.validate(request)
    when (result) {
        is ImportGlBalancesUseCase.Result.CompanyNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        is ImportGlBalancesUseCase.Result.Success ->
            respond(HttpStatusCode.OK, result.batch.toDto(result.rowResults))
    }
}

private suspend fun ApplicationCall.handleFixedAssetsImport(
    importFixedAssetsUseCase: ImportFixedAssetsUseCase,
    companyRepository: CompanyRepository,
    commit: Boolean
) {
    val companyId = parseOpeningImportCompanyId() ?: return
    val tenantId = resolveTenantForCompany(companyId, companyRepository) ?: return
    if (!verifyClaimedTenant(tenantId)) return
    val authorizedCaller = authorizeTenantForWrite(tenantId, companyId) ?: return
    val upload = readMultipartCsvUpload() ?: return

    val rows = try {
        upload.csvRows.mapIndexed { index, columns ->
            val rowNumber = index + 2 // row 1 is the header
            fun required(column: String) = columns[column]?.takeIf { it.isNotBlank() }
                ?: throw CsvParseException("row $rowNumber is missing required column '$column'")
            val cost = required("cost").toBigDecimalOrNull()
                ?: throw CsvParseException("row $rowNumber has an invalid cost: '${columns["cost"]}'")
            val acquisitionDate = try {
                LocalDate.parse(required("acquisition_date"))
            } catch (e: DateTimeParseException) {
                throw CsvParseException("row $rowNumber has an invalid acquisition_date: '${columns["acquisition_date"]}'")
            }
            val usefulLifeYears = columns["useful_life_years"]?.takeIf { it.isNotBlank() }?.let {
                it.toIntOrNull() ?: throw CsvParseException("row $rowNumber has an invalid useful_life_years: '$it'")
            }
            ImportFixedAssetsUseCase.Row(
                rowNumber = rowNumber,
                assetName = required("asset_name"),
                category = required("category"),
                acquisitionDate = acquisitionDate,
                cost = cost,
                currency = required("currency"),
                usefulLifeYears = usefulLifeYears,
                identifier = columns["identifier"]?.takeIf { it.isNotBlank() },
                fixedAssetAccountCode = required("fixed_asset_account_code")
            )
        }
    } catch (e: CsvParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_csv", e.message))
        return
    }

    val request = ImportFixedAssetsUseCase.Request(
        companyId = companyId,
        anchorDate = upload.anchorDate,
        filenameHash = upload.filenameHash,
        createdByEmail = authorizedCaller.email,
        rows = rows
    )

    val result = if (commit) importFixedAssetsUseCase.commit(request) else importFixedAssetsUseCase.validate(request)
    when (result) {
        is ImportFixedAssetsUseCase.Result.CompanyNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        is ImportFixedAssetsUseCase.Result.NoOpenPeriod ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "No open Period covers the anchor_date"))
        is ImportFixedAssetsUseCase.Result.Success ->
            respond(HttpStatusCode.OK, result.batch.toDto(result.rowResults))
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

private fun OpeningImportBatch.toDto(rowResults: List<OpeningImportRowResult>) = OpeningImportBatchResponseDto(
    batchId = id.value.toString(),
    domain = domain.name,
    status = status.name,
    rowCount = rowCount,
    acceptedCount = acceptedCount,
    rejectedCount = rejectedCount,
    needsItemizationCount = needsItemizationCount,
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
