package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeCreditorRepository
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.fish.application.FakeCustomerRepository
import com.theprodeogroup.fish.application.FakeJournalEntryRepository
import com.theprodeogroup.fish.application.FakeLeaveAccrualRepository
import com.theprodeogroup.fish.application.FakeMembershipRepository
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakeSalesInvoiceRecordRepository
import com.theprodeogroup.fish.application.FakeUserRepository
import com.theprodeogroup.fish.application.FakeTenantRepository
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.application.FakeTaxRuleRepository
import com.theprodeogroup.fish.application.FakeTaxComputationRepository
import com.theprodeogroup.fish.application.InviteStaffMemberUseCase
import com.theprodeogroup.fish.application.FakeStaffInviteNotificationGateway
import com.theprodeogroup.fish.application.OnboardTenantUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.FakeAdminPhoneVerificationChecker
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.application.ComputeBalanceSheetUseCase
import com.theprodeogroup.fish.application.ComputeProfitAndLossUseCase
import com.theprodeogroup.fish.application.ComputeCashFlowUseCase
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
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 21)
private const val TEST_EMAIL = "sop-caller@example.com"

/**
 * `POST /sales/record-sale` and `POST /sales/record-collection` via
 * Ktor's `testApplication` (docs/Sales_Order_Processing_DDD_Design.md
 * Section 0) - unlike [SalesOrderRoutesTest], there's no owning
 * aggregate to derive tenant scoping from, so every request body
 * carries `companyId` directly.
 */
class RecordSaleAndCollectionRoutesTest {

    private class Fixture(role: Role = Role.ACCOUNTANT) {
        val userRepository = FakeUserRepository()
        val membershipRepository = FakeMembershipRepository()
        val companyRepository = FakeCompanyRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
        val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
        val leaveAccrualRepository = FakeLeaveAccrualRepository()
        val remeasureLeaveAccrualUseCase = RemeasureLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
        val utilizeLeaveAccrualUseCase = UtilizeLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
        val customerRepository = FakeCustomerRepository()
        val recordSaleUseCase = RecordSaleUseCase(periodRepository, accountRepository, journalEntryRepository)
        val salesInvoiceRecordRepository = FakeSalesInvoiceRecordRepository()
        val createSalesInvoiceUseCase = CreateSalesInvoiceUseCase(
            periodRepository, accountRepository, customerRepository, journalEntryRepository, salesInvoiceRecordRepository
        )
        val listSalesInvoicesUseCase = ListSalesInvoicesUseCase(companyRepository, salesInvoiceRecordRepository)
        val recordCollectionUseCase = RecordCollectionUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordVendorObligationUseCase = RecordVendorObligationUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordVendorPaymentUseCase = RecordVendorPaymentUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordInventoryReceiptUseCase = RecordInventoryReceiptUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordInventoryIssueUseCase = RecordInventoryIssueUseCase(periodRepository, accountRepository, journalEntryRepository)
        val idempotencyKeyRepository = FakeIdempotencyKeyRepository()
        val tenantRepository = FakeTenantRepository()
        val onboardTenantUseCase = OnboardTenantUseCase(tenantRepository, companyRepository, userRepository, membershipRepository, accountRepository, periodRepository, journalEntryRepository)
        val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(tenantRepository, companyRepository, accountRepository, periodRepository, journalEntryRepository)
        val taxRuleRepository = FakeTaxRuleRepository()
        val taxComputationRepository = FakeTaxComputationRepository()
        val computeTaxUseCase = ComputeTaxUseCase(periodRepository, accountRepository, journalEntryRepository, taxComputationRepository)
        val inviteStaffMemberUseCase = InviteStaffMemberUseCase(tenantRepository, userRepository, membershipRepository, FakeStaffInviteNotificationGateway())
        val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
        val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

        val tenantId = TenantId.generate()
        val user = User.create(TEST_EMAIL, "Test SOP Caller").also { userRepository.save(it) }
        val membership = Membership.grant(user.id, tenantId, role).also { membershipRepository.save(it) }
        val company = Company.create(tenantId, "Test Co", ClientType.NON_PROFIT, "GB", GBP).also { companyRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            periodRepository.save(it)
        }
        val arControlAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable").also { accountRepository.save(it) }
        val revenueAccount = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales Revenue").also { accountRepository.save(it) }
        val cashAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }

        val adminPhoneVerificationChecker = FakeAdminPhoneVerificationChecker()

        val recordAdminPhoneNumberUseCase = RecordAdminPhoneNumberUseCase(tenantRepository, adminPhoneVerificationChecker)


        val computeMoneyVelocityUseCase = ComputeMoneyVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeExpenseVelocityUseCase = ComputeExpenseVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeSalesToExpenseRatioUseCase = ComputeSalesToExpenseRatioUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeBalanceSheetUseCase = ComputeBalanceSheetUseCase(companyRepository, accountRepository, journalEntryRepository)
        val computeProfitAndLossUseCase = ComputeProfitAndLossUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeCashFlowUseCase = ComputeCashFlowUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)



        fun installInto(app: Application) {
            app.fishModule(
                verifier = TestJwtSupport.verifier(),
                userRepository = userRepository,
                membershipRepository = membershipRepository,
                companyRepository = companyRepository,
                periodRepository = periodRepository,
                accountRepository = accountRepository,
                journalEntryRepository = journalEntryRepository,
                postJournalEntryUseCase = postJournalEntryUseCase,
                leaveAccrualRepository = leaveAccrualRepository,
                remeasureLeaveAccrualUseCase = remeasureLeaveAccrualUseCase,
                utilizeLeaveAccrualUseCase = utilizeLeaveAccrualUseCase,
                recordSaleUseCase = recordSaleUseCase,
                createSalesInvoiceUseCase = createSalesInvoiceUseCase,
                listSalesInvoicesUseCase = listSalesInvoicesUseCase,
                customerRepository = customerRepository,
                recordCollectionUseCase = recordCollectionUseCase,
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
                tenantRepository = tenantRepository,
                onboardTenantUseCase = onboardTenantUseCase,
                addCompanyToTenantUseCase = addCompanyToTenantUseCase,
                inviteStaffMemberUseCase = inviteStaffMemberUseCase,
                computeTaxUseCase = computeTaxUseCase,
                taxRuleRepository = taxRuleRepository,
                taxComputationRepository = taxComputationRepository
            )
        }
    }

    @Test
    fun `given a valid record-sale request with a bearer token and matching X-Tenant-Id, when posted, then it returns 200 Posted`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "amount": "45000.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: RecordSaleResponseDto = response.body()
        body.status shouldBe "POSTED"
    }

    @Test
    fun `given no bearer token, when record-sale is posted, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-sale") {
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "amount": "45000.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a claimed X-Tenant-Id that does not own the companyId, when record-sale is posted, then it returns 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", TenantId.generate().value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "amount": "45000.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given a nonexistent companyId, when record-sale is posted, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${UUID.randomUUID()}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "amount": "45000.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `given a caller with a READ_ONLY Membership, when record-sale is posted, then it returns 403`() = testApplication {
        val fixture = Fixture(Role.READ_ONLY)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "amount": "45000.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given a non-positive amount, when record-sale is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "amount": "0.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a valid record-collection request, when posted, then it returns 200 Posted`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-collection") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "settlementAccountId": "${fixture.cashAccount.id.value}",
                    |"arControlAccountId": "${fixture.arControlAccount.id.value}", "amount": "50000.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: RecordCollectionResponseDto = response.body()
        body.status shouldBe "POSTED"
    }

    @Test
    fun `given a settlement Account that does not exist, when record-collection is posted, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-collection") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "settlementAccountId": "${UUID.randomUUID()}",
                    |"arControlAccountId": "${fixture.arControlAccount.id.value}", "amount": "50000.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    // -- Idempotency-Key (docs/GL_Production_Readiness_Plan.md) --

    @Test
    fun `given the same Idempotency-Key and body posted twice, when record-sale is posted, then the second call replays the first response without posting a second JournalEntry`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val idempotencyKey = UUID.randomUUID().toString()
        val requestBody = """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
            |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
            |"revenueAccountId": "${fixture.revenueAccount.id.value}", "amount": "45000.00", "currency": "GBP",
            |"customerId": "${UUID.randomUUID()}"}""".trimMargin()

        val first = client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            header("Idempotency-Key", idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val second = client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            header("Idempotency-Key", idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        second.status shouldBe HttpStatusCode.OK
        val firstBody: RecordSaleResponseDto = first.body()
        val secondBody: RecordSaleResponseDto = second.body()
        secondBody.journalEntryId shouldBe firstBody.journalEntryId
        fixture.journalEntryRepository.saveCalls.size shouldBe 1
    }

    @Test
    fun `given the same Idempotency-Key reused with a different body, when record-sale is posted, then it returns 422`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val idempotencyKey = UUID.randomUUID().toString()

        client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            header("Idempotency-Key", idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "amount": "45000.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }
        val response = client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            header("Idempotency-Key", idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "amount": "99999.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        val body: ErrorResponseDto = response.body()
        body.error shouldBe "idempotency_key_reused"
    }

    @Test
    fun `given no Idempotency-Key header, when the same record-sale request is posted twice, then both calls post separately`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val requestBody = """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
            |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
            |"revenueAccountId": "${fixture.revenueAccount.id.value}", "amount": "45000.00", "currency": "GBP",
            |"customerId": "${UUID.randomUUID()}"}""".trimMargin()

        client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        fixture.journalEntryRepository.saveCalls.size shouldBe 2
    }
}
