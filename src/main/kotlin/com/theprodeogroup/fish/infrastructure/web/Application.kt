package com.theprodeogroup.fish.infrastructure.web

import com.auth0.jwt.interfaces.JWTVerifier
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.AssessFixedAssetImpairmentUseCase
import com.theprodeogroup.fish.application.ComputeBalanceSheetUseCase
import com.theprodeogroup.fish.application.ComputeCashFlowUseCase
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeFixedAssetRegisterUseCase
import com.theprodeogroup.fish.application.ComputeProfitAndLossUseCase
import com.theprodeogroup.fish.application.CreateFixedAssetUseCase
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.DisposeFixedAssetUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.application.ComputeInventoryPostingContextUseCase
import com.theprodeogroup.fish.application.ComputePayrollPostingContextUseCase
import com.theprodeogroup.fish.application.ComputePurchasePostingContextUseCase
import com.theprodeogroup.fish.application.ComputeCustomerBalancesUseCase
import com.theprodeogroup.fish.application.ComputeSalesPostingContextUseCase
import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
import com.theprodeogroup.fish.application.InviteStaffMemberUseCase
import com.theprodeogroup.fish.application.KybGracePeriodSweep
import com.theprodeogroup.fish.application.OnboardTenantUseCase
import com.theprodeogroup.fish.application.RecordAdminPhoneNumberUseCase
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
import com.theprodeogroup.fish.application.RecordVendorObligationUseCase
import com.theprodeogroup.fish.application.RecordVendorPaymentUseCase
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
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.UserRepository
import com.theprodeogroup.fish.infrastructure.ea.EaMembershipGateway
import com.theprodeogroup.fish.infrastructure.ea.KtorEaMembershipGateway
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCreditorRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCustomerRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedFixedAssetRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedSalesInvoiceRecordRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedIdempotencyKeyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedLeaveAccrualRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedMembershipRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTaxComputationRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTaxRuleRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTenantRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedUserRepository
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyKeyRepository
import com.theprodeogroup.fish.infrastructure.identity.CognitoAdminPhoneVerificationChecker
import com.theprodeogroup.fish.infrastructure.notification.SesStaffInviteNotificationGateway
import com.theprodeogroup.fish.infrastructure.notification.UnconfiguredStaffInviteNotificationGateway
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.json.json as clientJson
import io.ktor.server.application.Application
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
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.event.Level
import java.time.Duration

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
    val userRepository = ExposedUserRepository()
    val membershipRepository = ExposedMembershipRepository()
    val creditorRepository = ExposedCreditorRepository()
    val leaveAccrualRepository = ExposedLeaveAccrualRepository()
    val customerRepository = ExposedCustomerRepository()
    val idempotencyKeyRepository = ExposedIdempotencyKeyRepository()
    val tenantRepository = ExposedTenantRepository()
    val fixedAssetRepository = ExposedFixedAssetRepository()

    val onboardTenantUseCase = OnboardTenantUseCase(
        tenantRepository, companyRepository, userRepository, membershipRepository, accountRepository, periodRepository, journalEntryRepository
    )
    val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(
        tenantRepository, companyRepository, accountRepository, periodRepository, journalEntryRepository
    )
    val staffInviteNotificationGateway = System.getenv("GL_STAFF_INVITE_FROM_EMAIL")
        ?.let { SesStaffInviteNotificationGateway(it) }
        ?: UnconfiguredStaffInviteNotificationGateway()
    val inviteStaffMemberUseCase = InviteStaffMemberUseCase(
        tenantRepository, userRepository, membershipRepository, staffInviteNotificationGateway
    )
    val taxRuleRepository = ExposedTaxRuleRepository()
    val taxComputationRepository = ExposedTaxComputationRepository()
    val computeTaxUseCase = ComputeTaxUseCase(periodRepository, accountRepository, journalEntryRepository, taxComputationRepository)
    val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
    val createAccountUseCase = CreateAccountUseCase(companyRepository, accountRepository)
    val recordOpeningBalanceUseCase = RecordOpeningBalanceUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
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
    val recordVendorObligationUseCase = RecordVendorObligationUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordVendorPaymentUseCase = RecordVendorPaymentUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordInventoryReceiptUseCase = RecordInventoryReceiptUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordInventoryIssueUseCase = RecordInventoryIssueUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
    val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

    val cognitoUserPoolId = System.getenv("FISH_COGNITO_USER_POOL_ID")
        ?: error("FISH_COGNITO_USER_POOL_ID environment variable is required - no default for a security-relevant value")
    val recordAdminPhoneNumberUseCase = RecordAdminPhoneNumberUseCase(
        tenantRepository, CognitoAdminPhoneVerificationChecker(cognitoUserPoolId)
    )
    val computeMoneyVelocityUseCase = ComputeMoneyVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val computeExpenseVelocityUseCase = ComputeExpenseVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val computeSalesToExpenseRatioUseCase = ComputeSalesToExpenseRatioUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val computeBalanceSheetUseCase = ComputeBalanceSheetUseCase(companyRepository, accountRepository, journalEntryRepository)
    val computeProfitAndLossUseCase = ComputeProfitAndLossUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val computeCashFlowUseCase = ComputeCashFlowUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
    val createFixedAssetUseCase = CreateFixedAssetUseCase(companyRepository, fixedAssetRepository)
    val recordFixedAssetDepreciationUseCase = RecordFixedAssetDepreciationUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
    val assessFixedAssetImpairmentUseCase = AssessFixedAssetImpairmentUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
    val disposeFixedAssetUseCase = DisposeFixedAssetUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
    val computeFixedAssetRegisterUseCase = ComputeFixedAssetRegisterUseCase(companyRepository, fixedAssetRepository)

    // In-process scheduler for KybGracePeriodSweep (docs/DDD_Design.md
    // Section 9.4, extended 2026-08-27 to also cover the admin phone
    // number's 14-day sub-deadline) - safe as a simple background loop
    // tied to this Application's own coroutine scope specifically
    // because desired_count = 1 (ecs.tf) means there is only ever one
    // running instance; a second concurrent instance would double-run
    // this on every tick, which a real scheduled-task/EventBridge
    // approach wouldn't. Revisit if desired_count ever grows past 1.
    val kybGracePeriodSweep = KybGracePeriodSweep(tenantRepository)
    launch {
        delay(Duration.ofMinutes(1).toMillis()) // let the app finish starting up first
        while (isActive) {
            try {
                val result = kybGracePeriodSweep.run()
                if (result.suspended.isNotEmpty()) {
                    log.info("KybGracePeriodSweep suspended ${result.suspended.size} tenant(s) for expired KYB/phone verification")
                }
            } catch (e: Throwable) {
                log.error("KybGracePeriodSweep run failed", e)
            }
            delay(Duration.ofHours(24).toMillis())
        }
    }

    // EA (Enterprise Administration) - the human-facing half of
    // docs/Tenancy_Administration_Extraction_DDD_Design.md's rewiring.
    // No default/fallback for the base URL, same "no safe default for a
    // security-relevant value" reasoning as FISH_JWT_ISSUER etc. -
    // GL's own authorizeTenantFor*/`/me` genuinely cannot authorize a
    // human caller without this configured.
    val eaApiBaseUrl = System.getenv("EA_API_BASE_URL")
        ?: error("EA_API_BASE_URL environment variable is required - no default for a security-relevant value")
    val eaHttpClient = HttpClient(CIO) { install(ClientContentNegotiation) { clientJson() } }
    val eaMembershipGateway = KtorEaMembershipGateway(eaHttpClient, eaApiBaseUrl)

    fishModule(
        verifier = buildJwksVerifier(),
        serviceVerifier = buildJwksServiceVerifier(),
        imServiceVerifier = buildJwksServiceVerifierForIm(),
        hrServiceVerifier = buildJwksServiceVerifierForHr(),
        popServiceVerifier = buildJwksServiceVerifierForPop(),
        eaMembershipGateway = eaMembershipGateway,
        userRepository = userRepository,
        membershipRepository = membershipRepository,
        companyRepository = companyRepository,
        tenantRepository = tenantRepository,
        onboardTenantUseCase = onboardTenantUseCase,
        addCompanyToTenantUseCase = addCompanyToTenantUseCase,
        inviteStaffMemberUseCase = inviteStaffMemberUseCase,
        computeTaxUseCase = computeTaxUseCase,
        taxRuleRepository = taxRuleRepository,
        taxComputationRepository = taxComputationRepository,
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
        recordVendorObligationUseCase = recordVendorObligationUseCase,
        recordVendorPaymentUseCase = recordVendorPaymentUseCase,
        recordInventoryReceiptUseCase = recordInventoryReceiptUseCase,
        recordInventoryIssueUseCase = recordInventoryIssueUseCase,
        recordPayRunUseCase = recordPayRunUseCase,
        getOrCreateLeaveAccrualUseCase = getOrCreateLeaveAccrualUseCase,
        idempotencyKeyRepository = idempotencyKeyRepository,
        recordAdminPhoneNumberUseCase = recordAdminPhoneNumberUseCase,
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
    eaMembershipGateway: EaMembershipGateway,
    userRepository: UserRepository,
    membershipRepository: MembershipRepository,
    companyRepository: CompanyRepository,
    tenantRepository: TenantRepository,
    onboardTenantUseCase: OnboardTenantUseCase,
    addCompanyToTenantUseCase: AddCompanyToTenantUseCase,
    inviteStaffMemberUseCase: InviteStaffMemberUseCase,
    computeTaxUseCase: ComputeTaxUseCase,
    taxRuleRepository: TaxRuleRepository,
    taxComputationRepository: TaxComputationRepository,
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
    recordVendorObligationUseCase: RecordVendorObligationUseCase,
    recordVendorPaymentUseCase: RecordVendorPaymentUseCase,
    recordInventoryReceiptUseCase: RecordInventoryReceiptUseCase,
    recordInventoryIssueUseCase: RecordInventoryIssueUseCase,
    recordPayRunUseCase: RecordPayRunUseCase,
    getOrCreateLeaveAccrualUseCase: GetOrCreateLeaveAccrualUseCase,
    idempotencyKeyRepository: IdempotencyKeyRepository,
    recordAdminPhoneNumberUseCase: RecordAdminPhoneNumberUseCase,
    computeMoneyVelocityUseCase: ComputeMoneyVelocityUseCase,
    computeExpenseVelocityUseCase: ComputeExpenseVelocityUseCase,
    computeSalesToExpenseRatioUseCase: ComputeSalesToExpenseRatioUseCase,
    computeBalanceSheetUseCase: ComputeBalanceSheetUseCase,
    computeProfitAndLossUseCase: ComputeProfitAndLossUseCase,
    computeCashFlowUseCase: ComputeCashFlowUseCase,
    fixedAssetRepository: FixedAssetRepository,
    createFixedAssetUseCase: CreateFixedAssetUseCase,
    recordFixedAssetDepreciationUseCase: RecordFixedAssetDepreciationUseCase,
    assessFixedAssetImpairmentUseCase: AssessFixedAssetImpairmentUseCase,
    disposeFixedAssetUseCase: DisposeFixedAssetUseCase,
    computeFixedAssetRegisterUseCase: ComputeFixedAssetRegisterUseCase
) {
    install(ContentNegotiation) { json() }
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
        verifier, userRepository, membershipRepository, eaMembershipGateway,
        serviceVerifier ?: verifier, imServiceVerifier ?: verifier, hrServiceVerifier ?: verifier, popServiceVerifier ?: verifier
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
            fishOnboarding {
                tenantRoutesOnboarding(onboardTenantUseCase)
            }
            fishAuthenticated {
                tenantRoutesAuthenticated(addCompanyToTenantUseCase, inviteStaffMemberUseCase, tenantRepository, userRepository, membershipRepository)
                adminPhoneRoutes(recordAdminPhoneNumberUseCase)
                journalEntryRoutes(
                    postJournalEntryUseCase, createAccountUseCase, recordOpeningBalanceUseCase,
                    periodRepository, accountRepository, journalEntryRepository, companyRepository, idempotencyKeyRepository
                )
                payrollRoutes(
                    remeasureLeaveAccrualUseCase, utilizeLeaveAccrualUseCase, leaveAccrualRepository,
                    recordPayRunUseCase, getOrCreateLeaveAccrualUseCase,
                    companyRepository, idempotencyKeyRepository
                )
                recordSaleAndCollectionRoutes(recordSaleUseCase, recordCollectionUseCase, companyRepository, idempotencyKeyRepository)
                recordSalesReturnRoutes(recordSalesReturnUseCase, companyRepository, idempotencyKeyRepository)
                createSalesInvoiceRoutes(createSalesInvoiceUseCase, listSalesInvoicesUseCase, companyRepository, customerRepository, idempotencyKeyRepository)
                recordVendorObligationAndPaymentRoutes(recordVendorObligationUseCase, recordVendorPaymentUseCase, companyRepository, idempotencyKeyRepository)
                recordInventoryReceiptAndIssueRoutes(recordInventoryReceiptUseCase, recordInventoryIssueUseCase, companyRepository, idempotencyKeyRepository)
                meRoutes(companyRepository)
                moneyVelocityRoutes(computeMoneyVelocityUseCase, companyRepository)
                salesPostingContextRoutes(
                    ComputeSalesPostingContextUseCase(companyRepository, periodRepository, accountRepository), companyRepository
                )
                customerBalancesRoutes(
                    ComputeCustomerBalancesUseCase(companyRepository, accountRepository, journalEntryRepository), companyRepository
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
                reportsRoutes(computeBalanceSheetUseCase, computeProfitAndLossUseCase, computeCashFlowUseCase, companyRepository)
                taxRoutes(computeTaxUseCase, companyRepository, taxRuleRepository, taxComputationRepository)
                fixedAssetRoutes(
                    createFixedAssetUseCase, recordFixedAssetDepreciationUseCase, assessFixedAssetImpairmentUseCase,
                    disposeFixedAssetUseCase, computeFixedAssetRegisterUseCase, fixedAssetRepository, periodRepository,
                    companyRepository, idempotencyKeyRepository
                )
            }
        }
    }
}
