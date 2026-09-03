package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.CreateAccountUseCase
import com.theprodeogroup.fish.application.PostJournalEntryResult
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.RecordOpeningBalanceUseCase
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ExpenseClassification
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyKeyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Currency
import java.util.UUID

/**
 * `POST /journal-entries` - wraps [PostJournalEntryUseCase] (docs/DDD_Design.md
 * Section 10.19), one of this first pass's two representative endpoints
 * (the other is `purchaseOrderRoutes`) demonstrating the auth ->
 * routing -> use case -> `Result`-to-HTTP-status pattern before it gets
 * mechanically repeated across the other 12 use cases.
 *
 * **Tenant resolution for authorization**: the request body carries
 * `periodId`, not a `tenantId`/`companyId` directly - `Period.companyId`
 * is looked up first (via [periodRepository]), then `Company.tenantId`
 * (via [companyRepository]), so the caller's `X-Tenant-Id` header is
 * checked against the *actual* owning Tenant of the Period being posted
 * into, not just trusted at face value. Without this, any authenticated
 * caller with *any* Tenant Membership could post into any other
 * Tenant's Period by simply claiming a different Tenant in the request -
 * a real multi-tenancy leak this check closes.
 *
 * **Idempotency-Key support** (docs/GL_Production_Readiness_Plan.md) -
 * routes its final execute-and-respond step through [respondIdempotently].
 *
 * **`GET /companies/{companyId}/accounts` (2026-08-29, user request:
 * "Journals should be created and posted from the GL page")** - the
 * Chart of Accounts, just enough (id/code/name/type) for a manual
 * journal entry form's line-item account picker. Active accounts only,
 * sorted by code. [authorizeTenantForRead], matching every other read
 * route in this package.
 *
 * **`GET /companies/{companyId}/journal-entries` (2026-08-29, "treat
 * the journal posting page like we did the SOP's subpages")** - the
 * "Journal timeline" sub-page, every entry ever posted for this
 * Company, newest first. Mirrors [CreateSalesInvoiceRoutes]'s
 * `GET /companies/{companyId}/sales-invoices`: each line's `accountId`
 * is resolved to its code/name here (via [accountRepository]) rather
 * than left for the client to join against the accounts picker's own
 * list - a reader shouldn't need to cross-reference two responses to
 * see what a past entry actually touched.
 */
fun Route.journalEntryRoutes(
    postJournalEntryUseCase: PostJournalEntryUseCase,
    createAccountUseCase: CreateAccountUseCase,
    recordOpeningBalanceUseCase: RecordOpeningBalanceUseCase,
    periodRepository: PeriodRepository,
    accountRepository: AccountRepository,
    journalEntryRepository: JournalEntryRepository,
    companyRepository: CompanyRepository,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    get("/companies/{companyId}/accounts") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@get
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@get
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId) ?: return@get

        val accounts = accountRepository.findAllByCompany(companyId)
            .filter { it.active }
            .sortedBy { it.code }
            .map { AccountSummaryDto(it.id.value.toString(), it.code, it.name, it.type.name, it.classification?.name) }
        call.respond(accounts)
    }

    /**
     * `POST /companies/{companyId}/accounts` (2026-09-03, "We need work
     * on the Chart of Accounts. There is no setup for it.") - adds one
     * Account beyond whatever [com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate]
     * seeded at onboarding. [authorizeTenantForWrite], not [authorizeTenantForRead] -
     * this changes state.
     */
    post("/companies/{companyId}/accounts") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@post
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@post
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<CreateAccountRequestDto>()
        val type = try {
            AccountType.valueOf(request.type)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "type must be one of ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE"))
            return@post
        }
        val classification = request.classification?.let {
            try {
                AccountClassification.valueOf(it)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "classification must be CURRENT or NON_CURRENT"))
                return@post
            }
        }
        val expenseClassification = request.expenseClassification?.let {
            try {
                ExpenseClassification.valueOf(it)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "expenseClassification must be MANUFACTURING, TRADING, or PROFIT_AND_LOSS"))
                return@post
            }
        }
        val parentId = request.parentId?.let { call.parseUuid(it)?.let(::AccountId) ?: return@post }

        when (
            val result = createAccountUseCase.execute(
                CreateAccountUseCase.Request(companyId, type, classification, request.code, request.name, expenseClassification, parentId)
            )
        ) {
            is CreateAccountUseCase.Result.Success -> call.respond(
                HttpStatusCode.Created,
                AccountSummaryDto(
                    result.account.id.value.toString(), result.account.code, result.account.name,
                    result.account.type.name, result.account.classification?.name
                )
            )
            CreateAccountUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            is CreateAccountUseCase.Result.DuplicateCode ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("duplicate_code", "An account with code '${result.code}' already exists"))
            is CreateAccountUseCase.Result.InvalidAccount ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_account", result.message))
        }
    }

    /**
     * `POST /companies/{companyId}/accounts/{accountId}/opening-balance`
     * (2026-09-03, "the opening figures for the first fiscal year should
     * be available throughout the year") - a standing action, callable
     * any time there's an open Period covering the given date, not just
     * during onboarding. See [RecordOpeningBalanceUseCase]'s own KDoc.
     */
    post("/companies/{companyId}/accounts/{accountId}/opening-balance") {
        val companyIdRaw = call.parameters["companyId"]
        val accountIdRaw = call.parameters["accountId"]
        if (companyIdRaw == null || accountIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId and accountId path parameters are required"))
            return@post
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@post
        val companyId = CompanyId(companyUuid)
        val accountUuid = call.parseUuid(accountIdRaw) ?: return@post
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<RecordOpeningBalanceRequestDto>()
        val amount = request.amount.toBigDecimalOrNull()
        if (amount == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "amount is not a valid decimal"))
            return@post
        }
        val date = try {
            LocalDate.parse(request.date)
        } catch (e: DateTimeParseException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
            return@post
        }

        when (
            val result = recordOpeningBalanceUseCase.execute(
                RecordOpeningBalanceUseCase.Request(companyId, AccountId(accountUuid), amount, date)
            )
        ) {
            is RecordOpeningBalanceUseCase.Result.Success -> call.respond(
                HttpStatusCode.Created,
                OpeningBalanceResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
            )
            RecordOpeningBalanceUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            RecordOpeningBalanceUseCase.Result.AccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
            RecordOpeningBalanceUseCase.Result.OpeningBalanceEquityAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("opening_balance_equity_account_not_configured", "This Company's Chart of Accounts has no Opening Balance Equity account"))
            RecordOpeningBalanceUseCase.Result.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "No open Period covers this date"))
            is RecordOpeningBalanceUseCase.Result.InvalidAmount ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount", result.message))
        }
    }

    get("/companies/{companyId}/journal-entries") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@get
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@get
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId) ?: return@get

        val entries = journalEntryRepository.findAllByCompany(companyId)
            .sortedByDescending { it.date }
            .map { entry ->
                JournalEntryRecordDto(
                    id = entry.id.value.toString(),
                    date = entry.date.toString(),
                    description = entry.description,
                    status = entry.status.name,
                    source = entry.source.name,
                    lines = entry.lines.map { line ->
                        val account = accountRepository.findById(line.accountId)
                        JournalEntryRecordLineDto(
                            accountCode = account?.code ?: "",
                            accountName = account?.name ?: "Unknown account",
                            side = line.side.name,
                            amount = line.amount.amount.toPlainString(),
                            currency = line.amount.currency.currencyCode
                        )
                    }
                )
            }
        call.respond(entries)
    }

    post("/journal-entries") {
        val request = call.receive<PostJournalEntryRequestDto>()

        val periodId = call.parseUuid(request.periodId) ?: return@post
        val period = periodRepository.findById(PeriodId(periodId))
        if (period == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Period not found"))
            return@post
        }
        val tenantId = call.resolveTenantForCompany(period.companyId, companyRepository) ?: return@post

        val claimedTenantIdRaw = call.request.header("X-Tenant-Id")
        if (claimedTenantIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "X-Tenant-Id header is required"))
            return@post
        }
        val claimedTenantId = call.parseUuid(claimedTenantIdRaw) ?: return@post
        if (claimedTenantId != tenantId.value) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "X-Tenant-Id does not own the requested Period"))
            return@post
        }
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val lines = call.parseJournalLines(request.lines) ?: return@post
        val date = try {
            LocalDate.parse(request.date)
        } catch (e: DateTimeParseException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
            return@post
        }
        val source = try {
            JournalSource.valueOf(request.source)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "source must be a valid JournalSource"))
            return@post
        }

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "post-journal-entry", Json.encodeToString(PostJournalEntryRequestDto.serializer(), request)
        ) {
            val result = postJournalEntryUseCase.execute(
                PostJournalEntryUseCase.Request(PeriodId(periodId), date, lines, source, request.description)
            )

            when (result) {
                is PostJournalEntryResult.Success ->
                    HttpStatusCode.Created to Json.encodeToString(
                        JournalEntryResponseDto.serializer(),
                        JournalEntryResponseDto(result.entry.id.value.toString(), result.entry.status.name)
                    )
                is PostJournalEntryResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is PostJournalEntryResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is PostJournalEntryResult.InvalidLines -> HttpStatusCode.BadRequest to errorResponseJson("invalid_lines", result.errors.joinToString())
                is PostJournalEntryResult.AccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("account_not_found", result.accountId.value.toString())
            }
        }
    }
}

/** Parses a UUID string, responding 400 and returning `null` on failure - shared by every route file in this package. */
internal suspend fun ApplicationCall.parseUuid(value: String): UUID? =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'$value' is not a valid UUID"))
        null
    }

/**
 * Parses every [JournalLineDto] into a domain [JournalLine], responding
 * 400 with the specific reason and returning `null` on the *first*
 * invalid line - deliberately not accumulating every line's errors into
 * one response, unlike `JournalEntry.validateLines()`'s own
 * `ValidationResult` (which does accumulate) - this is wire-format
 * parsing, a different concern from that domain-level business-rule
 * validation, which still runs afterward inside the use case itself
 * once every line has already parsed successfully.
 */
private suspend fun ApplicationCall.parseJournalLines(dtos: List<JournalLineDto>): List<JournalLine>? {
    val lines = mutableListOf<JournalLine>()
    for ((index, dto) in dtos.withIndex()) {
        val accountUuid = try {
            UUID.fromString(dto.accountId)
        } catch (e: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines[$index].accountId is not a valid UUID"))
            return null
        }
        val currencyValue = try {
            Currency.getInstance(dto.currency)
        } catch (e: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines[$index].currency is not a valid ISO currency code"))
            return null
        }
        val amountValue = dto.amount.toBigDecimalOrNull()
        if (amountValue == null) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines[$index].amount is not a valid decimal"))
            return null
        }
        val sideValue = try {
            TransactionSide.valueOf(dto.side)
        } catch (e: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines[$index].side must be DEBIT or CREDIT"))
            return null
        }
        val dimensionsValue = mutableMapOf<DimensionType, String>()
        for ((key, dimensionValue) in dto.dimensions) {
            val dimensionType = try {
                DimensionType.valueOf(key)
            } catch (e: IllegalArgumentException) {
                respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines[$index].dimensions has an unknown key '$key'"))
                return null
            }
            dimensionsValue[dimensionType] = dimensionValue
        }
        lines.add(JournalLine(AccountId(accountUuid), Money(amountValue, currencyValue), sideValue, dimensionsValue))
    }
    return lines
}
