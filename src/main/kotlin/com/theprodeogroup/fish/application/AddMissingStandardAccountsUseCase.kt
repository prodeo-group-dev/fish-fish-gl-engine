package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * `POST /companies/{companyId}/standard-accounts` (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB60): brings an older
 * Company's chart up to the current template for its business type. A Company created before an account joined the
 * template (the VAT control account 2150 is the known case: Company 13de72e4's Sales screen answered 409
 * `vat_control_account_not_configured`) has no way to add it from the product; this does.
 *
 * It only ever ADDS, and only what is missing by code:
 * - A template code the Company does not have is added, with the template's type, classification and name.
 * - A template code the Company already has is left exactly as it is. If that account is of a different type from
 *   the template's, it is reported as a conflict so a person can look; it is never changed or replaced.
 * - Account 1000, if it exists with no cash/bank kind yet, becomes the CASH book (SRS decision D4).
 * - No Bank account is added: a bank account is a further cash book the owner creates as needed (D4).
 *
 * Idempotent: a second run adds nothing. Never touches another Company.
 */
class AddMissingStandardAccountsUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository
) {
    data class AddedAccount(val code: String, val name: String)
    data class Conflict(val code: String, val existingType: AccountType, val templateType: AccountType)

    sealed class Result {
        data class Success(
            val added: List<AddedAccount>,
            val conflicts: List<Conflict>,
            val cashBookKindSet: Boolean
        ) : Result()

        data object CompanyNotFound : Result()
    }

    fun execute(companyId: CompanyId): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val existingByCode = accountRepository.findAllByCompany(companyId).associateBy { it.code }

        val added = mutableListOf<AddedAccount>()
        val conflicts = mutableListOf<Conflict>()
        for (standard in ChartOfAccountsTemplate.accountsFor(company.clientType, companyId)) {
            val existing = existingByCode[standard.code]
            when {
                existing == null -> {
                    accountRepository.save(standard)
                    added += AddedAccount(standard.code, standard.name)
                }
                existing.type != standard.type -> conflicts += Conflict(standard.code, existing.type, standard.type)
            }
        }

        // Account 1000 is the Cash Book in every Company. An older one has no kind until the migration backfill
        // or this run sets it; a Company created from today's template already has it.
        val prime = existingByCode[ChartOfAccountsTemplate.CASH_CODE]
        var cashBookKindSet = false
        if (prime != null && prime.type == AccountType.ASSET && prime.cashBookKind == null) {
            prime.changeCashBookKind(CashBookKind.CASH)
            accountRepository.save(prime)
            cashBookKindSet = true
        }

        return Result.Success(added, conflicts, cashBookKindSet)
    }
}
