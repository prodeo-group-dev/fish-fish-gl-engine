package com.theprodeogroup.fish.infrastructure.web

import com.auth0.jwt.interfaces.JWTVerifier
import com.theprodeogroup.fish.application.PostInventoryIssueUseCase
import com.theprodeogroup.fish.application.PostInventoryReceiptUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.PostPayRunUseCase
import com.theprodeogroup.fish.application.PostPurchaseOrderUseCase
import com.theprodeogroup.fish.application.RemeasureLeaveAccrualUseCase
import com.theprodeogroup.fish.application.UtilizeLeaveAccrualUseCase
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualRepository
import com.theprodeogroup.fish.domain.payroll.PayRunRepository
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.UserRepository
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCreditorRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedLeaveAccrualRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedMembershipRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPayRunRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPurchaseOrderRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedStockItemRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedUserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
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

    val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
    val postPurchaseOrderUseCase = PostPurchaseOrderUseCase(
        purchaseOrderRepository, creditorRepository, stockItemRepository, periodRepository, accountRepository, journalEntryRepository
    )
    val postPayRunUseCase = PostPayRunUseCase(payRunRepository, periodRepository, accountRepository, journalEntryRepository)
    val remeasureLeaveAccrualUseCase = RemeasureLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
    val utilizeLeaveAccrualUseCase = UtilizeLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
    val postInventoryReceiptUseCase = PostInventoryReceiptUseCase(stockItemRepository, periodRepository, accountRepository, journalEntryRepository)
    val postInventoryIssueUseCase = PostInventoryIssueUseCase(stockItemRepository, periodRepository, accountRepository, journalEntryRepository)

    fishModule(
        verifier = buildJwksVerifier(),
        userRepository = userRepository,
        membershipRepository = membershipRepository,
        companyRepository = companyRepository,
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
        postInventoryIssueUseCase = postInventoryIssueUseCase
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
    postInventoryIssueUseCase: PostInventoryIssueUseCase
) {
    install(ContentNegotiation) { json() }
    install(CallLogging) { level = Level.INFO }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponseDto("internal_error", cause.message))
        }
    }
    installFishJwtAuth(verifier, userRepository, membershipRepository)

    routing {
        healthRoutes()
        fishAuthenticated {
            journalEntryRoutes(postJournalEntryUseCase, periodRepository, companyRepository)
            purchaseOrderRoutes(postPurchaseOrderUseCase, purchaseOrderRepository, companyRepository)
            payrollRoutes(
                postPayRunUseCase, payRunRepository,
                remeasureLeaveAccrualUseCase, utilizeLeaveAccrualUseCase, leaveAccrualRepository,
                companyRepository
            )
            inventoryRoutes(postInventoryReceiptUseCase, postInventoryIssueUseCase, stockItemRepository, companyRepository)
        }
    }
}
