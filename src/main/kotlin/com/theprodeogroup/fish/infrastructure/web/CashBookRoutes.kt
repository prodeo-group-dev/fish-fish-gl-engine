package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddMissingStandardAccountsUseCase
import com.theprodeogroup.fish.application.ChangeCashBookKindUseCase
import com.theprodeogroup.fish.application.ComputeCashBookUseCase
import com.theprodeogroup.fish.application.ListCashBooksUseCase
import com.theprodeogroup.fish.application.ListCounterAccountsUseCase
import com.theprodeogroup.fish.application.RecordCashBookEntryUseCase
import com.theprodeogroup.fish.application.RecordCashBookTransferUseCase
import com.theprodeogroup.fish.application.RecordVatSettlementUseCase
import com.theprodeogroup.fish.application.UndoCashBookEntryUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

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
    recordCashBookEntryUseCase: RecordCashBookEntryUseCase,
    listCounterAccountsUseCase: ListCounterAccountsUseCase,
    recordCashBookTransferUseCase: RecordCashBookTransferUseCase,
    undoCashBookEntryUseCase: UndoCashBookEntryUseCase,
    recordVatSettlementUseCase: RecordVatSettlementUseCase,
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
                                description = humanDescription(row.description) { id -> result.entriesById[id] },
                                source = row.source.name,
                                counterAccounts = row.counterAccounts.map {
                                    CashBookCounterAccountDto(it.id.value.toString(), it.code, it.name)
                                },
                                moneyIn = row.moneyIn.amount.toPlainString(),
                                moneyOut = row.moneyOut.amount.toPlainString(),
                                runningBalance = row.balance.amount.toPlainString(),
                                reversalOf = row.reversalOfEntryId?.value?.toString(),
                                reversedBy = row.reversedByEntryId?.value?.toString(),
                                reconciled = result.reconciledEntryIds?.let { row.entryId in it },
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

    get("/companies/{companyId}/cash-books/{accountId}/counter-accounts") {
        val companyId = call.parseCashBookCompanyId() ?: return@get
        val accountUuid = call.parseUuid(call.parameters["accountId"] ?: "") ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        val direction = when (call.request.queryParameters["direction"]) {
            "in" -> CashDirection.RECEIVED
            "out" -> CashDirection.PAID
            else -> {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "direction must be in or out"))
                return@get
            }
        }

        when (val result = listCounterAccountsUseCase.execute(companyId, AccountId(accountUuid), direction)) {
            is ListCounterAccountsUseCase.Result.Success -> call.respond(
                result.options.map { CounterAccountOptionDto(it.account.id.value.toString(), it.account.code, it.account.name, it.account.type.name, it.group) }
            )
            ListCounterAccountsUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ListCounterAccountsUseCase.Result.AccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
            ListCounterAccountsUseCase.Result.NotACashBook ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("not_a_cash_or_bank_account", "This account is not a cash or bank account, so it has no book"))
        }
    }

    post("/companies/{companyId}/cash-books/{accountId}/receipts") {
        call.handleCashBookEntry(CashDirection.RECEIVED, recordCashBookEntryUseCase, companyRepository)
    }

    post("/companies/{companyId}/cash-books/{accountId}/payments") {
        call.handleCashBookEntry(CashDirection.PAID, recordCashBookEntryUseCase, companyRepository)
    }

    post("/companies/{companyId}/cash-books/{accountId}/vat-settlement") {
        call.handleVatSettlement(recordVatSettlementUseCase, companyRepository)
    }

    post("/companies/{companyId}/cash-books/{accountId}/transfers") {
        call.handleCashBookTransfer(recordCashBookTransferUseCase, companyRepository)
    }

    post("/companies/{companyId}/cash-books/{accountId}/entries/{entryId}/undo") {
        call.handleCashBookUndo(undoCashBookEntryUseCase, companyRepository)
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
                AccountCashBookKindDto(
                    result.account.id.value.toString(), result.account.code, result.account.name, result.account.cashBookKind?.name,
                    result.account.formerCashBookKind?.name, result.account.stoppedOn?.toString()
                )
            )
            ChangeCashBookKindUseCase.Result.AccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
            is ChangeCashBookKindUseCase.Result.NotAnAssetAccount ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("kind_requires_asset_account", result.message ?: "Only an asset account can be a cash or bank account"))
            ChangeCashBookKindUseCase.Result.KindFixed ->
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponseDto("cash_book_kind_fixed", "A cash book stays a cash book and a bank account stays a bank account. Open a new account instead.")
                )
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

private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200

private val typedIdInText = Regex("""[A-Za-z]+Id\(value=([0-9a-fA-F-]{36})\)""")

private val reversalOfPlainId = Regex("""^Reversal of ([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$""")

/**
 * Descriptions written before 2026-10-11 can contain a typed id printed raw ("Reversal of JournalEntryId(value=...)",
 * "Pay run - PayRunId(value=...)"): a bug since fixed at the source. Those rows are already stored, so the book unwraps
 * them for display (it never changes the stored entry).
 *
 * An old reversal reads "Reversal of <id of the original>" (the wording before 2026-10-12). Where [find] can produce
 * the original, it is worded like a new one: "Reversal of <original description> (<original date>)". Where it cannot,
 * the stored text is left as it is: nothing is guessed. Only a description that is exactly that old form is touched.
 */
internal fun humanDescription(description: String?, find: (JournalEntryId) -> JournalEntry? = { null }): String? {
    val unwrapped = description?.replace(typedIdInText) { it.groupValues[1] } ?: return null
    val originalId = reversalOfPlainId.matchEntire(unwrapped)?.groupValues?.get(1)?.let { JournalEntryId(java.util.UUID.fromString(it)) }
        ?: return unwrapped
    val original = find(originalId) ?: return unwrapped
    return JournalEntry.reversalWording(original.description?.replace(typedIdInText) { it.groupValues[1] }, original.date)
}

private suspend fun ApplicationCall.handleCashBookTransfer(
    recordCashBookTransferUseCase: RecordCashBookTransferUseCase,
    companyRepository: CompanyRepository
) {
    val companyId = parseCashBookCompanyId() ?: return
    val fromUuid = parseUuid(parameters["accountId"] ?: "") ?: return
    val tenantId = resolveTenantForCompany(companyId, companyRepository) ?: return
    if (!verifyClaimedTenant(tenantId)) return
    authorizeTenantForWrite(tenantId, companyId) ?: return

    val idempotencyKey = request.header("Idempotency-Key")?.trim()
    if (idempotencyKey.isNullOrEmpty() || idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("idempotency_key_required", "An Idempotency-Key header of 1 to $MAX_IDEMPOTENCY_KEY_LENGTH characters is required"))
        return
    }
    val body = receive<CashBookTransferRequestDto>()
    val date = try {
        LocalDate.parse(body.date)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be a date, YYYY-MM-DD"))
        return
    }
    val amount = body.amount.toBigDecimalOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount", "amount must be a plain decimal number"))
        return
    }
    val toUuid = parseUuid(body.toAccountId) ?: return

    val entryId = JournalEntryId(
        UUID.nameUUIDFromBytes("cash-book-transfer|${tenantId.value}|${companyId.value}|$fromUuid|$toUuid|$idempotencyKey".toByteArray(Charsets.UTF_8))
    )
    when (
        val result = recordCashBookTransferUseCase.execute(
            RecordCashBookTransferUseCase.Request(companyId, AccountId(fromUuid), AccountId(toUuid), amount, date, body.description, entryId)
        )
    ) {
        is RecordCashBookTransferUseCase.Result.Posted -> respond(
            HttpStatusCode.Created,
            CashBookTransferResponseDto(
                entryId = result.entry.id.value.toString(), fromAccountId = fromUuid.toString(), toAccountId = toUuid.toString(),
                fromBalanceAfter = result.fromBalanceAfter.amount.toPlainString(), toBalanceAfter = result.toBalanceAfter.amount.toPlainString(),
                currency = result.fromBalanceAfter.currency.currencyCode,
                warnings = result.warnings.map { CashBookWarningDto(it.code, it.accountId.value.toString(), it.balanceAfter.amount.toPlainString()) },
                replayed = false
            )
        )
        is RecordCashBookTransferUseCase.Result.Replay -> respond(
            HttpStatusCode.Created,
            CashBookTransferResponseDto(
                entryId = result.entry.id.value.toString(), fromAccountId = fromUuid.toString(), toAccountId = toUuid.toString(),
                fromBalanceAfter = result.fromBalanceAfter.amount.toPlainString(), toBalanceAfter = result.toBalanceAfter.amount.toPlainString(),
                currency = result.fromBalanceAfter.currency.currencyCode, warnings = emptyList(), replayed = true
            )
        )
        RecordCashBookTransferUseCase.Result.KeyReused ->
            respond(HttpStatusCode.UnprocessableEntity, ErrorResponseDto("idempotency_key_reused", "This Idempotency-Key was already used for a different transfer"))
        RecordCashBookTransferUseCase.Result.CompanyNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        RecordCashBookTransferUseCase.Result.AccountNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
        RecordCashBookTransferUseCase.Result.NotACashBook ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("not_a_cash_or_bank_account", "Both accounts must be cash or bank accounts"))
        RecordCashBookTransferUseCase.Result.SameAccount ->
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("same_account", "Choose a different account to move the money to"))
        RecordCashBookTransferUseCase.Result.InvalidAmount ->
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount", "amount must be greater than zero, with no more decimal places than the currency"))
        RecordCashBookTransferUseCase.Result.NoOpenPeriod ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
    }
}

private suspend fun ApplicationCall.handleCashBookUndo(
    undoCashBookEntryUseCase: UndoCashBookEntryUseCase,
    companyRepository: CompanyRepository
) {
    val companyId = parseCashBookCompanyId() ?: return
    val accountUuid = parseUuid(parameters["accountId"] ?: "") ?: return
    val entryUuid = parseUuid(parameters["entryId"] ?: "") ?: return
    val tenantId = resolveTenantForCompany(companyId, companyRepository) ?: return
    if (!verifyClaimedTenant(tenantId)) return
    authorizeTenantForWrite(tenantId, companyId) ?: return

    // Femi 2026-10-23 ("Restrict to CBB now"; CBB corrects errors in a Bank and Cash Book, never the GL): this door is for the Cash and
    // Bank Books service only. A person reaching it here would skip CBB's reconciled lock; people undo through Cash and Bank Books.
    val caller = principal<AuthenticatedCaller>()
    if (caller?.isServiceAccount != true || caller.service != "cbb") {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "Undo is done in Cash and Bank Books"))
        return
    }

    // The body is optional (GL-T-39, standard S4): {"reason": "..."} says why. An empty body still works; a malformed one is a 400.
    val undoRequest = try {
        receiveText().takeIf { it.isNotBlank() }?.let { Json.decodeFromString(UndoCashBookEntryRequestDto.serializer(), it) }
    } catch (e: SerializationException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "The body, if sent, must be JSON like {\"reason\": \"why\"}"))
        return
    }
    if (!recordReason(undoRequest?.reason)) return

    when (val result = undoCashBookEntryUseCase.execute(companyId, AccountId(accountUuid), JournalEntryId(entryUuid))) {
        is UndoCashBookEntryUseCase.Result.Undone -> respond(
            HttpStatusCode.Created,
            CashBookUndoResponseDto(result.reversal.id.value.toString(), entryUuid.toString(), result.balanceAfter.amount.toPlainString(), result.balanceAfter.currency.currencyCode)
        )
        UndoCashBookEntryUseCase.Result.CompanyNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        UndoCashBookEntryUseCase.Result.AccountNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
        UndoCashBookEntryUseCase.Result.NotACashBook ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("not_a_cash_or_bank_account", "This account is not a cash or bank account, so it has no book"))
        UndoCashBookEntryUseCase.Result.EntryNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("entry_not_found", "No such entry in this book"))
        UndoCashBookEntryUseCase.Result.AlreadyUndone ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("already_undone", "This entry was already undone"))
        UndoCashBookEntryUseCase.Result.NotUndoable ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("not_undoable", "This entry cannot be undone"))
        UndoCashBookEntryUseCase.Result.PeriodNotOpen ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open", "The period this entry is in is no longer open"))
        is UndoCashBookEntryUseCase.Result.UndoElsewhere ->
            respond(
                HttpStatusCode.Conflict,
                CounterAccountNotAllowedDto("undo_elsewhere", "This entry was not made in a cash or bank book, so it is reversed where it was made", result.useInstead.name)
            )
    }
}

private suspend fun ApplicationCall.handleCashBookEntry(
    direction: CashDirection,
    recordCashBookEntryUseCase: RecordCashBookEntryUseCase,
    companyRepository: CompanyRepository
) {
    val companyId = parseCashBookCompanyId() ?: return
    val accountUuid = parseUuid(parameters["accountId"] ?: "") ?: return
    val tenantId = resolveTenantForCompany(companyId, companyRepository) ?: return
    if (!verifyClaimedTenant(tenantId)) return
    authorizeTenantForWrite(tenantId, companyId) ?: return

    // Money posting: a key is required (the entry id is derived from it), so a double-click or a retry can
    // never post twice. See RecordCashBookEntryUseCase.
    val idempotencyKey = request.header("Idempotency-Key")?.trim()
    if (idempotencyKey.isNullOrEmpty() || idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("idempotency_key_required", "An Idempotency-Key header of 1 to $MAX_IDEMPOTENCY_KEY_LENGTH characters is required"))
        return
    }

    val body = receive<CashBookEntryRequestDto>()
    if (!recordProvenance(body.sourceReference, body.vatBasis)) return
    val date = try {
        LocalDate.parse(body.date)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be a date, YYYY-MM-DD"))
        return
    }
    val amount = body.amount.toBigDecimalOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount", "amount must be a plain decimal number"))
        return
    }
    val counterUuid = parseUuid(body.counterAccountId) ?: return
    val activity = body.cashFlowActivity?.let {
        try {
            CashFlowActivity.valueOf(it)
        } catch (e: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "cashFlowActivity must be OPERATING, INVESTING or FINANCING"))
            return
        }
    } ?: CashFlowActivity.OPERATING

    // VAT inside the gross amount: both fields or neither. No rate is looked up; the caller worked the amount out.
    if ((body.vatCategory == null) != (body.vatAmount == null)) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("vat_incomplete", "vatCategory and vatAmount must be sent together, or neither"))
        return
    }
    val vat = if (body.vatCategory != null && body.vatAmount != null) {
        val category = VatCategory.entries.firstOrNull { it.name == body.vatCategory } ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("vat_category_not_supported", "vatCategory must be one of ${VatCategory.entries.joinToString { it.name }}"))
            return
        }
        val vatAmount = body.vatAmount.toBigDecimalOrNull() ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount", "vatAmount must be a plain decimal number"))
            return
        }
        RecordCashBookEntryUseCase.Request.Vat(category, vatAmount)
    } else null

    // Same Tenant, Company, book, direction and key always give the same entry id; anything different gives another.
    val entryId = JournalEntryId(
        UUID.nameUUIDFromBytes("cash-book-entry|${tenantId.value}|${companyId.value}|$accountUuid|$direction|$idempotencyKey".toByteArray(Charsets.UTF_8))
    )
    val result = recordCashBookEntryUseCase.execute(
        RecordCashBookEntryUseCase.Request(
            companyId, AccountId(accountUuid), direction, amount, date, AccountId(counterUuid),
            body.description, activity, entryId, vat
        )
    )
    respondCashBookEntryResult(result, accountUuid)
}


/**
 * `POST /companies/{companyId}/cash-books/{accountId}/vat-settlement` (GL-T-30, GL-FR-TAX-9, GL-UC-20; Femi 2026-10-22): VAT
 * paid to the authority (`PAID`: Dr the VAT control account, Cr the book) or a refund from it (`RECEIVED`). The standard Company
 * sequence and a mandatory Idempotency-Key, as for receipts and payments; see [RecordVatSettlementUseCase].
 */
private suspend fun ApplicationCall.handleVatSettlement(
    recordVatSettlementUseCase: RecordVatSettlementUseCase,
    companyRepository: CompanyRepository
) {
    val companyId = parseCashBookCompanyId() ?: return
    val accountUuid = parseUuid(parameters["accountId"] ?: "") ?: return
    val tenantId = resolveTenantForCompany(companyId, companyRepository) ?: return
    if (!verifyClaimedTenant(tenantId)) return
    authorizeTenantForWrite(tenantId, companyId) ?: return

    val idempotencyKey = request.header("Idempotency-Key")?.trim()
    if (idempotencyKey.isNullOrEmpty() || idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("idempotency_key_required", "An Idempotency-Key header of 1 to $MAX_IDEMPOTENCY_KEY_LENGTH characters is required"))
        return
    }

    val body = receive<VatSettlementRequestDto>()
    if (!recordProvenance(body.sourceReference, body.vatBasis)) return
    val date = try {
        LocalDate.parse(body.date)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be a date, YYYY-MM-DD"))
        return
    }
    val amount = body.amount.toBigDecimalOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount", "amount must be a plain decimal number"))
        return
    }
    val direction = when (body.direction) {
        "PAID" -> CashDirection.PAID
        "RECEIVED" -> CashDirection.RECEIVED
        else -> {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "direction must be PAID (VAT paid to the authority) or RECEIVED (a refund)"))
            return
        }
    }

    // A settlement has its own namespace in the entry id, so the same key on a receipt never collides with it.
    val entryId = JournalEntryId(
        UUID.nameUUIDFromBytes("vat-settlement|${tenantId.value}|${companyId.value}|$accountUuid|$direction|$idempotencyKey".toByteArray(Charsets.UTF_8))
    )
    val result = recordVatSettlementUseCase.execute(
        RecordVatSettlementUseCase.Request(companyId, AccountId(accountUuid), direction, amount, date, body.description, entryId)
    )
    respondCashBookEntryResult(result, accountUuid)
}

/** The answer to a recorded cash or bank book entry, shared by receipts, payments and VAT settlements. */
private suspend fun ApplicationCall.respondCashBookEntryResult(result: RecordCashBookEntryUseCase.Result, accountUuid: UUID) {
    when (result) {
        is RecordCashBookEntryUseCase.Result.Posted -> respond(
            HttpStatusCode.Created,
            CashBookEntryResponseDto(
                entryId = result.entry.id.value.toString(),
                accountId = accountUuid.toString(),
                balanceAfter = result.balanceAfter.amount.toPlainString(),
                currency = result.balanceAfter.currency.currencyCode,
                warnings = result.warnings.map { CashBookWarningDto(it.code, it.accountId.value.toString(), it.balanceAfter.amount.toPlainString()) },
                replayed = false
            )
        )
        is RecordCashBookEntryUseCase.Result.Replay -> respond(
            HttpStatusCode.Created,
            CashBookEntryResponseDto(
                entryId = result.entry.id.value.toString(),
                accountId = accountUuid.toString(),
                balanceAfter = result.balanceAfter.amount.toPlainString(),
                currency = result.balanceAfter.currency.currencyCode,
                warnings = emptyList(),
                replayed = true
            )
        )
        RecordCashBookEntryUseCase.Result.KeyReused ->
            respond(HttpStatusCode.UnprocessableEntity, ErrorResponseDto("idempotency_key_reused", "This Idempotency-Key was already used for a different entry"))
        RecordCashBookEntryUseCase.Result.CompanyNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        RecordCashBookEntryUseCase.Result.AccountNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
        RecordCashBookEntryUseCase.Result.NotACashBook ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("not_a_cash_or_bank_account", "This account is not a cash or bank account, so it has no book"))
        RecordCashBookEntryUseCase.Result.CounterAccountNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponseDto("counter_account_not_found", "The other account was not found"))
        RecordCashBookEntryUseCase.Result.CounterAccountInactive ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("counter_account_inactive", "The other account is no longer in use"))
        is RecordCashBookEntryUseCase.Result.CounterAccountNotAllowed ->
            respond(
                HttpStatusCode.Conflict,
                CounterAccountNotAllowedDto("counter_account_not_allowed", "That account is not recorded here; use ${result.useInstead.name.lowercase().replace('_', ' ')} instead", result.useInstead.name)
            )
        RecordCashBookEntryUseCase.Result.InvalidAmount ->
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount", "amount must be greater than zero, with no more decimal places than the currency"))
        RecordCashBookEntryUseCase.Result.NoOpenPeriod ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
        RecordCashBookEntryUseCase.Result.VatExceedsAmount ->
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("vat_exceeds_amount", "vatAmount must be less than the amount, which includes it"))
        RecordCashBookEntryUseCase.Result.VatNotApplicable ->
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("vat_not_applicable", "VAT can be recorded only against an income account (money in) or an expense account (money out)"))
        RecordCashBookEntryUseCase.Result.VatControlAccountNotConfigured ->
            respond(HttpStatusCode.Conflict, ErrorResponseDto("vat_control_account_not_configured", "This Company has no VAT control account"))
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
