package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeCreditorRepository
import com.theprodeogroup.fish.application.FakeCustomerRepository
import com.theprodeogroup.fish.application.FakeJournalEntryRepository
import com.theprodeogroup.fish.application.FakeLeaveAccrualRepository
import com.theprodeogroup.fish.application.FakeMembershipRepository
import com.theprodeogroup.fish.application.FakePayRunRepository
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakePurchaseOrderRepository
import com.theprodeogroup.fish.application.FakeSalesOrderRepository
import com.theprodeogroup.fish.application.FakeStockItemRepository
import com.theprodeogroup.fish.application.FakeUserRepository
import com.theprodeogroup.fish.application.FakeTenantRepository
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.OnboardTenantUseCase
import com.theprodeogroup.fish.application.PostInventoryIssueUseCase
import com.theprodeogroup.fish.application.PostInventoryReceiptUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.PostPayRunUseCase
import com.theprodeogroup.fish.application.PostPurchaseOrderUseCase
import com.theprodeogroup.fish.application.PostSalesOrderUseCase
import com.theprodeogroup.fish.application.FakeAdminPhoneVerificationChecker
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.application.RecordAdminPhoneNumberUseCase
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
import com.theprodeogroup.fish.application.RecordCollectionUseCase
import com.theprodeogroup.fish.application.RecordInventoryIssueUseCase
import com.theprodeogroup.fish.application.RecordInventoryReceiptUseCase
import com.theprodeogroup.fish.application.RecordPayRunUseCase
import com.theprodeogroup.fish.application.RecordSaleUseCase
import com.theprodeogroup.fish.application.RecordVendorObligationUseCase
import com.theprodeogroup.fish.application.RecordVendorPaymentUseCase
import com.theprodeogroup.fish.application.RemeasureLeaveAccrualUseCase
import com.theprodeogroup.fish.application.UtilizeLeaveAccrualUseCase
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.User
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.server.application.Application
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 26)
private const val TEST_EMAIL = "accountant@example.com"

/**
 * `POST /journal-entries` via Ktor's `testApplication` - fakes
 * throughout, no real database or network JWKS endpoint (see
 * [TestJwtSupport]). Covers the full auth -> tenant-ownership ->
 * use-case -> `Result`-to-HTTP pipeline, not just the use case itself
 * (already covered by `PostJournalEntryUseCaseTest`).
 */
class JournalEntryRoutesTest {

    private class Fixture(role: Role) {
        val userRepository = FakeUserRepository()
        val membershipRepository = FakeMembershipRepository()
        val companyRepository = FakeCompanyRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
        val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
        val postPurchaseOrderUseCase = PostPurchaseOrderUseCase(
            FakePurchaseOrderRepository(), FakeCreditorRepository(), FakeStockItemRepository(),
            periodRepository, accountRepository, journalEntryRepository
        )
        val purchaseOrderRepository = FakePurchaseOrderRepository()
        val payRunRepository = FakePayRunRepository()
        val postPayRunUseCase = PostPayRunUseCase(payRunRepository, periodRepository, accountRepository, journalEntryRepository)
        val leaveAccrualRepository = FakeLeaveAccrualRepository()
        val remeasureLeaveAccrualUseCase = RemeasureLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
        val utilizeLeaveAccrualUseCase = UtilizeLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
        val stockItemRepository = FakeStockItemRepository()
        val postInventoryReceiptUseCase = PostInventoryReceiptUseCase(stockItemRepository, periodRepository, accountRepository, journalEntryRepository)
        val postInventoryIssueUseCase = PostInventoryIssueUseCase(stockItemRepository, periodRepository, accountRepository, journalEntryRepository)
        val salesOrderRepository = FakeSalesOrderRepository()
        val postSalesOrderUseCase = PostSalesOrderUseCase(
            salesOrderRepository, FakeCustomerRepository(), stockItemRepository, periodRepository, accountRepository, journalEntryRepository
        )
        val recordSaleUseCase = RecordSaleUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordCollectionUseCase = RecordCollectionUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordVendorObligationUseCase = RecordVendorObligationUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordVendorPaymentUseCase = RecordVendorPaymentUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordInventoryReceiptUseCase = RecordInventoryReceiptUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordInventoryIssueUseCase = RecordInventoryIssueUseCase(periodRepository, accountRepository, journalEntryRepository)
        val idempotencyKeyRepository = FakeIdempotencyKeyRepository()
        val tenantRepository = FakeTenantRepository()
        val onboardTenantUseCase = OnboardTenantUseCase(tenantRepository, companyRepository, userRepository, membershipRepository, accountRepository, periodRepository, journalEntryRepository)
        val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(tenantRepository, companyRepository, accountRepository, periodRepository, journalEntryRepository)
        val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
        val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

        val tenantId = TenantId.generate()
        val user = User.create(TEST_EMAIL, "Test Accountant").also { userRepository.save(it) }
        val membership = Membership.grant(user.id, tenantId, role).also { membershipRepository.save(it) }
        val company = Company.create(tenantId, "Test Co", ClientType.NON_PROFIT, "GB", GBP).also { companyRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            periodRepository.save(it)
        }
        val debitAccount = Account.create(company.id, AccountType.EXPENSE, null, "5000", "Test Expense").also { accountRepository.save(it) }
        val creditAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Test Cash").also { accountRepository.save(it) }

        val adminPhoneVerificationChecker = FakeAdminPhoneVerificationChecker()

        val recordAdminPhoneNumberUseCase = RecordAdminPhoneNumberUseCase(tenantRepository, adminPhoneVerificationChecker)


        fun installInto(app: Application) {
            app.fishModule(
                verifier = TestJwtSupport.verifier(),
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
                idempotencyKeyRepository = idempotencyKeyRepository,
                recordAdminPhoneNumberUseCase = recordAdminPhoneNumberUseCase,
                tenantRepository = tenantRepository,
                onboardTenantUseCase = onboardTenantUseCase,
                addCompanyToTenantUseCase = addCompanyToTenantUseCase
            )
        }
    }

    private fun validLinesJson(fixture: Fixture) = """
        [
          {"accountId": "${fixture.debitAccount.id.value}", "amount": "100.00", "currency": "GBP", "side": "DEBIT"},
          {"accountId": "${fixture.creditAccount.id.value}", "amount": "100.00", "currency": "GBP", "side": "CREDIT"}
        ]
    """.trimIndent()

    @Test
    fun `given a valid request with a bearer token and matching X-Tenant-Id, when posted, then it returns 201 with the entry status`() = testApplication {
        val fixture = Fixture(Role.ACCOUNTANT)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/journal-entries") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"periodId": "${fixture.period.id.value}", "date": "$TODAY", "lines": ${validLinesJson(fixture)}, "source": "MANUAL"}""")
        }

        response.status shouldBe HttpStatusCode.Created
        val body: JournalEntryResponseDto = response.body()
        body.status shouldBe "POSTED"
    }

    @Test
    fun `given no bearer token, when posted, then it returns 401`() = testApplication {
        val fixture = Fixture(Role.ACCOUNTANT)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/journal-entries") {
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"periodId": "${fixture.period.id.value}", "date": "$TODAY", "lines": [], "source": "MANUAL"}""")
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a caller with a READ_ONLY Membership, when posted, then it returns 403`() = testApplication {
        val fixture = Fixture(Role.READ_ONLY)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/journal-entries") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"periodId": "${fixture.period.id.value}", "date": "$TODAY", "lines": [], "source": "MANUAL"}""")
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given a claimed X-Tenant-Id that does not own the Period, when posted, then it returns 403`() = testApplication {
        val fixture = Fixture(Role.ACCOUNTANT)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/journal-entries") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", TenantId.generate().value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"periodId": "${fixture.period.id.value}", "date": "$TODAY", "lines": [], "source": "MANUAL"}""")
        }

        response.status shouldBe HttpStatusCode.Forbidden
        response.bodyAsText() shouldContain "forbidden"
    }

    @Test
    fun `given a nonexistent Period id, when posted, then it returns 404`() = testApplication {
        val fixture = Fixture(Role.ACCOUNTANT)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/journal-entries") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"periodId": "${java.util.UUID.randomUUID()}", "date": "$TODAY", "lines": [], "source": "MANUAL"}""")
        }

        response.status shouldBe HttpStatusCode.NotFound
    }
}
