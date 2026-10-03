package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeBankReconciliationResult
import com.theprodeogroup.fish.application.ComputeBankReconciliationUseCase
import com.theprodeogroup.fish.application.MatchBankReconciliationLineResult
import com.theprodeogroup.fish.application.MatchBankReconciliationLineUseCase
import com.theprodeogroup.fish.application.StartBankReconciliationResult
import com.theprodeogroup.fish.application.StartBankReconciliationUseCase
import com.theprodeogroup.fish.application.UnmatchBankReconciliationLineResult
import com.theprodeogroup.fish.application.UnmatchBankReconciliationLineUseCase
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankReconciliationId
import com.theprodeogroup.fish.domain.ledger.BankStatementLineId
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Currency

/**
 * `/companies/{companyId}/bank-reconciliations` (UC-BANKREC-01/02/03/04,
 * docs/GL_Working_Capital_And_Bank_Reconciliation_Software_Requirements_Specification.md,
 * decided 2026-10-03) - start, match, unmatch, and read a Bank
 * Reconciliation. Write routes use [authorizeTenantForWrite]; the read
 * route uses [authorizeTenantForRead], same access-level split as every
 * other posting-vs-reporting pair in this codebase.
 */
fun Route.bankReconciliationRoutes(
    startBankReconciliationUseCase: StartBankReconciliationUseCase,
    matchBankReconciliationLineUseCase: MatchBankReconciliationLineUseCase,
    unmatchBankReconciliationLineUseCase: UnmatchBankReconciliationLineUseCase,
    computeBankReconciliationUseCase: ComputeBankReconciliationUseCase,
    companyRepository: CompanyRepository
) {
    post("/companies/{companyId}/bank-reconciliations") {
        val companyId = call.parseBankReconciliationCompanyId() ?: return@post
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@post

        val request = call.receive<StartBankReconciliationRequestDto>()
        val accountUuid = call.parseUuid(request.accountId) ?: return@post
        val statementDate = call.parseBankReconciliationDate(request.statementDate) ?: return@post
        val currency = call.parseBankReconciliationCurrency(request.currency) ?: return@post
        val statementEndingBalance = call.parseBankReconciliationMoney(request.statementEndingBalance, currency) ?: return@post
        val lines = call.parseStatementLines(request.lines, currency) ?: return@post

        val result = startBankReconciliationUseCase.execute(
            companyId,
            StartBankReconciliationUseCase.Request(AccountId(accountUuid), statementDate, statementEndingBalance, currency, lines)
        )
        when (result) {
            is StartBankReconciliationResult.Success -> call.respond(HttpStatusCode.OK, result.reconciliation.toDto())
            StartBankReconciliationResult.CompanyNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            StartBankReconciliationResult.AccountNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found for this Company"))
            StartBankReconciliationResult.CurrencyMismatch -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("currency_mismatch", "Statement currency must match the Company's base currency"))
        }
    }

    post("/companies/{companyId}/bank-reconciliations/{id}/match") {
        val companyId = call.parseBankReconciliationCompanyId() ?: return@post
        val reconciliationId = call.parseBankReconciliationId() ?: return@post
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@post

        val request = call.receive<MatchBankReconciliationLineRequestDto>()
        val statementLineUuid = call.parseUuid(request.statementLineId) ?: return@post
        val journalEntryUuid = call.parseUuid(request.journalEntryId) ?: return@post

        val result = matchBankReconciliationLineUseCase.execute(
            companyId, reconciliationId, BankStatementLineId(statementLineUuid), JournalEntryId(journalEntryUuid)
        )
        when (result) {
            is MatchBankReconciliationLineResult.Success -> call.respond(HttpStatusCode.OK, result.reconciliation.toDto())
            MatchBankReconciliationLineResult.CompanyNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            MatchBankReconciliationLineResult.ReconciliationNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("reconciliation_not_found", "Bank Reconciliation not found"))
            is MatchBankReconciliationLineResult.InvalidMatch -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("invalid_match", result.message))
        }
    }

    post("/companies/{companyId}/bank-reconciliations/{id}/unmatch") {
        val companyId = call.parseBankReconciliationCompanyId() ?: return@post
        val reconciliationId = call.parseBankReconciliationId() ?: return@post
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@post

        val request = call.receive<MatchBankReconciliationLineRequestDto>()
        val statementLineUuid = call.parseUuid(request.statementLineId) ?: return@post
        val journalEntryUuid = call.parseUuid(request.journalEntryId) ?: return@post

        val result = unmatchBankReconciliationLineUseCase.execute(
            companyId, reconciliationId, BankStatementLineId(statementLineUuid), JournalEntryId(journalEntryUuid)
        )
        when (result) {
            is UnmatchBankReconciliationLineResult.Success -> call.respond(HttpStatusCode.OK, result.reconciliation.toDto())
            UnmatchBankReconciliationLineResult.CompanyNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            UnmatchBankReconciliationLineResult.ReconciliationNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("reconciliation_not_found", "Bank Reconciliation not found"))
            is UnmatchBankReconciliationLineResult.InvalidUnmatch -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("invalid_unmatch", result.message))
        }
    }

    get("/companies/{companyId}/bank-reconciliations/{id}") {
        val companyId = call.parseBankReconciliationCompanyId() ?: return@get
        val reconciliationId = call.parseBankReconciliationId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = computeBankReconciliationUseCase.execute(companyId, reconciliationId)) {
            is ComputeBankReconciliationResult.Success -> call.respond(HttpStatusCode.OK, result.reconciliation.toDto())
            ComputeBankReconciliationResult.CompanyNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeBankReconciliationResult.ReconciliationNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("reconciliation_not_found", "Bank Reconciliation not found"))
        }
    }
}

private fun BankReconciliation.toDto(): BankReconciliationResponseDto = BankReconciliationResponseDto(
    id = id.value.toString(),
    accountId = accountId.value.toString(),
    statementDate = statementDate.toString(),
    statementEndingBalance = statementEndingBalance.amount.toPlainString(),
    currency = currency.currencyCode,
    statementLines = statementLines.map {
        BankStatementLineDto(it.id.value.toString(), it.date.toString(), it.amount.amount.toPlainString(), it.direction.name, it.description)
    },
    unmatchedStatementLineIds = unmatchedStatementLines.map { it.id.value.toString() },
    unmatchedJournalEntryIds = unmatchedEntries.map { it.id.value.toString() },
    matches = currentMatches.map { (statementLineId, journalEntryId) ->
        BankReconciliationMatchDto(statementLineId.value.toString(), journalEntryId.value.toString())
    },
    isFullyReconciled = isFullyReconciled
)

private suspend fun ApplicationCall.parseBankReconciliationCompanyId(): CompanyId? {
    val raw = parameters["companyId"]
    if (raw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
        return null
    }
    val uuid = parseUuid(raw) ?: return null
    return CompanyId(uuid)
}

private suspend fun ApplicationCall.parseBankReconciliationId(): BankReconciliationId? {
    val raw = parameters["id"]
    if (raw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "id path parameter is required"))
        return null
    }
    val uuid = parseUuid(raw) ?: return null
    return BankReconciliationId(uuid)
}

private suspend fun ApplicationCall.parseBankReconciliationDate(value: String): LocalDate? =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
        null
    }

private suspend fun ApplicationCall.parseBankReconciliationCurrency(value: String): Currency? =
    try {
        Currency.getInstance(value)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "currency is not a valid ISO currency code"))
        null
    }

private suspend fun ApplicationCall.parseBankReconciliationMoney(amount: String, currency: Currency): Money? {
    val amountValue = amount.toBigDecimalOrNull()
    if (amountValue == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "amount is not a valid decimal"))
        return null
    }
    return Money(amountValue, currency)
}

private suspend fun ApplicationCall.parseStatementLines(lines: List<StatementLineInputDto>, currency: Currency): List<StartBankReconciliationUseCase.StatementLineInput>? {
    val parsed = mutableListOf<StartBankReconciliationUseCase.StatementLineInput>()
    for (line in lines) {
        val date = parseBankReconciliationDate(line.date) ?: return null
        val amount = parseBankReconciliationMoney(line.amount, currency) ?: return null
        val direction = try {
            CashDirection.valueOf(line.direction)
        } catch (e: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${line.direction}' is not a valid direction"))
            return null
        }
        parsed.add(StartBankReconciliationUseCase.StatementLineInput(date, amount, direction, line.description))
    }
    return parsed
}
