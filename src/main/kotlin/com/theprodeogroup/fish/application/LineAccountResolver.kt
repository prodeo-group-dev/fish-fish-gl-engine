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
 */
class LineAccountResolver(
    private val accountRepository: AccountRepository,
    private val companyRepository: CompanyRepository? = null
) {
    enum class Kind { PURCHASE, SALE }

    enum class Reason { INACTIVE, WRONG_TYPE, CONTROL_OR_MODULE_OWNED }

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
        if (isSuspense(account)) return Result.Resolved(account)
        val reason = refusalFor(account, kind, clientTypeOf(companyId))
        return if (reason == null) Result.Resolved(account) else Result.NotAllowed(accountId, reason)
    }

    /** The Company's Suspense account, for a line that names no account. */
    fun suspense(companyId: CompanyId): Result =
        accountRepository.findAllByCompany(companyId).firstOrNull { isSuspense(it) }
            ?.let { Result.Resolved(it) }
            ?: Result.SuspenseNotConfigured

    /** Why [account] cannot take a line of [kind], or null when it can (the shared rule behind the account list). */
    fun refusalFor(account: Account, kind: Kind, clientType: ClientType): Reason? {
        if (!account.active) return Reason.INACTIVE
        val typeAllowed = when (kind) {
            Kind.PURCHASE -> account.type == AccountType.EXPENSE || account.type == AccountType.ASSET
            Kind.SALE -> account.type == AccountType.REVENUE
        }
        if (!typeAllowed) return Reason.WRONG_TYPE
        if (ModuleOwnedAccounts.useInstead(account, clientType) != null) return Reason.CONTROL_OR_MODULE_OWNED
        return null
    }

    /**
     * The accounts a person may pick for a line of [kind], in chart order, with the Company's Suspense account last as
     * the "not sure" choice. This is the list behind `GET /companies/{id}/accounts?usableFor=`: exactly what
     * [resolve] would accept, so a screen never offers an account GL would refuse.
     */
    fun usableAccounts(accounts: List<Account>, kind: Kind, clientType: ClientType): List<Account> {
        val usable = accounts.filter { !isSuspense(it) && refusalFor(it, kind, clientType) == null }.sortedBy { it.code }
        return usable + accounts.filter { isSuspense(it) && it.active }
    }

    private fun clientTypeOf(companyId: CompanyId): ClientType =
        companyRepository?.findById(companyId)?.clientType ?: ClientType.COMPANY_LIMITED

    private fun isSuspense(account: Account) =
        account.type == AccountType.EQUITY && account.code == ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE
}
