package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.AddMissingStandardAccountsUseCase
import com.theprodeogroup.fish.application.RecordCashBookEntryUseCase
import com.theprodeogroup.fish.application.RecordCashBookTransferUseCase
import com.theprodeogroup.fish.application.UndoCashBookEntryUseCase
import com.theprodeogroup.fish.application.ListCounterAccountsUseCase
import com.theprodeogroup.fish.application.ChangeCashBookKindUseCase
import com.theprodeogroup.fish.application.ComputeCashBookUseCase
import com.theprodeogroup.fish.application.ComputeCashBalanceSeriesUseCase
import com.theprodeogroup.fish.application.ListCashBooksUseCase
import com.theprodeogroup.fish.application.FakeBankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.math.BigDecimal
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import com.theprodeogroup.fish.application.ComputeReceivablesPayablesSummaryUseCase
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.PeriodId
import contracts.ContractFixtures
import io.ktor.client.statement.bodyAsText
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val GBP: Currency = Currency.getInstance("GBP")
private const val ADMIN_EMAIL = "founder@example.com"
private val TODAY: LocalDate = LocalDate.parse("2026-10-12") // only the copied Fixture's default Period uses it; this class builds its own Period

/** The server's "today" for the two reads that depend on it, so a response with dates is reproducible. */
private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC)

/**
 * Contract fixtures, GL as the SOURCE (docs/Cross_Service_Contract_Fixtures_SPUTO.md, T4; consumer EA). Each test drives the
 * REAL route through the whole application with a fully populated, deterministic, SYNTHETIC sample (fixed ids and dates,
 * a fixed clock, every list non-empty, each nullable field both null and non-null across the response) and compares the
 * answer with the files under `contracts/gl` in the pinned fish-contracts submodule. A difference fails the test; an
 * `UPDATE_CONTRACTS=1` run rewrites the fixture and the consumers are then told. Nobody hand-edits a fixture.
 */
class ContractFixtureRoutesTest {

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
        val listCashBooksUseCase = ListCashBooksUseCase(companyRepository, accountRepository, journalEntryRepository)
        val computeCashBalanceSeriesUseCase = ComputeCashBalanceSeriesUseCase(companyRepository, accountRepository, journalEntryRepository, FIXED_CLOCK)
        val computeReceivablesPayablesSummaryUseCase = ComputeReceivablesPayablesSummaryUseCase(companyRepository, accountRepository, journalEntryRepository, FIXED_CLOCK)
        val cashBookReconciliations = FakeBankReconciliationRepository()
        val computeCashBookUseCase = ComputeCashBookUseCase(companyRepository, accountRepository, journalEntryRepository, periodRepository, cashBookReconciliations)
        val changeCashBookKindUseCase = ChangeCashBookKindUseCase(accountRepository, cashBookReconciliations)
        val addMissingStandardAccountsUseCase = AddMissingStandardAccountsUseCase(companyRepository, accountRepository)
        val recordCashBookEntryUseCase = RecordCashBookEntryUseCase(companyRepository, accountRepository, periodRepository, journalEntryRepository, postJournalEntryUseCase)
        val listCounterAccountsUseCase = ListCounterAccountsUseCase(companyRepository, accountRepository)
        val recordCashBookTransferUseCase = RecordCashBookTransferUseCase(companyRepository, accountRepository, periodRepository, journalEntryRepository, postJournalEntryUseCase)
        val undoCashBookEntryUseCase = UndoCashBookEntryUseCase(companyRepository, accountRepository, journalEntryRepository, periodRepository)

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
                computeFixedAssetRegisterUseCase = computeFixedAssetRegisterUseCase,
                listCashBooksUseCase = listCashBooksUseCase,
                computeCashBalanceSeriesUseCase = computeCashBalanceSeriesUseCase,
                computeReceivablesPayablesSummaryUseCase = computeReceivablesPayablesSummaryUseCase,
                computeCashBookUseCase = computeCashBookUseCase,
                changeCashBookKindUseCase = changeCashBookKindUseCase,
                addMissingStandardAccountsUseCase = addMissingStandardAccountsUseCase,
                recordCashBookEntryUseCase = recordCashBookEntryUseCase,
                listCounterAccountsUseCase = listCounterAccountsUseCase,
                recordCashBookTransferUseCase = recordCashBookTransferUseCase,
                undoCashBookEntryUseCase = undoCashBookEntryUseCase
            )
        }
    }

    private fun id(n: Int) = AccountId(UUID.fromString("00000000-0000-4000-8000-%012d".format(n)))
    private val periodId = PeriodId(UUID.fromString("00000000-0000-4000-8000-0000000000f1"))
    private val customer = UUID.fromString("00000000-0000-4000-8000-0000000000c1")
    private val supplier = UUID.fromString("00000000-0000-4000-8000-0000000000a1")

    /**
     * The synthetic business: a Company with one open October 2026 Period, a cash book and a bank book, receivables with an
     * overdue part, payables with an overdue part, equipment, equity, cost of sales, operating expense, interest and income
     * tax, and cash flows in all three activities plus an uncategorized one.
     */
    private fun Fixture.seed() {
        Period.create(company.id, PeriodType.MONTH, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), periodId).also { it.open(); periodRepository.save(it) }
        fun account(n: Int, type: AccountType, classification: AccountClassification?, code: String, name: String, expense: ExpenseClassification? = null, kind: CashBookKind? = null) =
            Account.create(company.id, type, classification, code, name, expenseClassification = expense, id = id(n), cashBookKind = kind).also { accountRepository.save(it) }
        val cash = account(1, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", kind = CashBookKind.CASH)
        val bank = account(2, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", kind = CashBookKind.BANK)
        val ar = account(3, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable")
        val equipment = account(4, AccountType.ASSET, AccountClassification.NON_CURRENT, "1200", "Equipment")
        val ap = account(5, AccountType.LIABILITY, AccountClassification.CURRENT, "2000", "Accounts Payable")
        val vat = account(6, AccountType.LIABILITY, AccountClassification.CURRENT, "2150", "VAT Control Account")
        val capital = account(7, AccountType.EQUITY, null, "3000", "Owner's Capital")
        val sales = account(8, AccountType.REVENUE, null, "4000", "Sales Revenue")
        val costOfSales = account(9, AccountType.EXPENSE, null, "5010", "Cost of Sales", ExpenseClassification.COST_OF_GOODS_SOLD)
        val operating = account(10, AccountType.EXPENSE, null, "5100", "Operating Expenses", ExpenseClassification.ADMINISTRATIVE)
        val interest = account(11, AccountType.EXPENSE, null, "5600", "Interest Expense", ExpenseClassification.INTEREST_EXPENSE)
        val tax = account(12, AccountType.EXPENSE, null, "5700", "Income Tax Expense", ExpenseClassification.INCOME_TAX_EXPENSE)

        fun money(amount: String) = Money(BigDecimal(amount), GBP)
        fun dr(a: Account, amount: String, vararg dims: Pair<DimensionType, String>) = JournalLine(a.id, money(amount), TransactionSide.DEBIT, mapOf(*dims))
        fun cr(a: Account, amount: String, vararg dims: Pair<DimensionType, String>) = JournalLine(a.id, money(amount), TransactionSide.CREDIT, mapOf(*dims))
        fun post(date: String, vararg lines: JournalLine) {
            JournalEntry.create(periodId, LocalDate.parse(date), lines.toList(), JournalSource.MANUAL, "Synthetic").also { it.post(); journalEntryRepository.save(it) }
        }
        val cashFlow = DimensionType.CASH_FLOW_ACTIVITY
        val cust = DimensionType.CUSTOMER to customer.toString()
        val vend = DimensionType.VENDOR to supplier.toString()

        post("2026-08-28", dr(ar, "300.00", cust), cr(sales, "300.00"))                      // an old sale: overdue
        post("2026-09-01", dr(operating, "150.00"), cr(ap, "150.00", vend))                  // an old purchase: overdue
        post("2026-10-01", dr(cash, "10000.00", cashFlow to CashFlowActivity.FINANCING.name), cr(capital, "10000.00"))
        post("2026-10-02", dr(ar, "1200.00", cust), cr(sales, "1000.00"), cr(vat, "200.00", DimensionType.VAT_CATEGORY to "STANDARD"))
        post("2026-10-03", dr(costOfSales, "400.00"), cr(ap, "400.00", vend))
        post("2026-10-04", dr(equipment, "3000.00"), cr(cash, "3000.00", cashFlow to CashFlowActivity.INVESTING.name))
        post("2026-10-05", dr(cash, "250.00", cashFlow to CashFlowActivity.OPERATING.name), cr(ar, "250.00", cust))
        post("2026-10-06", dr(bank, "2000.00"), cr(cash, "2000.00"))                         // a move between two books
        post("2026-10-07", dr(interest, "20.00"), cr(cash, "20.00", cashFlow to CashFlowActivity.OPERATING.name))
        post("2026-10-08", dr(tax, "30.00"), cr(cash, "30.00"))                              // untagged, inferred as operating from its counter-account
        post("2026-10-09", dr(cash, "100.00"), cr(sales, "60.00"), cr(capital, "40.00"))     // untagged and ambiguous (revenue and equity): the uncategorized amount
    }

    private suspend fun io.ktor.client.HttpClient.fetch(fixture: Fixture, path: String): io.ktor.client.statement.HttpResponse =
        get("/api/companies/${fixture.company.id.value}/$path") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

    private fun contract(path: String, route: String) = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.fetch(fixture, route)

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate(path, response.bodyAsText())
    }

    @Test
    fun `contract fixture - GET reports cash-flow`() = contract("contracts/gl/cash-flow.json", "reports/cash-flow")

    @Test
    fun `contract fixture - GET reports profit-and-loss`() = contract("contracts/gl/profit-and-loss.json", "reports/profit-and-loss")

    @Test
    fun `contract fixture - GET reports balance-sheet`() = contract("contracts/gl/balance-sheet.json", "reports/balance-sheet")

    @Test
    fun `contract fixture - GET reports working-capital`() = contract("contracts/gl/working-capital.json", "reports/working-capital")

    @Test
    fun `contract fixture - GET reports trading-profit-and-loss`() = contract("contracts/gl/trading-profit-and-loss.json", "reports/trading-profit-and-loss")

    @Test
    fun `contract fixture - GET cash-balance-series days 30`() = contract("contracts/gl/cash-balance-series.json", "cash-balance-series?days=30")

    @Test
    fun `contract fixture - GET receivables-payables-summary`() = contract("contracts/gl/receivables-payables-summary.json", "receivables-payables-summary")

    @Test
    fun `contract fixture - the refusal body, fully populated from a real GL refusal`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // A real refusal with BOTH fields present: error and detail.
        val response = client.fetch(fixture, "cash-balance-series?days=0")

        response.status shouldBe HttpStatusCode.BadRequest
        ContractFixtures.verifyOrUpdate("contracts/gl/error-body.json", response.bodyAsText())
    }
}
