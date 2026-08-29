package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.ComputeBalanceSheetUseCase
import com.theprodeogroup.fish.application.ComputeCashFlowUseCase
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeInventoryScheduleUseCase
import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.application.ComputeProfitAndLossUseCase
import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeAdminPhoneVerificationChecker
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeCreditorRepository
import com.theprodeogroup.fish.application.FakeCustomerRepository
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
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
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.fish.application.OnboardTenantUseCase
import com.theprodeogroup.fish.application.PostInventoryIssueUseCase
import com.theprodeogroup.fish.application.PostInventoryReceiptUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.PostPayRunUseCase
import com.theprodeogroup.fish.application.PostPurchaseOrderUseCase
import com.theprodeogroup.fish.application.PostSalesOrderUseCase
import com.theprodeogroup.fish.application.RecordAdminPhoneNumberUseCase
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
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.User
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private const val ADMIN_EMAIL = "founder@example.com"
private val TODAY: LocalDate = LocalDate.now()

/**
 * `GET /companies/{companyId}/reports/{balance-sheet,profit-and-loss,cash-flow}` -
 * see ReportsRoutes.kt's own KDoc. One shared Fixture posts a small,
 * hand-checkable set of entries (owner investment, a cash sale, a cash
 * expense, a credit purchase) once, then each report is asserted against
 * the same known state - cheaper to keep in sync than three unrelated
 * scenarios, and the numbers cross-check each other (Balance Sheet's
 * [isBalanced] only means something if the same entries also produce the
 * expected P&L/Cash Flow totals).
 */
class ReportsRoutesTest {

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
        val adminPhoneVerificationChecker = FakeAdminPhoneVerificationChecker()
        val recordAdminPhoneNumberUseCase = RecordAdminPhoneNumberUseCase(tenantRepository, adminPhoneVerificationChecker)
        val computeMoneyVelocityUseCase = ComputeMoneyVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeExpenseVelocityUseCase = ComputeExpenseVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeSalesToExpenseRatioUseCase = ComputeSalesToExpenseRatioUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeBalanceSheetUseCase = ComputeBalanceSheetUseCase(companyRepository, accountRepository, journalEntryRepository)
        val computeProfitAndLossUseCase = ComputeProfitAndLossUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeCashFlowUseCase = ComputeCashFlowUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)

        val tenant = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        val company = Company.create(tenant.id, "Purse UK", ClientType.NON_PROFIT, "GB", GBP)
        val adminUser = User.create(ADMIN_EMAIL, "Founding Admin").also { userRepository.save(it) }
        val adminSetup = run {
            companyRepository.save(company)
            val membership = Membership.grant(adminUser.id, tenant.id, Role.OWNER_ADMIN)
            membershipRepository.save(membership)
            tenant.addCompany(company.id)
            tenant.addAdminMembership(membership.id)
            tenant.activate()
            tenantRepository.save(tenant)
        }

        val period = Period.create(company.id, PeriodType.MONTH, TODAY.minusDays(5), TODAY.plusDays(25)).also {
            it.open()
            periodRepository.save(it)
        }
        val cashAccount = Account.create(company.id, AccountType.ASSET, com.theprodeogroup.fish.domain.ledger.AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }
        val payableAccount = Account.create(company.id, AccountType.LIABILITY, com.theprodeogroup.fish.domain.ledger.AccountClassification.CURRENT, "2000", "Accounts Payable").also { accountRepository.save(it) }
        val equityAccount = Account.create(company.id, AccountType.EQUITY, null, "3000", "Share Capital").also { accountRepository.save(it) }
        val revenueAccount = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales Revenue").also { accountRepository.save(it) }
        val expenseAccount = Account.create(company.id, AccountType.EXPENSE, null, "5000", "Operating Expenses").also { accountRepository.save(it) }

        /** Owner invests 1000, a 500 cash sale, a 200 cash expense, a 100 credit purchase - all hand-checkable. */
        fun postSampleActivity() {
            post(listOf(JournalLine(cashAccount.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT), JournalLine(equityAccount.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)))
            post(listOf(JournalLine(cashAccount.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT), JournalLine(revenueAccount.id, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)))
            post(listOf(JournalLine(expenseAccount.id, Money(BigDecimal("200.00"), GBP), TransactionSide.DEBIT), JournalLine(cashAccount.id, Money(BigDecimal("200.00"), GBP), TransactionSide.CREDIT)))
            post(listOf(JournalLine(expenseAccount.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT), JournalLine(payableAccount.id, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)))
        }

        private fun post(lines: List<JournalLine>) {
            val entry = JournalEntry.create(period.id, TODAY, lines, JournalSource.MANUAL)
            entry.post()
            journalEntryRepository.save(entry)
        }

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
                computeCashFlowUseCase = computeCashFlowUseCase
            )
        }
    }

    @Test
    fun `given the sample activity, when GET reports balance-sheet is called, then it returns a balanced sheet with retained earnings`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/balance-sheet") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        val body: BalanceSheetResponseDto = response.body()
        body.assetLines.single().balance shouldBe "1300.00"
        body.liabilityLines.single().balance shouldBe "100.00"
        body.equityLines.single().balance shouldBe "1000.00"
        body.retainedEarnings shouldBe "200.00"
        body.totalAssets shouldBe "1300.00"
        body.totalLiabilities shouldBe "100.00"
        body.totalEquity shouldBe "1200.00"
        body.isBalanced shouldBe true
    }

    @Test
    fun `given no bearer token, when GET reports balance-sheet is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/balance-sheet") {
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given the sample activity, when GET reports profit-and-loss is called, then it returns revenue, expense and net income`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/profit-and-loss") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        val body: ProfitAndLossResponseDto = response.body()
        body.totalRevenue shouldBe "500.00"
        body.totalExpense shouldBe "300.00"
        body.netIncome shouldBe "200.00"
    }

    @Test
    fun `given no bearer token, when GET reports profit-and-loss is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/profit-and-loss") {
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given the sample activity, when GET reports cash-flow is called, then it returns the cash movement`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/cash-flow") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        val body: CashFlowResponseDto = response.body()
        body.openingBalance shouldBe "0.00"
        body.closingBalance shouldBe "1300.00"
        body.netCashFlow shouldBe "1300.00"
    }

    @Test
    fun `given no bearer token, when GET reports cash-flow is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/cash-flow") {
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }
}
