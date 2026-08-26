package com.theprodeogroup.fish.infrastructure.web

import com.auth0.jwt.interfaces.JWTVerifier
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
import com.theprodeogroup.fish.application.OnboardTenantUseCase
import com.theprodeogroup.fish.application.PostInventoryIssueUseCase
import com.theprodeogroup.fish.application.PostInventoryReceiptUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.PostPayRunUseCase
import com.theprodeogroup.fish.application.PostPurchaseOrderUseCase
import com.theprodeogroup.fish.application.PostSalesOrderUseCase
import com.theprodeogroup.fish.application.RecordCollectionUseCase
import com.theprodeogroup.fish.application.RecordInventoryIssueUseCase
import com.theprodeogroup.fish.application.RecordInventoryReceiptUseCase
import com.theprodeogroup.fish.application.RecordPayRunUseCase
import com.theprodeogroup.fish.application.RecordSaleUseCase
import com.theprodeogroup.fish.application.RecordVendorObligationUseCase
import com.theprodeogroup.fish.application.RecordVendorPaymentUseCase
import com.theprodeogroup.fish.application.RemeasureLeaveAccrualUseCase
import com.theprodeogroup.fish.application.UtilizeLeaveAccrualUseCase
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualRepository
import com.theprodeogroup.fish.domain.payroll.PayRunRepository
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderRepository
import com.theprodeogroup.fish.domain.sales.SalesOrderRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.UserRepository
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCreditorRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCustomerRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedIdempotencyKeyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedLeaveAccrualRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedMembershipRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPayRunRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPurchaseOrderRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedSalesOrderRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedStockItemRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTenantRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedUserRepository
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyKeyRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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
 * (`PostJournalEntryUseCase`/`PostPurchaseOrderUseCase`), confirmed
 * scope before building - covering both shapes of use case this
 * codebase has, so the pattern was reviewable before being repeated.**
 * **Section 10.20 applies that now-proven pattern to the HR/Payroll
 * posting interface** (`PostPayRunUseCase`/`RemeasureLeaveAccrualUseCase`/
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
    val stockItemRepository = ExposedStockItemRepository()
    val purchaseOrderRepository = ExposedPurchaseOrderRepository()
    val payRunRepository = ExposedPayRunRepository()
    val leaveAccrualRepository = ExposedLeaveAccrualRepository()
    val salesOrderRepository = ExposedSalesOrderRepository()
    val customerRepository = ExposedCustomerRepository()
    val idempotencyKeyRepository = ExposedIdempotencyKeyRepository()
    val tenantRepository = ExposedTenantRepository()

    val onboardTenantUseCase = OnboardTenantUseCase(
        tenantRepository, companyRepository, userRepository, membershipRepository, accountRepository, periodRepository, journalEntryRepository
    )
    val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(
        tenantRepository, companyRepository, accountRepository, periodRepository, journalEntryRepository
    )
    val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
    val postPurchaseOrderUseCase = PostPurchaseOrderUseCase(
        purchaseOrderRepository, creditorRepository, stockItemRepository, periodRepository, accountRepository, journalEntryRepository
    )
    val postPayRunUseCase = PostPayRunUseCase(payRunRepository, periodRepository, accountRepository, journalEntryRepository)
    val remeasureLeaveAccrualUseCase = RemeasureLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
    val utilizeLeaveAccrualUseCase = UtilizeLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
    val postInventoryReceiptUseCase = PostInventoryReceiptUseCase(stockItemRepository, periodRepository, accountRepository, journalEntryRepository)
    val postInventoryIssueUseCase = PostInventoryIssueUseCase(stockItemRepository, periodRepository, accountRepository, journalEntryRepository)
    val postSalesOrderUseCase = PostSalesOrderUseCase(
        salesOrderRepository, customerRepository, stockItemRepository, periodRepository, accountRepository, journalEntryRepository
    )
    val recordSaleUseCase = RecordSaleUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordCollectionUseCase = RecordCollectionUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordVendorObligationUseCase = RecordVendorObligationUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordVendorPaymentUseCase = RecordVendorPaymentUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordInventoryReceiptUseCase = RecordInventoryReceiptUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordInventoryIssueUseCase = RecordInventoryIssueUseCase(periodRepository, accountRepository, journalEntryRepository)
    val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
    val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

    fishModule(
        verifier = buildJwksVerifier(),
        userRepository = userRepository,
        membershipRepository = membershipRepository,
        companyRepository = companyRepository,
        tenantRepository = tenantRepository,
        onboardTenantUseCase = onboardTenantUseCase,
        addCompanyToTenantUseCase = addCompanyToTenantUseCase,
        periodRepository = periodRepository,
        postJournalEntryUseCase = postJournalEntryUseCase,
        purchaseOrderRepository = purchaseOrderRepository,
        postPurchaseOrderUseCase = postPurchaseOrderUseCase,
        payRunRepository = payRunRepository,
        postPayRunUseCase = postPayRunUseCase,
        leaveAccrualRepository = leaveAccrualRepository,
        remeasureLeaveAccrualUseCase = remeasureLeaveAccrualUseCase,
        utilizeLeaveAccrualUseCase = utilizeLeaveAccrualUseCase,
        stockItemRepository = stockItemRepository,
        postInventoryReceiptUseCase = postInventoryReceiptUseCase,
        postInventoryIssueUseCase = postInventoryIssueUseCase,
        salesOrderRepository = salesOrderRepository,
        postSalesOrderUseCase = postSalesOrderUseCase,
        recordSaleUseCase = recordSaleUseCase,
        recordCollectionUseCase = recordCollectionUseCase,
        recordVendorObligationUseCase = recordVendorObligationUseCase,
        recordVendorPaymentUseCase = recordVendorPaymentUseCase,
        recordInventoryReceiptUseCase = recordInventoryReceiptUseCase,
        recordInventoryIssueUseCase = recordInventoryIssueUseCase,
        recordPayRunUseCase = recordPayRunUseCase,
        getOrCreateLeaveAccrualUseCase = getOrCreateLeaveAccrualUseCase,
        idempotencyKeyRepository = idempotencyKeyRepository
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
    userRepository: UserRepository,
    membershipRepository: MembershipRepository,
    companyRepository: CompanyRepository,
    tenantRepository: TenantRepository,
    onboardTenantUseCase: OnboardTenantUseCase,
    addCompanyToTenantUseCase: AddCompanyToTenantUseCase,
    periodRepository: PeriodRepository,
    postJournalEntryUseCase: PostJournalEntryUseCase,
    purchaseOrderRepository: PurchaseOrderRepository,
    postPurchaseOrderUseCase: PostPurchaseOrderUseCase,
    payRunRepository: PayRunRepository,
    postPayRunUseCase: PostPayRunUseCase,
    leaveAccrualRepository: LeaveAccrualRepository,
    remeasureLeaveAccrualUseCase: RemeasureLeaveAccrualUseCase,
    utilizeLeaveAccrualUseCase: UtilizeLeaveAccrualUseCase,
    stockItemRepository: StockItemRepository,
    postInventoryReceiptUseCase: PostInventoryReceiptUseCase,
    postInventoryIssueUseCase: PostInventoryIssueUseCase,
    salesOrderRepository: SalesOrderRepository,
    postSalesOrderUseCase: PostSalesOrderUseCase,
    recordSaleUseCase: RecordSaleUseCase,
    recordCollectionUseCase: RecordCollectionUseCase,
    recordVendorObligationUseCase: RecordVendorObligationUseCase,
    recordVendorPaymentUseCase: RecordVendorPaymentUseCase,
    recordInventoryReceiptUseCase: RecordInventoryReceiptUseCase,
    recordInventoryIssueUseCase: RecordInventoryIssueUseCase,
    recordPayRunUseCase: RecordPayRunUseCase,
    getOrCreateLeaveAccrualUseCase: GetOrCreateLeaveAccrualUseCase,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    install(ContentNegotiation) { json() }
    install(CallLogging) { level = Level.INFO }
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
    installFishJwtAuth(verifier, userRepository, membershipRepository)

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
                tenantRoutesAuthenticated(addCompanyToTenantUseCase, tenantRepository)
                journalEntryRoutes(postJournalEntryUseCase, periodRepository, companyRepository, idempotencyKeyRepository)
                purchaseOrderRoutes(postPurchaseOrderUseCase, purchaseOrderRepository, companyRepository, idempotencyKeyRepository)
                payrollRoutes(
                    postPayRunUseCase, payRunRepository,
                    remeasureLeaveAccrualUseCase, utilizeLeaveAccrualUseCase, leaveAccrualRepository,
                    recordPayRunUseCase, getOrCreateLeaveAccrualUseCase,
                    companyRepository, idempotencyKeyRepository
                )
                inventoryRoutes(postInventoryReceiptUseCase, postInventoryIssueUseCase, stockItemRepository, companyRepository, idempotencyKeyRepository)
                salesOrderRoutes(postSalesOrderUseCase, salesOrderRepository, companyRepository, idempotencyKeyRepository)
                recordSaleAndCollectionRoutes(recordSaleUseCase, recordCollectionUseCase, companyRepository, idempotencyKeyRepository)
                recordVendorObligationAndPaymentRoutes(recordVendorObligationUseCase, recordVendorPaymentUseCase, companyRepository, idempotencyKeyRepository)
                recordInventoryReceiptAndIssueRoutes(recordInventoryReceiptUseCase, recordInventoryIssueUseCase, companyRepository, idempotencyKeyRepository)
            }
        }
    }
}
