package com.theprodeogroup.fish.infrastructure.web

import io.ktor.client.request.request
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import io.ktor.client.statement.bodyAsText
import io.kotest.assertions.withClue
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.AssessFixedAssetImpairmentUseCase
import com.theprodeogroup.fish.application.ComputeBalanceSheetUseCase
import com.theprodeogroup.fish.application.ComputeCashFlowUseCase
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeFixedAssetRegisterUseCase
import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.application.ComputeProfitAndLossUseCase
import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.application.CreateAccountUseCase
import com.theprodeogroup.fish.application.CreateFixedAssetUseCase
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.DisposeFixedAssetUseCase
import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeSupplierRepository
import com.theprodeogroup.fish.application.FakeCustomerRepository
import com.theprodeogroup.fish.application.FakeFixedAssetRepository
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.application.FakeJournalEntryRepository
import com.theprodeogroup.fish.application.FakeLeaveAccrualRepository
import com.theprodeogroup.fish.application.FakeMembershipRepository
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakeSalesInvoiceRecordRepository
import com.theprodeogroup.fish.application.FakeTaxComputationRepository
import com.theprodeogroup.fish.application.FakeTaxRuleRepository
import com.theprodeogroup.fish.application.FakeEaMembershipGateway
import com.theprodeogroup.fish.application.FakeUserRepository
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.RecordCollectionUseCase
import com.theprodeogroup.fish.application.RecordSalesReturnUseCase
import com.theprodeogroup.fish.application.RecordFixedAssetDepreciationUseCase
import com.theprodeogroup.fish.application.RecordInventoryIssueUseCase
import com.theprodeogroup.fish.application.RecordInventoryReceiptUseCase
import com.theprodeogroup.fish.application.RecordOpeningBalanceUseCase
import com.theprodeogroup.fish.application.RecordPayRunUseCase
import com.theprodeogroup.fish.application.RecordSaleUseCase
import com.theprodeogroup.fish.application.RecordSupplierObligationUseCase
import com.theprodeogroup.fish.application.RecordSupplierPaymentUseCase
import com.theprodeogroup.fish.application.RemeasureLeaveAccrualUseCase
import com.theprodeogroup.fish.application.UtilizeLeaveAccrualUseCase
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
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
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 9, 3)
private const val TEST_EMAIL = "fixed-asset-caller@example.com"

/**
 * `POST /fixed-assets`, `GET /companies/{companyId}/fixed-assets`,
 * `GET /companies/{companyId}/reports/fixed-asset-register`, and the
 * three `/fixed-assets/{id}/...` posting routes via Ktor's
 * `testApplication` - see FixedAssetRoutes.kt's own KDoc.
 */
class FixedAssetRoutesTest {

    private class Fixture(role: Role = Role.ACCOUNTANT) {
        val userRepository = FakeUserRepository()
        val membershipRepository = FakeMembershipRepository()
        val companyRepository = FakeCompanyRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
        val customerRepository = FakeCustomerRepository()
        val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
        val createAccountUseCase = CreateAccountUseCase(companyRepository, accountRepository)
        val recordOpeningBalanceUseCase = RecordOpeningBalanceUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val leaveAccrualRepository = FakeLeaveAccrualRepository()
        val remeasureLeaveAccrualUseCase = RemeasureLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
        val utilizeLeaveAccrualUseCase = UtilizeLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
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
        val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(companyRepository, accountRepository, periodRepository, journalEntryRepository)
        val taxRuleRepository = FakeTaxRuleRepository()
        val taxComputationRepository = FakeTaxComputationRepository()
        val computeTaxUseCase = ComputeTaxUseCase(periodRepository, accountRepository, journalEntryRepository, taxComputationRepository)
        val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
        val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

        val tenantId = TenantId.generate()
        val user = User.create(TEST_EMAIL, "Test Fixed Asset Caller").also { userRepository.save(it) }
        val company = Company.create(tenantId, "Test Co", ClientType.SOLE_TRADER, Jurisdiction.UK, GBP).also { companyRepository.save(it) }
        val membership = Membership.grant(user.id, tenantId, role, company.id).also { membershipRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            periodRepository.save(it)
        }
        val cashAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }
        val fixedAssetAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.NON_CURRENT, "1200", "Fixed Assets").also { accountRepository.save(it) }
        val accumulatedDepreciationAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.NON_CURRENT, "1210", "Accumulated Depreciation").also { accountRepository.save(it) }
        val depreciationExpenseAccount = Account.create(company.id, AccountType.EXPENSE, null, "6100", "Depreciation Expense").also { accountRepository.save(it) }
        val saleOfFixedAssetAccount = Account.create(company.id, AccountType.REVENUE, null, "4900", "Sale of Fixed Asset").also { accountRepository.save(it) }
        val suspenseAccount = Account.create(company.id, AccountType.EQUITY, null, "3910", "Suspense Account").also { accountRepository.save(it) }

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
                computeFixedAssetRegisterUseCase = computeFixedAssetRegisterUseCase
            )
        }
    }

    @Test
    fun `given a cash-funded request, when POST fixed-assets is called, then it posts to the Ledger and appears in the register report`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val createResponse = client.post("/api/fixed-assets") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "name": "Delivery Van", "category": "VEHICLES",
                    |"cost": "15000.00", "currency": "GBP", "acquisitionDate": "$TODAY", "usefulLifeYears": 5,
                    |"identifier": "REG-XY26 ABC", "periodId": "${fixture.period.id.value}",
                    |"fixedAssetAccountId": "${fixture.fixedAssetAccount.id.value}",
                    |"fundingMethod": "CASH", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()
            )
        }
        createResponse.status shouldBe HttpStatusCode.OK
        val posting: FixedAssetPostingResponseDto = createResponse.body()
        posting.journalEntryStatus shouldBe "POSTED"
        val created = posting.fixedAsset
        created.name shouldBe "Delivery Van"
        created.identifier shouldBe "REG-XY26 ABC"
        created.netBookValue shouldBe "15000.00"

        val registerResponse = client.get("/api/companies/${fixture.company.id.value}/reports/fixed-asset-register") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
        }
        registerResponse.status shouldBe HttpStatusCode.OK
        val register: FixedAssetRegisterResponseDto = registerResponse.body()
        register.lines.single().id shouldBe created.id
        register.totalCost shouldBe "15000.00"
        register.totalNetBookValue shouldBe "15000.00"
    }

    @Test
    fun `given an on-account request, when POST fixed-assets is called, then it credits the AP control account tagged with the supplier reference`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val apControlAccount = Account.create(fixture.company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2100", "Accounts Payable")
            .also { fixture.accountRepository.save(it) }

        val createResponse = client.post("/api/fixed-assets") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "name": "Warehouse Racking", "category": "EQUIPMENT",
                    |"cost": "8000.00", "currency": "GBP", "acquisitionDate": "$TODAY", "usefulLifeYears": 8,
                    |"periodId": "${fixture.period.id.value}", "fixedAssetAccountId": "${fixture.fixedAssetAccount.id.value}",
                    |"fundingMethod": "ON_ACCOUNT", "apControlAccountId": "${apControlAccount.id.value}",
                    |"supplierReference": "Acme Racking Ltd - Invoice 4471"}""".trimMargin()
            )
        }

        createResponse.status shouldBe HttpStatusCode.OK
        val posting: FixedAssetPostingResponseDto = createResponse.body()
        posting.journalEntryStatus shouldBe "POSTED"
        posting.fixedAsset.netBookValue shouldBe "8000.00"
    }

    @Test
    fun `given an already-owned request, when POST fixed-assets is called, then it credits the Suspense Account with no dimension tag`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val createResponse = client.post("/api/fixed-assets") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "name": "Car", "category": "VEHICLES",
                    |"cost": "5000.00", "currency": "GBP", "acquisitionDate": "$TODAY", "usefulLifeYears": 5,
                    |"identifier": "REG-CAR01", "periodId": "${fixture.period.id.value}",
                    |"fixedAssetAccountId": "${fixture.fixedAssetAccount.id.value}",
                    |"fundingMethod": "ALREADY_OWNED", "suspenseAccountId": "${fixture.suspenseAccount.id.value}"}""".trimMargin()
            )
        }

        createResponse.status shouldBe HttpStatusCode.OK
        val posting: FixedAssetPostingResponseDto = createResponse.body()
        posting.journalEntryStatus shouldBe "POSTED"
        posting.fixedAsset.netBookValue shouldBe "5000.00"
    }

    @Test
    fun `given a created FixedAsset, when record-depreciation is posted, then it reduces the net book value`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val createResponse = client.post("/api/fixed-assets") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "name": "Delivery Van", "category": "VEHICLES",
                    |"cost": "10000.00", "currency": "GBP", "acquisitionDate": "$TODAY", "usefulLifeYears": 5,
                    |"periodId": "${fixture.period.id.value}", "fixedAssetAccountId": "${fixture.fixedAssetAccount.id.value}",
                    |"fundingMethod": "CASH", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()
            )
        }
        val created: FixedAssetSummaryDto = createResponse.body<FixedAssetPostingResponseDto>().fixedAsset

        val depreciationResponse = client.post("/api/fixed-assets/${created.id}/record-depreciation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"depreciationExpenseAccountId": "${fixture.depreciationExpenseAccount.id.value}",
                    |"accumulatedDepreciationAccountId": "${fixture.accumulatedDepreciationAccount.id.value}",
                    |"periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        depreciationResponse.status shouldBe HttpStatusCode.OK
        val posting: FixedAssetPostingResponseDto = depreciationResponse.body()
        posting.journalEntryStatus shouldBe "POSTED"
        posting.fixedAsset.netBookValue shouldBe "8000.00"
    }

    @Test
    fun `given a created FixedAsset, when disposed, then it is removed from the register`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val createResponse = client.post("/api/fixed-assets") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "name": "Delivery Van", "category": "VEHICLES",
                    |"cost": "10000.00", "currency": "GBP", "acquisitionDate": "$TODAY", "usefulLifeYears": 5,
                    |"periodId": "${fixture.period.id.value}", "fixedAssetAccountId": "${fixture.fixedAssetAccount.id.value}",
                    |"fundingMethod": "CASH", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()
            )
        }
        val created: FixedAssetSummaryDto = createResponse.body<FixedAssetPostingResponseDto>().fixedAsset

        val disposeResponse = client.post("/api/fixed-assets/${created.id}/dispose") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"proceeds": "6000.00", "currency": "GBP", "cashAccountId": "${fixture.cashAccount.id.value}",
                    |"fixedAssetAccountId": "${fixture.fixedAssetAccount.id.value}",
                    |"accumulatedDepreciationAccountId": "${fixture.accumulatedDepreciationAccount.id.value}",
                    |"saleOfFixedAssetAccountId": "${fixture.saleOfFixedAssetAccount.id.value}",
                    |"periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        disposeResponse.status shouldBe HttpStatusCode.OK
        val posting: FixedAssetPostingResponseDto = disposeResponse.body()
        posting.fixedAsset.isDisposed shouldBe true

        val registerResponse = client.get("/api/companies/${fixture.company.id.value}/reports/fixed-asset-register") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
        }
        val register: FixedAssetRegisterResponseDto = registerResponse.body()
        register.lines.single().isDisposed shouldBe true
        register.totalCost shouldBe "0.00"
    }

    @Test
    fun `given a Company with no assets, when the register is read, then it is an empty 200 with these exact fields`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/fixed-asset-register") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe """{"currency":"GBP","lines":[],"totalCost":"0.00","totalAccumulatedDepreciation":"0.00","totalAccumulatedImpairmentLoss":"0.00","totalNetBookValue":"0.00","totalCarryingAmount":"0.00"}"""
    }

    // ---- GET open-period (the Period id the asset create needs) ----

    private fun openPeriodPath(companyId: String) = "/api/companies/$companyId/open-period"

    private suspend fun io.ktor.client.HttpClient.openPeriodStatus(path: String, email: String?, tenant: TenantId): HttpStatusCode =
        request(path) {
            if (email != null) header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(email)}")
            header("X-Tenant-Id", tenant.value.toString())
        }.status

    @Test
    fun `given an open Period, when open-period is read, then it is exactly periodId, startDate, endDate and status`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get(openPeriodPath(fixture.company.id.value.toString())) {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe """{"periodId":"${fixture.period.id.value}","startDate":"${fixture.period.startDate}","endDate":"${fixture.period.endDate}","status":"OPEN"}"""
    }

    @Test
    fun `given the only Period is closed, when open-period is read, then it is 409 no_open_period`() = testApplication {
        val fixture = Fixture()
        fixture.period.close()
        fixture.periodRepository.save(fixture.period)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get(openPeriodPath(fixture.company.id.value.toString())) {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
        }

        response.status shouldBe HttpStatusCode.Conflict
        response.body<ErrorResponseDto>().error shouldBe "no_open_period"
    }

    @Test
    fun `open-period needs only READ, refuses no token and no membership, and never shows another Company's Period`() = testApplication {
        val a = Fixture()
        val b = Fixture()
        val reader = User.create("fa-op-reader@example.com", "Reader").also { a.userRepository.save(it) }
        a.membershipRepository.save(Membership.grant(reader.id, a.tenantId, Role.ACCOUNTANT, a.company.id, accessLevel = AccessLevel.READ))
        val stranger = User.create("fa-op-stranger@example.com", "Stranger").also { a.userRepository.save(it) }
        // B's Company and Period live in A's repositories, with B's own member.
        a.companyRepository.save(b.company); a.periodRepository.save(b.period)
        application { a.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val aPath = openPeriodPath(a.company.id.value.toString())
        val bPath = openPeriodPath(b.company.id.value.toString())

        client.openPeriodStatus(aPath, "fa-op-reader@example.com", a.tenantId) shouldBe HttpStatusCode.OK
        client.openPeriodStatus(aPath, null, a.tenantId) shouldBe HttpStatusCode.Unauthorized
        val noMembership = client.openPeriodStatus(aPath, stranger.email, a.tenantId)
        (noMembership == HttpStatusCode.Forbidden || noMembership == HttpStatusCode.Unauthorized) shouldBe true
        // A's writer asking for B's Company, with either Tenant header: refused, and B's Period id is never in the answer.
        for (tenant in listOf(a.tenantId, b.tenantId)) {
            val response = client.request(bPath) {
                header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
                header("X-Tenant-Id", tenant.value.toString())
            }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText().contains(b.period.id.value.toString()) shouldBe false
        }
        // Unknown Company.
        client.openPeriodStatus(openPeriodPath(java.util.UUID.randomUUID().toString()), TEST_EMAIL, a.tenantId) shouldBe HttpStatusCode.NotFound
    }

    // ---- FS-14 (Fixed Assets Security Playbook): role and Company negative tests for the six Fixed Asset routes ----

    private fun createBody(fixture: Fixture, companyId: String = fixture.company.id.value.toString(), periodId: String = fixture.period.id.value.toString()) =
        """{"companyId": "$companyId", "name": "Delivery Van", "category": "VEHICLES",
            |"cost": "10000.00", "currency": "GBP", "acquisitionDate": "$TODAY", "usefulLifeYears": 5,
            |"periodId": "$periodId", "fixedAssetAccountId": "${fixture.fixedAssetAccount.id.value}",
            |"fundingMethod": "CASH", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()

    private fun depreciationBody(fixture: Fixture, periodId: String = fixture.period.id.value.toString()) =
        """{"depreciationExpenseAccountId": "${fixture.depreciationExpenseAccount.id.value}",
            |"accumulatedDepreciationAccountId": "${fixture.accumulatedDepreciationAccount.id.value}",
            |"periodId": "$periodId", "date": "$TODAY"}""".trimMargin()

    private fun impairmentBody(fixture: Fixture, periodId: String = fixture.period.id.value.toString()) =
        """{"recoverableAmount": "1000.00", "currency": "GBP",
            |"impairmentExpenseAccountId": "${fixture.depreciationExpenseAccount.id.value}",
            |"accumulatedImpairmentAccountId": "${fixture.accumulatedDepreciationAccount.id.value}",
            |"periodId": "$periodId", "date": "$TODAY"}""".trimMargin()

    private fun disposeBody(fixture: Fixture, periodId: String = fixture.period.id.value.toString()) =
        """{"proceeds": "6000.00", "currency": "GBP", "cashAccountId": "${fixture.cashAccount.id.value}",
            |"fixedAssetAccountId": "${fixture.fixedAssetAccount.id.value}",
            |"accumulatedDepreciationAccountId": "${fixture.accumulatedDepreciationAccount.id.value}",
            |"saleOfFixedAssetAccountId": "${fixture.saleOfFixedAssetAccount.id.value}",
            |"periodId": "$periodId", "date": "$TODAY"}""".trimMargin()

    private suspend fun io.ktor.client.HttpClient.call(
        method: io.ktor.http.HttpMethod, path: String, email: String?, tenant: TenantId, body: String? = null
    ): HttpStatusCode =
        request(path) {
            this.method = method
            if (email != null) header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(email)}")
            header("X-Tenant-Id", tenant.value.toString())
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }.status

    private suspend fun io.ktor.client.HttpClient.createAsset(fixture: Fixture, email: String = TEST_EMAIL): String {
        val response = post("/api/fixed-assets") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(email)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(createBody(fixture))
        }
        response.status shouldBe HttpStatusCode.OK
        return response.body<FixedAssetPostingResponseDto>().fixedAsset.id
    }

    /** The four mutation routes, and the two reads, as (name, method, path, body) for one asset in one Company. */
    private fun routes(fixture: Fixture, assetId: String, periodId: String = fixture.period.id.value.toString(), companyId: String = fixture.company.id.value.toString()) = listOf(
        listOf("create", io.ktor.http.HttpMethod.Post, "/api/fixed-assets", createBody(fixture, companyId, periodId)),
        listOf("depreciate", io.ktor.http.HttpMethod.Post, "/api/fixed-assets/$assetId/record-depreciation", depreciationBody(fixture, periodId)),
        listOf("impair", io.ktor.http.HttpMethod.Post, "/api/fixed-assets/$assetId/assess-impairment", impairmentBody(fixture, periodId)),
        listOf("dispose", io.ktor.http.HttpMethod.Post, "/api/fixed-assets/$assetId/dispose", disposeBody(fixture, periodId))
    )

    @Test
    fun `FS-14 a read-only member is refused on every Fixed Asset write, nothing is posted, and the reads still work`() = testApplication {
        val fixture = Fixture()
        val reader = User.create("fa-reader@example.com", "Reader").also { fixture.userRepository.save(it) }
        fixture.membershipRepository.save(Membership.grant(reader.id, fixture.tenantId, Role.ACCOUNTANT, fixture.company.id, accessLevel = AccessLevel.READ))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val assetId = client.createAsset(fixture)
        val entriesBefore = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).size
        val registerBefore = fixture.fixedAssetRepository.findAllByCompany(fixture.company.id).single()

        for (route in routes(fixture, assetId)) {
            withClue("a READ member on ${route[0]}") {
                client.call(route[1] as io.ktor.http.HttpMethod, route[2] as String, "fa-reader@example.com", fixture.tenantId, route[3] as String) shouldBe HttpStatusCode.Forbidden
            }
        }
        fixture.journalEntryRepository.findAllByCompany(fixture.company.id).size shouldBe entriesBefore
        val after = fixture.fixedAssetRepository.findAllByCompany(fixture.company.id).single()
        after.netBookValue shouldBe registerBefore.netBookValue
        after.isDisposed shouldBe false

        client.call(io.ktor.http.HttpMethod.Get, "/api/companies/${fixture.company.id.value}/fixed-assets", "fa-reader@example.com", fixture.tenantId) shouldBe HttpStatusCode.OK
        client.call(io.ktor.http.HttpMethod.Get, "/api/companies/${fixture.company.id.value}/reports/fixed-asset-register", "fa-reader@example.com", fixture.tenantId) shouldBe HttpStatusCode.OK
    }

    @Test
    fun `FS-14 a caller with no membership at the Company is refused on all six Fixed Asset routes, and with no token on all six`() = testApplication {
        val fixture = Fixture()
        val stranger = User.create("fa-stranger@example.com", "Stranger").also { fixture.userRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val assetId = client.createAsset(fixture)
        val entriesBefore = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).size
        val reads = listOf(
            "/api/companies/${fixture.company.id.value}/fixed-assets",
            "/api/companies/${fixture.company.id.value}/reports/fixed-asset-register"
        )

        for (route in routes(fixture, assetId)) {
            val status = client.call(route[1] as io.ktor.http.HttpMethod, route[2] as String, stranger.email, fixture.tenantId, route[3] as String)
            withClue("no membership on ${route[0]} was ${status.value}") { (status == HttpStatusCode.Forbidden || status == HttpStatusCode.Unauthorized) shouldBe true }
            withClue("no token on ${route[0]}") {
                client.call(route[1] as io.ktor.http.HttpMethod, route[2] as String, null, fixture.tenantId, route[3] as String) shouldBe HttpStatusCode.Unauthorized
            }
        }
        for (read in reads) {
            val status = client.call(io.ktor.http.HttpMethod.Get, read, stranger.email, fixture.tenantId)
            withClue("no membership on $read was ${status.value}") { (status == HttpStatusCode.Forbidden || status == HttpStatusCode.Unauthorized) shouldBe true }
            client.call(io.ktor.http.HttpMethod.Get, read, null, fixture.tenantId) shouldBe HttpStatusCode.Unauthorized
        }
        fixture.journalEntryRepository.findAllByCompany(fixture.company.id).size shouldBe entriesBefore
    }

    @Test
    fun `FS-14 a writer at one Company cannot reach another Company's assets, Period or books, and nothing is posted there`() = testApplication {
        val a = Fixture()
        val b = Fixture()
        // Two separate Companies, two Tenants, two writers; A's caller is TEST_EMAIL, B's has another email.
        val bWriter = User.create("fa-b-writer@example.com", "B writer").also { a.userRepository.save(it); b.userRepository.save(it) }
        // Put both worlds in one application: B's Company, Period, accounts and asset live in A's repositories.
        a.companyRepository.save(b.company); a.periodRepository.save(b.period)
        listOf(b.cashAccount, b.fixedAssetAccount, b.accumulatedDepreciationAccount, b.depreciationExpenseAccount, b.saleOfFixedAssetAccount, b.suspenseAccount)
            .forEach { a.accountRepository.save(it) }
        a.membershipRepository.save(Membership.grant(bWriter.id, b.tenantId, Role.ACCOUNTANT, b.company.id))
        application { a.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val assetA = client.createAsset(a)
        val assetB = client.run {
            val response = post("/api/fixed-assets") {
                header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken("fa-b-writer@example.com")}")
                header("X-Tenant-Id", b.tenantId.value.toString())
                contentType(ContentType.Application.Json)
                setBody(createBody(b))
            }
            response.status shouldBe HttpStatusCode.OK
            response.body<FixedAssetPostingResponseDto>().fixedAsset.id
        }
        val entriesB = a.journalEntryRepository.findAllByCompany(b.company.id).size
        val entriesA = a.journalEntryRepository.findAllByCompany(a.company.id).size
        val refused = setOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound)

        // A's writer, with A's tenant header, naming B's Company, B's Period or B's asset: refused every way round.
        withClue("create at B's Company") {
            client.call(io.ktor.http.HttpMethod.Post, "/api/fixed-assets", TEST_EMAIL, a.tenantId, createBody(a, b.company.id.value.toString(), b.period.id.value.toString())) shouldBe HttpStatusCode.Forbidden
        }
        withClue("create at A's Company with B's Period") {
            (client.call(io.ktor.http.HttpMethod.Post, "/api/fixed-assets", TEST_EMAIL, a.tenantId, createBody(a, a.company.id.value.toString(), b.period.id.value.toString())) in refused) shouldBe true
        }
        for ((name, path, body) in listOf(
            Triple("depreciate", "record-depreciation", ::depreciationBody),
            Triple("impair", "assess-impairment", ::impairmentBody),
            Triple("dispose", "dispose", ::disposeBody)
        )) {
            withClue("$name B's asset with B's Period, as A's writer, A's header") {
                client.call(io.ktor.http.HttpMethod.Post, "/api/fixed-assets/$assetB/$path", TEST_EMAIL, a.tenantId, body(a, b.period.id.value.toString())) shouldBe HttpStatusCode.Forbidden
            }
            withClue("$name B's asset with B's Period, as A's writer, B's header") {
                client.call(io.ktor.http.HttpMethod.Post, "/api/fixed-assets/$assetB/$path", TEST_EMAIL, b.tenantId, body(a, b.period.id.value.toString())) shouldBe HttpStatusCode.Forbidden
            }
            withClue("$name B's asset with A's Period (the F-FA1 shape: authorized at A, asset of B)") {
                client.call(io.ktor.http.HttpMethod.Post, "/api/fixed-assets/$assetB/$path", TEST_EMAIL, a.tenantId, body(a, a.period.id.value.toString())) shouldBe HttpStatusCode.NotFound
            }
            withClue("$name A's asset with B's Period") {
                (client.call(io.ktor.http.HttpMethod.Post, "/api/fixed-assets/$assetA/$path", TEST_EMAIL, a.tenantId, body(a, b.period.id.value.toString())) in refused) shouldBe true
            }
        }
        withClue("reads of B's Company as A's writer") {
            client.call(io.ktor.http.HttpMethod.Get, "/api/companies/${b.company.id.value}/fixed-assets", TEST_EMAIL, a.tenantId) shouldBe HttpStatusCode.Forbidden
            client.call(io.ktor.http.HttpMethod.Get, "/api/companies/${b.company.id.value}/fixed-assets", TEST_EMAIL, b.tenantId) shouldBe HttpStatusCode.Forbidden
            client.call(io.ktor.http.HttpMethod.Get, "/api/companies/${b.company.id.value}/reports/fixed-asset-register", TEST_EMAIL, b.tenantId) shouldBe HttpStatusCode.Forbidden
        }
        a.journalEntryRepository.findAllByCompany(b.company.id).size shouldBe entriesB
        a.journalEntryRepository.findAllByCompany(a.company.id).size shouldBe entriesA
        a.fixedAssetRepository.findById(com.theprodeogroup.fish.domain.fixedassets.FixedAssetId(java.util.UUID.fromString(assetB)))!!.isDisposed shouldBe false
    }

    @Test
    fun `FS-14 positive control - a write member does all four Fixed Asset writes on their own assets`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val sold = client.createAsset(fixture)
        val impaired = client.createAsset(fixture)

        client.call(io.ktor.http.HttpMethod.Post, "/api/fixed-assets/$sold/record-depreciation", TEST_EMAIL, fixture.tenantId, depreciationBody(fixture)) shouldBe HttpStatusCode.OK
        client.call(io.ktor.http.HttpMethod.Post, "/api/fixed-assets/$sold/dispose", TEST_EMAIL, fixture.tenantId, disposeBody(fixture)) shouldBe HttpStatusCode.OK
        val impair = client.post("/api/fixed-assets/$impaired/assess-impairment") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(impairmentBody(fixture))
        }
        withClue("impairment answered ${impair.status.value}: ${impair.bodyAsText()}") { impair.status shouldBe HttpStatusCode.OK }
    }
}
