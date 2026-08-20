package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.PostPayRunResult
import com.theprodeogroup.fish.application.PostPayRunUseCase
import com.theprodeogroup.fish.application.RemeasureLeaveAccrualResult
import com.theprodeogroup.fish.application.RemeasureLeaveAccrualUseCase
import com.theprodeogroup.fish.application.UtilizeLeaveAccrualResult
import com.theprodeogroup.fish.application.UtilizeLeaveAccrualUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualRepository
import com.theprodeogroup.fish.domain.payroll.PayRunId
import com.theprodeogroup.fish.domain.payroll.PayRunRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Currency

/**
 * The HR/Payroll system's posting interface, opened up over HTTP
 * (docs/DDD_Design.md Section 10.20) - `PostPayRunUseCase`/
 * `RemeasureLeaveAccrualUseCase`/`UtilizeLeaveAccrualUseCase` are
 * confirmed as exactly the fixed contract the separate, not-built-here
 * HR/Payroll system calls into (the same relationship Lending/Scrip/
 * Osusu already have to this repo) - these three routes are that
 * contract's actual HTTP surface, following the identical auth ->
 * tenant-ownership -> use-case -> `Result`-to-HTTP pattern already
 * established by `journalEntryRoutes`/`purchaseOrderRoutes` (Section
 * 10.19), applied mechanically now that the pattern is proven.
 *
 * **Three routes, not two** - `RemeasureLeaveAccrualUseCase` and
 * `UtilizeLeaveAccrualUseCase` are separate use cases (Section 10.18's
 * own reasoning: genuinely distinct domain operations, matching
 * `PostJournalEntryUseCase`/`ReverseJournalEntryUseCase`'s split), so
 * they get separate routes rather than one route with an operation
 * discriminator.
 */
fun Route.payrollRoutes(
    postPayRunUseCase: PostPayRunUseCase,
    payRunRepository: PayRunRepository,
    remeasureLeaveAccrualUseCase: RemeasureLeaveAccrualUseCase,
    utilizeLeaveAccrualUseCase: UtilizeLeaveAccrualUseCase,
    leaveAccrualRepository: LeaveAccrualRepository,
    companyRepository: CompanyRepository
) {
    post("/pay-runs/{payRunId}/post") {
        val payRunIdRaw = call.parameters["payRunId"]
        if (payRunIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "payRunId path parameter is required"))
            return@post
        }
        val payRunUuid = call.parseUuid(payRunIdRaw) ?: return@post
        val payRun = payRunRepository.findById(PayRunId(payRunUuid))
        if (payRun == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "PayRun not found"))
            return@post
        }
        val tenantId = call.resolveTenantForCompany(payRun.companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<PostPayRunRequestDto>()
        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val wagesAccountUuid = call.parseUuid(request.wagesExpenseAccountId) ?: return@post
        val salariesAccountUuid = call.parseUuid(request.salariesExpenseAccountId) ?: return@post
        val cashAccountUuid = call.parseUuid(request.cashAccountId) ?: return@post

        val result = postPayRunUseCase.execute(
            PostPayRunUseCase.Request(
                PayRunId(payRunUuid), PeriodId(periodUuid),
                AccountId(wagesAccountUuid), AccountId(salariesAccountUuid), AccountId(cashAccountUuid)
            )
        )

        when (result) {
            is PostPayRunResult.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    PostPayRunResponseDto(result.payRun.id.value.toString(), result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                )
            is PostPayRunResult.PayRunNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("pay_run_not_found"))
            is PostPayRunResult.PeriodNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("period_not_found"))
            is PostPayRunResult.PeriodNotOpen -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open"))
            is PostPayRunResult.WagesExpenseAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("wages_expense_account_not_found", result.accountId.value.toString()))
            is PostPayRunResult.SalariesExpenseAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("salaries_expense_account_not_found", result.accountId.value.toString()))
            is PostPayRunResult.CashAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("cash_account_not_found", result.accountId.value.toString()))
        }
    }

    post("/leave-accruals/{leaveAccrualId}/remeasure") {
        val leaveAccrual = call.loadLeaveAccrual(leaveAccrualRepository) ?: return@post
        val tenantId = call.resolveTenantForCompany(leaveAccrual.companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<RemeasureLeaveAccrualRequestDto>()
        val targetAmount = call.parseMoney(request.targetAmount, request.currency) ?: return@post
        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val leaveExpenseAccountUuid = call.parseUuid(request.leaveExpenseAccountId) ?: return@post
        val liabilityAccountUuid = call.parseUuid(request.accruedLeaveLiabilityAccountId) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post

        val result = remeasureLeaveAccrualUseCase.execute(
            RemeasureLeaveAccrualUseCase.Request(
                leaveAccrual.id, targetAmount, AccountId(leaveExpenseAccountUuid), AccountId(liabilityAccountUuid), PeriodId(periodUuid), date
            )
        )

        when (result) {
            is RemeasureLeaveAccrualResult.Success ->
                call.respond(HttpStatusCode.OK, result.leaveAccrual.toDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name))
            is RemeasureLeaveAccrualResult.NoChangeNeeded ->
                call.respond(HttpStatusCode.OK, leaveAccrual.toDto(journalEntryId = null, journalEntryStatus = null))
            is RemeasureLeaveAccrualResult.LeaveAccrualNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("leave_accrual_not_found"))
            is RemeasureLeaveAccrualResult.PeriodNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("period_not_found"))
            is RemeasureLeaveAccrualResult.PeriodNotOpen -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open"))
            is RemeasureLeaveAccrualResult.LeaveExpenseAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("leave_expense_account_not_found", result.accountId.value.toString()))
            is RemeasureLeaveAccrualResult.AccruedLeaveLiabilityAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("accrued_leave_liability_account_not_found", result.accountId.value.toString()))
            is RemeasureLeaveAccrualResult.CurrencyMismatch -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("currency_mismatch"))
        }
    }

    post("/leave-accruals/{leaveAccrualId}/utilize") {
        val leaveAccrual = call.loadLeaveAccrual(leaveAccrualRepository) ?: return@post
        val tenantId = call.resolveTenantForCompany(leaveAccrual.companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<UtilizeLeaveAccrualRequestDto>()
        val amount = call.parseMoney(request.amount, request.currency) ?: return@post
        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val cashAccountUuid = call.parseUuid(request.cashAccountId) ?: return@post
        val liabilityAccountUuid = call.parseUuid(request.accruedLeaveLiabilityAccountId) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post

        val result = utilizeLeaveAccrualUseCase.execute(
            UtilizeLeaveAccrualUseCase.Request(
                leaveAccrual.id, amount, AccountId(cashAccountUuid), AccountId(liabilityAccountUuid), PeriodId(periodUuid), date
            )
        )

        when (result) {
            is UtilizeLeaveAccrualResult.Success ->
                call.respond(HttpStatusCode.OK, result.leaveAccrual.toDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name))
            is UtilizeLeaveAccrualResult.LeaveAccrualNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("leave_accrual_not_found"))
            is UtilizeLeaveAccrualResult.PeriodNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("period_not_found"))
            is UtilizeLeaveAccrualResult.PeriodNotOpen -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open"))
            is UtilizeLeaveAccrualResult.CashAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("cash_account_not_found", result.accountId.value.toString()))
            is UtilizeLeaveAccrualResult.AccruedLeaveLiabilityAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("accrued_leave_liability_account_not_found", result.accountId.value.toString()))
            is UtilizeLeaveAccrualResult.CurrencyMismatch -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("currency_mismatch"))
            is UtilizeLeaveAccrualResult.NonPositiveAmount -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("non_positive_amount"))
            is UtilizeLeaveAccrualResult.NothingToUtilize -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("nothing_to_utilize"))
        }
    }
}

/** Loads the `LeaveAccrual` named by the `leaveAccrualId` path parameter, or responds 400/404 and returns `null`. */
private suspend fun ApplicationCall.loadLeaveAccrual(leaveAccrualRepository: LeaveAccrualRepository) =
    run {
        val leaveAccrualIdRaw = parameters["leaveAccrualId"]
        if (leaveAccrualIdRaw == null) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "leaveAccrualId path parameter is required"))
            return@run null
        }
        val leaveAccrualUuid = parseUuid(leaveAccrualIdRaw) ?: return@run null
        val leaveAccrual = leaveAccrualRepository.findById(LeaveAccrualId(leaveAccrualUuid))
        if (leaveAccrual == null) {
            respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "LeaveAccrual not found"))
            return@run null
        }
        leaveAccrual
    }

/**
 * Checks the caller-supplied `X-Tenant-Id` header against [actualTenantId]
 * (already resolved from the targeted resource's real owner) - shared
 * by every route in this file, the same real multi-tenancy check
 * `journalEntryRoutes`/`purchaseOrderRoutes` already established
 * (Section 10.19). Responds 400/403 and returns `false` on failure.
 */
private suspend fun ApplicationCall.verifyClaimedTenant(actualTenantId: TenantId): Boolean {
    val claimedTenantIdRaw = request.header("X-Tenant-Id")
    if (claimedTenantIdRaw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "X-Tenant-Id header is required"))
        return false
    }
    val claimedTenantId = parseUuid(claimedTenantIdRaw) ?: return false
    if (claimedTenantId != actualTenantId.value) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "X-Tenant-Id does not own the requested resource"))
        return false
    }
    return true
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

private fun LeaveAccrual.toDto(journalEntryId: String?, journalEntryStatus: String?) =
    LeaveAccrualResponseDto(
        leaveAccrualId = id.value.toString(),
        balanceAmount = balance.amount.toString(),
        balanceCurrency = balance.currency.currencyCode,
        journalEntryId = journalEntryId,
        journalEntryStatus = journalEntryStatus
    )
