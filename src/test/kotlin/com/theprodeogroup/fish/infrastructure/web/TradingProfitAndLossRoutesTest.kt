package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.application.FakeTaxRuleRepository
import com.theprodeogroup.fish.application.FakeTaxComputationRepository
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
import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeSupplierRepository
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
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
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.ExpenseClassification
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.math.BigDecimal
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

private val GBP: Currency = Currency.getInstance("GBP")
private const val ADMIN_EMAIL = "founder@example.com"
private val TODAY: LocalDate = LocalDate.now()

/** `GET /companies/{companyId}/sales-posting-context` - see SalesPostingContextRoutes.kt's own KDoc. */
class TradingProfitAndLossRoutesTest {

    private class Fixture(configureAccounts: Boolean = true, openPeriod: Boolean = true) {
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

        val period = if (openPeriod) {
            Period.create(company.id, PeriodType.MONTH, TODAY.minusDays(10), TODAY.plusDays(20)).also {
                it.open()
                periodRepository.save(it)
            }
        } else null

        val arAccount = if (configureAccounts) {
            Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable").also { accountRepository.save(it) }
        } else null
        val revenueAccount = if (configureAccounts) {
            Account.create(company.id, AccountType.REVENUE, null, "4000", "Revenue").also { accountRepository.save(it) }
        } else null
        val cashAccount = if (configureAccounts) {
            Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }
        } else null
        val vatAccount = if (configureAccounts) {
            Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2150", "VAT Control Account").also { accountRepository.save(it) }
        } else null

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


    private fun Fixture.account(code: String, type: AccountType, expense: ExpenseClassification? = null): Account =
        Account.create(company.id, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, "Acct $code", expense).also { accountRepository.save(it) }

    private fun Fixture.post(debit: Account, credit: Account, amount: String) {
        val entry = JournalEntry.create(
            period!!.id, TODAY,
            listOf(
                JournalLine(debit.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
                JournalLine(credit.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        entry.post()
        journalEntryRepository.save(entry)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.signedIn(fixture: Fixture) {
        header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
        header("X-Tenant-Id", fixture.tenant.value.toString())
    }

    @Test
    fun `given revenue, cost of sales, operating, interest and tax postings, when GET trading-profit-and-loss is called, then the split is returned and netProfit equals the plain profit-and-loss netIncome`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.account("1000", AccountType.ASSET)
        val cogs = fixture.account("5010", AccountType.EXPENSE, ExpenseClassification.COST_OF_GOODS_SOLD)
        val admin = fixture.account("5000", AccountType.EXPENSE)
        val interest = fixture.account("5600", AccountType.EXPENSE, ExpenseClassification.INTEREST_EXPENSE)
        val tax = fixture.account("5700", AccountType.EXPENSE, ExpenseClassification.INCOME_TAX_EXPENSE)
        fixture.post(cash, fixture.revenueAccount!!, "50000.00")
        fixture.post(cogs, cash, "20000.00")
        fixture.post(admin, cash, "6000.00")
        fixture.post(interest, cash, "1500.00")
        fixture.post(tax, cash, "3000.00")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/trading-profit-and-loss") { signedIn(fixture) }

        response.status shouldBe HttpStatusCode.OK
        val body: TradingProfitAndLossResponseDto = response.body()
        body.currency shouldBe "GBP"
        body.periodStart shouldBe fixture.period!!.startDate.toString()
        body.periodEnd shouldBe fixture.period!!.endDate.toString()
        body.costOfSalesConfigured shouldBe true
        body.interestConfigured shouldBe true
        body.revenue shouldBe "50000.00"
        body.costOfSales shouldBe "20000.00"
        body.grossProfit shouldBe "30000.00"
        body.operatingExpenses shouldBe "6000.00"
        body.operatingProfit shouldBe "24000.00"
        body.interestExpense shouldBe "1500.00"
        body.profitBeforeTax shouldBe "22500.00"
        body.incomeTaxExpense shouldBe "3000.00"
        body.netProfit shouldBe "19500.00"

        val plain: ProfitAndLossResponseDto = client.get("/api/companies/${fixture.company.id.value}/reports/profit-and-loss") { signedIn(fixture) }.body()
        body.netProfit shouldBe plain.netIncome
    }

    @Test
    fun `given nothing tagged as cost of sales or interest, when GET trading-profit-and-loss is called, then both configured flags are false`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.account("1000", AccountType.ASSET)
        val admin = fixture.account("5000", AccountType.EXPENSE)
        fixture.post(cash, fixture.revenueAccount!!, "1000.00")
        fixture.post(admin, cash, "400.00")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val body: TradingProfitAndLossResponseDto = client.get("/api/companies/${fixture.company.id.value}/reports/trading-profit-and-loss") { signedIn(fixture) }.body()

        body.costOfSalesConfigured shouldBe false
        body.interestConfigured shouldBe false
        body.grossProfit shouldBe body.revenue
    }

    @Test
    fun `given no open Period, when GET trading-profit-and-loss is called, then it returns 409`() = testApplication {
        val fixture = Fixture(openPeriod = false)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/reports/trading-profit-and-loss") { signedIn(fixture) }

        response.status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `given no bearer token, when GET trading-profit-and-loss is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.get("/api/companies/${fixture.company.id.value}/reports/trading-profit-and-loss").status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given an Expense account, when PUT expense-classification tags it as cost of sales, then the response shows it and the report picks it up`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.account("1000", AccountType.ASSET)
        val purchases = fixture.account("5001", AccountType.EXPENSE)
        fixture.post(cash, fixture.revenueAccount!!, "1000.00")
        fixture.post(purchases, cash, "700.00")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val put = client.put("/api/companies/${fixture.company.id.value}/accounts/${purchases.id.value}/expense-classification") {
            signedIn(fixture)
            contentType(ContentType.Application.Json)
            setBody("""{"expenseClassification": "COST_OF_GOODS_SOLD"}""")
        }

        put.status shouldBe HttpStatusCode.OK
        put.body<ExpenseAccountClassificationDto>().expenseClassification shouldBe "COST_OF_GOODS_SOLD"
        val report: TradingProfitAndLossResponseDto = client.get("/api/companies/${fixture.company.id.value}/reports/trading-profit-and-loss") { signedIn(fixture) }.body()
        report.costOfSalesConfigured shouldBe true
        report.costOfSales shouldBe "700.00"
        report.grossProfit shouldBe "300.00"
    }

    @Test
    fun `given null, when PUT expense-classification is called, then the tag is cleared`() = testApplication {
        val fixture = Fixture()
        val cogs = fixture.account("5010", AccountType.EXPENSE, ExpenseClassification.COST_OF_GOODS_SOLD)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val put = client.put("/api/companies/${fixture.company.id.value}/accounts/${cogs.id.value}/expense-classification") {
            signedIn(fixture)
            contentType(ContentType.Application.Json)
            setBody("""{"expenseClassification": null}""")
        }

        put.status shouldBe HttpStatusCode.OK
        put.body<ExpenseAccountClassificationDto>().expenseClassification shouldBe null
    }

    @Test
    fun `given a non-Expense account, an unknown value or an account of another Company, when PUT expense-classification is called, then it is refused`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.account("1000", AccountType.ASSET)
        val otherCompanyAccount = Account.create(
            com.theprodeogroup.fish.domain.tenancy.CompanyId.generate(), AccountType.EXPENSE, null, "5010", "Other"
        ).also { fixture.accountRepository.save(it) }
        val expense = fixture.account("5002", AccountType.EXPENSE)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        suspend fun put(accountId: String, value: String) = client.put("/api/companies/${fixture.company.id.value}/accounts/$accountId/expense-classification") {
            signedIn(fixture)
            contentType(ContentType.Application.Json)
            setBody("""{"expenseClassification": $value}""")
        }

        put(cash.id.value.toString(), "\"INTEREST_EXPENSE\"").status shouldBe HttpStatusCode.BadRequest
        put(expense.id.value.toString(), "\"NOT_A_CLASS\"").status shouldBe HttpStatusCode.BadRequest
        put(otherCompanyAccount.id.value.toString(), "\"INTEREST_EXPENSE\"").status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `given no bearer token, when PUT expense-classification is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.put("/api/companies/${fixture.company.id.value}/accounts/${UUID.randomUUID()}/expense-classification") {
            contentType(ContentType.Application.Json)
            setBody("""{"expenseClassification": null}""")
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }
}
