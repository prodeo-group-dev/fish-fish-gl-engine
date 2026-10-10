package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.application.FakeTaxRuleRepository
import com.theprodeogroup.fish.application.FakeTaxComputationRepository
import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeSupplierRepository
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.fish.application.FakeCustomerRepository
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.application.FakeJurisdictionRepository
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.application.ComputeBalanceSheetUseCase
import com.theprodeogroup.fish.application.ComputeProfitAndLossUseCase
import com.theprodeogroup.fish.application.ComputeCashFlowUseCase
import com.theprodeogroup.fish.application.AssessFixedAssetImpairmentUseCase
import com.theprodeogroup.fish.application.ComputeFixedAssetRegisterUseCase
import com.theprodeogroup.fish.application.CreateFixedAssetUseCase
import com.theprodeogroup.fish.application.DisposeFixedAssetUseCase
import com.theprodeogroup.fish.application.FakeFixedAssetRepository
import com.theprodeogroup.fish.application.RecordFixedAssetDepreciationUseCase
import com.theprodeogroup.fish.application.FakeJournalEntryRepository
import com.theprodeogroup.fish.application.FakeLeaveAccrualRepository
import com.theprodeogroup.fish.application.FakeMembershipRepository
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakeSalesInvoiceRecordRepository
import com.theprodeogroup.fish.application.FakeEaMembershipGateway
import com.theprodeogroup.fish.application.FakeUserRepository
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
import com.theprodeogroup.fish.application.CreateAccountUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.RecordOpeningBalanceUseCase
import com.theprodeogroup.fish.application.RecordCollectionUseCase
import com.theprodeogroup.fish.application.RecordSalesReturnUseCase
import com.theprodeogroup.fish.application.RecordInventoryIssueUseCase
import com.theprodeogroup.fish.application.RecordInventoryReceiptUseCase
import com.theprodeogroup.fish.application.RecordPayRunUseCase
import com.theprodeogroup.fish.application.RecordSaleUseCase
import com.theprodeogroup.fish.application.RecordSupplierObligationUseCase
import com.theprodeogroup.fish.application.RecordSupplierPaymentUseCase
import com.theprodeogroup.fish.application.RemeasureLeaveAccrualUseCase
import com.theprodeogroup.fish.application.UtilizeLeaveAccrualUseCase
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JurisdictionEntry
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.application.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.application.User
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
private const val EXISTING_ADMIN_EMAIL = "existing-admin@example.com"
private const val OUTSIDER_EMAIL = "outsider@example.com"

/**
 * `POST /tenants/{tenantId}/companies` via Ktor's `testApplication` - the
 * one Tenancy route still live in GL (see `TenantRoutes.kt`'s own KDoc).
 * Onboarding (`POST /tenants`) and staff invite/list
 * (`/tenants/{tenantId}/memberships`) moved to EA 2026-09-05
 * (`docs/Tenancy_Administration_Extraction_DDD_Design.md`'s WEB->EA+GL
 * handoff) - their own coverage now lives in EA's test suite, not here.
 */
class TenantRoutesTest {

    private class Fixture {
        val userRepository = FakeUserRepository()
        val membershipRepository = FakeMembershipRepository()
        val companyRepository = FakeCompanyRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
        val supplierRepository = FakeSupplierRepository()
        val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(companyRepository, accountRepository, periodRepository, journalEntryRepository)
        val taxRuleRepository = FakeTaxRuleRepository()
        val taxComputationRepository = FakeTaxComputationRepository()
        val computeTaxUseCase = ComputeTaxUseCase(periodRepository, accountRepository, journalEntryRepository, taxComputationRepository)
        val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
        val createAccountUseCase = CreateAccountUseCase(companyRepository, accountRepository)
        val recordOpeningBalanceUseCase = RecordOpeningBalanceUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
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
        val recordSalesReturnUseCase = RecordSalesReturnUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordSupplierObligationUseCase = RecordSupplierObligationUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordSupplierPaymentUseCase = RecordSupplierPaymentUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordInventoryReceiptUseCase = RecordInventoryReceiptUseCase(periodRepository, accountRepository, journalEntryRepository)
        val recordInventoryIssueUseCase = RecordInventoryIssueUseCase(periodRepository, accountRepository, journalEntryRepository)
        val idempotencyKeyRepository = FakeIdempotencyKeyRepository()
        val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
        val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

        val existingTenantId = TenantId.generate()
        val existingCompany = Company.create(existingTenantId, "Existing Co UK", ClientType.NON_PROFIT, Jurisdiction.UK, GBP).also { companyRepository.save(it) }
        val existingUser = User.create(EXISTING_ADMIN_EMAIL, "Existing Admin").also { userRepository.save(it) }
        val existingMembership = Membership.grant(existingUser.id, existingTenantId, Role.OWNER_ADMIN, existingCompany.id).also { membershipRepository.save(it) }

        // A genuinely different Tenant, so the "authenticated but wrong
        // Tenant" 403 case can be tested without also tripping the
        // separate "no User/Membership at all" 401 case.
        val otherTenantId = TenantId.generate()
        val outsiderUser = User.create(OUTSIDER_EMAIL, "Outsider Admin").also { userRepository.save(it) }
        val outsiderMembership = Membership.grant(outsiderUser.id, otherTenantId, Role.OWNER_ADMIN, CompanyId.generate()).also { membershipRepository.save(it) }

        val computeMoneyVelocityUseCase = ComputeMoneyVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeExpenseVelocityUseCase = ComputeExpenseVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeSalesToExpenseRatioUseCase = ComputeSalesToExpenseRatioUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeBalanceSheetUseCase = ComputeBalanceSheetUseCase(companyRepository, accountRepository, journalEntryRepository)
        val computeProfitAndLossUseCase = ComputeProfitAndLossUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeCashFlowUseCase = ComputeCashFlowUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val fixedAssetRepository = FakeFixedAssetRepository()
        val createFixedAssetUseCase = CreateFixedAssetUseCase(companyRepository, fixedAssetRepository, postJournalEntryUseCase)
        val recordFixedAssetDepreciationUseCase = RecordFixedAssetDepreciationUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
        val assessFixedAssetImpairmentUseCase = AssessFixedAssetImpairmentUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
        val disposeFixedAssetUseCase = DisposeFixedAssetUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeFixedAssetRegisterUseCase = ComputeFixedAssetRegisterUseCase(companyRepository, fixedAssetRepository)
        val jurisdictionRepository = FakeJurisdictionRepository()

        fun installInto(app: Application) {
            app.fishModule(
                verifier = TestJwtSupport.verifier(),
                eaMembershipGateway = FakeEaMembershipGateway(userRepository, membershipRepository),
                companyRepository = companyRepository,
                addCompanyToTenantUseCase = addCompanyToTenantUseCase,
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
                computeFixedAssetRegisterUseCase = computeFixedAssetRegisterUseCase,
                jurisdictionRepository = jurisdictionRepository,
                popServiceVerifier = TestJwtSupport.popServiceVerifier()
            )
        }
    }

    @Test
    fun `given a valid add-company request with a bearer token and matching X-Tenant-Id, when posted, then it returns 201`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/tenants/${fixture.existingTenantId.value}/companies") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(EXISTING_ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.existingTenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"companyName": "Existing Co SL", "clientType": "NON_PROFIT", "jurisdiction": "SL", "companyBaseCurrency": "SLE", "fiscalYearStartMonth": 1}""")
        }

        response.status shouldBe HttpStatusCode.Created
        val body: AddCompanyToTenantResponseDto = response.body()
        body.tenantId shouldBe fixture.existingTenantId.value.toString()
    }

    @Test
    fun `given a service credential, when add-company is posted for any Tenant id, then it returns 403 and creates no Company`() = testApplication {
        // T15 / F-T15-2: a Company is added to a Tenant by that Tenant's Owner-Admin (a person), never by a
        // service account. The service-account bypass used to let any service credential create a Company
        // under any Tenant id - even one that does not exist - because the use case trusts the auth layer.
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val serviceToken = TestJwtSupport.signPopServiceToken("pop-gl-service@theprodeogroup.com")
        val companiesBefore = fixture.companyRepository.findAllByTenant(fixture.existingTenantId).size

        for (tenantId in listOf(fixture.existingTenantId.value, java.util.UUID.randomUUID())) {
            val response = client.post("/api/tenants/$tenantId/companies") {
                header(HttpHeaders.Authorization, "Bearer $serviceToken")
                header("X-Tenant-Id", tenantId.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"companyName": "Planted Co", "clientType": "NON_PROFIT", "jurisdiction": "SL", "companyBaseCurrency": "SLE", "fiscalYearStartMonth": 1}""")
            }
            response.status shouldBe HttpStatusCode.Forbidden
        }
        fixture.companyRepository.findAllByTenant(fixture.existingTenantId).size shouldBe companiesBefore
    }

    @Test
    fun `given a caller authenticated in a different Tenant, when add-company is posted, then it returns 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/tenants/${fixture.existingTenantId.value}/companies") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(OUTSIDER_EMAIL)}")
            header("X-Tenant-Id", fixture.existingTenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"companyName": "Existing Co SL", "clientType": "NON_PROFIT", "jurisdiction": "SL", "companyBaseCurrency": "SLE", "fiscalYearStartMonth": 1}""")
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `when GET jurisdictions is called, then it returns every Jurisdiction code with its name straight from the enum, UK including Northern Ireland and no GB`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/jurisdictions") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(EXISTING_ADMIN_EMAIL)}")
        }

        response.status shouldBe HttpStatusCode.OK
        val body: ListJurisdictionsResponseDto = response.body()
        body.jurisdictions.map { it.code }.sorted() shouldBe listOf("CI", "GN", "IE", "LR", "NG", "SL", "UK")
        body.jurisdictions.first { it.code == "UK" }.name shouldBe "United Kingdom (including Northern Ireland)"
        body.jurisdictions.none { it.code == "GB" || it.code == "NI" } shouldBe true
    }

    @Test
    fun `given a signed-in caller who has no User or Tenant yet (mid-onboarding), when GET jurisdictions is called, then it still returns 200`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/jurisdictions") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken("brand-new-founder@example.com")}")
        }

        response.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `given no bearer token, when GET jurisdictions is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/jurisdictions")

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a jurisdiction not in the registry, when add-company is posted, then it returns 400 - and once it is added as data, the same request returns 201 with no code change`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        suspend fun postCompanyIn(jurisdiction: String) = client.post("/api/tenants/${fixture.existingTenantId.value}/companies") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(EXISTING_ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.existingTenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"companyName": "Cape Co", "clientType": "COMPANY_LIMITED", "jurisdiction": "$jurisdiction", "companyBaseCurrency": "GBP", "fiscalYearStartMonth": 1}""")
        }

        postCompanyIn("ZA").status shouldBe HttpStatusCode.BadRequest

        fixture.jurisdictionRepository.save(JurisdictionEntry(Jurisdiction("ZA"), "South Africa", enabled = true))

        postCompanyIn("ZA").status shouldBe HttpStatusCode.Created
    }

    @Test
    fun `given a jurisdiction that is registered but disabled, when add-company is posted, then it returns 400 and GET jurisdictions does not offer it`() = testApplication {
        val fixture = Fixture()
        fixture.jurisdictionRepository.save(JurisdictionEntry(Jurisdiction("ZA"), "South Africa", enabled = false))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val add = client.post("/api/tenants/${fixture.existingTenantId.value}/companies") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(EXISTING_ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.existingTenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"companyName": "Cape Co", "clientType": "COMPANY_LIMITED", "jurisdiction": "ZA", "companyBaseCurrency": "GBP", "fiscalYearStartMonth": 1}""")
        }
        val list: ListJurisdictionsResponseDto = client.get("/api/jurisdictions") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(EXISTING_ADMIN_EMAIL)}")
        }.body()

        add.status shouldBe HttpStatusCode.BadRequest
        list.jurisdictions.none { it.code == "ZA" } shouldBe true
    }

    @Test
    fun `given a malformed or retired code such as GB, when add-company is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        listOf("GB", "uk", "UKK", "").forEach { bad ->
            val response = client.post("/api/tenants/${fixture.existingTenantId.value}/companies") {
                header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(EXISTING_ADMIN_EMAIL)}")
                header("X-Tenant-Id", fixture.existingTenantId.value.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"companyName": "Bad Co", "clientType": "COMPANY_LIMITED", "jurisdiction": "$bad", "companyBaseCurrency": "GBP", "fiscalYearStartMonth": 1}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    // ---- the Company's currency comes from its jurisdiction (docs/GL_Cash_And_Bank_Books_SRS.md, D3, FR-CB07) ----

    private suspend fun io.ktor.client.HttpClient.addCompanyIn(fixture: Fixture, jurisdiction: String, currencyJson: String) =
        post("/api/tenants/${fixture.existingTenantId.value}/companies") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(EXISTING_ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.existingTenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"companyName": "Currency Co", "clientType": "COMPANY_LIMITED", "jurisdiction": "$jurisdiction", $currencyJson"fiscalYearStartMonth": 1}""")
        }

    @Test
    fun `given each jurisdiction and no currency in the request, when add-company is posted, then the Company gets the jurisdiction's currency`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val expected = mapOf("UK" to "GBP", "IE" to "EUR", "NG" to "NGN", "SL" to "SLE", "LR" to "SLE", "GN" to "SLE", "CI" to "SLE")

        for ((code, currency) in expected) {
            val response = client.addCompanyIn(fixture, code, "")
            response.status shouldBe HttpStatusCode.Created
            val companyId = response.body<AddCompanyToTenantResponseDto>().companyId
            fixture.companyRepository.findById(CompanyId(java.util.UUID.fromString(companyId)))!!.baseCurrency.currencyCode shouldBe currency
        }
    }

    @Test
    fun `given a currency that matches the jurisdiction's, when add-company is posted, then it is accepted`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.addCompanyIn(fixture, "UK", """"companyBaseCurrency": "GBP", """).status shouldBe HttpStatusCode.Created
    }

    @Test
    fun `given a currency that is not the jurisdiction's, when add-company is posted, then it is 409 currency_not_supported_for_jurisdiction and nothing is created`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.addCompanyIn(fixture, "UK", """"companyBaseCurrency": "USD", """)

        response.status shouldBe HttpStatusCode.Conflict
        response.body<ErrorResponseDto>().error shouldBe "currency_not_supported_for_jurisdiction"
    }

    @Test
    fun `given a jurisdiction with no currency on record and none in the request, when add-company is posted, then it is 400`() = testApplication {
        val fixture = Fixture()
        fixture.jurisdictionRepository.save(JurisdictionEntry(Jurisdiction("ZA"), "South Africa", enabled = true))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.addCompanyIn(fixture, "ZA", "").status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given the jurisdictions list, then each entry carries its currency`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val list: ListJurisdictionsResponseDto = client.get("/api/jurisdictions") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(EXISTING_ADMIN_EMAIL)}")
        }.body()

        list.jurisdictions.associate { it.code to it.currency } shouldBe
            mapOf("UK" to "GBP", "IE" to "EUR", "NG" to "NGN", "SL" to "SLE", "LR" to "SLE", "GN" to "SLE", "CI" to "SLE")
    }
}
