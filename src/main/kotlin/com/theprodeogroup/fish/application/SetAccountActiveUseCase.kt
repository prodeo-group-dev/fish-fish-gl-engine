package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.ModuleOwnedAccounts
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * `PUT /companies/{companyId}/accounts/{accountId}/active`: the owner inactivates an account, or reactivates it (Femi, 2026-10-23:
 * "the Business owner will determine the life span of his accounts").
 *
 * Inactivating is never blocked for a balance, open lines or unreconciled items: an inactive account keeps every posting and its balance in
 * every report; it only takes no NEW postings from modules, manual journals or cash book entries. The one refusal is an account the system
 * itself posts to by code ([ModuleOwnedAccounts.requiredBySystem]), because inactivating it would make sales, purchases, payroll or the cash
 * book fail. Reactivating is always allowed, and a repeat of the state it is already in is a success that changes nothing.
 * An account of another Company is reported as not found (never a distinct "forbidden").
 */
class SetAccountActiveUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository
) {
    sealed class Result {
        data class Success(val account: Account) : Result()
        data object AccountNotFound : Result()

        /** The system posts to this account by its code (the Cash Book, a control or module-owned account, Suspense), so it stays active. */
        data object RequiredBySystem : Result()
    }

    fun execute(companyId: CompanyId, accountId: AccountId, active: Boolean): Result {
        val company = companyRepository.findById(companyId) ?: return Result.AccountNotFound
        val account = accountRepository.findById(accountId)?.takeIf { it.companyId == companyId } ?: return Result.AccountNotFound
        if (account.active == active) return Result.Success(account)
        if (!active && ModuleOwnedAccounts.requiredBySystem(account, company.clientType)) return Result.RequiredBySystem

        if (active) account.reactivate() else account.deactivate()
        accountRepository.save(account)
        return Result.Success(account)
    }
}
