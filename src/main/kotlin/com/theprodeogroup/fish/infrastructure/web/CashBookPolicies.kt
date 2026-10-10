package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.log
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.util.UUID

/** How a new rule about cash and bank accounts behaves: only report what it WOULD refuse, or refuse. */
enum class PolicyMode {
    LOG, ENFORCE;

    companion object {
        /** Anything but "enforce" (case-insensitive) is [LOG]: a new rule changes no behaviour until it is switched on deliberately. */
        fun fromEnvironment(value: String?): PolicyMode = if (value?.trim()?.equals("enforce", ignoreCase = true) == true) ENFORCE else LOG
    }
}

/**
 * Two rules from docs/GL_Cash_And_Bank_Books_SRS.md that tighten flows that already work, introduced log-first like the
 * service allow-list was (T15 / G3), so nothing changes on deploy and the logs show what would break before anyone is refused:
 *
 * - **Settlement account** (`FISH_SETTLEMENT_ACCOUNT_MODE`, decision D7 area, FR-CB52): the account a sales collection, a
 *   supplier payment, a pay run or a leave payout settles into must be a cash or bank book of the Company. Refusal when
 *   enforced: 409 `settlement_account_not_a_cash_book`.
 * - **Bank-only reconciliation** (`FISH_BANK_RECONCILIATION_MODE`, D7, FR-CB30): only a BANK account is reconciled. Refusal
 *   when enforced: 409 `reconciliation_requires_bank_account`.
 *
 * Unset or anything but `enforce` is [PolicyMode.LOG] for both. [onViolation] receives each logged line (tests read it);
 * every line also goes to the application log at WARN.
 */
class CashBookPolicies(
    val settlementAccount: PolicyMode = PolicyMode.LOG,
    val bankOnlyReconciliation: PolicyMode = PolicyMode.LOG,
    private val onViolation: ((String) -> Unit)? = null
) {
    internal fun report(call: ApplicationCall, line: String) {
        call.application.log.warn(line)
        onViolation?.invoke(line)
    }
}

private fun ApplicationCall.callerLabel(): String = principal<AuthenticatedCaller>()?.let { it.service ?: "person" } ?: "unknown"

/**
 * True if the posting may go ahead. An account that does not exist, or belongs to another Company, is not judged here (the
 * use case refuses it as it always did). An account that is not a cash or bank book is a violation: in [PolicyMode.LOG] it is
 * logged as `WOULD REFUSE` (service, company, account, route; no token) and the request proceeds unchanged; in
 * [PolicyMode.ENFORCE] the request is answered 409 and false is returned.
 */
internal suspend fun ApplicationCall.settlementAccountAllowed(
    policies: CashBookPolicies,
    accountRepository: AccountRepository,
    companyId: CompanyId,
    accountUuid: UUID,
    route: String
): Boolean {
    val account = accountRepository.findById(AccountId(accountUuid))?.takeIf { it.companyId == companyId } ?: return true
    if (account.cashBookKind != null) return true
    return violation(
        policies, policies.settlementAccount, "settlement_account_not_a_cash_book", companyId, accountUuid, route,
        "The account the money settles into must be a cash or bank account of the Company"
    )
}

/** The same, for starting a bank reconciliation: the account must be a BANK account. */
internal suspend fun ApplicationCall.reconciliationAccountAllowed(
    policies: CashBookPolicies,
    accountRepository: AccountRepository,
    companyId: CompanyId,
    accountUuid: UUID,
    route: String
): Boolean {
    val account = accountRepository.findById(AccountId(accountUuid))?.takeIf { it.companyId == companyId } ?: return true
    if (account.cashBookKind == CashBookKind.BANK) return true
    return violation(
        policies, policies.bankOnlyReconciliation, "reconciliation_requires_bank_account", companyId, accountUuid, route,
        "Only a bank account can be reconciled; add the bank account and reconcile that"
    )
}

private suspend fun ApplicationCall.violation(
    policies: CashBookPolicies,
    mode: PolicyMode,
    code: String,
    companyId: CompanyId,
    accountUuid: UUID,
    route: String,
    detail: String
): Boolean {
    val verb = if (mode == PolicyMode.ENFORCE) "REFUSED" else "WOULD REFUSE"
    policies.report(this, "$verb $code service=${callerLabel()} company=${companyId.value} account=$accountUuid route=$route")
    if (mode == PolicyMode.LOG) return true
    respond(HttpStatusCode.Conflict, ErrorResponseDto(code, detail))
    return false
}
