package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddMissingStandardAccountsUseCase
import com.theprodeogroup.fish.application.ChangeCashBookKindUseCase
import com.theprodeogroup.fish.application.ComputeCashBookUseCase
import com.theprodeogroup.fish.application.ListCashBooksUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.CashBookKind
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
import io.ktor.server.routing.put
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Cash and bank books (docs/GL_Cash_And_Bank_Books_SRS.md, Femi 2026-10-10: cash and bank accounts, books of
 * original entry, one book per account).
 *
 * - `GET  /companies/{companyId}/cash-books`: the Company's cash and bank accounts with balances. READ.
 * - `GET  /companies/{companyId}/cash-books/{accountId}?from=&to=`: one account's book. READ.
 * - `PUT  /companies/{companyId}/accounts/{accountId}/cash-book-kind`: flag an account CASH or BANK, or clear it. WRITE.
 *
 * All three follow the standard Company sequence: the Company's own Tenant is resolved from GL's record, the
 * claimed Tenant is verified, then the caller's authorization is checked. People only: none of these is on a
 * service allow-list. An account of another Company is "not found", never "forbidden".
 */
fun Route.cashBookRoutes(
    listCashBooksUseCase: ListCashBooksUseCase,
    computeCashBookUseCase: ComputeCashBookUseCase,
    changeCashBookKindUseCase: ChangeCashBookKindUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/cash-books") {
        val companyId = call.parseCashBookCompanyId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = listCashBooksUseCase.execute(companyId)) {
            is ListCashBooksUseCase.Result.Success -> call.respond(
                result.items.map { item ->
                    CashBookListItemDto(
                        accountId = item.account.id.value.toString(),
                        code = item.account.code,
                        name = item.account.name,
                        kind = item.account.cashBookKind!!.name,
                        balance = item.balance.amount.toPlainString(),
                        currency = result.currency.currencyCode,
                        isDefault = item.isDefault,
                        reconcilable = item.account.cashBookKind == CashBookKind.BANK,
                        lastEntryDate = item.lastEntryDate?.toString()
                    )
                }
            )
            ListCashBooksUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        }
    }

    get("/companies/{companyId}/cash-books/{accountId}") {
        val companyId = call.parseCashBookCompanyId() ?: return@get
        val accountUuid = call.parseUuid(call.parameters["accountId"] ?: "") ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        val from = call.parseOptionalDate("from") ?: if (call.request.queryParameters["from"] != null) return@get else null
        val to = call.parseOptionalDate("to") ?: if (call.request.queryParameters["to"] != null) return@get else null

        when (val result = computeCashBookUseCase.execute(companyId, AccountId(accountUuid), from, to)) {
            is ComputeCashBookUseCase.Result.Success -> {
                val book = result.book
                call.respond(
                    CashBookResponseDto(
                        accountId = book.account.id.value.toString(),
                        code = book.account.code,
                        name = book.account.name,
                        kind = book.account.cashBookKind!!.name,
                        currency = book.currency.currencyCode,
                        from = book.from.toString(),
                        to = book.to.toString(),
                        openingBalance = book.openingBalance.amount.toPlainString(),
                        totalIn = book.totalMoneyIn.amount.toPlainString(),
                        totalOut = book.totalMoneyOut.amount.toPlainString(),
                        closingBalance = book.closingBalance.amount.toPlainString(),
                        rows = book.rows.map { row ->
                            CashBookRowDto(
                                entryId = row.entryId.value.toString(),
                                date = row.date.toString(),
                                description = row.description,
                                source = row.source.name,
                                counterAccounts = row.counterAccounts.map {
                                    CashBookCounterAccountDto(it.id.value.toString(), it.code, it.name)
                                },
                                moneyIn = row.moneyIn.amount.toPlainString(),
                                moneyOut = row.moneyOut.amount.toPlainString(),
                                runningBalance = row.balance.amount.toPlainString(),
                                reversalOf = row.reversalOfEntryId?.value?.toString(),
                                reversedBy = row.reversedByEntryId?.value?.toString(),
                                canUndo = result.canUndo.getValue(row.entryId)
                            )
                        }
                    )
                )
            }
            ComputeCashBookUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeCashBookUseCase.Result.AccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
            ComputeCashBookUseCase.Result.NotACashBook ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("not_a_cash_or_bank_account", "This account is not a cash or bank account, so it has no book"))
            ComputeCashBookUseCase.Result.InvalidRange ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_range", "to cannot be before from"))
            is ComputeCashBookUseCase.Result.RangeTooLarge ->
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    CashBookRangeTooLargeDto(
                        "range_too_large",
                        "This range has ${result.rowCount} entries; a book shows at most ${result.limit}. Narrow the dates.",
                        result.rowCount, result.limit
                    )
                )
        }
    }

    put("/companies/{companyId}/accounts/{accountId}/cash-book-kind") {
        val companyId = call.parseCashBookCompanyId() ?: return@put
        val accountUuid = call.parseUuid(call.parameters["accountId"] ?: "") ?: return@put
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@put
        if (!call.verifyClaimedTenant(tenantId)) return@put
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@put

        val request = call.receive<ChangeCashBookKindRequestDto>()
        val kind = request.cashBookKind?.let {
            try {
                CashBookKind.valueOf(it)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "cashBookKind must be null, CASH or BANK"))
                return@put
            }
        }

        when (val result = changeCashBookKindUseCase.execute(companyId, AccountId(accountUuid), kind)) {
            is ChangeCashBookKindUseCase.Result.Success -> call.respond(
                AccountCashBookKindDto(result.account.id.value.toString(), result.account.code, result.account.name, result.account.cashBookKind?.name)
            )
            ChangeCashBookKindUseCase.Result.AccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
            is ChangeCashBookKindUseCase.Result.NotAnAssetAccount ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("kind_requires_asset_account", result.message ?: "Only an asset account can be a cash or bank account"))
            ChangeCashBookKindUseCase.Result.ReconciliationsExist ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("reconciliations_exist", "This bank account has bank reconciliations, so it stays a bank account"))
            ChangeCashBookKindUseCase.Result.PrimeCashBookKindFixed ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("prime_cash_book_kind_fixed", "Account 1000 is the Cash Book and stays a cash account; add a bank account instead"))
        }
    }
}

/**
 * `POST /companies/{companyId}/standard-accounts` (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB60): adds the standard
 * accounts an older Company's chart is missing (the VAT control account 2150 is the known case) and makes account
 * 1000 the CASH book. Only adds, never changes an existing account. Idempotent. WRITE; people only.
 */
fun Route.standardAccountsRoutes(
    addMissingStandardAccountsUseCase: AddMissingStandardAccountsUseCase,
    companyRepository: CompanyRepository
) {
    post("/companies/{companyId}/standard-accounts") {
        val companyId = call.parseCashBookCompanyId() ?: return@post
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@post

        when (val result = addMissingStandardAccountsUseCase.execute(companyId)) {
            is AddMissingStandardAccountsUseCase.Result.Success -> call.respond(
                StandardAccountsResponseDto(
                    added = result.added.map { StandardAccountsAddedDto(it.code, it.name) },
                    conflicts = result.conflicts.map { StandardAccountsConflictDto(it.code, it.existingType.name, it.templateType.name) },
                    cashBookKindSet = result.cashBookKindSet
                )
            )
            AddMissingStandardAccountsUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        }
    }
}

private suspend fun ApplicationCall.parseCashBookCompanyId(): CompanyId? {
    val raw = parameters["companyId"]
    if (raw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
        return null
    }
    return parseUuid(raw)?.let(::CompanyId)
}

/** The query parameter as a date, or null when it is absent. A present but invalid value answers 400 and also yields null; callers tell the two apart by checking the raw parameter. */
private suspend fun ApplicationCall.parseOptionalDate(name: String): LocalDate? {
    val raw = request.queryParameters[name] ?: return null
    return try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_range", "$name must be a date, YYYY-MM-DD"))
        null
    }
}
