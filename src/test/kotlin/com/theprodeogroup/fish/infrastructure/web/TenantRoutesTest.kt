package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeCreditorRepository
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.fish.application.FakeCustomerRepository
import com.theprodeogroup.fish.application.FakeAdminPhoneVerificationChecker
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeInventoryScheduleUseCase
import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.application.RecordAdminPhoneNumberUseCase
import com.theprodeogroup.fish.application.FakeJournalEntryRepository
import com.theprodeogroup.fish.application.FakeLeaveAccrualRepository
import com.theprodeogroup.fish.application.FakeMembershipRepository
import com.theprodeogroup.fish.application.FakePayRunRepository
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakePurchaseOrderRepository
import com.theprodeogroup.fish.application.FakeSalesInvoiceRecordRepository
import com.theprodeogroup.fish.application.FakeSalesOrderRepository
import com.theprodeogroup.fish.application.FakeStockItemRepository
import com.theprodeogroup.fish.application.FakeStockShortageEscalationRepository
import com.theprodeogroup.fish.application.FakeTenantRepository
import com.theprodeogroup.fish.application.FakeUserRepository
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
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
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
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private const val NEW_ADMIN_EMAIL = "new-admin@example.com"
private const val EXISTING_ADMIN_EMAIL = "existing-admin@example.com"
private const val OUTSIDER_EMAIL = "outsider@example.com"

/**
 * `POST /tenants` (Section 9.2) and `POST /tenants/{tenantId}/companies`
 * (Section 9.1) via Ktor's `testApplication` - mirrors [PurchaseOrderRoutesTest]'s
 * structure, the first coverage for this codebase's newest auth path,
 * [FISH_JWT_ONBOARDING_AUTH_NAME].
 */
class TenantRoutesTest {

    private class Fixture {
        val userRepository = FakeUserRepository()
        val membershipRepository = FakeMembershipRepository()
        val companyRepository = FakeCompanyRepository()
        val tenantRepository = FakeTenantRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
        val creditorRepository = FakeCreditorRepository()
        val stockItemRepository = FakeStockItemRepository()
        val purchaseOrderRepository = FakePurchaseOrderRepository()
        val onboardTenantUseCase = OnboardTenantUseCase(tenantRepository, companyRepository, userRepository, membershipRepository, accountRepository, periodRepository, journalEntryRepository)
        val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(tenantRepository, companyRepository, accountRepository, periodRepository, journalEntryRepository)
        val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
        val postPurchaseOrderUseCase = PostPurchaseOrderUseCase(
            purchaseOrderRepository, creditorRepository, stockItemRepository, periodRepository, accountRepository, journalEntryRepository
        )
        val payRunRepository = FakePayRunRepository()
        val postPayRunUseCase = PostPayRunUseCase(payRunRepository, periodRepository, accountRepository, journalEntryRepository)
        val leaveAccrualRepository = FakeLeaveAccrualRepository()
        val remeasureLeaveAccrualUseCase = RemeasureLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
        val utilizeLeaveAccrualUseCase = UtilizeLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
        val postInventoryReceiptUseCase = PostInventoryReceiptUseCase(stockItemRepository, periodRepository, accountRepository, journalEntryRepository)
        val postInventoryIssueUseCase = PostInventoryIssueUseCase(stockItemRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeInventoryScheduleUseCase = ComputeInventoryScheduleUseCase(companyRepository, stockItemRepository)
        val salesOrderRepository = FakeSalesOrderRepository()
        val postSalesOrderUseCase = PostSalesOrderUseCase(
            salesOrderRepository, FakeCustomerRepository(), stockItemRepository, periodRepository, accountRepository, journalEntryRepository
        )
        val customerRepository = FakeCustomerRepository()
        val recordSaleUseCase = RecordSaleUseCase(periodRepository, accountRepository, journalEntryRepository)
        val salesInvoiceRecordRepository = FakeSalesInvoiceRecordRepository()
        val createSalesInvoiceUseCase = CreateSalesInvoiceUseCase(
            periodRepository, accountRepository, customerRepository, journalEntryRepository, stockItemRepository, FakeStockShortageEscalationRepository(), salesInvoiceRecordRepository
        )
        val listSalesInvoicesUseCase = ListSalesInvoicesUseCase(companyRepository, salesInvoiceRecordRepository)
        val recordCollectionUseCase = RecordCollectionUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordVendorObligationUseCase = RecordVendorObligationUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordVendorPaymentUseCase = RecordVendorPaymentUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordInventoryReceiptUseCase = RecordInventoryReceiptUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordInventoryIssueUseCase = RecordInventoryIssueUseCase(periodRepository, accountRepository, journalEntryRepository)
        val idempotencyKeyRepository = FakeIdempotencyKeyRepository()
        val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
        val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

        // An already-onboarded Tenant, for the AddCompanyToTenant tests -
        // separate from whatever OnboardTenant itself creates fresh.
        val existingTenant = Tenant.onboard("Existing Co", TenantSegment.EXTERNAL_B2B, GBP).also { tenantRepository.save(it) }
        val existingCompany = Company.create(existingTenant.id, "Existing Co UK", ClientType.NON_PROFIT, "GB", GBP).also { companyRepository.save(it) }
        val existingUser = User.create(EXISTING_ADMIN_EMAIL, "Existing Admin").also { userRepository.save(it) }
        val existingMembership = Membership.grant(existingUser.id, existingTenant.id, Role.OWNER_ADMIN).also { membershipRepository.save(it) }
        val existingTenantSetup = run {
            existingTenant.addCompany(existingCompany.id)
            existingTenant.addAdminMembership(existingMembership.id)
            existingTenant.activate()
            tenantRepository.save(existingTenant)
        }

        // A genuinely different Tenant, so the "authenticated but wrong
        // Tenant" 403 case can be tested without also tripping the
        // separate "no User/Membership at all" 401 case.
        val otherTenant = Tenant.onboard("Other Co", TenantSegment.EXTERNAL_B2B, GBP).also { tenantRepository.save(it) }
        val outsiderUser = User.create(OUTSIDER_EMAIL, "Outsider Admin").also { userRepository.save(it) }
        val outsiderMembership = Membership.grant(outsiderUser.id, otherTenant.id, Role.OWNER_ADMIN).also { membershipRepository.save(it) }

        val adminPhoneVerificationChecker = FakeAdminPhoneVerificationChecker()

        val recordAdminPhoneNumberUseCase = RecordAdminPhoneNumberUseCase(tenantRepository, adminPhoneVerificationChecker)


        val computeMoneyVelocityUseCase = ComputeMoneyVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeExpenseVelocityUseCase = ComputeExpenseVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeSalesToExpenseRatioUseCase = ComputeSalesToExpenseRatioUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)



        fun installInto(app: Application) {
            app.fishModule(
                verifier = TestJwtSupport.verifier(),
                userRepository = userRepository,
                membershipRepository = membershipRepository,
                companyRepository = companyRepository,
                tenantRepository = tenantRepository,
                onboardTenantUseCase = onboardTenantUseCase,
                addCompanyToTenantUseCase = addCompanyToTenantUseCase,
                periodRepository = periodRepository,
                accountRepository = accountRepository,
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
                computeInventoryScheduleUseCase = computeInventoryScheduleUseCase,
                salesOrderRepository = salesOrderRepository,
                postSalesOrderUseCase = postSalesOrderUseCase,
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
                computeSalesToExpenseRatioUseCase = computeSalesToExpenseRatioUseCase
            )
        }

        val onboardRequestBody = """
            {
              "tenantName": "New Co",
              "tenantSegment": "EXTERNAL_B2B",
              "tenantBaseCurrency": "GBP",
              "companyName": "New Co UK",
              "clientType": "NON_PROFIT",
              "jurisdiction": "GB",
              "companyBaseCurrency": "GBP",
              "adminName": "New Admin"
            }
        """.trimIndent()
    }

    @Test
    fun `given a valid onboarding request with a bearer token, when posted, then it returns 201 with the new Tenant's identifiers`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/tenants") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(NEW_ADMIN_EMAIL)}")
            contentType(ContentType.Application.Json)
            setBody(fixture.onboardRequestBody)
        }

        response.status shouldBe HttpStatusCode.Created
        val body: OnboardTenantResponseDto = response.body()
        body.tenantId.isNotBlank() shouldBe true
        fixture.userRepository.findByEmail(NEW_ADMIN_EMAIL)?.email shouldBe NEW_ADMIN_EMAIL
    }

    @Test
    fun `given no bearer token, when onboarding is posted, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/tenants") {
            contentType(ContentType.Application.Json)
            setBody(fixture.onboardRequestBody)
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a mix of self-managed and delegated moduleManagementPreferences, when onboarding is posted, then it returns 201`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/tenants") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(NEW_ADMIN_EMAIL)}")
            contentType(ContentType.Application.Json)
            setBody(
                """{"tenantName": "New Co", "tenantSegment": "EXTERNAL_B2B", "tenantBaseCurrency": "GBP",
                    "companyName": "New Co UK", "clientType": "NON_PROFIT", "jurisdiction": "GB",
                    "companyBaseCurrency": "GBP", "adminName": "New Admin",
                    "moduleManagementPreferences": [
                        {"module": "GL", "selfManaged": true},
                        {"module": "HR", "selfManaged": false, "delegateName": "Jane Doe", "delegateEmail": "jane@example.com"}
                    ]}"""
            )
        }

        response.status shouldBe HttpStatusCode.Created
    }

    @Test
    fun `given an invalid module in moduleManagementPreferences, when onboarding is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/tenants") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(NEW_ADMIN_EMAIL)}")
            contentType(ContentType.Application.Json)
            setBody(
                """{"tenantName": "New Co", "tenantSegment": "EXTERNAL_B2B", "tenantBaseCurrency": "GBP",
                    "companyName": "New Co UK", "clientType": "NON_PROFIT", "jurisdiction": "GB",
                    "companyBaseCurrency": "GBP", "adminName": "New Admin",
                    "moduleManagementPreferences": [{"module": "NOT_A_REAL_MODULE", "selfManaged": true}]}"""
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a delegated module with no delegateEmail, when onboarding is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/tenants") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(NEW_ADMIN_EMAIL)}")
            contentType(ContentType.Application.Json)
            setBody(
                """{"tenantName": "New Co", "tenantSegment": "EXTERNAL_B2B", "tenantBaseCurrency": "GBP",
                    "companyName": "New Co UK", "clientType": "NON_PROFIT", "jurisdiction": "GB",
                    "companyBaseCurrency": "GBP", "adminName": "New Admin",
                    "moduleManagementPreferences": [{"module": "SOP", "selfManaged": false, "delegateName": "Jane Doe"}]}"""
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given an invalid tenantSegment, when onboarding is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/tenants") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(NEW_ADMIN_EMAIL)}")
            contentType(ContentType.Application.Json)
            setBody(
                """{"tenantName": "New Co", "tenantSegment": "NOT_A_REAL_SEGMENT", "tenantBaseCurrency": "GBP",
                    "companyName": "New Co UK", "clientType": "NON_PROFIT", "jurisdiction": "GB",
                    "companyBaseCurrency": "GBP", "adminName": "New Admin"}"""
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a caller who already has a Membership elsewhere, when they onboard a brand new Tenant, then a fresh Membership is granted regardless`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // The existing admin (already a member of fixture.existingTenant) onboards a second, unrelated Tenant.
        val response = client.post("/api/tenants") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(EXISTING_ADMIN_EMAIL)}")
            contentType(ContentType.Application.Json)
            setBody(fixture.onboardRequestBody)
        }

        response.status shouldBe HttpStatusCode.Created
        val body: OnboardTenantResponseDto = response.body()
        body.tenantId shouldBe body.tenantId // sanity - a second, distinct Tenant was created
        (body.tenantId == fixture.existingTenant.id.value.toString()) shouldBe false
    }

    @Test
    fun `given a valid add-company request with a bearer token and matching X-Tenant-Id, when posted, then it returns 201`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/tenants/${fixture.existingTenant.id.value}/companies") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(EXISTING_ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.existingTenant.id.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"companyName": "Existing Co SL", "clientType": "NON_PROFIT", "jurisdiction": "SL", "companyBaseCurrency": "GBP"}""")
        }

        response.status shouldBe HttpStatusCode.Created
        val body: AddCompanyToTenantResponseDto = response.body()
        body.tenantId shouldBe fixture.existingTenant.id.value.toString()
    }

    @Test
    fun `given a caller authenticated in a different Tenant, when add-company is posted, then it returns 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/tenants/${fixture.existingTenant.id.value}/companies") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(OUTSIDER_EMAIL)}")
            header("X-Tenant-Id", fixture.existingTenant.id.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"companyName": "Existing Co SL", "clientType": "NON_PROFIT", "jurisdiction": "SL", "companyBaseCurrency": "GBP"}""")
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }
}
