package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.application.FakeTaxRuleRepository
import com.theprodeogroup.fish.application.FakeTaxComputationRepository
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
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeSupplierRepository
import com.theprodeogroup.fish.application.FakeCustomerRepository
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.application.FakeJournalEntryRepository
import com.theprodeogroup.fish.application.FakeLeaveAccrualRepository
import com.theprodeogroup.fish.application.FakeMembershipRepository
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakeSalesInvoiceRecordRepository
import com.theprodeogroup.fish.application.FakeEaMembershipGateway
import com.theprodeogroup.fish.application.FakeUserRepository
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
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
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
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

        val tenant = TenantId.generate()
        val company = Company.create(tenant, "Purse UK", ClientType.NON_PROFIT, Jurisdiction.UK, GBP)
        val adminUser = User.create(ADMIN_EMAIL, "Founding Admin").also { userRepository.save(it) }
        val adminSetup = run {
            companyRepository.save(company)
            val membership = Membership.grant(adminUser.id, tenant, Role.OWNER_ADMIN, company.id)
            membershipRepository.save(membership)
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
    fun `given the sample activity, when GET reports balance-sheet is called, then it returns a balanced sheet with retained earnings`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/balance-sheet") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
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
    fun `given activity dated today, when the balance sheet is read as of yesterday, then it is empty, and as of today it matches the full sheet`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        suspend fun sheet(query: String): BalanceSheetResponseDto = client.get("/api/companies/${fixture.company.id.value}/reports/balance-sheet$query") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }.body()

        val before = sheet("?asOf=${TODAY.minusDays(1)}")
        before.totalAssets shouldBe "0.00"
        before.totalLiabilities shouldBe "0.00"
        before.retainedEarnings shouldBe "0.00"
        before.isBalanced shouldBe true

        sheet("?asOf=$TODAY") shouldBe sheet("")
        sheet("").totalAssets shouldBe "1300.00"
    }

    @Test
    fun `given an asOf that is not a date, when the balance sheet is requested, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/balance-sheet?asOf=yesterday") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given no bearer token, when GET reports balance-sheet is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/balance-sheet") {
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    private suspend fun io.ktor.client.HttpClient.report(fixture: Fixture, path: String) =
        get("/api/companies/${fixture.company.id.value}/reports/$path") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

    @Test
    fun `given the sample activity, when GET reports trial-balance is called, then every account shows in its column and debits equal credits`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.report(fixture, "trial-balance")

        response.status shouldBe HttpStatusCode.OK
        val body: TrialBalanceResponseDto = response.body()
        body.currency shouldBe "GBP"
        body.asOf shouldBe null
        body.lines.map { it.code } shouldBe listOf("1000", "2000", "3000", "4000", "5000")
        body.lines.map { it.debit to it.credit } shouldBe listOf(
            "1300.00" to "0.00", "0.00" to "100.00", "0.00" to "1000.00", "0.00" to "500.00", "300.00" to "0.00"
        )
        body.totalDebits shouldBe "1600.00"
        body.totalCredits shouldBe "1600.00"
        body.difference shouldBe "0.00"
        body.isBalanced shouldBe true
    }

    @Test
    fun `given the sample activity, when the trial balance is read next to the balance sheet and profit and loss, then the three agree`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val tb: TrialBalanceResponseDto = client.report(fixture, "trial-balance").body()
        val bs: BalanceSheetResponseDto = client.report(fixture, "balance-sheet").body()
        val pl: ProfitAndLossResponseDto = client.report(fixture, "profit-and-loss").body()

        fun sum(type: String) = tb.lines.filter { it.type == type }
            .fold(BigDecimal.ZERO) { acc, l -> acc + BigDecimal(l.debit) - BigDecimal(l.credit) }
        fun credits(type: String) = -sum(type)

        sum("ASSET") shouldBe BigDecimal(bs.totalAssets)
        credits("LIABILITY") shouldBe BigDecimal(bs.totalLiabilities)
        credits("EQUITY") + BigDecimal(bs.retainedEarnings) shouldBe BigDecimal(bs.totalEquity)
        credits("REVENUE") shouldBe BigDecimal(pl.totalRevenue)
        sum("EXPENSE") shouldBe BigDecimal(pl.totalExpense)
        credits("REVENUE") - sum("EXPENSE") shouldBe BigDecimal(pl.netIncome)
        BigDecimal(bs.totalAssets) shouldBe BigDecimal(bs.totalLiabilities) + BigDecimal(bs.totalEquity)
    }

    @Test
    fun `given activity dated today, when the trial balance is read as of yesterday, then it is empty and still balanced`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val body: TrialBalanceResponseDto = client.report(fixture, "trial-balance?asOf=${TODAY.minusDays(1)}").body()

        body.asOf shouldBe TODAY.minusDays(1).toString()
        body.totalDebits shouldBe "0.00"
        body.totalCredits shouldBe "0.00"
        body.lines.all { it.debit == "0.00" && it.credit == "0.00" } shouldBe true
        body.isBalanced shouldBe true
    }

    @Test
    fun `given an asOf that is not a date, when the trial balance is requested, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.report(fixture, "trial-balance?asOf=yesterday").status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a claimed Tenant that does not own the Company, when the trial balance is requested, then it returns 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/trial-balance") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", TenantId.generate().value.toString())
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given the sample activity, when GET reports profit-and-loss is called, then it returns revenue, expense and net income`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/profit-and-loss") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
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
            header("X-Tenant-Id", fixture.tenant.value.toString())
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
            header("X-Tenant-Id", fixture.tenant.value.toString())
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
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given the sample activity, when GET reports working-capital is called, then it returns current assets minus current liabilities`() = testApplication {
        val fixture = Fixture()
        fixture.postSampleActivity()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/working-capital") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        val body: WorkingCapitalResponseDto = response.body()
        body.totalCurrentAssets shouldBe "1300.00"
        body.totalCurrentLiabilities shouldBe "100.00"
        body.workingCapital shouldBe "1200.00"
    }

    @Test
    fun `given no bearer token, when GET reports working-capital is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/working-capital") {
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a nonexistent Company, when GET reports working-capital is called, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${java.util.UUID.randomUUID()}/reports/working-capital") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

        response.status shouldBe HttpStatusCode.NotFound
    }
}
