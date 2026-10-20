package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeSupplierRepository
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.fish.application.FakeCustomerRepository
import com.theprodeogroup.fish.application.FakeJournalEntryRepository
import com.theprodeogroup.fish.application.FakeLeaveAccrualRepository
import com.theprodeogroup.fish.application.FakeMembershipRepository
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakeSalesInvoiceRecordRepository
import com.theprodeogroup.fish.application.FakeEaMembershipGateway
import com.theprodeogroup.fish.application.FakeUserRepository
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.application.FakeTaxRuleRepository
import com.theprodeogroup.fish.application.FakeTaxComputationRepository
import com.theprodeogroup.fish.application.CreateAccountUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.RecordOpeningBalanceUseCase
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.application.FakeVatRateRepository
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
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
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
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.application.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.application.User
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

    private class Fixture(role: Role = Role.ACCOUNTANT, jurisdiction: Jurisdiction = Jurisdiction.UK, accessLevel: AccessLevel = Membership.defaultAccessLevelFor(role)) {
        val userRepository = FakeUserRepository()
        val membershipRepository = FakeMembershipRepository()
        val companyRepository = FakeCompanyRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
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
        val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(companyRepository, accountRepository, periodRepository, journalEntryRepository)
        val taxRuleRepository = FakeTaxRuleRepository()
        val taxComputationRepository = FakeTaxComputationRepository()
        val computeTaxUseCase = ComputeTaxUseCase(periodRepository, accountRepository, journalEntryRepository, taxComputationRepository)
        val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
        val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

        val tenantId = TenantId.generate()
        val user = User.create(TEST_EMAIL, "Test SOP Caller").also { userRepository.save(it) }
        val company = Company.create(tenantId, "Test Co", ClientType.NON_PROFIT, jurisdiction, GBP).also { companyRepository.save(it) }
        val membership = Membership.grant(user.id, tenantId, role, company.id, accessLevel = accessLevel).also { membershipRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            periodRepository.save(it)
        }
        val arControlAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable").also { accountRepository.save(it) }
        val revenueAccount = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales Revenue").also { accountRepository.save(it) }
        val cashAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }
        val vatControlAccount = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2150", "VAT Control Account").also { accountRepository.save(it) }


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
                vatRateRepository = FakeVatRateRepository(),
                addCompanyToTenantUseCase = addCompanyToTenantUseCase,
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
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "45000.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: RecordSaleResponseDto = response.body()
        body.status shouldBe "POSTED"
        body.grossAmount shouldBe "45000.00"
        body.vatAmount shouldBe "0.00"
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
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "45000.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
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
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "45000.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
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
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "45000.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `given a caller with a READ_ONLY Membership, when record-sale is posted, then it returns 403`() = testApplication {
        val fixture = Fixture(accessLevel = AccessLevel.READ)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "45000.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
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
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "0.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    // -- Jurisdiction-based VAT schedule routing (2026-09-21) --

    @Test
    fun `given a company whose jurisdiction has no configured VAT schedule, when record-sale is posted, then it returns 409`() = testApplication {
        val fixture = Fixture(jurisdiction = Jurisdiction.NG)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "45000.00", "vatCategory": "STANDARD"}], "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Conflict
        val body: ErrorResponseDto = response.body()
        body.error shouldBe "no_vat_rate_schedule"
    }

    @Test
    fun `given a STANDARD-rated line for the UK company, when record-sale is posted, then the VAT line reflects the UK 20 percent rate`() = testApplication {
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
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "1000.00", "vatCategory": "STANDARD"}], "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val entry = fixture.journalEntryRepository.saveCalls.last().let { fixture.journalEntryRepository.findById(it) }
        val vatLine = entry?.lines?.single { it.accountId == fixture.vatControlAccount.id }
        vatLine?.amount?.amount shouldBe java.math.BigDecimal("200.00")
    }

    @Test
    fun `given a SECOND_REDUCED line for the UK company, when record-sale is posted, then it returns 400`() = testApplication {
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
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "100.00", "vatCategory": "SECOND_REDUCED"}], "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
        val body: ErrorResponseDto = response.body()
        body.error shouldBe "vat_category_not_supported"
    }

    @Test
    fun `given a valid record-sales-return request, when posted, then it returns 200 Posted`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-sales-return") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "salesReturnsAccountId": "${fixture.revenueAccount.id.value}",
                    |"arControlAccountId": "${fixture.arControlAccount.id.value}", "amount": "1200.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: RecordSalesReturnResponseDto = response.body()
        body.status shouldBe "POSTED"
    }

    @Test
    fun `given a Sales Returns Account that does not exist, when record-sales-return is posted, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/sales/record-sales-return") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "salesReturnsAccountId": "${UUID.randomUUID()}",
                    |"arControlAccountId": "${fixture.arControlAccount.id.value}", "amount": "1200.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.NotFound
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
            |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
            |"lines": [{"netAmount": "45000.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
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
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "45000.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
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
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "99999.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
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
            |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
            |"lines": [{"netAmount": "45000.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
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

    @Test
    fun `given a standard-rated sale, when record-sale is posted, then grossAmount is net plus VAT and vatAmount is the VAT component - what a collection must clear`() = testApplication {
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
                    |"revenueAccountId": "${fixture.revenueAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "100.00", "vatCategory": "STANDARD"}], "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: RecordSaleResponseDto = response.body()
        body.grossAmount shouldBe "120.00"
        body.vatAmount shouldBe "20.00"
    }

    // -- a refusal does not spend the key (only successful responses are remembered) --

    private fun saleBody(fixture: Fixture, revenueAccountId: UUID, net: String = "45000.00") =
        """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
            |"date": "$TODAY", "arControlAccountId": "${fixture.arControlAccount.id.value}",
            |"revenueAccountId": "$revenueAccountId", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
            |"lines": [{"netAmount": "$net", "vatCategory": "EXEMPT"}], "currency": "GBP",
            |"customerId": "00000000-0000-0000-0000-000000000042"}""".trimMargin()

    private suspend fun io.ktor.client.HttpClient.recordSale(fixture: Fixture, key: String, body: String) =
        post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `given a request that was refused, when the cause is fixed and the same key and body are sent again, then it posts - the refusal is not replayed`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val key = UUID.randomUUID().toString()
        val missingRevenueId = UUID.randomUUID()
        val body = saleBody(fixture, missingRevenueId)

        val refused = client.recordSale(fixture, key, body)
        refused.status shouldBe HttpStatusCode.NotFound
        fixture.journalEntryRepository.saveCalls.size shouldBe 0

        // The cause is fixed: the revenue account now exists, with exactly the id the request named.
        fixture.accountRepository.save(
            Account.create(fixture.company.id, AccountType.REVENUE, null, "4999", "Late revenue", id = AccountId(missingRevenueId))
        )
        val retried = client.recordSale(fixture, key, body)

        retried.status shouldBe HttpStatusCode.OK
        fixture.journalEntryRepository.saveCalls.size shouldBe 1
    }

    @Test
    fun `given a request that was refused, when the same key is used with a different body, then it is not key reuse - the refusal left nothing behind`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val key = UUID.randomUUID().toString()

        client.recordSale(fixture, key, saleBody(fixture, UUID.randomUUID())).status shouldBe HttpStatusCode.NotFound
        val corrected = client.recordSale(fixture, key, saleBody(fixture, fixture.revenueAccount.id.value))

        corrected.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `given a request that succeeded, when the same key is sent again with the same body and again with a different body, then the first is a replay and the second is 422`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val key = UUID.randomUUID().toString()
        val body = saleBody(fixture, fixture.revenueAccount.id.value)

        val first: RecordSaleResponseDto = client.recordSale(fixture, key, body).body()
        val replay: RecordSaleResponseDto = client.recordSale(fixture, key, body).body()
        val changed = client.recordSale(fixture, key, saleBody(fixture, fixture.revenueAccount.id.value, net = "1.00"))

        replay.journalEntryId shouldBe first.journalEntryId
        changed.status shouldBe HttpStatusCode.UnprocessableEntity
        fixture.journalEntryRepository.saveCalls.size shouldBe 1
    }

    // --- per-line revenue accounts (Femi 2026-10-18: the account a transaction posts to is chosen by its type; none set means Suspense) ---

    private fun lineAccountBody(fixture: Fixture, lines: String, legacyAccountId: UUID? = null) =
        """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY",
            |"arControlAccountId": "${fixture.arControlAccount.id.value}",
            |${if (legacyAccountId != null) "\"revenueAccountId\": \"$legacyAccountId\"," else ""}
            |"vatControlAccountId": "${fixture.vatControlAccount.id.value}",
            |"lines": $lines, "currency": "GBP", "customerId": "${UUID.randomUUID()}"}""".trimMargin()

    private suspend fun io.ktor.client.HttpClient.postSale(fixture: Fixture, body: String) =
        post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun revenueAccount(fixture: Fixture, code: String, name: String) =
        Account.create(fixture.company.id, AccountType.REVENUE, null, code, name).also { fixture.accountRepository.save(it) }

    @Test
    fun `given goods and fees lines each with its own revenue account and no request-level account, when record-sale is posted, then it credits the two accounts and tags the output VAT`() = testApplication {
        val fixture = Fixture()
        val goods = revenueAccount(fixture, "4010", "Sales of goods")
        val fees = revenueAccount(fixture, "4020", "Fee income")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postSale(
            fixture,
            lineAccountBody(
                fixture,
                """[{"netAmount": "1000.00", "vatCategory": "STANDARD", "accountId": "${goods.id.value}"},
                   |{"netAmount": "200.00", "vatCategory": "ZERO_RATED", "accountId": "${fees.id.value}"}]""".trimMargin()
            )
        )

        response.status shouldBe HttpStatusCode.OK
        val posted = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single()
        posted.lines.filter { it.accountId == goods.id || it.accountId == fees.id }.map { it.amount.amount.toPlainString() } shouldBe listOf("1000.00", "200.00")
        posted.lines.single { it.accountId == fixture.arControlAccount.id }.amount.amount.toPlainString() shouldBe "1400.00"
        posted.lines.filter { it.accountId == fixture.vatControlAccount.id }
            .all { it.dimensions[com.theprodeogroup.fish.domain.common.DimensionType.VAT_DIRECTION] == "OUTPUT" } shouldBe true
    }

    @Test
    fun `given no account anywhere, when record-sale is posted, then the net credits Suspense, or 409 suspense_account_not_configured without one`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val lines = """[{"netAmount": "1000.00", "vatCategory": "EXEMPT"}]"""

        val refused = client.postSale(fixture, lineAccountBody(fixture, lines))
        refused.status shouldBe HttpStatusCode.Conflict
        refused.body<ErrorResponseDto>().error shouldBe "suspense_account_not_configured"
        fixture.journalEntryRepository.saveCalls.size shouldBe 0

        val suspense = Account.create(fixture.company.id, AccountType.EQUITY, null, "3910", "Suspense Account").also { fixture.accountRepository.save(it) }
        client.postSale(fixture, lineAccountBody(fixture, lines)).status shouldBe HttpStatusCode.OK
        fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single().lines.any { it.accountId == suspense.id } shouldBe true
    }

    @Test
    fun `given a line account of another Company or an expense account, when record-sale is posted, then it is 404 or 400 with the line codes`() = testApplication {
        val fixture = Fixture()
        val foreign = Account.create(com.theprodeogroup.fish.domain.tenancy.CompanyId.generate(), AccountType.REVENUE, null, "4010", "Sales")
            .also { fixture.accountRepository.save(it) }
        val expense = Account.create(fixture.company.id, AccountType.EXPENSE, null, "5000", "Operating Expenses").also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        fun lines(accountId: UUID) = """[{"netAmount": "100.00", "vatCategory": "EXEMPT", "accountId": "$accountId"}]"""

        val notFound = client.postSale(fixture, lineAccountBody(fixture, lines(foreign.id.value)))
        notFound.status shouldBe HttpStatusCode.NotFound
        notFound.body<ErrorResponseDto>().error shouldBe "line_account_not_found"

        val wrongType = client.postSale(fixture, lineAccountBody(fixture, lines(expense.id.value)))
        wrongType.status shouldBe HttpStatusCode.BadRequest
        wrongType.body<ErrorResponseDto>().detail shouldBe "${expense.id.value}: wrong_type"
        fixture.journalEntryRepository.saveCalls.size shouldBe 0
    }

    @Test
    fun `given a receipt reference on record-collection, when posted, then the entry carries it in its description`() = testApplication {
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
                    |"arControlAccountId": "${fixture.arControlAccount.id.value}", "amount": "50.00", "currency": "GBP",
                    |"customerId": "${UUID.randomUUID()}", "description": "School fee", "reference": "RCT-000123"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single().description shouldBe "School fee - receipt RCT-000123"
    }

    @Test
    fun `given a blank or over-long receipt reference, when record-collection is posted, then it returns 400 and posts nothing`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        for (reference in listOf("   ", "R".repeat(65))) {
            val response = client.post("/api/sales/record-collection") {
                header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
                header("X-Tenant-Id", fixture.tenantId.value.toString())
                contentType(ContentType.Application.Json)
                setBody(
                    """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                        |"date": "$TODAY", "settlementAccountId": "${fixture.cashAccount.id.value}",
                        |"arControlAccountId": "${fixture.arControlAccount.id.value}", "amount": "50.00", "currency": "GBP",
                        |"customerId": "${UUID.randomUUID()}", "reference": "$reference"}""".trimMargin()
                )
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
        fixture.journalEntryRepository.saveCalls.size shouldBe 0
    }
}
