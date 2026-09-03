package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AssessFixedAssetImpairmentResult
import com.theprodeogroup.fish.application.AssessFixedAssetImpairmentUseCase
import com.theprodeogroup.fish.application.ComputeFixedAssetRegisterUseCase
import com.theprodeogroup.fish.application.CreateFixedAssetUseCase
import com.theprodeogroup.fish.application.DisposeFixedAssetResult
import com.theprodeogroup.fish.application.DisposeFixedAssetUseCase
import com.theprodeogroup.fish.application.RecordFixedAssetDepreciationResult
import com.theprodeogroup.fish.application.RecordFixedAssetDepreciationUseCase
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetId
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRepository
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyKeyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Currency

/**
 * The Fixed Asset Register's HTTP layer (docs/DDD_Design.md Section
 * 2.8) - `FixedAsset` was domain-only since 2026-08-12; this is its
 * first persistence/use-case/routing wiring (2026-09-03, "Fixed Asset
 * Register"), scoped deliberately to GL-internal acquisition/
 * depreciation/impairment/disposal only. Cross-module PO-driven
 * acquisition, Asset-Under-Construction staging, and disposal-via-SOP
 * are a separate, larger follow-on - confirmed with the user, not
 * built here.
 *
 * **`POST /fixed-assets`** carries `companyId` in the body (same shape
 * as `createSalesInvoiceRoutes`'s `POST /sales/create-invoice` - no
 * owning aggregate to derive it from otherwise). The other three
 * mutation routes are scoped under `/fixed-assets/{fixedAssetId}` and
 * carry `periodId` instead - tenant is resolved via
 * `Period.companyId` -> `Company.tenantId`, the same "resolve from the
 * request body, not a URL path" shape `journalEntryRoutes`'s
 * `POST /journal-entries` established, since a `FixedAssetId` alone
 * doesn't carry a `companyId` a client could put in the URL either.
 *
 * **`GET /companies/{companyId}/reports/fixed-asset-register`** lives
 * here (not `reportsRoutes`) for cohesion with this aggregate's own
 * mutation routes, even though its URL shape matches the other Reports
 * sub-page endpoints.
 */
fun Route.fixedAssetRoutes(
    createFixedAssetUseCase: CreateFixedAssetUseCase,
    recordFixedAssetDepreciationUseCase: RecordFixedAssetDepreciationUseCase,
    assessFixedAssetImpairmentUseCase: AssessFixedAssetImpairmentUseCase,
    disposeFixedAssetUseCase: DisposeFixedAssetUseCase,
    computeFixedAssetRegisterUseCase: ComputeFixedAssetRegisterUseCase,
    fixedAssetRepository: FixedAssetRepository,
    periodRepository: PeriodRepository,
    companyRepository: CompanyRepository,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    get("/companies/{companyId}/fixed-assets") {
        val companyId = call.parseFixedAssetCompanyId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId) ?: return@get

        call.respond(fixedAssetRepository.findAllByCompany(companyId).map { it.toDto() })
    }

    get("/companies/{companyId}/reports/fixed-asset-register") {
        val companyId = call.parseFixedAssetCompanyId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId) ?: return@get

        when (val result = computeFixedAssetRegisterUseCase.execute(companyId)) {
            is ComputeFixedAssetRegisterUseCase.Result.Success -> {
                val register = result.register
                call.respond(
                    FixedAssetRegisterResponseDto(
                        currency = register.currency.currencyCode,
                        lines = register.lines.map {
                            FixedAssetSummaryDto(
                                id = it.id.value.toString(),
                                companyId = companyId.value.toString(),
                                name = it.name,
                                category = it.category.name,
                                cost = it.cost.amount.toPlainString(),
                                currency = it.cost.currency.currencyCode,
                                acquisitionDate = it.acquisitionDate.toString(),
                                usefulLifeYears = it.usefulLifeYears,
                                accumulatedDepreciation = it.accumulatedDepreciation.amount.toPlainString(),
                                accumulatedImpairmentLoss = it.accumulatedImpairmentLoss.amount.toPlainString(),
                                netBookValue = it.netBookValue.amount.toPlainString(),
                                carryingAmount = it.carryingAmount.amount.toPlainString(),
                                isDisposed = it.isDisposed
                            )
                        },
                        totalCost = register.totalCost.amount.toPlainString(),
                        totalAccumulatedDepreciation = register.totalAccumulatedDepreciation.amount.toPlainString(),
                        totalAccumulatedImpairmentLoss = register.totalAccumulatedImpairmentLoss.amount.toPlainString(),
                        totalNetBookValue = register.totalNetBookValue.amount.toPlainString(),
                        totalCarryingAmount = register.totalCarryingAmount.amount.toPlainString()
                    )
                )
            }
            ComputeFixedAssetRegisterUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeFixedAssetRegisterUseCase.Result.NoFixedAssetsForCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_fixed_assets", "This Company has no Fixed Asset Register entries"))
        }
    }

    post("/fixed-assets") {
        val request = call.receive<CreateFixedAssetRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val category = try {
            AssetCategory.valueOf(request.category.uppercase())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "category must be one of LAND, BUILDINGS, EQUIPMENT, VEHICLES"))
            return@post
        }
        val cost = call.parseMoney(request.cost, request.currency) ?: return@post
        val acquisitionDate = call.parseLocalDate(request.acquisitionDate) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "create-fixed-asset",
            Json.encodeToString(CreateFixedAssetRequestDto.serializer(), request)
        ) {
            val result = createFixedAssetUseCase.execute(
                CreateFixedAssetUseCase.Request(companyId, request.name, category, cost, acquisitionDate, request.usefulLifeYears)
            )
            when (result) {
                is CreateFixedAssetUseCase.Result.Success ->
                    HttpStatusCode.OK to Json.encodeToString(FixedAssetSummaryDto.serializer(), result.fixedAsset.toDto())
                is CreateFixedAssetUseCase.Result.CompanyNotFound -> HttpStatusCode.NotFound to errorResponseJson("company_not_found")
                is CreateFixedAssetUseCase.Result.InvalidFixedAsset -> HttpStatusCode.BadRequest to errorResponseJson("invalid_fixed_asset", result.message)
            }
        }
    }

    post("/fixed-assets/{fixedAssetId}/record-depreciation") {
        val fixedAssetId = call.parseFixedAssetId() ?: return@post
        val request = call.receive<RecordFixedAssetDepreciationRequestDto>()
        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val period = periodRepository.findById(PeriodId(periodUuid))
        if (period == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Period not found"))
            return@post
        }
        val tenantId = call.resolveTenantForCompany(period.companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val depreciationExpenseAccountId = call.parseUuid(request.depreciationExpenseAccountId) ?: return@post
        val accumulatedDepreciationAccountId = call.parseUuid(request.accumulatedDepreciationAccountId) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "record-fixed-asset-depreciation",
            Json.encodeToString(RecordFixedAssetDepreciationRequestDto.serializer(), request)
        ) {
            val result = recordFixedAssetDepreciationUseCase.execute(
                RecordFixedAssetDepreciationUseCase.Request(
                    fixedAssetId, AccountId(depreciationExpenseAccountId), AccountId(accumulatedDepreciationAccountId),
                    period.id, date
                )
            )
            when (result) {
                is RecordFixedAssetDepreciationResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        FixedAssetPostingResponseDto.serializer(),
                        FixedAssetPostingResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name, result.fixedAsset.toDto())
                    )
                is RecordFixedAssetDepreciationResult.FixedAssetNotFound -> HttpStatusCode.NotFound to errorResponseJson("fixed_asset_not_found")
                is RecordFixedAssetDepreciationResult.NoChangeNeeded -> HttpStatusCode.Conflict to errorResponseJson("no_change_needed")
                is RecordFixedAssetDepreciationResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is RecordFixedAssetDepreciationResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is RecordFixedAssetDepreciationResult.DepreciationExpenseAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("depreciation_expense_account_not_found", result.accountId.value.toString())
                is RecordFixedAssetDepreciationResult.AccumulatedDepreciationAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("accumulated_depreciation_account_not_found", result.accountId.value.toString())
            }
        }
    }

    post("/fixed-assets/{fixedAssetId}/assess-impairment") {
        val fixedAssetId = call.parseFixedAssetId() ?: return@post
        val request = call.receive<AssessFixedAssetImpairmentRequestDto>()
        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val period = periodRepository.findById(PeriodId(periodUuid))
        if (period == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Period not found"))
            return@post
        }
        val tenantId = call.resolveTenantForCompany(period.companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val recoverableAmount = call.parseMoney(request.recoverableAmount, request.currency) ?: return@post
        val impairmentExpenseAccountId = call.parseUuid(request.impairmentExpenseAccountId) ?: return@post
        val accumulatedImpairmentAccountId = call.parseUuid(request.accumulatedImpairmentAccountId) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "assess-fixed-asset-impairment",
            Json.encodeToString(AssessFixedAssetImpairmentRequestDto.serializer(), request)
        ) {
            val result = assessFixedAssetImpairmentUseCase.execute(
                AssessFixedAssetImpairmentUseCase.Request(
                    fixedAssetId, recoverableAmount, AccountId(impairmentExpenseAccountId), AccountId(accumulatedImpairmentAccountId),
                    period.id, date
                )
            )
            when (result) {
                is AssessFixedAssetImpairmentResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        FixedAssetPostingResponseDto.serializer(),
                        FixedAssetPostingResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name, result.fixedAsset.toDto())
                    )
                is AssessFixedAssetImpairmentResult.FixedAssetNotFound -> HttpStatusCode.NotFound to errorResponseJson("fixed_asset_not_found")
                is AssessFixedAssetImpairmentResult.NoChangeNeeded -> HttpStatusCode.Conflict to errorResponseJson("no_change_needed")
                is AssessFixedAssetImpairmentResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is AssessFixedAssetImpairmentResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is AssessFixedAssetImpairmentResult.ImpairmentExpenseAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("impairment_expense_account_not_found", result.accountId.value.toString())
                is AssessFixedAssetImpairmentResult.AccumulatedImpairmentAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("accumulated_impairment_account_not_found", result.accountId.value.toString())
            }
        }
    }

    post("/fixed-assets/{fixedAssetId}/dispose") {
        val fixedAssetId = call.parseFixedAssetId() ?: return@post
        val request = call.receive<DisposeFixedAssetRequestDto>()
        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val period = periodRepository.findById(PeriodId(periodUuid))
        if (period == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Period not found"))
            return@post
        }
        val tenantId = call.resolveTenantForCompany(period.companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val proceeds = call.parseMoney(request.proceeds, request.currency) ?: return@post
        val cashAccountId = call.parseUuid(request.cashAccountId) ?: return@post
        val fixedAssetAccountId = call.parseUuid(request.fixedAssetAccountId) ?: return@post
        val accumulatedDepreciationAccountId = call.parseUuid(request.accumulatedDepreciationAccountId) ?: return@post
        val saleOfFixedAssetAccountId = call.parseUuid(request.saleOfFixedAssetAccountId) ?: return@post
        val accumulatedImpairmentAccountId = request.accumulatedImpairmentAccountId?.let { call.parseUuid(it) ?: return@post }
        val date = call.parseLocalDate(request.date) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "dispose-fixed-asset",
            Json.encodeToString(DisposeFixedAssetRequestDto.serializer(), request)
        ) {
            val result = disposeFixedAssetUseCase.execute(
                DisposeFixedAssetUseCase.Request(
                    fixedAssetId, proceeds, AccountId(cashAccountId), AccountId(fixedAssetAccountId),
                    AccountId(accumulatedDepreciationAccountId), AccountId(saleOfFixedAssetAccountId),
                    period.id, date, accumulatedImpairmentAccountId?.let { AccountId(it) }
                )
            )
            when (result) {
                is DisposeFixedAssetResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        FixedAssetPostingResponseDto.serializer(),
                        FixedAssetPostingResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name, result.fixedAsset.toDto())
                    )
                is DisposeFixedAssetResult.FixedAssetNotFound -> HttpStatusCode.NotFound to errorResponseJson("fixed_asset_not_found")
                is DisposeFixedAssetResult.AlreadyDisposed -> HttpStatusCode.Conflict to errorResponseJson("already_disposed")
                is DisposeFixedAssetResult.AccumulatedImpairmentAccountRequired ->
                    HttpStatusCode.BadRequest to errorResponseJson("accumulated_impairment_account_required")
                is DisposeFixedAssetResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is DisposeFixedAssetResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is DisposeFixedAssetResult.CashAccountNotFound -> HttpStatusCode.NotFound to errorResponseJson("cash_account_not_found", result.accountId.value.toString())
                is DisposeFixedAssetResult.FixedAssetAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("fixed_asset_account_not_found", result.accountId.value.toString())
                is DisposeFixedAssetResult.AccumulatedDepreciationAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("accumulated_depreciation_account_not_found", result.accountId.value.toString())
                is DisposeFixedAssetResult.SaleOfFixedAssetAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("sale_of_fixed_asset_account_not_found", result.accountId.value.toString())
                is DisposeFixedAssetResult.AccumulatedImpairmentAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("accumulated_impairment_account_not_found", result.accountId.value.toString())
            }
        }
    }
}

private fun FixedAsset.toDto() = FixedAssetSummaryDto(
    id = id.value.toString(),
    companyId = companyId.value.toString(),
    name = name,
    category = category.name,
    cost = cost.amount.toPlainString(),
    currency = cost.currency.currencyCode,
    acquisitionDate = acquisitionDate.toString(),
    usefulLifeYears = usefulLifeYears,
    accumulatedDepreciation = accumulatedDepreciation.amount.toPlainString(),
    accumulatedImpairmentLoss = accumulatedImpairmentLoss.amount.toPlainString(),
    netBookValue = netBookValue.amount.toPlainString(),
    carryingAmount = carryingAmount.amount.toPlainString(),
    isDisposed = isDisposed
)

private suspend fun ApplicationCall.parseFixedAssetCompanyId(): CompanyId? {
    val companyIdRaw = parameters["companyId"]
    if (companyIdRaw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
        return null
    }
    val companyUuid = parseUuid(companyIdRaw) ?: return null
    return CompanyId(companyUuid)
}

private suspend fun ApplicationCall.parseFixedAssetId(): FixedAssetId? {
    val raw = parameters["fixedAssetId"]
    if (raw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "fixedAssetId path parameter is required"))
        return null
    }
    val uuid = parseUuid(raw) ?: return null
    return FixedAssetId(uuid)
}

/** Parses an amount+currency pair into a domain [Money], responding 400 and returning `null` on failure. */
private suspend fun ApplicationCall.parseMoney(amount: String, currency: String): Money? {
    val amountValue = amount.toBigDecimalOrNull()
    if (amountValue == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "amount is not a valid decimal"))
        return null
    }
    val currencyValue = try {
        Currency.getInstance(currency)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "currency is not a valid ISO currency code"))
        return null
    }
    return Money(amountValue, currencyValue)
}

/** Parses an ISO-8601 date string, responding 400 and returning `null` on failure. */
private suspend fun ApplicationCall.parseLocalDate(value: String): LocalDate? =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
        null
    }
