package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeAccountBalanceUseCase
import com.theprodeogroup.fish.application.ComputeAccountPostingsUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * `GET /companies/{companyId}/accounts/{accountId}/postings` and `.../balance` (2026-10-19, Femi: Cash and Bank Books becomes its
 * own service): the two reads a sub-ledger builds a book and a reconciliation from. Same read authorization as every report; works
 * for any account of the Company; writes nothing.
 */
fun Route.accountReadsRoutes(
    computeAccountPostingsUseCase: ComputeAccountPostingsUseCase,
    computeAccountBalanceUseCase: ComputeAccountBalanceUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/accounts/{accountId}/postings") {
        val (companyId, accountId) = call.authorizeAccountRead(companyRepository) ?: return@get
        val from = call.optionalDate("from")
        val to = call.optionalDate("to")
        if (call.request.queryParameters["from"] != null && from == null) return@get
        if (call.request.queryParameters["to"] != null && to == null) return@get

        when (val result = computeAccountPostingsUseCase.execute(companyId, accountId, from, to)) {
            is ComputeAccountPostingsUseCase.Result.Success -> call.respond(
                AccountPostingsResponseDto(
                    accountId = result.account.id.value.toString(),
                    code = result.account.code,
                    name = result.account.name,
                    type = result.account.type.name,
                    cashBookKind = result.account.cashBookKind?.name,
                    currency = result.currency.currencyCode,
                    from = result.from.toString(),
                    to = result.to.toString(),
                    openingNet = result.openingNet.amount.toPlainString(),
                    postings = result.postings.map { posting ->
                        val entry = posting.entry
                        AccountPostingDto(
                            entryId = entry.id.value.toString(),
                            periodId = entry.periodId.value.toString(),
                            periodOpen = posting.periodOpen,
                            status = entry.status.name,
                            date = entry.date.toString(),
                            description = entry.description,
                            source = entry.source.name,
                            reversalOfEntryId = entry.reversalOfEntryId?.value?.toString(),
                            reversedByEntryId = posting.reversedByEntryId?.value?.toString(),
                            recordedSeq = entry.recordedSeq,
                            lines = entry.lines.map { line ->
                                val account = result.accountsById[line.accountId]
                                AccountPostingLineDto(
                                    accountId = line.accountId.value.toString(),
                                    code = account?.code ?: "",
                                    name = account?.name ?: "",
                                    side = line.side.name,
                                    amount = line.amount.amount.toPlainString()
                                )
                            }
                        )
                    }
                )
            )
            ComputeAccountPostingsUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeAccountPostingsUseCase.Result.AccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
            ComputeAccountPostingsUseCase.Result.InvalidRange ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_range", "to cannot be before from"))
            is ComputeAccountPostingsUseCase.Result.RangeTooLarge ->
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    CashBookRangeTooLargeDto(
                        "range_too_large",
                        "This range has ${result.rowCount} postings; at most ${result.limit} are returned. Narrow the dates.",
                        result.rowCount, result.limit
                    )
                )
        }
    }

    get("/companies/{companyId}/accounts/{accountId}/balance") {
        val (companyId, accountId) = call.authorizeAccountRead(companyRepository) ?: return@get
        val asOf = call.optionalDate("asOf")
        if (call.request.queryParameters["asOf"] != null && asOf == null) return@get

        when (val result = computeAccountBalanceUseCase.execute(companyId, accountId, asOf)) {
            is ComputeAccountBalanceUseCase.Result.Success -> call.respond(
                AccountBalanceResponseDto(
                    accountId = result.account.id.value.toString(),
                    code = result.account.code,
                    name = result.account.name,
                    type = result.account.type.name,
                    currency = result.currency.currencyCode,
                    asOf = result.asOf.toString(),
                    balance = result.balance.amount.toPlainString(),
                    side = result.side.name
                )
            )
            ComputeAccountBalanceUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeAccountBalanceUseCase.Result.AccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
        }
    }
}

/** Parses the two path ids and authorizes the caller to READ the Company; responds and returns null when anything fails. */
private suspend fun ApplicationCall.authorizeAccountRead(companyRepository: CompanyRepository): Pair<CompanyId, AccountId>? {
    val companyUuid = parseUuid(parameters["companyId"] ?: "") ?: return null
    val accountUuid = parseUuid(parameters["accountId"] ?: "") ?: return null
    val companyId = CompanyId(companyUuid)
    val tenantId = resolveTenantForCompany(companyId, companyRepository) ?: return null
    if (!verifyClaimedTenant(tenantId)) return null
    authorizeTenantForRead(tenantId, companyId) ?: return null
    return companyId to AccountId(accountUuid)
}

/** Parses an optional ISO date query parameter; responds 400 and returns null when it is present but malformed. */
private suspend fun ApplicationCall.optionalDate(name: String): LocalDate? {
    val raw = request.queryParameters[name] ?: return null
    return try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "$name must be a date, YYYY-MM-DD"))
        null
    }
}
