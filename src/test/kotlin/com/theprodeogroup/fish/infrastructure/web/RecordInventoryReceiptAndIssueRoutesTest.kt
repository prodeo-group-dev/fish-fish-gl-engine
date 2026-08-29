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
import com.theprodeogroup.fish.application.FakePayRunRepository
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakePurchaseOrderRepository
import com.theprodeogroup.fish.application.FakeSalesInvoiceRecordRepository
import com.theprodeogroup.fish.application.FakeSalesOrderRepository
import com.theprodeogroup.fish.application.FakeStockItemRepository
import com.theprodeogroup.fish.application.FakeStockShortageEscalationRepository
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
import com.theprodeogroup.fish.application.RecordCollectionUseCase
import com.theprodeogroup.fish.application.RecordInventoryIssueUseCase
import com.theprodeogroup.fish.application.RecordInventoryReceiptUseCase
import com.theprodeogroup.fish.application.FakeAdminPhoneVerificationChecker
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeInventoryScheduleUseCase
import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.application.RecordAdminPhoneNumberUseCase
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
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
private const val TEST_EMAIL = "im-caller@example.com"

/**
 * `POST /inventory/record-receipt` and `POST /inventory/record-issue`
 * via Ktor's `testApplication`
 * (`docs/Ecosystem_Extraction_DDD_Design.md` Section 1.3's Option B
 * resolution) - unlike [InventoryRoutesTest] (the old, still-coexisting
 * `Post*` pair), there's no `StockItem` lookup to derive tenant scoping
 * from, so every request body carries `companyId` directly, same
 * pattern as [RecordSaleAndCollectionRoutesTest].
 */
class RecordInventoryReceiptAndIssueRoutesTest {

    private class Fixture(role: Role = Role.ACCOUNTANT) {
        val userRepository = FakeUserRepository()
        val membershipRepository = FakeMembershipRepository()
        val companyRepository = FakeCompanyRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
        val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
        val purchaseOrderRepository = FakePurchaseOrderRepository()
        val stockItemRepository = FakeStockItemRepository()
        val postPurchaseOrderUseCase = PostPurchaseOrderUseCase(
            purchaseOrderRepository, FakeCreditorRepository(), stockItemRepository, periodRepository, accountRepository, journalEntryRepository
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
        val customerRepository = FakeCustomerRepository()
        val postSalesOrderUseCase = PostSalesOrderUseCase(
            salesOrderRepository, customerRepository, stockItemRepository, periodRepository, accountRepository, journalEntryRepository
        )
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
        val tenantRepository = FakeTenantRepository()
        val onboardTenantUseCase = OnboardTenantUseCase(tenantRepository, companyRepository, userRepository, membershipRepository, accountRepository, periodRepository, journalEntryRepository)
        val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(tenantRepository, companyRepository, accountRepository, periodRepository, journalEntryRepository)
        val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
        val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

        val tenantId = TenantId.generate()
        val user = User.create(TEST_EMAIL, "Test IM Caller").also { userRepository.save(it) }
        val membership = Membership.grant(user.id, tenantId, role).also { membershipRepository.save(it) }
        val company = Company.create(tenantId, "Test Co", ClientType.NON_PROFIT, "GB", GBP).also { companyRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            periodRepository.save(it)
        }
        val inventoryAssetAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Inventory").also { accountRepository.save(it) }
        val apControlAccount = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2100", "Accounts Payable").also { accountRepository.save(it) }
        val cogsAccount = Account.create(company.id, AccountType.EXPENSE, null, "5000", "Cost of Goods Sold").also { accountRepository.save(it) }

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
                computeSalesToExpenseRatioUseCase = computeSalesToExpenseRatioUseCase,
                tenantRepository = tenantRepository,
                onboardTenantUseCase = onboardTenantUseCase,
                addCompanyToTenantUseCase = addCompanyToTenantUseCase
            )
        }
    }

    @Test
    fun `given a valid record-receipt request with a bearer token and matching X-Tenant-Id, when posted, then it returns 200 Posted`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/inventory/record-receipt") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}",
                    |"contraAccountId": "${fixture.apControlAccount.id.value}", "committedCost": "18500.00",
                    |"committedCostCurrency": "GBP", "itemId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: RecordInventoryReceiptResponseDto = response.body()
        body.status shouldBe "POSTED"
    }

    @Test
    fun `given no bearer token, when record-receipt is posted, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/inventory/record-receipt") {
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}",
                    |"contraAccountId": "${fixture.apControlAccount.id.value}", "committedCost": "18500.00",
                    |"committedCostCurrency": "GBP", "itemId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a claimed X-Tenant-Id that does not own the companyId, when record-receipt is posted, then it returns 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/inventory/record-receipt") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", TenantId.generate().value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}",
                    |"contraAccountId": "${fixture.apControlAccount.id.value}", "committedCost": "18500.00",
                    |"committedCostCurrency": "GBP", "itemId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given a nonexistent companyId, when record-receipt is posted, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/inventory/record-receipt") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${UUID.randomUUID()}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}",
                    |"contraAccountId": "${fixture.apControlAccount.id.value}", "committedCost": "18500.00",
                    |"committedCostCurrency": "GBP", "itemId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `given a caller with a READ_ONLY Membership, when record-receipt is posted, then it returns 403`() = testApplication {
        val fixture = Fixture(Role.READ_ONLY)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/inventory/record-receipt") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}",
                    |"contraAccountId": "${fixture.apControlAccount.id.value}", "committedCost": "18500.00",
                    |"committedCostCurrency": "GBP", "itemId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given a non-positive committed cost, when record-receipt is posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/inventory/record-receipt") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}",
                    |"contraAccountId": "${fixture.apControlAccount.id.value}", "committedCost": "0.00",
                    |"committedCostCurrency": "GBP", "itemId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a valid record-issue request, when posted, then it returns 200 Posted`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/inventory/record-issue") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "contraAccountId": "${fixture.cogsAccount.id.value}",
                    |"inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}", "committedCost": "9200.00",
                    |"committedCostCurrency": "GBP", "itemId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: RecordInventoryIssueResponseDto = response.body()
        body.status shouldBe "POSTED"
    }

    @Test
    fun `given an Inventory Asset Account that does not exist, when record-issue is posted, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/inventory/record-issue") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}",
                    |"date": "$TODAY", "contraAccountId": "${fixture.cogsAccount.id.value}",
                    |"inventoryAssetAccountId": "${UUID.randomUUID()}", "committedCost": "9200.00",
                    |"committedCostCurrency": "GBP", "itemId": "${UUID.randomUUID()}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.NotFound
    }
}
