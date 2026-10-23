# GL error tokens

**What this is.** Every `{"error": "<token>", "detail": "..."}` the GL Engine can answer, with its HTTP status and what it means in plain words. The `error` token is a stable contract for software; the `detail` is English for support and may change. Written 2026-10-22 (GL Playbook `GL-FR-CTR-3`, task `GL-T-22`) from the code on `master` `b0e0e35`.

**This table is checked by a test** (`ErrorTokenTableTest`): every token the code can answer must appear in the first column below, and every token listed here must still exist in the code. Add a row in the same change as a new token.

Status codes are what the code answers today; a token that appears with two statuses on different routes is listed with both. A refused request never spends an `Idempotency-Key`; only a 2xx is remembered.

## Sign-in, access, service callers

| Token | HTTP | Meaning |
|---|---|---|
| `unauthorized` | 401 | No valid sign-in token. Sign out and in. |
| `forbidden` | 403 | Signed in but not allowed; the detail says why (no Membership in the business, access level too low at this Company, Owner-Admin only, module not granted). |
| `forbidden_endpoint` | 403 | A service login called a GL route that is not on its allow-list. Tell the GL session: the list is missing a route that service really needs. |
| `service_unavailable` | 503 | GL could not reach EA to learn what the person may do, so it refuses rather than guesses. Retry in a minute. |
| `bad_request` | 400 | Something in the request is wrong (missing header, bad id, bad date, bad amount); the detail names it. |
| `internal_error` | 500 | A fault on GL's side. The real detail is in GL's log, not returned. Report the time and the request id. |
| `not_found` | 404 | The thing named does not exist for that business (GL answers the same for another business's id, so it never confirms it exists). |

## Idempotency

| Token | HTTP | Meaning |
|---|---|---|
| `idempotency_key_required` | 400 | A money-posting request needs an `Idempotency-Key` header, so a retry cannot post twice. Always on the cash-book routes and for the Payroll and Cash and Bank Books service logins; logged first for other callers. |
| `idempotency_key_reused` | 422 | This key was already used with a different request body. Send a new key for a different request, or the same body to replay the first answer. |

## Companies, periods, ranges

| Token | HTTP | Meaning |
|---|---|---|
| `company_not_found` | 404 | No such Company (or not one the caller may see). |
| `period_not_found` | 404 | The period named does not exist for this Company. Nothing was recorded. |
| `period_not_open` | 409 | The period named is not open. Nothing was recorded. |
| `no_open_period` | 409 | The Company has no open period to post into. Nothing was recorded. |
| `period_mismatch` | 409 | The period named does not belong to this Company (tax computation). |
| `invalid_range` | 400 | The `to` date is before the `from` date. |
| `invalid_as_of` | 400 | The `asOf` date is not a valid YYYY-MM-DD date. |
| `invalid_days` | 400 | `days` must be a whole number within the cash-balance series limits (the detail gives the minimum and maximum). |
| `range_too_large` | 422 | More postings in the range than the read allows (5,000); the answer carries `rowCount` and `limit`. Narrow the dates. |

## Amounts, lines, journals

| Token | HTTP | Meaning |
|---|---|---|
| `invalid_amount` | 400 | An amount was zero, negative or not a number. |
| `invalid_amounts` | 400 | One or more amounts in the request are invalid. |
| `non_positive_amount` | 400 | An amount that must be positive was not. |
| `invalid_lines` | 400 | The lines are empty or malformed (bad kind, side, amount or id). |
| `invalid_posting` | 400 | A fixed-asset action built an entry that failed its own posting check; the detail lists the errors. |
| `unbalanced_journal` | 400 | Debits do not equal credits in one currency. Nothing was recorded. |
| `currency_mismatch` | 400 | A currency in the request does not match what the route expects. |
| `entry_not_found` | 404 | No such journal entry for this Company. |
| `same_account` | 400 | The two accounts of a move or entry are the same account. |

## Accounts: lookups and configuration

| Token | HTTP | Meaning |
|---|---|---|
| `account_not_found` | 404 | An account named does not exist for this Company. |
| `invalid_account` | 400 | The account named in a journal line cannot take it; the detail says why. |
| `duplicate_code` | 409 | An account with this code already exists. |
| `no_accounts` | 409 | The Company has no Chart of Accounts (velocity reads). |
| `blank_customer_name` | 400 | A customer name was blank. |
| `cash_account_not_found` | 404 | The cash account named does not exist for this Company. |
| `cash_account_not_configured` | 409 | The Company's chart has no cash account (code 1000) GL expected. Create it, then retry. |
| `no_cash_account` | 409 | The Company has no Cash account (cash reads). |
| `ar_control_account_not_found` | 404 | The accounts-receivable control account named does not exist. |
| `ar_control_account_not_configured` | 409 | The chart has no accounts-receivable control account (1100). |
| `ap_control_account_not_found` | 404 | The accounts-payable control account named does not exist. |
| `ap_control_account_not_configured` | 409 | The chart has no accounts-payable control account (2000). |
| `vat_control_account_not_found` | 404 | The VAT control account named does not exist. |
| `vat_control_account_not_configured` | 409 | The chart has no VAT control account (2150). |
| `revenue_account_not_found` | 404 | The revenue account named does not exist. |
| `revenue_account_not_configured` | 409 | The chart has no revenue account GL expected. |
| `expense_account_not_configured` | 409 | The chart has no expense account GL expected. |
| `expense_or_asset_account_not_found` | 404 | The expense or asset account named does not exist. |
| `inventory_asset_account_not_found` | 404 | The inventory asset account named does not exist. |
| `contra_account_not_found` | 404 | The contra account named does not exist. |
| `sales_returns_account_not_found` | 404 | The sales-returns account named does not exist. |
| `discounts_received_account_not_found` | 404 | The discounts-received account named does not exist. |
| `discounts_received_account_not_allowed` | 400 | The discounts-received account must be an income account. |
| `settlement_account_not_found` | 404 | The settlement account named does not exist. |
| `settlement_account_required` | 400 | A settlement line named no cash or bank book. |
| `settlement_account_not_a_cash_book` | 409 | The account the money settles into must be a cash or bank account of the Company (only refused when the rule is enforced). |
| `already_reversed` | 409 | The entry was already reversed (GL's generic journal reversal, `POST /companies/{id}/journal-entries/{entryId}/reverse`); a second reversal is refused, not repeated. |
| `not_reversible` | 409 | The entry is itself a reversal or not posted, so it cannot be reversed; a wrong reversal is corrected with a new journal. |
| `correct_in_cash_and_bank_books` | 409 | The entry was made in a cash or bank book, so GL's journal reversal refuses it: it is corrected in Cash and Bank Books, never in the GL (Femi, 2026-10-24). Every other journal is reversed, with no reconciliation check. |
| `suspense_account_not_configured` | 409 | A line named no account and the Company has no Suspense account (3910). |
| `line_account_not_found` | 404 | A line's account does not exist for this Company; the detail is the id. |
| `line_account_not_allowed` | 400 | A line's account has the wrong type, is inactive, or is a control or module-owned account; the detail is `<id>: <reason>`. |
| `gain_loss_account_not_found` | 404 | The gain or loss account named for a disposal does not exist. |
| `gain_loss_account_not_allowed` | 400 | The gain account must be income and the loss account an expense. |
| `salaries_expense_account_not_found` | 404 | The salaries expense account named does not exist. |
| `salaries_expense_account_not_configured` | 409 | The chart has no salaries expense account. |
| `wages_expense_account_not_found` | 404 | The wages expense account named does not exist. |
| `wages_expense_account_not_configured` | 409 | The chart has no wages expense account. |
| `leave_expense_account_not_found` | 404 | The leave expense account named does not exist. |
| `leave_expense_account_not_configured` | 409 | The chart has no leave expense account. |
| `accrued_leave_liability_account_not_found` | 404 | The accrued-leave liability account named does not exist. |
| `accrued_leave_liability_account_not_configured` | 409 | The chart has no accrued-leave liability account (2200). |

## Cash and bank books, bank reconciliation

| Token | HTTP | Meaning |
|---|---|---|
| `not_a_cash_or_bank_account` | 409 | The account is not a cash or bank book, so it has no book. |
| `prime_cash_book_kind_fixed` | 409 | Account 1000 is the Cash Book and stays a cash account; add a bank account instead. |
| `kind_requires_asset_account` | 409 | Only an asset account can be a cash or bank book. |
| `reconciliations_exist` | 409 | This bank book has reconciliations, so it stays a bank account. |
| `counter_account_not_found` | 404 | The other side of a receipt or payment does not exist for this Company. |
| `counter_account_inactive` | 409 | The other side of a receipt or payment is inactive. |
| `counter_account_not_allowed` | 409 | The other side is a control or module-owned account; the answer carries a `useInstead` token naming the right screen (sales collection, supplier payment, inventory, fixed assets, VAT, opening figures, transfer). |
| `undo_elsewhere` | 409 | This entry was not made in a cash or bank book, so it cannot be undone here; the answer says where to reverse it. |
| `already_undone` | 409 | The entry was already reversed. |
| `not_undoable` | 409 | This entry cannot be undone. |
| `reconciliation_not_found` | 404 | No such bank reconciliation for this Company. |
| `reconciliation_requires_bank_account` | 409 | Only a bank account can be reconciled (only refused when the rule is enforced). |
| `invalid_match` | 409 | That statement line and entry cannot be matched; the detail gives the reason. |
| `invalid_unmatch` | 409 | That pair is not matched, so it cannot be unmatched. |
| `not_open` | 409 | The reconciliation is already completed or cancelled and cannot be changed. |
| `not_fully_matched` | 409 | Every statement line must be matched before the reconciliation can be completed; the answer lists the unmatched lines. |
| `balance_difference` | 409 | The statement balance and the ledger do not agree (only refused when the tie-out is enforced); the answer carries the figures. |

## Fixed assets, leave

| Token | HTTP | Meaning |
|---|---|---|
| `fixed_asset_not_found` | 404 | No such fixed asset for this Company. |
| `invalid_fixed_asset` | 400 | The fixed-asset request is invalid (cost, life, date or category). |
| `fixed_asset_account_not_found` | 404 | The fixed-asset account named does not exist. |
| `accumulated_depreciation_account_not_found` | 404 | The accumulated-depreciation account named does not exist. |
| `depreciation_expense_account_not_found` | 404 | The depreciation-expense account named does not exist. |
| `impairment_expense_account_not_found` | 404 | The impairment-expense account named does not exist. |
| `accumulated_impairment_account_not_found` | 404 | The accumulated-impairment account named does not exist. |
| `accumulated_impairment_account_required` | 400 | The asset has an impairment, so disposing of it needs the accumulated-impairment account. |
| `already_disposed` | 409 | The asset was already disposed of. |
| `proceeds_negative` | 400 | Disposal proceeds cannot be negative. |
| `proceeds_currency_mismatch` | 400 | The proceeds are in a different currency from the asset's. |
| `leave_accrual_not_found` | 404 | No such leave accrual. |
| `nothing_to_utilize` | 409 | The leave accrual has nothing left to pay out. |
| `no_change_needed` | 409 | The leave accrual already equals the new target; nothing to post. |

## Tax and VAT

| Token | HTTP | Meaning |
|---|---|---|
| `no_tax_rule` | 409 | No Corporate Income Tax rule is configured for the Company's jurisdiction. |
| `invalid_tax_inputs` | 400 | The tax computation inputs are missing or not valid for this rule; the detail says which. |
| `no_vat_rate_schedule` | 409 | The Company's country has no verified VAT rates; GL refuses rather than guess. |
| `vat_category_not_supported` | 400 | The VAT category does not exist in that country. `GET /companies/{id}/vat-categories` lists what is allowed. |
| `vat_rate_not_in_force` | 400 | The rate table has no row for that category on the transaction date. |
| `vat_amount_on_every_line` | 400 | A sale sent a finished `vatAmount` on some lines and not others; send it on every line (the caller worked the VAT out) or on none (GL computes it). |
| `vat_incomplete` | 400 | A cash or bank book receipt or payment sent only one of `vatCategory` and `vatAmount`; send both or neither. |
| `vat_exceeds_amount` | 400 | The VAT is not less than the amount, which includes it, so the net would be nothing. |
| `vat_not_applicable` | 400 | VAT was sent for an account that is not income (money in) or expense (money out). |

## Company creation, imports

| Token | HTTP | Meaning |
|---|---|---|
| `currency_not_supported_for_jurisdiction` | 409 | A Company in that jurisdiction is created in the jurisdiction's currency, not the one requested. |
| `invalid_csv` | 400 | The opening-figures CSV could not be read; the detail says why. |
