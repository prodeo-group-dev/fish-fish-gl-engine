package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.ValidationResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * A Chart of Accounts entry belonging to a Company (docs/DDD_Design.md
 * Section 3.1): type is one of Asset/Liability/Equity/Revenue/Expense,
 * optionally organized into a parent/child hierarchy.
 *
 * Construction-time invariants (classification requirement, self-parent)
 * throw `IllegalArgumentException` via `require()`, matching `Money`'s
 * precedent for constructor-level violations. Post-construction state
 * transitions ([deactivate]/[reactivate]/[validateDeletion]) return
 * [ValidationResult], matching `Tenant`'s precedent for expected
 * domain-rule outcomes on an already-valid object.
 *
 * **Only a self-parent cycle is checked here.** Deeper multi-level cycles
 * (A -> B -> C -> A) and cross-Company parent references need the full
 * Account tree, which a single aggregate can't see in isolation - that
 * check belongs at the application-service level, the same way
 * `PostingService` checks `Period.status` outside `JournalEntry` itself
 * (Section 3.1's design note on `Period`).
 *
 * [expenseClassification] (added 2026-08-12, `ExpenseClassification`)
 * is a second, optional sub-classification alongside [classification] -
 * unlike [classification], which `AccountType.requiresClassification()`
 * enforces as mandatory for Asset/Liability, `expenseClassification` is
 * never required even for Expense accounts (see
 * `AccountType.requiresExpenseClassification()`'s KDoc for why) - only
 * enforced in the one direction that a non-Expense type can't be given
 * one.
 */
class Account private constructor(
    val id: AccountId,
    val companyId: CompanyId,
    val type: AccountType,
    val classification: AccountClassification?,
    val code: String,
    val name: String,
    val expenseClassification: ExpenseClassification?,
    val parentId: AccountId?
) {
    var active: Boolean = true
        private set

    private var hasPostedActivity: Boolean = false

    /** Called by the application service when a JournalEntry posts against this Account. */
    fun recordActivity() {
        hasPostedActivity = true
    }

    /** Section 6: an Account with posted activity cannot be hard-deleted - deactivate instead. */
    fun validateDeletion(): ValidationResult {
        return if (hasPostedActivity) {
            ValidationResult.failure("Cannot delete an Account with posted activity - deactivate it instead")
        } else {
            ValidationResult.success()
        }
    }

    fun deactivate(): ValidationResult {
        if (!active) {
            return ValidationResult.failure("Account is already inactive")
        }
        active = false
        return ValidationResult.success()
    }

    fun reactivate(): ValidationResult {
        if (active) {
            return ValidationResult.failure("Account is already active")
        }
        active = true
        return ValidationResult.success()
    }

    companion object {
        fun create(
            companyId: CompanyId,
            type: AccountType,
            classification: AccountClassification?,
            code: String,
            name: String,
            expenseClassification: ExpenseClassification? = null,
            parentId: AccountId? = null,
            id: AccountId = AccountId.generate()
        ): Account {
            if (type.requiresClassification()) {
                require(classification != null) {
                    "AccountType.$type requires a current/non-current classification"
                }
            } else {
                require(classification == null) {
                    "AccountType.$type does not use a current/non-current classification"
                }
            }
            if (!type.requiresExpenseClassification()) {
                require(expenseClassification == null) {
                    "AccountType.$type does not use an expense classification"
                }
            }
            require(parentId != id) {
                "An Account cannot be its own parent"
            }
            return Account(id, companyId, type, classification, code, name, expenseClassification, parentId)
        }
    }
}
