package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.common.ValidationResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.LocalDate
import java.time.ZoneOffset

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
    expenseClassification: ExpenseClassification?,
    val parentId: AccountId?,
    cashBookKind: CashBookKind? = null,
    formerCashBookKind: CashBookKind? = null,
    stoppedOn: LocalDate? = null
) {
    /**
     * How an Expense account is grouped in the trading P&L (cost of sales,
     * operating, interest, tax). Changeable after creation (2026-10-07,
     * [reclassifyExpense]) because it is a REPORTING attribute: it regroups
     * existing balances, it never changes them.
     */
    var expenseClassification: ExpenseClassification? = expenseClassification
        private set

    /**
     * Whether this ASSET account is a cash or a bank account with its own book (docs/GL_Cash_And_Bank_Books_SRS.md,
     * FR-CB01). `null` = no book. Designated once after creation if it was not set at creation ([changeCashBookKind]); it never
     * switches between cash and bank afterwards (GL-T-18). It changes what the account can DO, never a balance.
     */
    var cashBookKind: CashBookKind? = cashBookKind
        private set

    /**
     * The kind this account had when its owner last stopped using it as a book (V38, GL-T-18); `null` for an account that was never
     * stopped. Never cleared once set. It is what tells Cash and Bank Books a STOPPED book from an account that was never a book, so a
     * final balance can still be reconciled on the statement date.
     */
    var formerCashBookKind: CashBookKind? = formerCashBookKind
        private set

    /** The UTC day the owner last stopped using this account as a book; `null` while it is a book again, or if it was never stopped. */
    var stoppedOn: LocalDate? = stoppedOn
        private set

    var active: Boolean = true
        private set

    /**
     * `internal`, not `private` (added 2026-08-19) - `AccountRepository`
     * implementations need to read this to persist it, the same
     * "shared within the module, not part of the public domain API"
     * reasoning as `reconstitute()`. The setter stays `private` -
     * [recordActivity] (or `reconstitute()`, via companion-object
     * access) are still the only way to change it.
     */
    internal var hasPostedActivity: Boolean = false
        private set

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

    /**
     * Re-tags (or clears, with `null`) this Expense account's reporting
     * classification. Only an EXPENSE account carries one. Allowed after the
     * account has posted activity: it regroups the trading P&L, it does not
     * touch any balance.
     */
    fun reclassifyExpense(newClassification: ExpenseClassification?): ValidationResult {
        if (!type.requiresExpenseClassification()) {
            return ValidationResult.failure("AccountType.$type does not use an expense classification")
        }
        expenseClassification = newClassification
        return ValidationResult.success()
    }

    /**
     * Sets (or clears, `null`) this account's cash/bank kind. Only an ASSET account may have one.
     * GL-T-18 (Femi, 2026-10-23, Decisions_Log "A cash or bank book's kind never changes"): once an account is a cash book it can
     * never become a bank account, nor a bank account a cash book. The kind is set from `null` once, or repeated unchanged; any switch
     * to the other kind is refused here, as the second wall behind the use case.
     */
    fun changeCashBookKind(newKind: CashBookKind?, on: LocalDate = LocalDate.now(ZoneOffset.UTC)): ValidationResult {
        if (newKind != null && type != AccountType.ASSET) {
            return ValidationResult.failure("Only an ASSET account can be a cash or bank account (this is $type)")
        }
        val current = cashBookKind
        if (current != null && newKind != null && newKind != current) {
            return ValidationResult.failure("A cash book stays a cash book and a bank account stays a bank account (this is $current)")
        }
        if (newKind == null && current != null) {
            // Stopping use of a book (Femi, 2026-10-23: the owner's prerogative). Remember what it was and when; touch nothing posted.
            formerCashBookKind = current
            stoppedOn = on
        } else if (newKind != null) {
            stoppedOn = null
        }
        cashBookKind = newKind
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
            id: AccountId = AccountId.generate(),
            cashBookKind: CashBookKind? = null
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
            require(cashBookKind == null || type == AccountType.ASSET) {
                "AccountType.$type cannot be a cash or bank account - only an ASSET account can"
            }
            return Account(id, companyId, type, classification, code, name, expenseClassification, parentId, cashBookKind)
        }

        /**
         * Rebuilds an `Account` from persisted data, bypassing [create]'s
         * validation - a row that was already saved was already valid
         * when it was saved, so re-validating on every load is pure
         * overhead. `internal`, not public - only `AccountRepository`
         * implementations should call this, matching the `internal`
         * visibility already used for `hasHistoricalEffect()`
         * (`account_balances.kt`) for the same "shared within the
         * module, not part of the public domain API" reasoning.
         */
        internal fun reconstitute(
            id: AccountId,
            companyId: CompanyId,
            type: AccountType,
            classification: AccountClassification?,
            code: String,
            name: String,
            expenseClassification: ExpenseClassification?,
            parentId: AccountId?,
            active: Boolean,
            hasPostedActivity: Boolean,
            cashBookKind: CashBookKind? = null,
            formerCashBookKind: CashBookKind? = null,
            stoppedOn: LocalDate? = null
        ): Account {
            val account = Account(id, companyId, type, classification, code, name, expenseClassification, parentId, cashBookKind, formerCashBookKind, stoppedOn)
            account.active = active
            account.hasPostedActivity = hasPostedActivity
            return account
        }
    }
}
