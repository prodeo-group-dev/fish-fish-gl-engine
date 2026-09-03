package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.ComputeBalanceSheetUseCase
import com.theprodeogroup.fish.application.ComputeCashFlowUseCase
import com.theprodeogroup.fish.application.AssessFixedAssetImpairmentUseCase
import com.theprodeogroup.fish.application.ComputeFixedAssetRegisterUseCase
import com.theprodeogroup.fish.application.CreateFixedAssetUseCase
import com.theprodeogroup.fish.application.DisposeFixedAssetUseCase
import com.theprodeogroup.fish.application.FakeFixedAssetRepository
import com.theprodeogroup.fish.application.RecordFixedAssetDepreciationUseCase
import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.application.ComputeProfitAndLossUseCase
import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.application.ComputeTaxUseCase
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
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakeSalesInvoiceRecordRepository
import com.theprodeogroup.fish.application.FakeStaffInviteNotificationGateway
import com.theprodeogroup.fish.application.FakeTaxComputationRepository
import com.theprodeogroup.fish.application.FakeTaxRuleRepository
import com.theprodeogroup.fish.application.FakeTenantRepository
import com.theprodeogroup.fish.application.FakeUserRepository
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
import com.theprodeogroup.fish.application.InviteStaffMemberUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.fish.application.OnboardTenantUseCase
import com.theprodeogroup.fish.application.CreateAccountUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.RecordOpeningBalanceUseCase
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
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tax.TaxRule
import com.theprodeogroup.fish.domain.tax.TaxType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private const val ADMIN_EMAIL = "tax-admin@example.com"
private const val RESTRICTED_EMAIL = "hr-only@example.com"
private val TODAY: LocalDate = LocalDate.now()

/**
 * `POST`/`GET /companies/{companyId}/tax` - closes the parity audit's
 * "wire or shelve tax computation" gap and gives Tax its own
 * [ManagedModule] (2026-08-31, "Tax management should be its own
 * module"). First route in this codebase where a Membership having the
 * right [com.theprodeogroup.fish.domain.tenancy.AccessLevel] isn't
 * enough on its own - the module grant is checked too.
 */
class TaxRoutesTest {

    private class Fixture {
        val userRepository = FakeUserRepository()
        val membershipRepository = FakeMembershipRepository()
        val companyRepository = FakeCompanyRepository()
        val tenantRepository = FakeTenantRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
        val creditorRepository = FakeCreditorRepository()
        val onboardTenantUseCase = OnboardTenantUseCase(tenantRepository, companyRepository, userRepository, membershipRepository, accountRepository, periodRepository, journalEntryRepository)
        val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(tenantRepository, companyRepository, accountRepository, periodRepository, journalEntryRepository)
        val taxRuleRepository = FakeTaxRuleRepository()
        val taxComputationRepository = FakeTaxComputationRepository()
        val computeTaxUseCase = ComputeTaxUseCase(periodRepository, accountRepository, journalEntryRepository, taxComputationRepository)
        val inviteStaffMemberUseCase = InviteStaffMemberUseCase(tenantRepository, userRepository, membershipRepository, FakeStaffInviteNotificationGateway())
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
        val fixedAssetRepository = FakeFixedAssetRepository()
        val createFixedAssetUseCase = CreateFixedAssetUseCase(companyRepository, fixedAssetRepository)
        val recordFixedAssetDepreciationUseCase = RecordFixedAssetDepreciationUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
        val assessFixedAssetImpairmentUseCase = AssessFixedAssetImpairmentUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
        val disposeFixedAssetUseCase = DisposeFixedAssetUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)
        val computeFixedAssetRegisterUseCase = ComputeFixedAssetRegisterUseCase(companyRepository, fixedAssetRepository)

        val tenant = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        val company = Company.create(tenant.id, "Purse UK", ClientType.NON_PROFIT, "GB", GBP)
        val adminUser = User.create(ADMIN_EMAIL, "Tax Admin").also { userRepository.save(it) }
        val adminMembership = Membership.grant(adminUser.id, tenant.id, Role.OWNER_ADMIN)
        val restrictedUser = User.create(RESTRICTED_EMAIL, "HR-only Staff").also { userRepository.save(it) }
        val restrictedMembership = Membership.grant(restrictedUser.id, tenant.id, Role.ACCOUNTANT, grantedModules = setOf(ManagedModule.HR))

        val setup = run {
            companyRepository.save(company)
            membershipRepository.save(adminMembership)
            membershipRepository.save(restrictedMembership)
            tenant.addCompany(company.id)
            tenant.addAdminMembership(adminMembership.id)
            tenant.activate()
            tenantRepository.save(tenant)
        }

        val period = Period.create(company.id, PeriodType.MONTH, TODAY.minusDays(5), TODAY.plusDays(25)).also {
            it.open()
            periodRepository.save(it)
        }
        val cashAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }
        val equityAccount = Account.create(company.id, AccountType.EQUITY, null, "3000", "Share Capital").also { accountRepository.save(it) }
        val revenueAccount = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales Revenue").also { accountRepository.save(it) }
        val expenseAccount = Account.create(company.id, AccountType.EXPENSE, null, "5000", "Operating Expenses").also { accountRepository.save(it) }

        /** Owner invests 1000, a 500 cash sale, a 200 cash expense - net income 300.00, hand-checkable. */
        fun postSampleActivity() {
            post(listOf(JournalLine(cashAccount.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT), JournalLine(equityAccount.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)))
            post(listOf(JournalLine(cashAccount.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT), JournalLine(revenueAccount.id, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)))
            post(listOf(JournalLine(expenseAccount.id, Money(BigDecimal("200.00"), GBP), TransactionSide.DEBIT), JournalLine(cashAccount.id, Money(BigDecimal("200.00"), GBP), TransactionSide.CREDIT)))
        }

        private fun post(lines: List<JournalLine>) {
            val entry = JournalEntry.create(period.id, TODAY, lines, JournalSource.MANUAL)
            entry.post()
            journalEntryRepository.save(entry)
        }

        fun saveGbFlatRateTaxRule(rate: BigDecimal = BigDecimal("0.30")) {
            taxRuleRepository.save(TaxRule.create("GB", TaxType.CORPORATE_INCOME_TAX, rate))
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
                inviteStaffMemberUseCase = inviteStaffMemberUseCase,
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
    fun `given an OWNER_ADMIN with a configured tax rule, when tax is computed, then it returns 201 with the correct taxDue`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        fixture.saveGbFlatRateTaxRule(BigDecimal("0.30"))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/tax") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"periodId": "${fixture.period.id.value}"}""")
        }

        response.status shouldBe HttpStatusCode.Created
        val body: TaxComputationDto = response.body()
        body.taxableProfit shouldBe "300.00"
        body.taxDue shouldBe "90.00"
        body.currency shouldBe "GBP"
    }

    @Test
    fun `given no bearer token, when tax is computed, then it returns 401`() = testApplication {
        val fixture = Fixture()
        fixture.saveGbFlatRateTaxRule()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/tax") {
            contentType(ContentType.Application.Json)
            setBody("""{"periodId": "${fixture.period.id.value}"}""")
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a Membership without the TAX module granted, when tax is computed, then it returns 403`() = testApplication {
        val fixture = Fixture()
        fixture.saveGbFlatRateTaxRule()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/tax") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(RESTRICTED_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"periodId": "${fixture.period.id.value}"}""")
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given no TaxRule configured for the Company's jurisdiction, when tax is computed, then it returns 409`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        // Deliberately no saveGbFlatRateTaxRule() call.
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/tax") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"periodId": "${fixture.period.id.value}"}""")
        }

        response.status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `given a previously-computed tax record, when listed, then it is returned`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        fixture.saveGbFlatRateTaxRule()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.post("/api/companies/${fixture.company.id.value}/tax") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.id.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"periodId": "${fixture.period.id.value}"}""")
        }

        val response = client.get("/api/companies/${fixture.company.id.value}/tax") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
        header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        val body: List<TaxComputationDto> = response.body()
        body.single().taxDue shouldBe "90.00"
    }

    @Test
    fun `given a Membership without the TAX module granted, when computations are listed, then it returns 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/tax") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(RESTRICTED_EMAIL)}")
        header("X-Tenant-Id", fixture.tenant.id.value.toString())
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }
}
