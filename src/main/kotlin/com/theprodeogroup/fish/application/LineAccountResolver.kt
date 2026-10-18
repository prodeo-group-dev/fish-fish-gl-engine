package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.ModuleOwnedAccounts
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * Decides whether an account a caller named for a purchase or sale line is one GL will post to, and finds the
 * Suspense account when no account was named at all (Femi, 2026-10-18: the account a transaction posts to is chosen
 * by the type of transaction; with none set it goes to Suspense, never to some default account;
 * docs/GL_Per_Line_Account_Routing_Audit_And_Design.md).
 *
 * The rule is the exact complement of the account list a screen offers (`GET /companies/{id}/accounts?usableFor=`),
 * so a screen never offers what this would refuse. An account of another Company is "not found", never a distinct
 * "forbidden", so this never confirms another Company's account exists.
 *
 * [companyRepository] supplies the Company's client type, which decides whether the business control-account codes
 * (1100, 2000, 1300 ...) mean what they mean on a business chart; without it the chart is treated as a business one.
 *
 * **Module-owned accounts are refused for every caller.** A control or module-owned account (Accounts Receivable and Payable,
 * Inventory, Fixed Assets, the VAT control account, a cash or bank book, Suspense used as an opening figure) is refused here
 * whoever is calling; the rule that the owning module itself may post to it (Payroll to its own liability accounts, POP and SOP
 * to their control accounts) is NOT implemented. Those modules post through their own dedicated posting interfaces, not through
 * a per-line account, so nothing needs the exception yet; it is designed with step 2 if a per-line caller ever does.
 */
class LineAccountResolver(
    private val accountRepository: AccountRepository,
    private val companyRepository: CompanyRepository? = null
) {
    enum class Kind { PURCHASE, SALE, GAIN_ON_DISPOSAL, LOSS_ON_DISPOSAL, PAYROLL_EXPENSE, PAYROLL_LIABILITY, SETTLEMENT }

    enum class Reason { INACTIVE, WRONG_TYPE, CONTROL_OR_MODULE_OWNED, NOT_A_CASH_BOOK }

    sealed class Result {
        data class Resolved(val account: Account) : Result()
        data class NotFound(val accountId: AccountId) : Result()
        data class NotAllowed(val accountId: AccountId, val reason: Reason) : Result()
        data object SuspenseNotConfigured : Result()
    }

    /** The account the caller named for a line of [kind], or why it cannot be used. */
    fun resolve(companyId: CompanyId, kind: Kind, accountId: AccountId): Result {
        val account = accountRepository.findById(accountId)?.takeIf { it.companyId == companyId } ?: return Result.NotFound(accountId)
        // Suspense is the system's own "not yet classified" account: a person may name it deliberately too.
        // It must be active, like every other account: an inactive Suspense account is refused here and is not offered in the list.
        if (kind != Kind.SETTLEMENT && isSuspense(account)) return if (account.active) Result.Resolved(account) else Result.NotAllowed(accountId, Reason.INACTIVE)
        val reason = refusalFor(account, kind, clientTypeOf(companyId))
        return if (reason == null) Result.Resolved(account) else Result.NotAllowed(accountId, reason)
    }

    /** The Company's Suspense account, for a line that names no account. */
    fun suspense(companyId: CompanyId): Result =
        accountRepository.findAllByCompany(companyId).firstOrNull { isSuspense(it) && it.active }
            ?.let { Result.Resolved(it) }
            ?: Result.SuspenseNotConfigured

    /** Why [account] cannot take a line of [kind], or null when it can (the shared rule behind the account list). */
    fun refusalFor(account: Account, kind: Kind, clientType: ClientType): Reason? {
        if (!account.active) return Reason.INACTIVE
        // A settlement line is the cash or bank side of a payment: it must be one of the Company's cash or bank books.
        if (kind == Kind.SETTLEMENT) return if (account.cashBookKind == null) Reason.NOT_A_CASH_BOOK else null
        val typeAllowed = when (kind) {
            Kind.PURCHASE -> account.type == AccountType.EXPENSE || account.type == AccountType.ASSET
            Kind.SALE, Kind.GAIN_ON_DISPOSAL -> account.type == AccountType.REVENUE
            Kind.LOSS_ON_DISPOSAL, Kind.PAYROLL_EXPENSE -> account.type == AccountType.EXPENSE
            Kind.PAYROLL_LIABILITY -> account.type == AccountType.LIABILITY
            Kind.SETTLEMENT -> true
        }
        if (!typeAllowed) return Reason.WRONG_TYPE
        if (ModuleOwnedAccounts.useInstead(account, clientType) != null) return Reason.CONTROL_OR_MODULE_OWNED
        // The trade finance facility is not a payroll liability (HR, 2026-10-18); accrued leave 2200 is payroll-owned and stays usable.
        if (kind == Kind.PAYROLL_LIABILITY && account.code == ChartOfAccountsTemplate.FACILITY_LIABILITY_CODE) return Reason.CONTROL_OR_MODULE_OWNED
        return null
    }

    /**
     * The accounts a person may pick for a line of [kind], in chart order, with the Company's Suspense account last as
     * the "not sure" choice. This is the list behind `GET /companies/{id}/accounts?usableFor=`: exactly what
     * [resolve] would accept, so a screen never offers an account GL would refuse.
     */
    fun usableAccounts(accounts: List<Account>, kind: Kind, clientType: ClientType): List<Account> {
        val usable = accounts.filter { (kind == Kind.SETTLEMENT || !isSuspense(it)) && refusalFor(it, kind, clientType) == null }.sortedBy { it.code }
        return if (kind == Kind.SETTLEMENT) usable else usable + accounts.filter { isSuspense(it) && it.active }
    }

    private fun clientTypeOf(companyId: CompanyId): ClientType =
        companyRepository?.findById(companyId)?.clientType ?: ClientType.COMPANY_LIMITED

    private fun isSuspense(account: Account) =
        account.type == AccountType.EQUITY && account.code == ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE
}
