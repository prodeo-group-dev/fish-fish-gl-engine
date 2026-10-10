package com.theprodeogroup.fish.infrastructure.web

import com.auth0.jwt.interfaces.JWTVerifier
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.AssessFixedAssetImpairmentUseCase
import com.theprodeogroup.fish.application.ComputeBalanceSheetUseCase
import com.theprodeogroup.fish.application.ComputeCashFlowUseCase
import com.theprodeogroup.fish.application.ComputeTrialBalanceUseCase
import com.theprodeogroup.fish.application.ComputeWorkingCapitalUseCase
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeFixedAssetRegisterUseCase
import com.theprodeogroup.fish.application.ComputeProfitAndLossForDatesUseCase
import com.theprodeogroup.fish.application.ComputeProfitAndLossUseCase
import com.theprodeogroup.fish.application.CreateFixedAssetUseCase
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.DisposeFixedAssetUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.application.ComputeInventoryPostingContextUseCase
import com.theprodeogroup.fish.application.ComputePayrollPostingContextUseCase
import com.theprodeogroup.fish.application.ComputePurchasePostingContextUseCase
import com.theprodeogroup.fish.application.ComputeAccountsPayableAgingUseCase
import com.theprodeogroup.fish.application.ComputeAccountsReceivableAgingUseCase
import com.theprodeogroup.fish.application.ComputeCustomerBalancesUseCase
import com.theprodeogroup.fish.application.ComputeSupplierBalancesUseCase
import com.theprodeogroup.fish.application.ComputeSalesPostingContextUseCase
import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
import com.theprodeogroup.fish.application.ImportFixedAssetsUseCase
import com.theprodeogroup.fish.application.StartBankReconciliationUseCase
import com.theprodeogroup.fish.application.MatchBankReconciliationLineUseCase
import com.theprodeogroup.fish.application.UnmatchBankReconciliationLineUseCase
import com.theprodeogroup.fish.application.CompleteBankReconciliationUseCase
import com.theprodeogroup.fish.application.CancelBankReconciliationUseCase
import com.theprodeogroup.fish.application.ComputeBankReconciliationUseCase
import com.theprodeogroup.fish.application.ListBankReconciliationsUseCase
import com.theprodeogroup.fish.application.ImportGlBalancesUseCase
import com.theprodeogroup.fish.application.CreateAccountUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.RecordOpeningBalanceUseCase
import com.theprodeogroup.fish.application.RecordCollectionUseCase
import com.theprodeogroup.fish.application.RecordFixedAssetDepreciationUseCase
import com.theprodeogroup.fish.application.RecordInventoryIssueUseCase
import com.theprodeogroup.fish.application.RecordInventoryReceiptUseCase
import com.theprodeogroup.fish.application.RecordPayRunUseCase
import com.theprodeogroup.fish.application.RecordSaleUseCase
import com.theprodeogroup.fish.application.RecordSalesReturnUseCase
import com.theprodeogroup.fish.application.RecordSupplierObligationUseCase
import com.theprodeogroup.fish.application.RecordSupplierPaymentUseCase
import com.theprodeogroup.fish.application.RemeasureLeaveAccrualUseCase
import com.theprodeogroup.fish.application.UtilizeLeaveAccrualUseCase
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRepository
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualRepository
import com.theprodeogroup.fish.domain.sales.CustomerRepository
import com.theprodeogroup.fish.domain.tax.TaxComputationRepository
import com.theprodeogroup.fish.domain.tax.TaxRuleRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.infrastructure.ea.EaMembershipGateway
import com.theprodeogroup.fish.infrastructure.ea.KtorEaMembershipGateway
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedSupplierRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCustomerRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedFixedAssetRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedSalesInvoiceRecordRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedIdempotencyKeyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJurisdictionRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedVatRateRepository
import com.theprodeogroup.fish.domain.tax.VatRateRepository
import com.theprodeogroup.fish.domain.tax.VatRateRow
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
import com.theprodeogroup.fish.application.ComputeVatCategoriesUseCase
import com.theprodeogroup.fish.application.ChangeCashBookKindUseCase
import com.theprodeogroup.fish.application.ClassifyExpenseAccountUseCase
import com.theprodeogroup.fish.application.ComputeCashBookUseCase
import com.theprodeogroup.fish.application.ListCashBooksUseCase
import com.theprodeogroup.fish.application.ComputeTradingProfitAndLossUseCase
import com.theprodeogroup.fish.domain.common.JurisdictionEntry
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JurisdictionRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedOpeningImportBatchRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedOpeningImportRowResultRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedLeaveAccrualRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTaxComputationRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTaxRuleRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedVatReturnRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedBankReconciliationRepository
import com.theprodeogroup.fish.application.ComputeVatReturnUseCase
import com.theprodeogroup.fish.domain.tax.VatReturnRepository
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyKeyRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.json.json as clientJson
import kotlinx.serialization.json.Json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import java.net.URI
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.event.Level

/**
 * The GL Engine's HTTP entry point (docs/DDD_Design.md Section 10.19/10.20) -
 * the first web/API layer this codebase has had; every prior increment
 * exposed use cases only as plain Kotlin classes with no way for an
 * external client to actually call them. Ktor + Netty, chosen as the
 * natural fit given this project's existing Kotlin/Gradle toolchain -
 * no new language/ecosystem to introduce.
 *
 * **Section 10.19 opened a skeleton + two representative endpoints
 * (`PostJournalEntryUseCase`/the now-retired `PostPurchaseOrderUseCase`), confirmed
 * scope before building - covering both shapes of use case this
 * codebase has, so the pattern was reviewable before being repeated.**
 * **Section 10.20 applies that now-proven pattern to the HR/Payroll
 * posting interface** (`RecordPayRunUseCase`/`RemeasureLeaveAccrualUseCase`/
 * `UtilizeLeaveAccrualUseCase`) - the fixed contract the separate,
 * not-built-here HR/Payroll system calls into, confirmed with the user
 * directly.
 *
 * Wires real `Exposed*Repository` implementations directly - unlike the
 * use cases themselves (framework-agnostic, `domain`-only dependencies),
 * this file's whole job *is* the infrastructure wiring, so depending on
 * `infrastructure.persistence` here is correct, not a layering
 * violation.
 */
fun main() {
    val dataSource = DatabaseConfig.dataSource()
    DatabaseMigrator.migrate(dataSource)
    DatabaseConfig.connectExposed(dataSource)

    val port = System.getenv("FISH_HTTP_PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, module = Application::productionModule).start(wait = true)
}

fun Application.productionModule() {
    val accountRepository = ExposedAccountRepository()
    val periodRepository = ExposedPeriodRepository()
    val journalEntryRepository = ExposedJournalEntryRepository()
    val companyRepository = ExposedCompanyRepository()
    val supplierRepository = ExposedSupplierRepository()
    val leaveAccrualRepository = ExposedLeaveAccrualRepository()
    val customerRepository = ExposedCustomerRepository()
    val idempotencyKeyRepository = ExposedIdempotencyKeyRepository()
    val fixedAssetRepository = ExposedFixedAssetRepository()

    val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(
        companyRepository, accountRepository, periodRepository, journalEntryRepository
    )
    val taxRuleRepository = ExposedTaxRuleRepository()
    val taxComputationRepository = ExposedTaxComputationRepository()
    val computeTaxUseCase = ComputeTaxUseCase(periodRepository, accountRepository, journalEntryRepository, taxComputationRepository)
    val vatReturnRepository = ExposedVatReturnRepository()
    val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
    val createAccountUseCase = CreateAccountUseCase(companyRepository, accountRepository)
    val recordOpeningBalanceUseCase = RecordOpeningBalanceUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val openingImportBatchRepository = ExposedOpeningImportBatchRepository()
    val openingImportRowResultRepository = ExposedOpeningImportRowResultRepository()
    val importGlBalancesUseCase = ImportGlBalancesUseCase(
        companyRepository, accountRepository, recordOpeningBalanceUseCase, openingImportBatchRepository, openingImportRowResultRepository
    )
    val remeasureLeaveAccrualUseCase = RemeasureLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
    val utilizeLeaveAccrualUseCase = UtilizeLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
    val recordSaleUseCase = RecordSaleUseCase(periodRepository, accountRepository, journalEntryRepository)
    val salesInvoiceRecordRepository = ExposedSalesInvoiceRecordRepository()
    val createSalesInvoiceUseCase = CreateSalesInvoiceUseCase(
        periodRepository, accountRepository, customerRepository, journalEntryRepository, salesInvoiceRecordRepository
    )
    val listSalesInvoicesUseCase = ListSalesInvoicesUseCase(companyRepository, salesInvoiceRecordRepository)
    val recordCollectionUseCase = RecordCollectionUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordSalesReturnUseCase = RecordSalesReturnUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordSupplierObligationUseCase = RecordSupplierObligationUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordSupplierPaymentUseCase = RecordSupplierPaymentUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordInventoryReceiptUseCase = RecordInventoryReceiptUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordInventoryIssueUseCase = RecordInventoryIssueUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
    val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

    val computeMoneyVelocityUseCase = ComputeMoneyVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val computeExpenseVelocityUseCase = ComputeExpenseVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val computeSalesToExpenseRatioUseCase = ComputeSalesToExpenseRatioUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val computeBalanceSheetUseCase = ComputeBalanceSheetUseCase(companyRepository, accountRepository, journalEntryRepository)
    val computeProfitAndLossUseCase = ComputeProfitAndLossUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val computeCashFlowUseCase = ComputeCashFlowUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val createFixedAssetUseCase = CreateFixedAssetUseCase(companyRepository, fixedAssetRepository, postJournalEntryUseCase)
    val recordFixedAssetDepreciationUseCase = RecordFixedAssetDepreciationUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
    val assessFixedAssetImpairmentUseCase = AssessFixedAssetImpairmentUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
    val disposeFixedAssetUseCase = DisposeFixedAssetUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
    val computeFixedAssetRegisterUseCase = ComputeFixedAssetRegisterUseCase(companyRepository, fixedAssetRepository)
    val importFixedAssetsUseCase = ImportFixedAssetsUseCase(
        companyRepository, periodRepository, accountRepository, fixedAssetRepository, createFixedAssetUseCase,
        openingImportBatchRepository, openingImportRowResultRepository
    )

    val bankReconciliationRepository = ExposedBankReconciliationRepository()
    val startBankReconciliationUseCase = StartBankReconciliationUseCase(companyRepository, accountRepository, journalEntryRepository, bankReconciliationRepository)
    val matchBankReconciliationLineUseCase = MatchBankReconciliationLineUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository)
    val unmatchBankReconciliationLineUseCase = UnmatchBankReconciliationLineUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository)
    // Whether Complete also requires the statement balance to tie out to the ledger is Femi's call (UAT v2.2 W-M2, put to him via WEB 2026-10-09); off until he says yes, with the 409 balance_difference contract already in place.
    val completeBankReconciliationUseCase = CompleteBankReconciliationUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository, enforceBalanceTieOut = false)
    val cancelBankReconciliationUseCase = CancelBankReconciliationUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository)
    val computeBankReconciliationUseCase = ComputeBankReconciliationUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository)
    val listBankReconciliationsUseCase = ListBankReconciliationsUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository)

    // Cash and bank books (docs/GL_Cash_And_Bank_Books_SRS.md).
    val listCashBooksUseCase = ListCashBooksUseCase(companyRepository, accountRepository, journalEntryRepository)
    val computeCashBookUseCase = ComputeCashBookUseCase(companyRepository, accountRepository, journalEntryRepository, periodRepository)
    val changeCashBookKindUseCase = ChangeCashBookKindUseCase(accountRepository, bankReconciliationRepository)

    // EA (Enterprise Administration) - the human-facing half of
    // docs/Tenancy_Administration_Extraction_DDD_Design.md's rewiring.
    // No default/fallback for the base URL, same "no safe default for a
    // security-relevant value" reasoning as FISH_JWT_ISSUER etc. -
    // GL's own authorizeTenantFor*/`/me` genuinely cannot authorize a
    // human caller without this configured.
    val eaApiBaseUrl = System.getenv("EA_API_BASE_URL")
        ?: error("EA_API_BASE_URL environment variable is required - no default for a security-relevant value")
    // Strict decoding (reverted 2026-09-29, direct user instruction: "This
    // is a Multi-Tenanted Cloud-Native Project. Security is paramount and
    // sacrosanct. There should not be a case where there exists unknown
    // keys being processed."). Previously ignoreUnknownKeys = true
    // (2026-09-21) after EA's GET /me response shape drifted out from
    // under EaMyProfileResponseDto's strict mirror twice (kycStatus, then
    // userId) and hard-crashed every GL route calling out to EA for a
    // membership check. That was a resilience trade-off, but the wrong one
    // for an authentication/authorization payload specifically - a future
    // EA field could be security-relevant (a revoked/suspended flag, a
    // tightened permission), and GL would silently never see it while
    // still proceeding as authorized. A loud, immediately-visible crash on
    // a shape change is the correct trade-off here, matching SOP's own
    // fix for the identical class of incident. GL's EA DTOs
    // (EaMyProfileResponseDto/EaTenantMembershipDto/EaCompanySummaryDto)
    // were re-verified field-for-field against EA's current Dtos.kt before
    // this reverted, including schoolId, which GL's mirror had also missed.
    val eaHttpClient = HttpClient(CIO) { install(ClientContentNegotiation) { clientJson(Json) } }
    val eaMembershipGateway = KtorEaMembershipGateway(eaHttpClient, eaApiBaseUrl)

    // A service provider whose audience variable is unset is DENY-ALL (T15 / G0): that service's calls are
    // refused with 401 until the variable is set. Say so loudly at start-up instead of failing quietly.
    fun serviceVerifierOrWarn(service: String, variable: String, verifier: JWTVerifier?): JWTVerifier? {
        if (verifier == null) {
            log.warn("$variable is not set: the $service service-account provider is DENY-ALL, so every $service call to GL will be refused (401) until it is configured")
        }
        return verifier
    }

    fishModule(
        verifier = buildJwksVerifier(),
        serviceVerifier = serviceVerifierOrWarn("SOP", "FISH_JWT_SERVICE_AUDIENCE", buildJwksServiceVerifier()),
        imServiceVerifier = serviceVerifierOrWarn("IM", "FISH_JWT_SERVICE_AUDIENCE_IM", buildJwksServiceVerifierForIm()),
        hrServiceVerifier = serviceVerifierOrWarn("HR", "FISH_JWT_SERVICE_AUDIENCE_HR", buildJwksServiceVerifierForHr()),
        popServiceVerifier = serviceVerifierOrWarn("POP", "FISH_JWT_SERVICE_AUDIENCE_POP", buildJwksServiceVerifierForPop()),
        serviceAllowListMode = ServiceAllowListMode.fromEnvironment(System.getenv("FISH_SERVICE_ALLOWLIST_MODE")).also {
            if (it == ServiceAllowListMode.LOG) log.warn("FISH_SERVICE_ALLOWLIST_MODE=log: service calls off a credential's endpoint allow-list are ANSWERED and logged as WOULD BLOCK, not refused")
        },
        eaMembershipGateway = eaMembershipGateway,
        companyRepository = companyRepository,
        addCompanyToTenantUseCase = addCompanyToTenantUseCase,
        jurisdictionRepository = ExposedJurisdictionRepository(),
        vatRateRepository = ExposedVatRateRepository(),
        computeTaxUseCase = computeTaxUseCase,
        taxRuleRepository = taxRuleRepository,
        taxComputationRepository = taxComputationRepository,
        vatReturnRepository = vatReturnRepository,
        importGlBalancesUseCase = importGlBalancesUseCase,
        importFixedAssetsUseCase = importFixedAssetsUseCase,
        startBankReconciliationUseCase = startBankReconciliationUseCase,
        matchBankReconciliationLineUseCase = matchBankReconciliationLineUseCase,
        unmatchBankReconciliationLineUseCase = unmatchBankReconciliationLineUseCase,
        completeBankReconciliationUseCase = completeBankReconciliationUseCase,
        cancelBankReconciliationUseCase = cancelBankReconciliationUseCase,
        computeBankReconciliationUseCase = computeBankReconciliationUseCase,
        listBankReconciliationsUseCase = listBankReconciliationsUseCase,
        listCashBooksUseCase = listCashBooksUseCase,
        computeCashBookUseCase = computeCashBookUseCase,
        changeCashBookKindUseCase = changeCashBookKindUseCase,
        periodRepository = periodRepository,
        accountRepository = accountRepository,
        journalEntryRepository = journalEntryRepository,
        postJournalEntryUseCase = postJournalEntryUseCase,
        createAccountUseCase = createAccountUseCase,
        recordOpeningBalanceUseCase = recordOpeningBalanceUseCase,
        leaveAccrualRepository = leaveAccrualRepository,
        remeasureLeaveAccrualUseCase = remeasureLeaveAccrualUseCase,
        utilizeLeaveAccrualUseCase = utilizeLeaveAccrualUseCase,
        recordSaleUseCase = recordSaleUseCase,
        createSalesInvoiceUseCase = createSalesInvoiceUseCase,
        listSalesInvoicesUseCase = listSalesInvoicesUseCase,
        customerRepository = customerRepository,
        recordCollectionUseCase = recordCollectionUseCase,
        recordSalesReturnUseCase = recordSalesReturnUseCase,
        recordSupplierObligationUseCase = recordSupplierObligationUseCase,
        recordSupplierPaymentUseCase = recordSupplierPaymentUseCase,
        recordInventoryReceiptUseCase = recordInventoryReceiptUseCase,
        recordInventoryIssueUseCase = recordInventoryIssueUseCase,
        recordPayRunUseCase = recordPayRunUseCase,
        getOrCreateLeaveAccrualUseCase = getOrCreateLeaveAccrualUseCase,
        idempotencyKeyRepository = idempotencyKeyRepository,
        computeMoneyVelocityUseCase = computeMoneyVelocityUseCase,
        computeExpenseVelocityUseCase = computeExpenseVelocityUseCase,
        computeSalesToExpenseRatioUseCase = computeSalesToExpenseRatioUseCase,
        computeBalanceSheetUseCase = computeBalanceSheetUseCase,
        computeProfitAndLossUseCase = computeProfitAndLossUseCase,
        computeCashFlowUseCase = computeCashFlowUseCase,
        fixedAssetRepository = fixedAssetRepository,
        createFixedAssetUseCase = createFixedAssetUseCase,
        recordFixedAssetDepreciationUseCase = recordFixedAssetDepreciationUseCase,
        assessFixedAssetImpairmentUseCase = assessFixedAssetImpairmentUseCase,
        disposeFixedAssetUseCase = disposeFixedAssetUseCase,
        computeFixedAssetRegisterUseCase = computeFixedAssetRegisterUseCase
    )
}


/**
 * The testable module wiring - everything that varies between
 * production and tests (the JWT verifier, every repository) is a
 * parameter, matching the constructor-injection pattern already used
 * throughout `application`'s use cases. Production's [productionModule]
 * above builds the real dependencies and delegates here; tests call
 * this overload directly with fakes/a locally-built test verifier,
 * needing no real database or network JWKS endpoint.
 */
fun Application.fishModule(
    verifier: JWTVerifier,
    serviceVerifier: JWTVerifier? = null,
    imServiceVerifier: JWTVerifier? = null,
    hrServiceVerifier: JWTVerifier? = null,
    popServiceVerifier: JWTVerifier? = null,
    // T15 / G3: whether a service call off its credential's endpoint allow-list is refused (the default) or only
    // logged as WOULD BLOCK. Production reads FISH_SERVICE_ALLOWLIST_MODE; unset or anything but "log" enforces.
    serviceAllowListMode: ServiceAllowListMode = ServiceAllowListMode.ENFORCE,
    eaMembershipGateway: EaMembershipGateway,
    companyRepository: CompanyRepository,
    addCompanyToTenantUseCase: AddCompanyToTenantUseCase,
    computeTaxUseCase: ComputeTaxUseCase,
    taxRuleRepository: TaxRuleRepository,
    taxComputationRepository: TaxComputationRepository,
    // Nullable, unlike every other repository param here (2026-09-19,
    // VAT MVP) - deliberately, not an oversight: VAT is genuinely new,
    // additive capability (docs/IE/IE_VAT_MVP_Design.md), and making it
    // required would mean touching every one of the ~20 existing test
    // files that already call this function with a full fixture, for a
    // capability most of those tests have no reason to exercise. Mirrors
    // the same optional-with-fallback treatment `serviceVerifier`/
    // `imServiceVerifier`/etc. already get above, not a new pattern.
    vatReturnRepository: VatReturnRepository? = null,
    // Nullable for the same reason vatReturnRepository is/was - keeps every
    // existing route-test fixture that doesn't care about this capability
    // compiling unchanged. Now genuinely wired in `productionModule()`
    // (Exposed persistence for OpeningImportBatch/RowResult, V26 migration) -
    // unlike vatReturnRepository's own history, this one went from Fakes to
    // a real backing store before this parameter's default was ever live.
    importGlBalancesUseCase: ImportGlBalancesUseCase? = null,
    // Same nullable shape, added the same day - Fixed Assets is step 2
    // of docs/Opening_Figures_CSV_Upload_DDD_Design.md's build order.
    importFixedAssetsUseCase: ImportFixedAssetsUseCase? = null,
    // Nullable, same reasoning as vatReturnRepository/importGlBalancesUseCase -
    // Bank Reconciliation needs its own persisted repository (unlike
    // Working Capital's computeWorkingCapitalUseCase above, which could
    // default-construct from repos already in scope), so there's no
    // "free" real default to fall back to here the way there was there.
    startBankReconciliationUseCase: StartBankReconciliationUseCase? = null,
    matchBankReconciliationLineUseCase: MatchBankReconciliationLineUseCase? = null,
    unmatchBankReconciliationLineUseCase: UnmatchBankReconciliationLineUseCase? = null,
    completeBankReconciliationUseCase: CompleteBankReconciliationUseCase? = null,
    cancelBankReconciliationUseCase: CancelBankReconciliationUseCase? = null,
    computeBankReconciliationUseCase: ComputeBankReconciliationUseCase? = null,
    listBankReconciliationsUseCase: ListBankReconciliationsUseCase? = null,
    listCashBooksUseCase: ListCashBooksUseCase? = null,
    computeCashBookUseCase: ComputeCashBookUseCase? = null,
    changeCashBookKindUseCase: ChangeCashBookKindUseCase? = null,
    periodRepository: PeriodRepository,
    accountRepository: AccountRepository,
    journalEntryRepository: JournalEntryRepository,
    postJournalEntryUseCase: PostJournalEntryUseCase,
    createAccountUseCase: CreateAccountUseCase,
    recordOpeningBalanceUseCase: RecordOpeningBalanceUseCase,
    leaveAccrualRepository: LeaveAccrualRepository,
    remeasureLeaveAccrualUseCase: RemeasureLeaveAccrualUseCase,
    utilizeLeaveAccrualUseCase: UtilizeLeaveAccrualUseCase,
    recordSaleUseCase: RecordSaleUseCase,
    createSalesInvoiceUseCase: CreateSalesInvoiceUseCase,
    listSalesInvoicesUseCase: ListSalesInvoicesUseCase,
    customerRepository: CustomerRepository,
    recordCollectionUseCase: RecordCollectionUseCase,
    recordSalesReturnUseCase: RecordSalesReturnUseCase,
    recordSupplierObligationUseCase: RecordSupplierObligationUseCase,
    recordSupplierPaymentUseCase: RecordSupplierPaymentUseCase,
    recordInventoryReceiptUseCase: RecordInventoryReceiptUseCase,
    recordInventoryIssueUseCase: RecordInventoryIssueUseCase,
    recordPayRunUseCase: RecordPayRunUseCase,
    getOrCreateLeaveAccrualUseCase: GetOrCreateLeaveAccrualUseCase,
    idempotencyKeyRepository: IdempotencyKeyRepository,
    computeMoneyVelocityUseCase: ComputeMoneyVelocityUseCase,
    computeExpenseVelocityUseCase: ComputeExpenseVelocityUseCase,
    computeSalesToExpenseRatioUseCase: ComputeSalesToExpenseRatioUseCase,
    computeBalanceSheetUseCase: ComputeBalanceSheetUseCase,
    computeProfitAndLossUseCase: ComputeProfitAndLossUseCase,
    computeCashFlowUseCase: ComputeCashFlowUseCase,
    // Defaulted (not a new required param) rather than touching every
    // one of the ~25 existing test fixtures that already call this
    // function - same "new, additive, most tests don't exercise it"
    // reasoning as vatReturnRepository/importGlBalancesUseCase above,
    // but as a real default construction (not nullable) since
    // reportsRoutes() registers this route unconditionally alongside
    // its three siblings, unlike those two's conditional registration.
    computeWorkingCapitalUseCase: ComputeWorkingCapitalUseCase = ComputeWorkingCapitalUseCase(companyRepository, accountRepository, journalEntryRepository),
    fixedAssetRepository: FixedAssetRepository,
    createFixedAssetUseCase: CreateFixedAssetUseCase,
    recordFixedAssetDepreciationUseCase: RecordFixedAssetDepreciationUseCase,
    assessFixedAssetImpairmentUseCase: AssessFixedAssetImpairmentUseCase,
    disposeFixedAssetUseCase: DisposeFixedAssetUseCase,
    computeFixedAssetRegisterUseCase: ComputeFixedAssetRegisterUseCase,
    // Defaulted to an empty registry rather than required (every one of
    // the ~25 existing test fixtures calls this function) - FAIL CLOSED:
    // an unconfigured registry offers no jurisdictions and accepts none,
    // so a fixture that forgets it can never silently approve a country.
    // productionModule() always passes the real, table-backed one.
    jurisdictionRepository: JurisdictionRepository = NoJurisdictionsRepository,
    // Defaulted to an empty rate table rather than required (same reasoning as
    // jurisdictionRepository above) - FAIL CLOSED: with no verified rates every
    // jurisdiction has no VAT schedule, so a fixture that forgets it can never
    // post a sale at an invented rate. productionModule() passes the real one.
    vatRateRepository: VatRateRepository = NoVatRatesRepository
) {
    install(ContentNegotiation) { json() }
    // Defense-in-depth against any caching-capable intermediary between
    // a client and this service (a corporate/ISP proxy, a misconfigured
    // CDN) - every response here is either a personalized, authenticated
    // JSON payload (Ledger/financial data) or an error, never something
    // safe to cache and replay to a different caller. Surfaced 2026-09-29
    // investigating a reported cross-tenant data exposure - not confirmed
    // as the mechanism, but the total absence of any Cache-Control header
    // anywhere in this codebase (confirmed by grep) was a real, independent
    // gap regardless. Plain intercept, not the `DefaultHeaders` plugin -
    // this project doesn't depend on `ktor-server-default-headers`, and
    // adding it for one header isn't worth a new dependency.
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header(HttpHeaders.CacheControl, "no-store, private")
    }
    // CallId first, CallLogging second - callIdMdc puts the id CallId
    // generates/retrieves into MDC's "requestId" key, which logback.xml's
    // LogstashEncoder then includes in every JSON log line for this
    // request's lifecycle (docs/GL_Production_Readiness_Assessment.md's
    // "no structured logging" finding - this is the piece that actually
    // makes logs queryable across services, not just JSON-shaped).
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { java.util.UUID.randomUUID().toString() }
        verify { it.isNotEmpty() }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        // Ktor's default format embeds raw ANSI color codes into the
        // status text - harmless in a plain-text console, but they land
        // inside the JSON "message" field's string value once
        // LogstashEncoder is in the loop, showing up as literal escape
        // bytes in CloudWatch instead of rendering as color. Plain text
        // only; the fields that actually make this queryable
        // (level/logger_name/requestId) come from logback.xml, not this
        // string.
        format { call ->
            val status = call.response.status()?.value ?: "-"
            "$status ${call.request.httpMethod.value} ${call.request.path()}"
        }
    }
    // fish-gl-web (a browser-based PWA) calls this API cross-origin -
    // discovered missing 2026-08-26 testing the first real deploy
    // against the new frontend, which every browser would otherwise
    // silently block regardless of the request itself being valid.
    // Vite's dev/preview ports are always allowed (harmless - they only
    // ever run on a developer's own machine); FISH_CORS_ALLOWED_ORIGIN
    // covers wherever fish-gl-web ends up actually hosted, which isn't
    // decided yet - unset in production until it is, matching this
    // codebase's "no guessed default for a security-relevant value"
    // pattern (same reasoning as the JWT settings before Cognito).
    install(CORS) {
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Tenant-Id")
        allowHeader("Idempotency-Key")
        allowHost("localhost:5173", schemes = listOf("http"))
        allowHost("localhost:4173", schemes = listOf("http"))
        System.getenv("FISH_CORS_ALLOWED_ORIGIN")?.let { origin ->
            val uri = URI(origin)
            allowHost(uri.authority, schemes = listOf(uri.scheme))
        }
    }
    install(StatusPages) {
        // docs/GL_Production_Readiness_Assessment.md Section 1, critical
        // finding #3 - cause.message previously went straight into the
        // response body, which can leak exception class names, stack
        // internals, or fragments of a failed SQL statement to any
        // caller able to trigger a 500. The real detail is logged
        // server-side (where an operator can see it) instead of
        // returned to the client, who only ever gets a generic message.
        exception<Throwable> { call, cause ->
            call.application.log.error(
                "Unhandled exception handling ${call.request.httpMethod.value} ${call.request.path()}", cause
            )
            call.respond(HttpStatusCode.InternalServerError, ErrorResponseDto("internal_error"))
        }
    }
    installFishJwtAuth(
        verifier, eaMembershipGateway,
        serviceVerifier ?: DenyAllJwtVerifier, imServiceVerifier ?: DenyAllJwtVerifier, hrServiceVerifier ?: DenyAllJwtVerifier, popServiceVerifier ?: DenyAllJwtVerifier,
        serviceAllowListMode
    )

    routing {
        // Unprefixed and outside /api deliberately - the ALB target
        // group's own health check (infra/terraform/alb.tf) hits this
        // container directly, bypassing CloudFront entirely, so moving
        // it would need a coordinated Terraform change for no benefit.
        healthRoutes()

        // Everything else lives under /api now that CloudFront fronts
        // this same domain alongside the WEB SPA's static assets
        // (infra/terraform/frontend.tf) - CloudFront routes /api/*
        // here and everything else to S3, so this prefix is load-bearing
        // infrastructure, not cosmetic.
        route("/api") {
            fishAuthenticated {
                tenantRoutesAuthenticated(addCompanyToTenantUseCase, jurisdictionRepository)
                journalEntryRoutes(
                    postJournalEntryUseCase, createAccountUseCase, recordOpeningBalanceUseCase,
                    periodRepository, accountRepository, journalEntryRepository, companyRepository, idempotencyKeyRepository
                )
                payrollRoutes(
                    remeasureLeaveAccrualUseCase, utilizeLeaveAccrualUseCase, leaveAccrualRepository,
                    recordPayRunUseCase, getOrCreateLeaveAccrualUseCase,
                    companyRepository, idempotencyKeyRepository
                )
                recordSaleAndCollectionRoutes(recordSaleUseCase, recordCollectionUseCase, companyRepository, vatRateRepository, idempotencyKeyRepository)
                recordSalesReturnRoutes(recordSalesReturnUseCase, companyRepository, idempotencyKeyRepository)
                createSalesInvoiceRoutes(createSalesInvoiceUseCase, listSalesInvoicesUseCase, companyRepository, customerRepository, idempotencyKeyRepository)
                recordSupplierObligationAndPaymentRoutes(recordSupplierObligationUseCase, recordSupplierPaymentUseCase, companyRepository, vatRateRepository, idempotencyKeyRepository)
                vatCategoriesRoutes(ComputeVatCategoriesUseCase(companyRepository, vatRateRepository), companyRepository)
                recordInventoryReceiptAndIssueRoutes(recordInventoryReceiptUseCase, recordInventoryIssueUseCase, companyRepository, idempotencyKeyRepository)
                meRoutes()
                jurisdictionRoutes(jurisdictionRepository)
                moneyVelocityRoutes(computeMoneyVelocityUseCase, companyRepository)
                tradingProfitAndLossRoutes(
                    ComputeTradingProfitAndLossUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository),
                    ClassifyExpenseAccountUseCase(accountRepository),
                    companyRepository
                )
                salesPostingContextRoutes(
                    ComputeSalesPostingContextUseCase(companyRepository, periodRepository, accountRepository), companyRepository
                )
                customerBalancesRoutes(
                    ComputeCustomerBalancesUseCase(companyRepository, accountRepository, journalEntryRepository), companyRepository
                )
                accountsReceivableAgingRoutes(
                    ComputeAccountsReceivableAgingUseCase(companyRepository, accountRepository, journalEntryRepository), companyRepository
                )
                supplierBalancesRoutes(
                    ComputeSupplierBalancesUseCase(companyRepository, accountRepository, journalEntryRepository), companyRepository
                )
                accountsPayableAgingRoutes(
                    ComputeAccountsPayableAgingUseCase(companyRepository, accountRepository, journalEntryRepository), companyRepository
                )
                purchasePostingContextRoutes(
                    ComputePurchasePostingContextUseCase(companyRepository, periodRepository, accountRepository), companyRepository
                )
                inventoryPostingContextRoutes(
                    ComputeInventoryPostingContextUseCase(companyRepository, periodRepository, accountRepository), companyRepository
                )
                payrollPostingContextRoutes(
                    ComputePayrollPostingContextUseCase(companyRepository, periodRepository, accountRepository), companyRepository
                )
                expenseVelocityRoutes(computeExpenseVelocityUseCase, companyRepository)
                salesToExpenseRatioRoutes(computeSalesToExpenseRatioUseCase, companyRepository)
                reportsRoutes(computeBalanceSheetUseCase, computeProfitAndLossUseCase, ComputeProfitAndLossForDatesUseCase(companyRepository, accountRepository, journalEntryRepository), computeCashFlowUseCase, computeWorkingCapitalUseCase, ComputeTrialBalanceUseCase(companyRepository, accountRepository, journalEntryRepository), companyRepository)
                taxRoutes(computeTaxUseCase, companyRepository, taxRuleRepository, taxComputationRepository)
                if (vatReturnRepository != null) {
                    vatReturnRoutes(
                        ComputeVatReturnUseCase(accountRepository, journalEntryRepository, vatReturnRepository), companyRepository
                    )
                }
                if (importGlBalancesUseCase != null && importFixedAssetsUseCase != null) {
                    openingImportRoutes(importGlBalancesUseCase, importFixedAssetsUseCase, companyRepository)
                }
                if (startBankReconciliationUseCase != null && matchBankReconciliationLineUseCase != null &&
                    unmatchBankReconciliationLineUseCase != null && completeBankReconciliationUseCase != null &&
                    cancelBankReconciliationUseCase != null && computeBankReconciliationUseCase != null &&
                    listBankReconciliationsUseCase != null
                ) {
                    bankReconciliationRoutes(
                        startBankReconciliationUseCase, matchBankReconciliationLineUseCase,
                        unmatchBankReconciliationLineUseCase, completeBankReconciliationUseCase, cancelBankReconciliationUseCase, computeBankReconciliationUseCase,
                        listBankReconciliationsUseCase, companyRepository
                    )
                }
                if (listCashBooksUseCase != null && computeCashBookUseCase != null && changeCashBookKindUseCase != null) {
                    cashBookRoutes(listCashBooksUseCase, computeCashBookUseCase, changeCashBookKindUseCase, companyRepository)
                }
                fixedAssetRoutes(
                    createFixedAssetUseCase, recordFixedAssetDepreciationUseCase, assessFixedAssetImpairmentUseCase,
                    disposeFixedAssetUseCase, computeFixedAssetRegisterUseCase, fixedAssetRepository, periodRepository,
                    companyRepository, idempotencyKeyRepository
                )
            }
        }
    }
}

/**
 * The fail-closed default for [fishModule]'s `jurisdictionRepository`:
 * no jurisdiction is offered and none is accepted. Read-only on purpose -
 * [save] throws so a fixture can't mistake it for a working registry.
 */
private object NoJurisdictionsRepository : JurisdictionRepository {
    override fun save(entry: JurisdictionEntry) = error("NoJurisdictionsRepository is read-only; pass a real JurisdictionRepository")
    override fun findAllEnabled(): List<JurisdictionEntry> = emptyList()
    override fun findEnabledByCode(code: Jurisdiction): JurisdictionEntry? = null
}

/**
 * The fail-closed default for [fishModule]'s `vatRateRepository`: no
 * jurisdiction has a VAT schedule. Read-only on purpose - [save] throws so a
 * fixture can't mistake it for a working rate table.
 */
private object NoVatRatesRepository : VatRateRepository {
    override fun save(row: VatRateRow) = error("NoVatRatesRepository is read-only; pass a real VatRateRepository")
    override fun findVerifiedScheduleFor(jurisdiction: Jurisdiction): VatRateSchedule? = null
}
