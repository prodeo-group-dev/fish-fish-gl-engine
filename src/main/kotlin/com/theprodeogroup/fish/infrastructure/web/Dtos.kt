package com.theprodeogroup.fish.infrastructure.web

import kotlinx.serialization.Serializable

/**
 * Wire-format request/response shapes for the web layer (docs/DDD_Design.md
 * Section 10.19) - deliberately separate types from the domain model,
 * not `@Serializable` domain classes. Domain value classes (`AccountId`,
 * `Money`) and `BigDecimal`/`Currency` don't serialize cleanly with
 * kotlinx.serialization without custom serializers, and keeping the
 * wire format decoupled from the domain model means a future domain
 * refactor doesn't automatically become a breaking API change. Route
 * handlers parse these into domain types explicitly (and validate the
 * parse - a malformed UUID/amount is a 400, not a 500).
 */
@Serializable
data class ErrorResponseDto(val error: String, val detail: String? = null)

@Serializable
data class JournalLineDto(
    val accountId: String,
    val amount: String,
    val currency: String,
    val side: String,
    val dimensions: Map<String, String> = emptyMap()
)

@Serializable
data class PostJournalEntryRequestDto(
    val periodId: String,
    val date: String,
    val lines: List<JournalLineDto>,
    val source: String,
    val description: String? = null
)

@Serializable
data class JournalEntryResponseDto(
    val id: String,
    val status: String
)

/**
 * The HR/Payroll posting interface's wire shapes (docs/DDD_Design.md
 * Section 10.20) - `RecordPayRun`/`RemeasureLeaveAccrual`/`UtilizeLeaveAccrual`
 * are the fixed contract the (separate, not-built-here) HR/Payroll
 * system calls into, per the already-confirmed HR/Payroll separation.
 * `PostPayRunRequestDto`/`PostPayRunResponseDto` (the persisted-lookup-
 * by-ID flow) were removed 2026-09-01 alongside `PostPayRunUseCase` -
 * dead, confirmed unused by `fish-hr-payroll`'s own gateway.
 */
@Serializable
data class RemeasureLeaveAccrualRequestDto(
    val targetAmount: String,
    val currency: String,
    val leaveExpenseAccountId: String,
    val accruedLeaveLiabilityAccountId: String,
    val periodId: String,
    val date: String
)

@Serializable
data class UtilizeLeaveAccrualRequestDto(
    val amount: String,
    val currency: String,
    val cashAccountId: String,
    val accruedLeaveLiabilityAccountId: String,
    val periodId: String,
    val date: String
)

/**
 * Shared response shape for both `LeaveAccrual` endpoints -
 * [journalEntryId]/[journalEntryStatus] are `null` specifically for
 * `RemeasureLeaveAccrualResult.NoChangeNeeded` (a legitimate "nothing
 * to post" outcome, not an error - see [RemeasureLeaveAccrualResult]'s
 * own KDoc), the one case where a successful call posts nothing.
 */
@Serializable
data class LeaveAccrualResponseDto(
    val leaveAccrualId: String,
    val balanceAmount: String,
    val balanceCurrency: String,
    val journalEntryId: String? = null,
    val journalEntryStatus: String? = null
)

@Serializable
data class InventoryPostingContextResponseDto(
    val periodId: String,
    val apControlAccountId: String,
    val currency: String
)

@Serializable
data class PurchasePostingContextResponseDto(
    val periodId: String,
    val apControlAccountId: String,
    val expenseOrAssetAccountId: String,
    val settlementAccountId: String,
    val currency: String,
    val facilityLiabilityAccountId: String? = null
)

@Serializable
data class PayrollPostingContextResponseDto(
    val periodId: String,
    val wagesExpenseAccountId: String,
    val salariesExpenseAccountId: String,
    val cashAccountId: String,
    val accruedLeaveLiabilityAccountId: String,
    val leaveExpenseAccountId: String,
    val currency: String
)

/**
 * Wire shapes for `RecordSaleUseCase`/`RecordCollectionUseCase`
 * (docs/Sales_Order_Processing_DDD_Design.md Section 0) - the two thin
 * posting interfaces the separate, not-built-here `fish-sales-order-
 * processing` (SOP) system calls into. Unlike every other request DTO
 * in this file, [RecordSaleRequestDto.companyId]/
 * [RecordCollectionRequestDto.companyId] are included directly -
 * neither use case has an owning aggregate in this repo to resolve
 * tenant scoping from.
 */
@Serializable
data class RecordSaleRequestDto(
    val companyId: String,
    val periodId: String,
    val date: String,
    val arControlAccountId: String,
    val revenueAccountId: String,
    val amount: String,
    val currency: String,
    val customerId: String,
    val description: String? = null
)

@Serializable
data class RecordSaleResponseDto(
    val journalEntryId: String,
    val status: String
)

@Serializable
data class RecordCollectionRequestDto(
    val companyId: String,
    val periodId: String,
    val date: String,
    val settlementAccountId: String,
    val arControlAccountId: String,
    val amount: String,
    val currency: String,
    val customerId: String,
    val description: String? = null
)

@Serializable
data class RecordCollectionResponseDto(
    val journalEntryId: String,
    val status: String
)

/**
 * Wire shapes for `CreateSalesInvoiceUseCase` - the business-owner-
 * facing "record a sale" form behind the SOP dashboard tab. Unlike
 * [RecordSaleRequestDto], the caller supplies no `periodId`/account
 * IDs/`customerId` - just what a person filling out a form actually
 * knows (sale type, a customer name, an amount, an optional item
 * description). `date` is optional and defaults to today server-side
 * when blank.
 */
@Serializable
data class CreateSalesInvoiceRequestDto(
    val companyId: String,
    val saleType: String,
    val saleMethod: String,
    val customerName: String? = null,
    val amount: String,
    val currency: String,
    val date: String? = null,
    val description: String? = null
)

/** `GET /companies/{companyId}/accounts` - the Chart of Accounts, for a manual journal entry form's line-item account picker. */
@Serializable
data class AccountSummaryDto(
    val accountId: String,
    val code: String,
    val name: String,
    val type: String,
    val classification: String? = null
)

/**
 * `POST /companies/{companyId}/accounts` (2026-09-03, Chart of
 * Accounts setup). [classification] is CURRENT or NON_CURRENT for
 * Asset/Liability accounts - "Fixed" (Asset) or "Long term"
 * (Liability) is NON_CURRENT, "Current" is CURRENT for either type;
 * omit for Equity/Revenue/Expense, which don't use it.
 */
@Serializable
data class CreateAccountRequestDto(
    val type: String,
    val code: String,
    val name: String,
    val classification: String? = null,
    val expenseClassification: String? = null,
    val parentId: String? = null
)

/** `POST /companies/{companyId}/accounts/{accountId}/opening-balance` - [amount] is always positive; the account's own normal balance decides debit vs. credit. */
@Serializable
data class RecordOpeningBalanceRequestDto(
    val amount: String,
    val date: String
)

@Serializable
data class OpeningBalanceResponseDto(
    val journalEntryId: String,
    val status: String
)

/** One line within a [JournalEntryRecordDto] - the account resolved to its code/name, not left as a bare id. */
@Serializable
data class JournalEntryRecordLineDto(
    val accountCode: String,
    val accountName: String,
    val side: String,
    val amount: String,
    val currency: String
)

/** `GET /companies/{companyId}/journal-entries` - the "Journal timeline," every entry posted on this Company, newest first. */
@Serializable
data class JournalEntryRecordDto(
    val id: String,
    val date: String,
    val description: String?,
    val status: String,
    val source: String,
    val lines: List<JournalEntryRecordLineDto>
)

/** `GET /companies/{companyId}/customers` - the "Schedule of Customers," and the SOP sale form's customer picker source. */
@Serializable
data class CustomerSummaryDto(
    val customerId: String,
    val name: String,
    val balance: String,
    val currency: String
)

/** `GET /companies/{companyId}/sales-invoices` - the "listing of sales (each timestamped)." */
@Serializable
data class SalesInvoiceRecordDto(
    val invoiceNumber: String,
    val journalEntryId: String,
    val customerId: String,
    val customerName: String,
    val saleType: String,
    val saleMethod: String,
    val amount: String,
    val currency: String,
    val paid: Boolean,
    val description: String?,
    val recordedAt: String
)

@Serializable
data class CreateSalesInvoiceResponseDto(
    val invoiceNumber: String,
    val journalEntryId: String,
    val status: String,
    val paid: Boolean,
    val date: String,
    val companyId: String,
    val companyName: String,
    val customerName: String,
    val saleType: String,
    val saleMethod: String,
    val description: String?,
    val amount: String,
    val currency: String
)

/**
 * Wire shapes for `RecordVendorObligationUseCase`/`RecordVendorPaymentUseCase`
 * (docs/Purchase_Order_Processing_DDD_Design.md Section 0) - the
 * Purchasing mirror of `RecordSaleRequestDto`/`RecordCollectionRequestDto`
 * above, for the separate, not-built-here `fish-purchase-order-
 * processing` (POP) system. Same reasoning: `companyId` is included
 * directly since neither use case has an owning aggregate in this repo
 * to resolve tenant scoping from.
 */
@Serializable
data class RecordVendorObligationRequestDto(
    val companyId: String,
    val periodId: String,
    val date: String,
    val expenseOrAssetAccountId: String,
    val apControlAccountId: String,
    val amount: String,
    val currency: String,
    val vendorId: String,
    val description: String? = null
)

@Serializable
data class RecordVendorObligationResponseDto(
    val journalEntryId: String,
    val status: String
)

@Serializable
data class RecordVendorPaymentRequestDto(
    val companyId: String,
    val periodId: String,
    val date: String,
    val apControlAccountId: String,
    val settlementAccountId: String,
    val amount: String,
    val currency: String,
    val vendorId: String,
    val description: String? = null
)

@Serializable
data class RecordVendorPaymentResponseDto(
    val journalEntryId: String,
    val status: String
)

/**
 * Wire shapes for `RecordInventoryReceiptUseCase`/`RecordInventoryIssueUseCase`
 * (Option B resolution, `docs/Ecosystem_Extraction_DDD_Design.md` Section
 * 1.3) - Inventory's mirror of `RecordSaleRequestDto`/`RecordCollectionRequestDto`
 * above, for the separate, not-built-here `fish-inventory-management`
 * (IM) system, which now owns the whole IAS 2 costing engine. Same
 * reasoning: `companyId` is included directly since neither use case
 * has an owning aggregate in this repo to resolve tenant scoping from.
 * `committedCost`/`committedCostCurrency` (a caller-supplied total, not
 * a per-unit figure) reflect that IM has already computed the costing;
 * this repo only records the financial effect. The older, StockItem-based
 * `PostInventoryReceipt`/`PostInventoryIssue` posting interface these
 * once stood alongside was retired 2026-09-01 ("Retire GL's StockItem
 * from its legacy costing").
 */
@Serializable
data class RecordInventoryReceiptRequestDto(
    val companyId: String,
    val periodId: String,
    val date: String,
    val inventoryAssetAccountId: String,
    val contraAccountId: String,
    val committedCost: String,
    val committedCostCurrency: String,
    val itemId: String,
    val description: String? = null
)

@Serializable
data class RecordInventoryReceiptResponseDto(
    val journalEntryId: String,
    val status: String
)

@Serializable
data class RecordInventoryIssueRequestDto(
    val companyId: String,
    val periodId: String,
    val date: String,
    val contraAccountId: String,
    val inventoryAssetAccountId: String,
    val committedCost: String,
    val committedCostCurrency: String,
    val itemId: String,
    val description: String? = null
)

@Serializable
data class RecordInventoryIssueResponseDto(
    val journalEntryId: String,
    val status: String
)

/**
 * Wire shapes for `RecordPayRunUseCase`/`GetOrCreateLeaveAccrualUseCase`
 * - the HR/Payroll counterpart to `RecordSaleUseCase`/`RecordCollectionUseCase`
 * above, same "no owning aggregate here, so `companyId` travels in the
 * request body" reasoning.
 */
@Serializable
data class RecordPayRunRequestDto(
    val companyId: String,
    val periodId: String,
    val date: String,
    val totalWages: String,
    val totalSalaries: String,
    val currency: String,
    val wagesExpenseAccountId: String,
    val salariesExpenseAccountId: String,
    val cashAccountId: String
)

@Serializable
data class RecordPayRunResponseDto(
    val journalEntryId: String,
    val status: String
)

@Serializable
data class GetOrCreateLeaveAccrualRequestDto(
    val companyId: String,
    val employeeId: String,
    val currency: String
)

/**
 * Section 9.2's onboarding wire shapes. Deliberately no `adminEmail`
 * field on the request - the admin's identity comes from the verified
 * JWT's `email` claim ([VerifiedIdentity]), never from caller-supplied
 * request data, matching [FISH_JWT_ONBOARDING_AUTH_NAME]'s whole point.
 *
 * `openingCashBalance` is optional and nullable, not required - most
 * users onboarding are expected to have incomplete records, not a full
 * opening trial balance, so this shouldn't be a mandatory field forcing
 * a value nobody has yet (see `OnboardTenantUseCase`'s own KDoc).
 */
/**
 * "Will you manage this yourself, or will someone else?" per
 * ManagedModule (docs/DDD_Design.md-adjacent, 2026-08-29) - see
 * ModuleManagementPreference's own KDoc. [delegateName]/[delegateEmail]
 * are required together when [selfManaged] is false; the route validates
 * that pairing before it ever reaches the domain factory.
 */
@Serializable
data class ModuleManagementPreferenceDto(
    val module: String,
    val selfManaged: Boolean,
    val delegateName: String? = null,
    val delegateEmail: String? = null
)

@Serializable
data class OnboardTenantRequestDto(
    val tenantName: String,
    val tenantSegment: String,
    val tenantBaseCurrency: String,
    val companyName: String,
    val clientType: String,
    val jurisdiction: String,
    val companyBaseCurrency: String,
    /** 1 (January) through 12 (December) - "the fiscal year has to be set during Tenant onboarding" (2026-09-02), required, no default. */
    val fiscalYearStartMonth: Int,
    val adminName: String,
    val openingCashBalance: String? = null,
    val moduleManagementPreferences: List<ModuleManagementPreferenceDto> = emptyList()
)

@Serializable
data class OnboardTenantResponseDto(
    val tenantId: String,
    val companyId: String,
    val adminUserId: String,
    val adminMembershipId: String,
    val openingBalanceJournalEntryId: String? = null
)

@Serializable
data class AddCompanyToTenantRequestDto(
    val companyName: String,
    val clientType: String,
    val jurisdiction: String,
    val companyBaseCurrency: String,
    /** 1 (January) through 12 (December) - same requirement as OnboardTenantRequestDto's own field. */
    val fiscalYearStartMonth: Int,
    val openingCashBalance: String? = null
)

@Serializable
data class AddCompanyToTenantResponseDto(
    val tenantId: String,
    val companyId: String,
    val openingBalanceJournalEntryId: String? = null
)

@Serializable
data class RecordAdminPhoneNumberRequestDto(val phoneNumber: String)

@Serializable
data class RecordAdminPhoneNumberResponseDto(
    val tenantId: String,
    val adminPhoneVerificationStatus: String
)

@Serializable
data class CompanySummaryDto(
    val id: String,
    val name: String
)

@Serializable
data class MyTenantDto(
    val tenantId: String,
    val tenantName: String,
    val role: String,
    val tenantStatus: String,
    val kybStatus: String,
    val adminKycStatus: String,
    val adminPhoneNumber: String?,
    val adminPhoneVerificationStatus: String,
    val phoneVerificationDeadline: String?,
    val companies: List<CompanySummaryDto>,
    val grantedModules: List<String>
)

@Serializable
data class MyProfileResponseDto(
    val email: String,
    val name: String,
    val tenants: List<MyTenantDto>
)

@Serializable
data class InviteStaffMemberRequestDto(
    val email: String,
    val name: String,
    val role: String,
    val modules: List<String>
)

@Serializable
data class InviteStaffMemberResponseDto(
    val userId: String,
    val membershipId: String,
    val role: String,
    val alreadyMember: Boolean,
    val notificationSent: Boolean,
    val grantedModules: List<String>
)

@Serializable
data class MembershipDto(
    val membershipId: String,
    val userId: String,
    val name: String,
    val email: String,
    val role: String,
    val status: String,
    val grantedModules: List<String>
)

@Serializable
data class ComputeTaxRequestDto(
    val periodId: String,
    val category: String? = null,
    val turnover: String? = null,
    val fixedAssets: String? = null
)

@Serializable
data class TaxComputationDto(
    val id: String,
    val companyId: String,
    val periodId: String,
    val taxRuleId: String,
    val taxableProfit: String,
    val taxDue: String,
    val currency: String,
    val computedAt: String
)

@Serializable
data class SalesPostingContextResponseDto(
    val periodId: String,
    val arControlAccountId: String,
    val revenueAccountId: String,
    val currency: String
)

@Serializable
data class MoneyVelocityResponseDto(
    val periodId: String,
    val periodStartDate: String,
    val netIncome: String,
    val dailyRate: String,
    val currency: String,
    val daysElapsed: Long
)

@Serializable
data class ExpenseVelocityResponseDto(
    val periodId: String,
    val periodStartDate: String,
    val operatingExpense: String,
    val dailyRate: String,
    val currency: String,
    val daysElapsed: Long
)

@Serializable
data class SalesToExpenseRatioResponseDto(
    val periodId: String,
    val periodStartDate: String,
    val totalRevenue: String,
    val operatingExpense: String,
    val currency: String,
    val ratio: String
)

/** One line within a [BalanceSheetResponseDto]'s asset/liability/equity section. */
@Serializable
data class BalanceSheetLineDto(
    val accountId: String,
    val code: String,
    val name: String,
    val classification: String?,
    val balance: String
)

/** `GET /companies/{companyId}/reports/balance-sheet` - the "Reports" sub-page's Balance sheet report. */
@Serializable
data class BalanceSheetResponseDto(
    val currency: String,
    val assetLines: List<BalanceSheetLineDto>,
    val liabilityLines: List<BalanceSheetLineDto>,
    val equityLines: List<BalanceSheetLineDto>,
    val retainedEarnings: String,
    val totalAssets: String,
    val totalLiabilities: String,
    val totalEquity: String,
    val isBalanced: Boolean
)

/** `GET /companies/{companyId}/reports/profit-and-loss` - the "Reports" sub-page's Profit and loss report. */
@Serializable
data class ProfitAndLossResponseDto(
    val periodId: String,
    val currency: String,
    val totalRevenue: String,
    val totalExpense: String,
    val netIncome: String
)

/** One IAS 7 activity category's net movement within a [CashFlowResponseDto]. */
@Serializable
data class CashFlowActivityAmountDto(
    val activity: String,
    val netAmount: String
)

/** `GET /companies/{companyId}/reports/cash-flow` - the "Reports" sub-page's Cash flow report. */
@Serializable
data class CashFlowResponseDto(
    val currency: String,
    val startDate: String,
    val endDate: String,
    val openingBalance: String,
    val closingBalance: String,
    val netCashFlow: String,
    val activityAmounts: List<CashFlowActivityAmountDto>,
    val uncategorizedAmount: String
)

@Serializable
data class CreateFixedAssetRequestDto(
    val companyId: String,
    val name: String,
    val category: String,
    val cost: String,
    val currency: String,
    val acquisitionDate: String,
    val usefulLifeYears: Int? = null
)

/** Shared shape for a single Fixed Asset - a `POST /fixed-assets` response and one [FixedAssetRegisterResponseDto] line alike. */
@Serializable
data class FixedAssetSummaryDto(
    val id: String,
    val companyId: String,
    val name: String,
    val category: String,
    val cost: String,
    val currency: String,
    val acquisitionDate: String,
    val usefulLifeYears: Int?,
    val accumulatedDepreciation: String,
    val accumulatedImpairmentLoss: String,
    val netBookValue: String,
    val carryingAmount: String,
    val isDisposed: Boolean
)

/** `GET /companies/{companyId}/reports/fixed-asset-register`. */
@Serializable
data class FixedAssetRegisterResponseDto(
    val currency: String,
    val lines: List<FixedAssetSummaryDto>,
    val totalCost: String,
    val totalAccumulatedDepreciation: String,
    val totalAccumulatedImpairmentLoss: String,
    val totalNetBookValue: String,
    val totalCarryingAmount: String
)

@Serializable
data class RecordFixedAssetDepreciationRequestDto(
    val depreciationExpenseAccountId: String,
    val accumulatedDepreciationAccountId: String,
    val periodId: String,
    val date: String
)

@Serializable
data class AssessFixedAssetImpairmentRequestDto(
    val recoverableAmount: String,
    val currency: String,
    val impairmentExpenseAccountId: String,
    val accumulatedImpairmentAccountId: String,
    val periodId: String,
    val date: String
)

@Serializable
data class DisposeFixedAssetRequestDto(
    val proceeds: String,
    val currency: String,
    val cashAccountId: String,
    val fixedAssetAccountId: String,
    val accumulatedDepreciationAccountId: String,
    val saleOfFixedAssetAccountId: String,
    val periodId: String,
    val date: String,
    val accumulatedImpairmentAccountId: String? = null
)

/** Response for the three fixed-asset posting routes (depreciation/impairment/disposal) - just the resulting `JournalEntry` plus the asset's refreshed figures. */
@Serializable
data class FixedAssetPostingResponseDto(
    val journalEntryId: String,
    val journalEntryStatus: String,
    val fixedAsset: FixedAssetSummaryDto
)
