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

@Serializable
data class PostPurchaseOrderRequestDto(
    val periodId: String,
    val apControlAccountId: String
)

@Serializable
data class PostPurchaseOrderResponseDto(
    val purchaseOrderId: String,
    val status: String,
    val journalEntryId: String
)

/**
 * The HR/Payroll posting interface's wire shapes (docs/DDD_Design.md
 * Section 10.20) - `PostPayRun`/`RemeasureLeaveAccrual`/`UtilizeLeaveAccrual`
 * are the fixed contract the (separate, not-built-here) HR/Payroll
 * system calls into, per the already-confirmed HR/Payroll separation.
 */
@Serializable
data class PostPayRunRequestDto(
    val periodId: String,
    val wagesExpenseAccountId: String,
    val salariesExpenseAccountId: String,
    val cashAccountId: String
)

@Serializable
data class PostPayRunResponseDto(
    val payRunId: String,
    val journalEntryId: String,
    val journalEntryStatus: String
)

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

/**
 * Inventory Management's standalone posting interface's wire shapes
 * (docs/DDD_Design.md Section 10.21) - `PostInventoryReceipt`/
 * `PostInventoryIssue` are the fixed contract the separate Inventory
 * Management system calls into, the same treatment given HR/Payroll's
 * `PostPayRun`/`RemeasureLeaveAccrual`/`UtilizeLeaveAccrual` in Section
 * 10.20.
 */
@Serializable
data class PostInventoryReceiptRequestDto(
    val quantityReceived: String,
    val costReceived: String,
    val costCurrency: String,
    val inventoryAssetAccountId: String,
    val contraAccountId: String,
    val periodId: String,
    val date: String
)

@Serializable
data class PostInventoryIssueRequestDto(
    val quantityIssued: String,
    val inventoryAssetAccountId: String,
    val contraAccountId: String,
    val periodId: String,
    val date: String
)

/** Shared response shape for both inventory posting endpoints. */
@Serializable
data class StockItemJournalEntryResponseDto(
    val stockItemId: String,
    val quantityOnHand: String,
    val unitCost: String,
    val unitCostCurrency: String,
    val journalEntryId: String,
    val journalEntryStatus: String
)

/**
 * `PostSalesOrderUseCase`'s wire shape (docs/DDD_Design.md Section
 * 10.22) - completes the "ecosystem" HTTP surface: Purchase Order and
 * Inventory Management were already open, Sales Order Processing was
 * the one remaining gap. [cogsExpenseAccountId]/[inventoryAssetAccountId]
 * are only required for a GOODS line, matching `PostSalesOrderUseCase.Request`.
 */
@Serializable
data class PostSalesOrderRequestDto(
    val lineIndex: Int,
    val periodId: String,
    val arControlAccountId: String,
    val cogsExpenseAccountId: String? = null,
    val inventoryAssetAccountId: String? = null
)

@Serializable
data class PostSalesOrderResponseDto(
    val salesOrderId: String,
    val status: String,
    val journalEntryId: String
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
 * has an owning aggregate in this repo to resolve tenant scoping from
 * - unlike `PostInventoryReceiptRequestDto`/`PostInventoryIssueRequestDto`
 * above, which still resolve tenant scoping via a `StockItem` lookup.
 * `committedCost`/`committedCostCurrency` replace `quantityReceived`/
 * `costReceived` (per-unit) - IM has already computed the total; this
 * repo only records it.
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
@Serializable
data class OnboardTenantRequestDto(
    val tenantName: String,
    val tenantSegment: String,
    val tenantBaseCurrency: String,
    val companyName: String,
    val clientType: String,
    val jurisdiction: String,
    val companyBaseCurrency: String,
    val adminName: String,
    val openingCashBalance: String? = null
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
    val openingCashBalance: String? = null
)

@Serializable
data class AddCompanyToTenantResponseDto(
    val tenantId: String,
    val companyId: String,
    val openingBalanceJournalEntryId: String? = null
)
