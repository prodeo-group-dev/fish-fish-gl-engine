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
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.application.FakeTaxRuleRepository
import com.theprodeogroup.fish.application.FakeTaxComputationRepository
import com.theprodeogroup.fish.application.InviteStaffMemberUseCase
import com.theprodeogroup.fish.application.IssueStockForSaleUseCase
import com.theprodeogroup.fish.application.FakeStaffInviteNotificationGateway
import com.theprodeogroup.fish.application.OnboardTenantUseCase
import com.theprodeogroup.fish.application.PostInventoryIssueUseCase
import com.theprodeogroup.fish.application.PostInventoryReceiptUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.PostPayRunUseCase
import com.theprodeogroup.fish.application.PostPurchaseOrderUseCase
import com.theprodeogroup.fish.application.PostSalesOrderUseCase
import com.theprodeogroup.fish.application.FakeAdminPhoneVerificationChecker
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeInventoryScheduleUseCase
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
import com.theprodeogroup.fish.domain.inventory.StockItem
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
private val TODAY = LocalDate.of(2026, 8, 27)
private const val TEST_EMAIL = "inventory-caller@example.com"

/**
 * Inventory Management's standalone posting interface's HTTP surface
 * (docs/DDD_Design.md Section 10.21) via Ktor's `testApplication` -
 * mirrors [PayrollRoutesTest]'s structure, covering
 * `POST /stock-items/{id}/receipts` and `POST /stock-items/{id}/issues`.
 */
class InventoryRoutesTest {

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
        val stockShortageEscalationRepository = FakeStockShortageEscalationRepository()
        val issueStockForSaleUseCase = IssueStockForSaleUseCase(stockItemRepository, stockShortageEscalationRepository)
        val postPurchaseOrderUseCase = PostPurchaseOrderUseCase(
            purchaseOrderRepository, FakeCreditorRepository(), stockItemRepository,
            periodRepository, accountRepository, journalEntryRepository
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
        val user = User.create(TEST_EMAIL, "Test Inventory Caller").also { userRepository.save(it) }
        val membership = Membership.grant(user.id, tenantId, role).also { membershipRepository.save(it) }
        val company = Company.create(tenantId, "Test Co", ClientType.NON_PROFIT, "GB", GBP).also { companyRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            periodRepository.save(it)
        }
        val inventoryAssetAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1200", "Inventory").also { accountRepository.save(it) }
        val contraAccount = Account.create(company.id, AccountType.EQUITY, null, "3900", "Opening Balance Equity").also { accountRepository.save(it) }

        val stockItem = StockItem.create(company.id, "Test Widget", GBP).also { stockItemRepository.save(it) }

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
                purchaseOrderRepository = purchaseOrderRepository,
                postPurchaseOrderUseCase = postPurchaseOrderUseCase,
                payRunRepository = payRunRepository,
                postPayRunUseCase = postPayRunUseCase,
                leaveAccrualRepository = leaveAccrualRepository,
                remeasureLeaveAccrualUseCase = remeasureLeaveAccrualUseCase,
                utilizeLeaveAccrualUseCase = utilizeLeaveAccrualUseCase,
                stockItemRepository = stockItemRepository,
                issueStockForSaleUseCase = issueStockForSaleUseCase,
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

    // -- GET /companies/{companyId}/stock-items and /inventory-schedule --

    @Test
    fun `given StockItems for the Company, when GET stock-items is called, then it returns a summary of each`() = testApplication {
        val fixture = Fixture()
        fixture.stockItem.recordReceipt(java.math.BigDecimal("5"), com.theprodeogroup.common.Money(java.math.BigDecimal("10.00"), GBP))
        fixture.stockItemRepository.save(fixture.stockItem)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/stock-items") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        val body: List<StockItemSummaryDto> = response.body()
        body.single().name shouldBe "Test Widget"
        body.single().quantityOnHand shouldBe "5"
    }

    @Test
    fun `given StockItems for the Company, when GET inventory-schedule is called, then it returns a line per item with totals`() = testApplication {
        val fixture = Fixture()
        fixture.stockItem.recordReceipt(java.math.BigDecimal("5"), com.theprodeogroup.common.Money(java.math.BigDecimal("10.00"), GBP))
        fixture.stockItemRepository.save(fixture.stockItem)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/inventory-schedule") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        val body: InventoryScheduleResponseDto = response.body()
        body.lines.single().name shouldBe "Test Widget"
        body.lines.single().quantityOnHand shouldBe "5"
        body.totalCost shouldBe "50.00"
    }

    @Test
    fun `given a caller with a READ_ONLY Membership, when GET inventory-schedule is called, then it returns 200`() = testApplication {
        val fixture = Fixture(Role.READ_ONLY)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/inventory-schedule") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `given no bearer token, when GET inventory-schedule is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/inventory-schedule") {
            header("X-Tenant-Id", fixture.tenantId.value.toString())
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    // -- POST /stock-items/{id}/receipts --

    @Test
    fun `given a valid standalone receipt, when posted, then it returns 200 with the increased quantity`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/stock-items/${fixture.stockItem.id.value}/receipts") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"quantityReceived": "10", "costReceived": "5.00", "costCurrency": "GBP",
                    |"inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}", "contraAccountId": "${fixture.contraAccount.id.value}",
                    |"periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: StockItemJournalEntryResponseDto = response.body()
        body.quantityOnHand shouldBe "10"
        body.journalEntryStatus shouldBe "POSTED"
    }

    @Test
    fun `given no bearer token, when a receipt is posted, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/stock-items/${fixture.stockItem.id.value}/receipts") {
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"quantityReceived": "10", "costReceived": "5.00", "costCurrency": "GBP",
                    |"inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}", "contraAccountId": "${fixture.contraAccount.id.value}",
                    |"periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a claimed X-Tenant-Id that does not own the StockItem, when a receipt is posted, then it returns 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/stock-items/${fixture.stockItem.id.value}/receipts") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", TenantId.generate().value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"quantityReceived": "10", "costReceived": "5.00", "costCurrency": "GBP",
                    |"inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}", "contraAccountId": "${fixture.contraAccount.id.value}",
                    |"periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given a nonexistent StockItem id, when a receipt is posted, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/stock-items/${java.util.UUID.randomUUID()}/receipts") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"quantityReceived": "10", "costReceived": "5.00", "costCurrency": "GBP",
                    |"inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}", "contraAccountId": "${fixture.contraAccount.id.value}",
                    |"periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    // -- POST /stock-items/{id}/issues --

    @Test
    fun `given an issue against a positive quantity on hand, when posted, then it returns 200 with the reduced quantity`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.post("/api/stock-items/${fixture.stockItem.id.value}/receipts") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"quantityReceived": "10", "costReceived": "5.00", "costCurrency": "GBP",
                    |"inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}", "contraAccountId": "${fixture.contraAccount.id.value}",
                    |"periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        val response = client.post("/api/stock-items/${fixture.stockItem.id.value}/issues") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"quantityIssued": "4",
                    |"inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}", "contraAccountId": "${fixture.contraAccount.id.value}",
                    |"periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: StockItemJournalEntryResponseDto = response.body()
        body.quantityOnHand shouldBe "6"
    }

    @Test
    fun `given an issue quantity greater than what's on hand, when posted, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/stock-items/${fixture.stockItem.id.value}/issues") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"quantityIssued": "4",
                    |"inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}", "contraAccountId": "${fixture.contraAccount.id.value}",
                    |"periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a caller with a READ_ONLY Membership, when an issue is posted, then it returns 403`() = testApplication {
        val fixture = Fixture(Role.READ_ONLY)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/stock-items/${fixture.stockItem.id.value}/issues") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"quantityIssued": "1",
                    |"inventoryAssetAccountId": "${fixture.inventoryAssetAccount.id.value}", "contraAccountId": "${fixture.contraAccount.id.value}",
                    |"periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    // -- POST /stock-items/{id}/issue-for-sale --

    @Test
    fun `given sufficient stock, when issue-for-sale is posted, then it returns 200 with the committed cost`() = testApplication {
        val fixture = Fixture()
        fixture.stockItem.recordReceipt(java.math.BigDecimal("100"), com.theprodeogroup.common.Money(java.math.BigDecimal("5.00"), GBP))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/stock-items/${fixture.stockItem.id.value}/issue-for-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": "10", "requestedByEmail": "sales@example.com"}""")
        }

        response.status shouldBe HttpStatusCode.OK
        val body: IssueStockForSaleResponseDto = response.body()
        body.committedCost shouldBe "50.00"
        body.committedCostCurrency shouldBe "GBP"
        fixture.stockItemRepository.findById(fixture.stockItem.id)?.quantityOnHand shouldBe java.math.BigDecimal("90")
    }

    @Test
    fun `given insufficient stock, when issue-for-sale is posted, then it returns 409 and does not mutate quantity`() = testApplication {
        val fixture = Fixture()
        fixture.stockItem.recordReceipt(java.math.BigDecimal("2"), com.theprodeogroup.common.Money(java.math.BigDecimal("5.00"), GBP))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/stock-items/${fixture.stockItem.id.value}/issue-for-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": "10", "requestedByEmail": "sales@example.com"}""")
        }

        response.status shouldBe HttpStatusCode.Conflict
        fixture.stockItemRepository.findById(fixture.stockItem.id)?.quantityOnHand shouldBe java.math.BigDecimal("2")
    }

    @Test
    fun `given a nonexistent StockItem id, when issue-for-sale is posted, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/stock-items/${java.util.UUID.randomUUID()}/issue-for-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": "10", "requestedByEmail": "sales@example.com"}""")
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `given no bearer token, when issue-for-sale is posted, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/stock-items/${fixture.stockItem.id.value}/issue-for-sale") {
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": "10", "requestedByEmail": "sales@example.com"}""")
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }
}
