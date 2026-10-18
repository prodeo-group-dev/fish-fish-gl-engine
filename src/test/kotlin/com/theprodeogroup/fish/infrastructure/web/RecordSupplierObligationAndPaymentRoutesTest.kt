package com.theprodeogroup.fish.infrastructure.web

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
import com.theprodeogroup.fish.application.RecordCollectionUseCase
import com.theprodeogroup.fish.application.RecordSalesReturnUseCase
import com.theprodeogroup.fish.application.RecordInventoryIssueUseCase
import com.theprodeogroup.fish.application.RecordInventoryReceiptUseCase
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
private const val TEST_EMAIL = "pop-caller@example.com"

/**
 * `POST /purchasing/record-obligation` and `POST /purchasing/record-payment`
 * via Ktor's `testApplication` (docs/Purchase_Order_Processing_DDD_Design.md
 * Section 0) - the Purchasing mirror of [RecordSaleAndCollectionRoutesTest].
 * No owning aggregate to derive tenant scoping from, so every request body
 * carries `companyId` directly.
 */
class RecordSupplierObligationAndPaymentRoutesTest {

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
        val user = User.create(TEST_EMAIL, "Test POP Caller").also { userRepository.save(it) }
        val company = Company.create(tenantId, "Test Co", ClientType.NON_PROFIT, jurisdiction, GBP).also { companyRepository.save(it) }
        val membership = Membership.grant(user.id, tenantId, role, company.id, accessLevel = accessLevel).also { membershipRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            periodRepository.save(it)
        }
        val apControlAccount = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2100", "Accounts Payable").also { accountRepository.save(it) }
        val inventoryAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Inventory").also { accountRepository.save(it) }
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
    fun `given a valid record-obligation request with a bearer token and matching X-Tenant-Id, when posted, then it returns 200 Posted`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "12500.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: RecordSupplierObligationResponseDto = response.body()
        body.status shouldBe "POSTED"
    }

    @Test
    fun `given no bearer token, when record-obligation is posted, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "12500.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a claimed X-Tenant-Id that does not own the companyId, when record-obligation is posted, then it returns 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", TenantId.generate().value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "12500.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given a nonexistent companyId, when record-obligation is posted, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${UUID.randomUUID()}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "12500.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `given a caller with a READ_ONLY Membership, when record-obligation is posted, then it returns 403`() = testApplication {
        val fixture = Fixture(accessLevel = AccessLevel.READ)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "12500.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given a non-positive amount, when record-obligation is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "0.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    // -- Jurisdiction-based VAT schedule routing (2026-09-21) --

    @Test
    fun `given a company whose jurisdiction has no configured VAT schedule, when record-obligation is posted, then it returns 409`() = testApplication {
        val fixture = Fixture(jurisdiction = Jurisdiction.NG)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "12500.00", "vatCategory": "STANDARD"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Conflict
        val body: ErrorResponseDto = response.body()
        body.error shouldBe "no_vat_rate_schedule"
    }

    @Test
    fun `given a STANDARD-rated line for the UK company, when record-obligation is posted, then the VAT line reflects the UK 20 percent rate`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "1000.00", "vatCategory": "STANDARD"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val entry = fixture.journalEntryRepository.saveCalls.last().let { fixture.journalEntryRepository.findById(it) }
        val vatLine = entry?.lines?.single { it.accountId == fixture.vatControlAccount.id }
        vatLine?.amount?.amount shouldBe java.math.BigDecimal("200.00")
    }

    @Test
    fun `given a SECOND_REDUCED line for the UK company, when record-obligation is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "100.00", "vatCategory": "SECOND_REDUCED"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
        val body: ErrorResponseDto = response.body()
        body.error shouldBe "vat_category_not_supported"
    }

    @Test
    fun `given a valid record-payment request, when posted, then it returns 200 Posted`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-payment") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "apControlAccountId": "${fixture.apControlAccount.id.value}",
                    |"settlementAccountId": "${fixture.cashAccount.id.value}", "amount": "12500.00", "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: RecordSupplierPaymentResponseDto = response.body()
        body.status shouldBe "POSTED"
    }

    @Test
    fun `given a settlement Account that does not exist, when record-payment is posted, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-payment") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "apControlAccountId": "${fixture.apControlAccount.id.value}",
                    |"settlementAccountId": "${UUID.randomUUID()}", "amount": "12500.00", "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `given a journalSource of IMPORT, when record-obligation is posted, then the posted entry carries it instead of the INTEGRATION default`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "12500.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}", "journalSource": "IMPORT"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val posted = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single()
        posted.source shouldBe com.theprodeogroup.fish.domain.common.JournalSource.IMPORT
    }

    @Test
    fun `given an invalid journalSource, when record-obligation is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "expenseOrAssetAccountId": "${fixture.inventoryAccount.id.value}",
                    |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
                    |"lines": [{"netAmount": "12500.00", "vatCategory": "EXEMPT"}], "currency": "GBP",
                    |"supplierId": "${UUID.randomUUID()}", "journalSource": "NOT_A_REAL_SOURCE"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }
    // ---- POST /purchasing/record-credit-note (2026-10-16): a supplier credit note for a discount or a rebate ----

    private fun creditNoteBody(
        fixture: Fixture,
        discountsAccountId: UUID,
        kind: String = "DISCOUNT",
        lines: String = """[{"netAmount": "100.00", "vatCategory": "STANDARD"}]""",
        apControlAccountId: UUID = fixture.apControlAccount.id.value
    ) = """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY",
        |"supplierId": "${UUID.randomUUID()}", "kind": "$kind", "apControlAccountId": "$apControlAccountId",
        |"discountsReceivedAccountId": "$discountsAccountId", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
        |"lines": $lines, "currency": "GBP"}""".trimMargin()

    private fun discountsAccount(fixture: Fixture) =
        Account.create(fixture.company.id, AccountType.REVENUE, null, "4920", "Discounts Received").also { fixture.accountRepository.save(it) }

    private suspend fun io.ktor.client.HttpClient.postCreditNote(fixture: Fixture, body: String, key: String? = null) =
        post("/api/purchasing/record-credit-note") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            if (key != null) header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `given a valid record-credit-note request on a UK Company, when posted, then it returns 200 with what GL computed and posts three lines`() = testApplication {
        val fixture = Fixture()
        val discounts = discountsAccount(fixture)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postCreditNote(fixture, creditNoteBody(fixture, discounts.id.value))

        response.status shouldBe HttpStatusCode.OK
        val body: RecordSupplierCreditNoteResponseDto = response.body()
        body.status shouldBe "POSTED"
        body.creditNoteTotal shouldBe MoneyAmountDto("120.00", "GBP")
        body.vat shouldBe MoneyAmountDto("20.00", "GBP")
        val entry = fixture.journalEntryRepository.findById(com.theprodeogroup.fish.domain.ledger.JournalEntryId(UUID.fromString(body.journalEntryId)))!!
        entry.lines.size shouldBe 3
        entry.description shouldBe "DISCOUNT"
    }

    @Test
    fun `given a Company whose jurisdiction has no verified VAT rates, when record-credit-note is posted, then it returns 409 no_vat_rate_schedule and posts nothing`() = testApplication {
        val fixture = Fixture(jurisdiction = Jurisdiction.SL)
        val discounts = discountsAccount(fixture)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postCreditNote(fixture, creditNoteBody(fixture, discounts.id.value))

        response.status shouldBe HttpStatusCode.Conflict
        response.body<ErrorResponseDto>().error shouldBe "no_vat_rate_schedule"
        fixture.journalEntryRepository.saveCalls.size shouldBe 0
    }

    @Test
    fun `given an unknown kind or no lines, when record-credit-note is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        val discounts = discountsAccount(fixture)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.postCreditNote(fixture, creditNoteBody(fixture, discounts.id.value, kind = "RETURN")).status shouldBe HttpStatusCode.BadRequest
        client.postCreditNote(fixture, creditNoteBody(fixture, discounts.id.value, lines = "[]")).status shouldBe HttpStatusCode.BadRequest
        client.postCreditNote(fixture, creditNoteBody(fixture, discounts.id.value, lines = """[{"netAmount": "0.00", "vatCategory": "EXEMPT"}]""")).status shouldBe HttpStatusCode.BadRequest
        client.postCreditNote(fixture, creditNoteBody(fixture, discounts.id.value, lines = """[{"netAmount": "5.00", "vatCategory": "NOPE"}]""")).status shouldBe HttpStatusCode.BadRequest
        fixture.journalEntryRepository.saveCalls.size shouldBe 0
    }

    @Test
    fun `given a READ-only caller or a claimed Tenant that does not own the Company, when record-credit-note is posted, then it returns 403`() = testApplication {
        val readOnly = Fixture(accessLevel = AccessLevel.READ)
        val discountsA = discountsAccount(readOnly)
        application { readOnly.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.postCreditNote(readOnly, creditNoteBody(readOnly, discountsA.id.value)).status shouldBe HttpStatusCode.Forbidden
        val wrongTenant = client.post("/api/purchasing/record-credit-note") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", TenantId.generate().value.toString())
            contentType(ContentType.Application.Json)
            setBody(creditNoteBody(readOnly, discountsA.id.value))
        }
        wrongTenant.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given the same Idempotency-Key and body twice, when record-credit-note is posted, then it posts once and replays the answer`() = testApplication {
        val fixture = Fixture()
        val discounts = discountsAccount(fixture)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val body = creditNoteBody(fixture, discounts.id.value)

        val first: RecordSupplierCreditNoteResponseDto = client.postCreditNote(fixture, body, key = "po-credit:1").body()
        val second: RecordSupplierCreditNoteResponseDto = client.postCreditNote(fixture, body, key = "po-credit:1").body()

        second.journalEntryId shouldBe first.journalEntryId
        fixture.journalEntryRepository.saveCalls.size shouldBe 1
    }

    @Test
    fun `given a refused request then the same key and body once the cause is fixed, when record-credit-note is posted, then the refusal did not spend the key`() = testApplication {
        val fixture = Fixture()
        val discounts = discountsAccount(fixture)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val missingAp = UUID.randomUUID()

        val refused = client.postCreditNote(fixture, creditNoteBody(fixture, discounts.id.value, apControlAccountId = missingAp), key = "po-credit:2")
        refused.status shouldBe HttpStatusCode.NotFound
        fixture.journalEntryRepository.saveCalls.size shouldBe 0

        // The cause is fixed (an account with that id now exists), same key, same body.
        fixture.accountRepository.save(
            Account.create(fixture.company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2000", "Accounts Payable", id = com.theprodeogroup.fish.domain.ledger.AccountId(missingAp))
        )
        val retried = client.postCreditNote(fixture, creditNoteBody(fixture, discounts.id.value, apControlAccountId = missingAp), key = "po-credit:2")
        retried.status shouldBe HttpStatusCode.OK
        fixture.journalEntryRepository.saveCalls.size shouldBe 1
    }

    // --- per-line accounts (Femi 2026-10-18: the account a transaction posts to is chosen by its type; none set means Suspense) ---

    private fun lineAccountBody(fixture: Fixture, lines: String, legacyAccountId: UUID? = null) =
        """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY",
            |${if (legacyAccountId != null) "\"expenseOrAssetAccountId\": \"$legacyAccountId\"," else ""}
            |"apControlAccountId": "${fixture.apControlAccount.id.value}", "vatControlAccountId": "${fixture.vatControlAccount.id.value}",
            |"lines": $lines, "currency": "GBP", "supplierId": "${UUID.randomUUID()}"}""".trimMargin()

    private suspend fun io.ktor.client.HttpClient.postObligation(fixture: Fixture, body: String) =
        post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun expenseAccount(fixture: Fixture, code: String, name: String) =
        Account.create(fixture.company.id, AccountType.EXPENSE, null, code, name).also { fixture.accountRepository.save(it) }

    @Test
    fun `given rent and electricity lines each with its own account and no request-level account, when record-obligation is posted, then it posts to the two accounts and tags the input VAT`() = testApplication {
        val fixture = Fixture()
        val rent = expenseAccount(fixture, "5100", "Rent")
        val electricity = expenseAccount(fixture, "5110", "Electricity")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postObligation(
            fixture,
            lineAccountBody(
                fixture,
                """[{"netAmount": "1000.00", "vatCategory": "STANDARD", "accountId": "${rent.id.value}"},
                   |{"netAmount": "400.00", "vatCategory": "REDUCED", "accountId": "${electricity.id.value}"}]""".trimMargin()
            )
        )

        response.status shouldBe HttpStatusCode.OK
        val posted = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single()
        posted.lines.filter { it.accountId == rent.id || it.accountId == electricity.id }.map { it.amount.amount.toPlainString() } shouldBe listOf("1000.00", "400.00")
        posted.lines.filter { it.accountId == fixture.vatControlAccount.id }
            .all { it.dimensions[com.theprodeogroup.fish.domain.common.DimensionType.VAT_DIRECTION] == "INPUT" } shouldBe true
        posted.lines.single { it.accountId == fixture.apControlAccount.id }.amount.amount.toPlainString() shouldBe "1620.00"
    }

    @Test
    fun `given no account anywhere and the Company has a Suspense account, when record-obligation is posted, then the net goes to Suspense`() = testApplication {
        val fixture = Fixture()
        val suspense = Account.create(fixture.company.id, AccountType.EQUITY, null, "3910", "Suspense Account").also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.postObligation(fixture, lineAccountBody(fixture, """[{"netAmount": "1000.00", "vatCategory": "EXEMPT"}]""")).status shouldBe HttpStatusCode.OK

        fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single().lines.any { it.accountId == suspense.id } shouldBe true
    }

    @Test
    fun `given no account anywhere and no Suspense account, when record-obligation is posted, then it returns 409 suspense_account_not_configured and posts nothing`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postObligation(fixture, lineAccountBody(fixture, """[{"netAmount": "1000.00", "vatCategory": "EXEMPT"}]"""))

        response.status shouldBe HttpStatusCode.Conflict
        response.body<ErrorResponseDto>().error shouldBe "suspense_account_not_configured"
        fixture.journalEntryRepository.saveCalls.size shouldBe 0
    }

    @Test
    fun `given a line account of another Company, a revenue account, or a control account, when record-obligation is posted, then it is 404 or 400 with the line codes`() = testApplication {
        val fixture = Fixture()
        val foreign = Account.create(com.theprodeogroup.fish.domain.tenancy.CompanyId.generate(), AccountType.EXPENSE, null, "5100", "Rent")
            .also { fixture.accountRepository.save(it) }
        val revenue = Account.create(fixture.company.id, AccountType.REVENUE, null, "4000", "Sales").also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        fun lines(accountId: UUID) = """[{"netAmount": "100.00", "vatCategory": "EXEMPT", "accountId": "$accountId"}]"""

        val notFound = client.postObligation(fixture, lineAccountBody(fixture, lines(foreign.id.value)))
        notFound.status shouldBe HttpStatusCode.NotFound
        notFound.body<ErrorResponseDto>().error shouldBe "line_account_not_found"

        val wrongType = client.postObligation(fixture, lineAccountBody(fixture, lines(revenue.id.value)))
        wrongType.status shouldBe HttpStatusCode.BadRequest
        wrongType.body<ErrorResponseDto>().let {
            it.error shouldBe "line_account_not_allowed"
            it.detail shouldBe "${revenue.id.value}: wrong_type"
        }

        val control = client.postObligation(fixture, lineAccountBody(fixture, lines(fixture.inventoryAccount.id.value)))
        control.status shouldBe HttpStatusCode.BadRequest
        control.body<ErrorResponseDto>().detail shouldBe "${fixture.inventoryAccount.id.value}: control_or_module_owned"

        fixture.journalEntryRepository.saveCalls.size shouldBe 0
    }

    @Test
    fun `given a malformed line accountId, when record-obligation is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.postObligation(fixture, lineAccountBody(fixture, """[{"netAmount": "100.00", "vatCategory": "EXEMPT", "accountId": "not-a-uuid"}]"""))
            .status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a purchase with VAT, when record-obligation is posted, then the answer carries the gross credited to AP as total and the VAT in it`() = testApplication {
        val fixture = Fixture()
        val rent = expenseAccount(fixture, "5100", "Rent")
        val suspense = Account.create(fixture.company.id, AccountType.EQUITY, null, "3910", "Suspense Account").also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // One line on Rent, one with no account (falls back to Suspense): the total is the whole credit to AP either way.
        val response = client.postObligation(
            fixture,
            lineAccountBody(
                fixture,
                """[{"netAmount": "1000.00", "vatCategory": "STANDARD", "accountId": "${rent.id.value}"},
                   |{"netAmount": "400.00", "vatCategory": "REDUCED"}]""".trimMargin()
            )
        )

        response.status shouldBe HttpStatusCode.OK
        val body: RecordSupplierObligationResponseDto = response.body()
        body.total shouldBe MoneyAmountDto("1620.00", "GBP")
        body.vat shouldBe MoneyAmountDto("220.00", "GBP")
        fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single().lines.any { it.accountId == suspense.id } shouldBe true
    }
}
