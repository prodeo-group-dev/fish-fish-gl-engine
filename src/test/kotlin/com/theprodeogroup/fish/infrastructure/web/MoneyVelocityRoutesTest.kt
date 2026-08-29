package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeInventoryScheduleUseCase
import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeAdminPhoneVerificationChecker
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeCreditorRepository
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
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
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
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
// LocalDate.now(), not a hardcoded date - this fixture's Period/JournalEntry
// dates must track whatever "now" actually is, since the route itself always
// computes daysElapsed against the real LocalDate.now() (no fake-clock
// injection point exists for a route test) - a hardcoded date here silently
// breaks daysElapsed assertions as real time passes past it.
private val TODAY: LocalDate = LocalDate.now()

/** `GET /companies/{companyId}/money-velocity` - see MoneyVelocityRoutes.kt's own KDoc. */
class MoneyVelocityRoutesTest {

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

        val period = Period.create(company.id, PeriodType.MONTH, TODAY.minusDays(10), TODAY.plusDays(20)).also {
            it.open()
            periodRepository.save(it)
        }
        val revenueAccount = Account.create(company.id, AccountType.REVENUE, null, "4000", "Revenue").also { accountRepository.save(it) }

        fun postRevenue(amount: String) {
            val entry = JournalEntry.create(
                period.id, TODAY,
                listOf(
                    JournalLine(AccountId.generate(), Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
                    JournalLine(revenueAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
                ),
                JournalSource.MANUAL
            )
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
    }

    @Test
    fun `given a matching X-Tenant-Id and bearer token, when GET money-velocity is called, then it returns the daily rate`() = testApplication {
        val fixture = Fixture()
        fixture.postRevenue("500.00")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/money-velocity") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        val body: MoneyVelocityResponseDto = response.body()
        body.netIncome shouldBe "500.00"
        body.currency shouldBe "GBP"
        body.daysElapsed shouldBe 10L
        body.dailyRate shouldBe "50.00"
    }

    @Test
    fun `given no bearer token, when GET money-velocity is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/money-velocity") {
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a caller authenticated in a different Tenant, when GET money-velocity is called, then it returns 403`() = testApplication {
        val fixture = Fixture()
        val outsiderTenant = Tenant.onboard("Other Co", TenantSegment.EXTERNAL_B2B, GBP)
        val outsiderUser = User.create("outsider@example.com", "Outsider").also { fixture.userRepository.save(it) }
        val outsiderMembership = Membership.grant(outsiderUser.id, outsiderTenant.id, Role.OWNER_ADMIN)
        fixture.membershipRepository.save(outsiderMembership)
        fixture.tenantRepository.save(outsiderTenant)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/money-velocity") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken("outsider@example.com")}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given a Membership with AccessLevel NONE in the correct Tenant, when GET money-velocity is called, then it returns 403`() = testApplication {
        val fixture = Fixture()
        val noAccessUser = User.create("no-access@example.com", "No Access").also { fixture.userRepository.save(it) }
        val noAccessMembership = Membership.grant(noAccessUser.id, fixture.tenant.id, Role.READ_ONLY, AccessLevel.NONE)
        fixture.membershipRepository.save(noAccessMembership)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/money-velocity") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken("no-access@example.com")}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given a nonexistent Company, when GET money-velocity is called, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${java.util.UUID.randomUUID()}/money-velocity") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.NotFound
    }
}
