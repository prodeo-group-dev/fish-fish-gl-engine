package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.AddMissingStandardAccountsUseCase
import com.theprodeogroup.fish.application.RecordCashBookEntryUseCase
import com.theprodeogroup.fish.application.RecordCashBookTransferUseCase
import com.theprodeogroup.fish.application.UndoCashBookEntryUseCase
import com.theprodeogroup.fish.application.ListCounterAccountsUseCase
import com.theprodeogroup.fish.application.ChangeCashBookKindUseCase
import com.theprodeogroup.fish.application.ComputeVatLinesUseCase
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
import com.theprodeogroup.fish.application.FakeVatRateRepository
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
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.EntryProvenance
import com.theprodeogroup.fish.domain.ledger.SourceReference
import com.theprodeogroup.fish.domain.ledger.SourceReferenceType
import com.theprodeogroup.fish.domain.ledger.VatBasis
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
        val company = Company.create(tenant, "Purse UK", ClientType.NON_PROFIT, Jurisdiction.UK, GBP, id = CompanyId(UUID.fromString("00000000-0000-4000-8000-0000000000c0")))
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

        // The registry as GET /jurisdictions serves it; one entry with no currency so the sample shows that field both ways.
        val jurisdictionRepository = com.theprodeogroup.fish.application.FakeJurisdictionRepository().also {
            it.save(com.theprodeogroup.fish.domain.common.JurisdictionEntry(Jurisdiction("GH"), "Ghana", true, null))
        }

        /** The settlement-account policy for the one fixture that shows the enforced refusal (FR-CB52). */
        var enforceSettlement = false

        fun installInto(app: Application) {
            app.fishModule(
                verifier = TestJwtSupport.verifier(),
                vatRateRepository = FakeVatRateRepository(),
                computeVatLinesUseCase = ComputeVatLinesUseCase(companyRepository, accountRepository, journalEntryRepository) { java.time.Instant.parse("2026-12-31T23:00:00Z") },
                cashBookPolicies = CashBookPolicies(settlementAccount = if (enforceSettlement) PolicyMode.ENFORCE else PolicyMode.LOG),
                jurisdictionRepository = jurisdictionRepository,
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
    private val employee = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
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

    // The RANGE form (?from&to, inclusive) that Tax reads for the corporate-tax computation. The seed gives a profit over October
    // 2026 and, over 3 to 8 October, expenses with no revenue: a LOSS (negative netIncome), the string shape Tax floors at zero.
    @Test
    fun `contract fixture - GET reports profit-and-loss for a date range, a profit`() = contract("contracts/gl/profit-and-loss-range.json", "reports/profit-and-loss?from=2026-10-01&to=2026-10-31")

    @Test
    fun `contract fixture - GET reports profit-and-loss for a date range, a loss`() = contract("contracts/gl/profit-and-loss-range-loss.json", "reports/profit-and-loss?from=2026-10-03&to=2026-10-08")

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
    fun `contract fixture - GET sales-posting-context`() = contract("contracts/gl/sales-posting-context.json", "sales-posting-context")


    // --- SOP's posting interfaces (record-sale, record-collection): success and the refusals SOP relays (2026-10-22, GL-T-2) ---

    private val pinnedEntryId = "00000000-0000-4000-8000-0000000000e3"

    private fun pinEntryId(text: String) = text.replace(Regex("\"journalEntryId\":\"[0-9a-f-]{36}\""), "\"journalEntryId\":\"$pinnedEntryId\"")

    private suspend fun io.ktor.client.HttpClient.postSale(fixture: Fixture, linesJson: String): io.ktor.client.statement.HttpResponse =
        post("/api/sales/record-sale") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${periodId.value}", "date": "2026-10-12",
                    |"arControlAccountId": "${id(3).value}", "vatControlAccountId": "${id(6).value}",
                    |"lines": $linesJson, "currency": "GBP", "customerId": "$customer"}""".trimMargin()
            )
        }

    private suspend fun io.ktor.client.HttpClient.postCollection(fixture: Fixture, settlementAccountId: AccountId): io.ktor.client.statement.HttpResponse =
        post("/api/sales/record-collection") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${periodId.value}", "date": "2026-10-13",
                    |"settlementAccountId": "${settlementAccountId.value}", "arControlAccountId": "${id(3).value}",
                    |"amount": "500.00", "currency": "GBP", "customerId": "$customer", "reference": "RCT-000001"}""".trimMargin()
            )
        }

    @Test
    fun `contract fixture - POST sales record-sale`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        Account.create(fixture.company.id, AccountType.REVENUE, null, "4100", "Service Income", id = id(19)).also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // Two lines on two revenue accounts at two VAT categories: gross 1620.00, VAT 220.00.
        val response = client.postSale(
            fixture,
            """[{"netAmount": "1000.00", "vatCategory": "STANDARD", "accountId": "${id(8).value}"},
               |{"netAmount": "400.00", "vatCategory": "REDUCED", "accountId": "${id(19).value}"}]""".trimMargin()
        )

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/record-sale.json", pinEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - POST sales record-sale refused, a line account of the wrong type`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // id(10) is an expense account: not allowed on a sale line.
        val response = client.postSale(fixture, """[{"netAmount": "1000.00", "vatCategory": "STANDARD", "accountId": "${id(10).value}"}]""")

        response.status shouldBe HttpStatusCode.BadRequest
        ContractFixtures.verifyOrUpdate("contracts/gl/record-sale-line-account-not-allowed.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - POST sales record-sale refused, a line account that does not exist`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postSale(fixture, """[{"netAmount": "1000.00", "vatCategory": "STANDARD", "accountId": "${id(99).value}"}]""")

        response.status shouldBe HttpStatusCode.NotFound
        ContractFixtures.verifyOrUpdate("contracts/gl/record-sale-line-account-not-found.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - POST sales record-sale refused, no account named and no Suspense account`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postSale(fixture, """[{"netAmount": "1000.00", "vatCategory": "STANDARD"}]""")

        response.status shouldBe HttpStatusCode.Conflict
        ContractFixtures.verifyOrUpdate("contracts/gl/record-sale-suspense-account-not-configured.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - POST sales record-collection`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postCollection(fixture, id(2))

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/record-collection.json", pinEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - POST sales record-collection refused, the settlement account is not a cash or bank book`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        fixture.enforceSettlement = true
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // id(8) is the revenue account: not a book.
        val response = client.postCollection(fixture, id(8))

        response.status shouldBe HttpStatusCode.Conflict
        ContractFixtures.verifyOrUpdate("contracts/gl/record-collection-settlement-not-a-cash-book.json", response.bodyAsText())
    }


    // --- The remaining posting answers and posting contexts the services read (2026-10-22, GL-T-2) ---

    private fun Fixture.extra(n: Int, type: AccountType, code: String, name: String, classification: AccountClassification? = null) =
        Account.create(company.id, type, classification, code, name, id = id(n)).also { accountRepository.save(it) }

    private suspend fun io.ktor.client.HttpClient.postRoute(fixture: Fixture, route: String, body: String): io.ktor.client.statement.HttpResponse =
        post("/api/$route") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun fixtureBody(fixture: Fixture, rest: String) =
        """{"companyId": "${fixture.company.id.value}", "periodId": "${periodId.value}", "date": "2026-10-12", $rest}"""

    @Test
    fun `contract fixture - POST sales record-sales-return`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        val salesReturns = fixture.extra(29, AccountType.REVENUE, "4910", "Sales Returns")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postRoute(
            fixture, "sales/record-sales-return",
            fixtureBody(fixture, """"salesReturnsAccountId": "${salesReturns.id.value}", "arControlAccountId": "${id(3).value}", "amount": "100.00", "currency": "GBP", "customerId": "$customer"""")
        )

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/record-sales-return.json", pinEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - POST purchasing record-payment`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postRoute(
            fixture, "purchasing/record-payment",
            fixtureBody(fixture, """"apControlAccountId": "${id(5).value}", "settlementAccountId": "${id(1).value}", "amount": "100.00", "currency": "GBP", "supplierId": "$supplier"""")
        )

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/record-payment.json", pinEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - POST inventory record-receipt`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        val inventory = fixture.extra(24, AccountType.ASSET, "1300", "Inventory", AccountClassification.CURRENT)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postRoute(
            fixture, "inventory/record-receipt",
            fixtureBody(fixture, """"inventoryAssetAccountId": "${inventory.id.value}", "contraAccountId": "${id(5).value}", "committedCost": "100.00", "committedCostCurrency": "GBP", "itemId": "00000000-0000-4000-8000-0000000000a1"""")
        )

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/record-receipt.json", pinEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - POST inventory record-issue`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        val inventory = fixture.extra(24, AccountType.ASSET, "1300", "Inventory", AccountClassification.CURRENT)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postRoute(
            fixture, "inventory/record-issue",
            fixtureBody(fixture, """"contraAccountId": "${id(9).value}", "inventoryAssetAccountId": "${inventory.id.value}", "committedCost": "100.00", "committedCostCurrency": "GBP", "itemId": "00000000-0000-4000-8000-0000000000a1"""")
        )

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/record-issue.json", pinEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - GET purchase-posting-context`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        // The optional accounts present, so every field is shown non-null.
        fixture.extra(21, AccountType.LIABILITY, "2300", "Trade Finance Facility Payable", AccountClassification.CURRENT)
        fixture.extra(22, AccountType.REVENUE, "4920", "Discounts Received")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.fetch(fixture, "purchase-posting-context")

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/purchase-posting-context.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - GET inventory-posting-context`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        fixture.extra(23, AccountType.EQUITY, "3910", "Suspense Account")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.fetch(fixture, "inventory-posting-context")

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/inventory-posting-context.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - GET payroll-posting-context`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        fixture.extra(25, AccountType.EXPENSE, "5200", "Wages Expense")
        fixture.extra(26, AccountType.EXPENSE, "5300", "Salaries Expense")
        fixture.extra(27, AccountType.EXPENSE, "5400", "Leave Expense")
        fixture.extra(28, AccountType.LIABILITY, "2200", "Accrued Leave Liability", AccountClassification.CURRENT)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.fetch(fixture, "payroll-posting-context")

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/payroll-posting-context.json", response.bodyAsText())
    }


    // --- Refusals SOP/POP relay, and the leave-accrual routes Payroll calls (2026-10-22, GL-T-2, GL-FR-CTR-2) ---

    @Test
    fun `contract fixture - POST purchasing record-obligation refused, a line account of the wrong type`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // id(8) is the revenue account: not allowed on a purchase line.
        val response = client.postRoute(
            fixture, "purchasing/record-obligation",
            fixtureBody(fixture, """"supplierId": "$supplier", "apControlAccountId": "${id(5).value}", "vatControlAccountId": "${id(6).value}",
                |"lines": [{"netAmount": "1000.00", "vatCategory": "STANDARD", "accountId": "${id(8).value}"}], "currency": "GBP"""".trimMargin())
        )

        response.status shouldBe HttpStatusCode.BadRequest
        ContractFixtures.verifyOrUpdate("contracts/gl/supplier-obligation-line-account-not-allowed.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - POST purchasing record-obligation refused, no account named and no Suspense account`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postRoute(
            fixture, "purchasing/record-obligation",
            fixtureBody(fixture, """"supplierId": "$supplier", "apControlAccountId": "${id(5).value}", "vatControlAccountId": "${id(6).value}",
                |"lines": [{"netAmount": "1000.00", "vatCategory": "STANDARD"}], "currency": "GBP"""".trimMargin())
        )

        response.status shouldBe HttpStatusCode.Conflict
        ContractFixtures.verifyOrUpdate("contracts/gl/supplier-obligation-suspense-account-not-configured.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - POST purchasing record-credit-note refused, the discounts account does not exist`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postRoute(
            fixture, "purchasing/record-credit-note",
            fixtureBody(fixture, """"supplierId": "$supplier", "kind": "DISCOUNT", "apControlAccountId": "${id(5).value}",
                |"discountsReceivedAccountId": "${id(99).value}", "vatControlAccountId": "${id(6).value}",
                |"lines": [{"netAmount": "100.00", "vatCategory": "STANDARD"}], "currency": "GBP"""".trimMargin())
        )

        response.status shouldBe HttpStatusCode.NotFound
        ContractFixtures.verifyOrUpdate("contracts/gl/supplier-credit-note-discounts-account-not-found.json", response.bodyAsText())
    }

    /** The leave-accrual lifecycle in order: create, remeasure up (posts), utilize (posts), remeasure to the same figure (posts nothing). */
    @Test
    fun `contract fixture - leave accrual create, remeasure, utilize and remeasure with no change`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        fixture.extra(27, AccountType.EXPENSE, "5400", "Leave Expense")
        fixture.extra(28, AccountType.LIABILITY, "2200", "Accrued Leave Liability", AccountClassification.CURRENT)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val pinAccrual = { text: String -> text.replace(Regex("\"leaveAccrualId\":\"[0-9a-f-]{36}\""), "\"leaveAccrualId\":\"00000000-0000-4000-8000-0000000000d1\"") }

        val created = client.postRoute(fixture, "leave-accruals", """{"companyId": "${fixture.company.id.value}", "employeeId": "$employee", "currency": "GBP"}""")
        created.status shouldBe HttpStatusCode.OK
        val createdText = created.bodyAsText()
        val accrualId = Regex("\"leaveAccrualId\":\"([0-9a-f-]{36})\"").find(createdText)!!.groupValues[1]
        ContractFixtures.verifyOrUpdate("contracts/gl/leave-accrual-created.json", pinAccrual(createdText))

        val remeasured = client.postRoute(
            fixture, "leave-accruals/$accrualId/remeasure",
            """{"targetAmount": "300.00", "currency": "GBP", "leaveExpenseAccountId": "${id(27).value}", "accruedLeaveLiabilityAccountId": "${id(28).value}", "periodId": "${periodId.value}", "date": "2026-10-12"}"""
        )
        remeasured.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/leave-accrual-remeasured.json", pinEntryId(pinAccrual(remeasured.bodyAsText())))

        val utilized = client.postRoute(
            fixture, "leave-accruals/$accrualId/utilize",
            """{"amount": "100.00", "currency": "GBP", "cashAccountId": "${id(1).value}", "accruedLeaveLiabilityAccountId": "${id(28).value}", "periodId": "${periodId.value}", "date": "2026-10-13"}"""
        )
        utilized.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/leave-accrual-utilized.json", pinEntryId(pinAccrual(utilized.bodyAsText())))

        val unchanged = client.postRoute(
            fixture, "leave-accruals/$accrualId/remeasure",
            """{"targetAmount": "200.00", "currency": "GBP", "leaveExpenseAccountId": "${id(27).value}", "accruedLeaveLiabilityAccountId": "${id(28).value}", "periodId": "${periodId.value}", "date": "2026-10-14"}"""
        )
        unchanged.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/leave-accrual-remeasure-no-change.json", pinAccrual(unchanged.bodyAsText()))
    }

    // --- Cash and bank book receipts and payments, with and without VAT (2026-10-22, GL-T-27, GL-FR-TAX-8, GL-FR-CTR-2) ---

    private val pinnedCashEntryId = "00000000-0000-4000-8000-0000000000e4"

    private fun pinCashEntryId(text: String) = text.replace(Regex("\"entryId\":\"[0-9a-f-]{36}\""), "\"entryId\":\"$pinnedCashEntryId\"")

    private suspend fun io.ktor.client.HttpClient.recordCashBookEntry(fixture: Fixture, segment: String, counter: AccountId, amount: String, extra: String, key: String) =
        post("/api/companies/${fixture.company.id.value}/cash-books/${id(1).value}/$segment") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody("""{"date": "2026-10-12", "amount": "$amount", "counterAccountId": "${counter.value}", "description": "Takings"$extra}""")
        }

    @Test
    fun `contract fixture - POST cash-books receipts`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordCashBookEntry(fixture, "receipts", id(8), "100.00", "", "fx-receipt")

        response.status shouldBe HttpStatusCode.Created
        ContractFixtures.verifyOrUpdate("contracts/gl/cash-book-entry.json", pinCashEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - POST cash-books receipts with VAT`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // 125.00 gross: 100.00 net to Sales Revenue and 25.00 VAT to the VAT control account; the book moves by the gross.
        val response = client.recordCashBookEntry(fixture, "receipts", id(8), "125.00", ""","vatCategory": "STANDARD", "vatAmount": "25.00"""", "fx-receipt-vat")

        response.status shouldBe HttpStatusCode.Created
        ContractFixtures.verifyOrUpdate("contracts/gl/cash-book-entry-with-vat.json", pinCashEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - POST cash-books payments with VAT`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // 60.00 gross: 50.00 net to Operating Expenses and 10.00 input VAT.
        val response = client.recordCashBookEntry(fixture, "payments", id(10), "60.00", ""","vatCategory": "STANDARD", "vatAmount": "10.00"""", "fx-payment-vat")

        response.status shouldBe HttpStatusCode.Created
        ContractFixtures.verifyOrUpdate("contracts/gl/cash-book-payment-with-vat.json", pinCashEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - POST cash-books receipts refused, VAT category without an amount`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordCashBookEntry(fixture, "receipts", id(8), "125.00", ""","vatCategory": "STANDARD"""", "fx-vat-incomplete")

        response.status shouldBe HttpStatusCode.BadRequest
        ContractFixtures.verifyOrUpdate("contracts/gl/cash-book-entry-vat-incomplete.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - POST cash-books receipts refused, VAT not less than the amount`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordCashBookEntry(fixture, "receipts", id(8), "25.00", ""","vatCategory": "STANDARD", "vatAmount": "25.00"""", "fx-vat-exceeds")

        response.status shouldBe HttpStatusCode.BadRequest
        ContractFixtures.verifyOrUpdate("contracts/gl/cash-book-entry-vat-exceeds-amount.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - POST cash-books receipts refused, VAT on an account that is not income`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // id(10) is an expense account: a receipt's VAT needs an income account.
        val response = client.recordCashBookEntry(fixture, "receipts", id(10), "125.00", ""","vatCategory": "STANDARD", "vatAmount": "25.00"""", "fx-vat-not-applicable")

        response.status shouldBe HttpStatusCode.BadRequest
        ContractFixtures.verifyOrUpdate("contracts/gl/cash-book-entry-vat-not-applicable.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - POST cash-books receipts refused, the Company has no VAT control account`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        Period.create(fixture.company.id, PeriodType.MONTH, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), periodId).also { it.open(); fixture.periodRepository.save(it) }
        Account.create(fixture.company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", id = id(1), cashBookKind = CashBookKind.CASH).also { fixture.accountRepository.save(it) }
        Account.create(fixture.company.id, AccountType.REVENUE, null, "4000", "Sales Revenue", id = id(8)).also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordCashBookEntry(fixture, "receipts", id(8), "125.00", ""","vatCategory": "STANDARD", "vatAmount": "25.00"""", "fx-vat-no-control")

        response.status shouldBe HttpStatusCode.Conflict
        ContractFixtures.verifyOrUpdate("contracts/gl/cash-book-entry-vat-control-account-not-configured.json", response.bodyAsText())
    }


    // --- Paying VAT to the authority (2026-10-22, GL-T-30, GL-FR-TAX-9, GL-FR-CTR-2) ---

    @Test
    fun `contract fixture - POST cash-books vat-settlement`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // 150.00 paid to the authority from the cash book: Dr VAT Control, Cr Cash, the VAT line tagged SETTLEMENT.
        val response = client.post("/api/companies/${fixture.company.id.value}/cash-books/${id(1).value}/vat-settlement") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            header("Idempotency-Key", "fx-vat-settlement")
            contentType(ContentType.Application.Json)
            setBody("""{"date": "2026-10-12", "amount": "150.00", "direction": "PAID", "description": "Q3 VAT"}""")
        }

        response.status shouldBe HttpStatusCode.Created
        ContractFixtures.verifyOrUpdate("contracts/gl/vat-settlement.json", pinCashEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - POST cash-books payments refused, the VAT control account is paid through vat-settlement`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordCashBookEntry(fixture, "payments", id(6), "150.00", "", "fx-vat-account-refused")

        response.status shouldBe HttpStatusCode.Conflict
        ContractFixtures.verifyOrUpdate("contracts/gl/cash-book-entry-counter-account-not-allowed-vat.json", response.bodyAsText())
    }

    // --- record-sale with the VAT worked out by the caller (2026-10-22, GL-T-26, GL-FR-TAX-7, GL-FR-CTR-2) ---

    @Test
    fun `contract fixture - POST sales record-sale with the VAT amount on every line`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        Account.create(fixture.company.id, AccountType.REVENUE, null, "4100", "Service Income", id = id(19)).also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // The caller (SOP) worked the VAT out: 200.00 on 1000.00 and 20.00 on 400.00. GL posts what it is sent and reads no rate.
        val response = client.postSale(
            fixture,
            """[{"netAmount": "1000.00", "vatCategory": "STANDARD", "accountId": "${id(8).value}", "vatAmount": "200.00"},
               |{"netAmount": "400.00", "vatCategory": "REDUCED", "accountId": "${id(19).value}", "vatAmount": "20.00"}]""".trimMargin()
        )

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/record-sale-finished-lines.json", pinEntryId(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - POST sales record-sale refused, the VAT amount on some lines and not others`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postSale(
            fixture,
            """[{"netAmount": "1000.00", "vatCategory": "STANDARD", "accountId": "${id(8).value}", "vatAmount": "200.00"},
               |{"netAmount": "400.00", "vatCategory": "REDUCED", "accountId": "${id(8).value}"}]""".trimMargin()
        )

        response.status shouldBe HttpStatusCode.BadRequest
        ContractFixtures.verifyOrUpdate("contracts/gl/record-sale-vat-amount-on-every-line.json", response.bodyAsText())
    }

    // --- The journal list (2026-10-22, GL-T-37, GL-FR-LED-9, GL-FR-CTR-2; standard S3, F22-26): link fields and recording time ---

    @Test
    fun `contract fixture - GET journal-entries with a reversed entry and its reversal`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        Period.create(fixture.company.id, PeriodType.MONTH, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), periodId).also { it.open(); fixture.periodRepository.save(it) }
        Account.create(fixture.company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", id = id(1), cashBookKind = CashBookKind.CASH).also { fixture.accountRepository.save(it) }
        Account.create(fixture.company.id, AccountType.REVENUE, null, "4000", "Sales Revenue", id = id(8)).also { fixture.accountRepository.save(it) }
        fun stamp(entry: JournalEntry, seq: Long, at: String) = JournalEntry.reconstitute(
            entry.id, entry.periodId, entry.date, entry.lines, entry.source, entry.description, entry.status, entry.reversalOfEntryId,
            seq, java.time.Instant.parse(at)
        )
        fun receipt(entryId: String, amount: String, description: String) = JournalEntry.create(
            periodId, LocalDate.parse("2026-10-05"),
            listOf(
                JournalLine(id(1), Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
                JournalLine(id(8), Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.CASH_BOOK, description, JournalEntryId(UUID.fromString(entryId))
        ).also { it.post() }
        val original = receipt("00000000-0000-4000-8000-0000000000a1", "100.00", "Takings")
        val reversal = original.reverse(java.time.Instant.parse("2026-10-06T09:00:00Z"), JournalEntryId(UUID.fromString("00000000-0000-4000-8000-0000000000a2")), "Undo of Takings")!!
        val plain = receipt("00000000-0000-4000-8000-0000000000a3", "40.00", "Later takings")
        fixture.journalEntryRepository.save(stamp(original, 1, "2026-10-05T08:00:00Z"))
        fixture.journalEntryRepository.save(stamp(reversal, 2, "2026-10-06T09:00:00Z"))
        fixture.journalEntryRepository.save(stamp(plain, 3, "2026-10-07T10:30:00Z"))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.fetch(fixture, "journal-entries")

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/journal-entries.json", response.bodyAsText())
    }

    // --- Who, why and what document on a posting (V36; 2026-10-22, GL-T-38, GL-T-31, GL-FR-AUD-4, GL-FR-CTR-2): opt-in with ?include=audit ---

    @Test
    fun `contract fixture - GET account postings with include audit`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        // One receipt recorded through the door by a person, with the document it came from and the rate row used: it carries the audit fields.
        // The seeded entries were stored before V36, so they show null: that is what every old entry will read.
        val receipt = client.post("/api/companies/${fixture.company.id.value}/cash-books/${id(1).value}/receipts") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            header("Idempotency-Key", "fx-audit-receipt")
            contentType(ContentType.Application.Json)
            setBody(
                """{"date": "2026-10-11", "amount": "125.00", "counterAccountId": "${id(8).value}", "description": "Takings",
                    |"vatCategory": "STANDARD", "vatAmount": "25.00",
                    |"sourceReference": {"type": "CASH_BOOK_ENTRY", "id": "cb-2026-0042", "number": "RCT-0042"},
                    |"vatBasis": {"ratePercent": "20", "rulebookVersion": 41, "effectiveFrom": "2011-01-04"}}""".trimMargin()
            )
        }
        receipt.status shouldBe HttpStatusCode.Created

        val response = client.fetch(fixture, "accounts/${id(1).value}/postings?from=2026-10-04&to=2026-10-12&include=audit")

        response.status shouldBe HttpStatusCode.OK
        val pinnedActor = pinEntryIds(response.bodyAsText()).replace(Regex("\"recordedBy\":\"USER:[0-9a-f-]{36}\""), "\"recordedBy\":\"USER:00000000-0000-4000-8000-0000000000b9\"")
        ContractFixtures.verifyOrUpdate("contracts/gl/account-postings-audit.json", pinnedActor)
    }

    @Test
    fun `contract fixture - GET journal-entries with include audit`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        Period.create(fixture.company.id, PeriodType.MONTH, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), periodId).also { it.open(); fixture.periodRepository.save(it) }
        Account.create(fixture.company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", id = id(1), cashBookKind = CashBookKind.CASH).also { fixture.accountRepository.save(it) }
        Account.create(fixture.company.id, AccountType.REVENUE, null, "4000", "Sales Revenue", id = id(8)).also { fixture.accountRepository.save(it) }
        fun receipt(entryId: String, description: String) = JournalEntry.create(
            periodId, LocalDate.parse("2026-10-05"),
            listOf(
                JournalLine(id(1), Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
                JournalLine(id(8), Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.CASH_BOOK, description, JournalEntryId(UUID.fromString(entryId))
        ).also { it.post() }
        fun stamp(entry: JournalEntry, seq: Long, at: String, provenance: EntryProvenance?) = JournalEntry.reconstitute(
            entry.id, entry.periodId, entry.date, entry.lines, entry.source, entry.description, entry.status, entry.reversalOfEntryId,
            seq, java.time.Instant.parse(at), provenance
        )
        val original = receipt("00000000-0000-4000-8000-0000000000b1", "Takings")
        val reversal = original.reverse(java.time.Instant.parse("2026-10-06T09:00:00Z"), JournalEntryId(UUID.fromString("00000000-0000-4000-8000-0000000000b2")), "Undo of Takings")!!
        fixture.journalEntryRepository.save(
            stamp(
                original, 1, "2026-10-05T08:00:00Z",
                EntryProvenance(
                    "USER:00000000-0000-4000-8000-0000000000b9", null, null,
                    SourceReference(SourceReferenceType.CASH_BOOK_ENTRY, "cb-2026-0042", "RCT-0042"), VatBasis("20", 41, LocalDate.parse("2011-01-04"))
                )
            )
        )
        fixture.journalEntryRepository.save(
            stamp(reversal, 2, "2026-10-06T09:00:00Z", EntryProvenance("SERVICE:cbb", "00000000-0000-4000-8000-0000000000b8", "Posted to the wrong book", null, null))
        )
        // An entry stored before V36: every audit field is null.
        fixture.journalEntryRepository.save(stamp(receipt("00000000-0000-4000-8000-0000000000b3", "Older takings"), 0, "2026-10-04T10:30:00Z", null))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.fetch(fixture, "journal-entries?include=audit")

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/journal-entries-audit.json", response.bodyAsText())
    }

    // --- The VAT lines read Tax computes a return from (2026-10-22, GL-T-32, GL-FR-TAX-10, GL-FR-CTR-2; consumer Tax T-TS-13) ---

    /** A quarter on the VAT Control Account: a sale, a purchase, a cash-book VAT receipt, the quarter's remittance, a reversed entry and its reversal, and an untagged manual debit. */
    private fun Fixture.seedVatLines() {
        Period.create(company.id, PeriodType.MONTH, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"), periodId).also { it.open(); periodRepository.save(it) }
        Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", id = id(2), cashBookKind = CashBookKind.BANK).also { accountRepository.save(it) }
        Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2150", "VAT Control Account", id = id(6)).also { accountRepository.save(it) }
        fun vatEntry(n: Int, date: String, vatSide: TransactionSide, amount: String, direction: String?, category: String?, source: JournalSource = JournalSource.INTEGRATION) =
            JournalEntry.create(
                periodId, LocalDate.parse(date),
                listOf(
                    JournalLine(id(2), Money(BigDecimal(amount), GBP), if (vatSide == TransactionSide.CREDIT) TransactionSide.DEBIT else TransactionSide.CREDIT),
                    JournalLine(
                        id(6), Money(BigDecimal(amount), GBP), vatSide,
                        listOfNotNull(direction?.let { DimensionType.VAT_DIRECTION to it }, category?.let { DimensionType.VAT_CATEGORY to it }).toMap()
                    )
                ),
                source, "VAT entry $n", JournalEntryId(UUID.fromString("00000000-0000-4000-8000-%012d".format(900 + n)))
            ).also { it.post() }
        fun store(entry: JournalEntry, seq: Long, provenance: EntryProvenance? = null) =
            journalEntryRepository.save(
                JournalEntry.reconstitute(
                    entry.id, entry.periodId, entry.date, entry.lines, entry.source, entry.description, entry.status, entry.reversalOfEntryId,
                    recordedSeq = seq, provenance = provenance
                )
            )
        store(
            vatEntry(1, "2026-10-12", TransactionSide.CREDIT, "200.00", "OUTPUT", "STANDARD"), 1,
            EntryProvenance("SERVICE:sop", null, null, SourceReference(SourceReferenceType.SALES_INVOICE, "sale-77", "INV-0077"), null)
        )
        store(vatEntry(2, "2026-10-13", TransactionSide.DEBIT, "50.00", "INPUT", "REDUCED"), 2)
        store(
            vatEntry(3, "2026-10-20", TransactionSide.CREDIT, "25.00", "OUTPUT", "STANDARD", JournalSource.CASH_BOOK), 3,
            EntryProvenance("SERVICE:cbb", null, null, SourceReference(SourceReferenceType.CASH_BOOK_ENTRY, "cb-2026-0042", "RCT-0042"), null)
        )
        val undone = vatEntry(4, "2026-11-02", TransactionSide.CREDIT, "40.00", "OUTPUT", "STANDARD")
        val reversal = undone.reverse(java.time.Instant.parse("2026-11-03T09:00:00Z"), JournalEntryId(UUID.fromString("00000000-0000-4000-8000-000000000905")), "Undo of VAT entry 4")!!
        store(undone, 4)
        store(reversal, 5)
        store(vatEntry(6, "2026-11-20", TransactionSide.DEBIT, "175.00", "SETTLEMENT", null, JournalSource.CASH_BOOK), 6)
        store(vatEntry(7, "2026-12-15", TransactionSide.DEBIT, "10.00", null, null, JournalSource.MANUAL), 7)
    }

    @Test
    fun `contract fixture - GET vat-lines for a quarter`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seedVatLines()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.fetch(fixture, "vat-lines?from=2026-10-01&to=2026-12-31")

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/vat-lines.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - GET vat-lines first page with a cursor`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seedVatLines()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.fetch(fixture, "vat-lines?from=2026-10-01&to=2026-12-31&limit=3")

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/vat-lines-page.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - GET vat-lines refused, no VAT control account`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.fetch(fixture, "vat-lines?from=2026-10-01&to=2026-12-31")

        response.status shouldBe HttpStatusCode.Conflict
        ContractFixtures.verifyOrUpdate("contracts/gl/vat-lines-no-control-account.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - GET jurisdictions`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/jurisdictions") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
        }

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/jurisdictions.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - GET company`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/company.json", response.bodyAsText())
    }

    @Test
    fun `contract fixture - POST purchasing record-obligation`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        // Rent and electricity are added here rather than in seed(), so no other fixture (reports list accounts) changes.
        val rent = Account.create(fixture.company.id, AccountType.EXPENSE, null, "5100", "Rent", id = id(14)).also { fixture.accountRepository.save(it) }
        val electricity = Account.create(fixture.company.id, AccountType.EXPENSE, null, "5110", "Electricity", id = id(15)).also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // Two lines on two accounts (rent at the standard rate, electricity at the reduced rate); no request-level account.
        val response = client.post("/api/purchasing/record-obligation") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${periodId.value}", "date": "2026-10-12",
                    |"supplierId": "$supplier", "apControlAccountId": "${id(5).value}", "vatControlAccountId": "${id(6).value}",
                    |"lines": [{"netAmount": "1000.00", "vatCategory": "STANDARD", "accountId": "${rent.id.value}"},
                    |          {"netAmount": "400.00", "vatCategory": "REDUCED", "accountId": "${electricity.id.value}"}],
                    |"currency": "GBP", "description": "Synthetic"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        // The only non-deterministic field is the new entry's id: pinned to a fixed value so the fixture is reproducible.
        val text = response.bodyAsText().replace(Regex("\"journalEntryId\":\"[0-9a-f-]{36}\""), "\"journalEntryId\":\"00000000-0000-4000-8000-0000000000e2\"")
        ContractFixtures.verifyOrUpdate("contracts/gl/supplier-obligation.json", text)
    }

    @Test
    fun `contract fixture - POST payroll record-pay-run-journal`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        // Added here rather than in seed(), so no other fixture (reports list accounts) changes.
        val netPay = Account.create(fixture.company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2210", "Net Pay Payable", id = id(16)).also { fixture.accountRepository.save(it) }
        val paye = Account.create(fixture.company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2220", "PAYE Payable", id = id(17)).also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/payroll/record-pay-run-journal") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${periodId.value}", "date": "2026-10-19", "currency": "GBP",
                    |"reference": "RUN-2026-10", "description": "Synthetic pay run",
                    |"lines": [{"element": "SALARIES", "kind": "EXPENSE", "side": "DEBIT", "amount": "1000.00", "accountId": "${id(10).value}"},
                    |          {"element": "NET_PAY_PAYABLE", "kind": "LIABILITY", "side": "CREDIT", "amount": "800.00", "accountId": "${netPay.id.value}"},
                    |          {"element": "PAYE_PAYABLE", "kind": "LIABILITY", "side": "CREDIT", "amount": "200.00", "accountId": "${paye.id.value}"}]}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        // The only non-deterministic field is the new entry's id: pinned to a fixed value so the fixture is reproducible.
        val text = response.bodyAsText().replace(Regex("\"journalEntryId\":\"[0-9a-f-]{36}\""), "\"journalEntryId\":\"00000000-0000-4000-8000-0000000000e3\"")
        ContractFixtures.verifyOrUpdate("contracts/gl/payroll-pay-run-journal.json", text)
    }

    /** Entry ids are random in the seeded Company: pinned to a sequence by first appearance so the fixture is reproducible. */
    private fun pinEntryIds(text: String): String {
        val pinned = linkedMapOf<String, String>()
        Regex("\"entryId\":\"([0-9a-f-]{36})\"").findAll(text).forEach { pinned.getOrPut(it.groupValues[1]) { "00000000-0000-4000-8000-%012d".format(300 + pinned.size) } }
        return pinned.entries.fold(text) { acc, (real, fixed) -> acc.replace(real, fixed) }
    }

    @Test
    fun `contract fixture - GET account postings`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.fetch(fixture, "accounts/${id(1).value}/postings?from=2026-10-04&to=2026-10-09")

        response.status shouldBe HttpStatusCode.OK
        ContractFixtures.verifyOrUpdate("contracts/gl/account-postings.json", pinEntryIds(response.bodyAsText()))
    }

    @Test
    fun `contract fixture - GET account balance`() = contract("contracts/gl/account-balance.json", "accounts/${id(1).value}/balance?asOf=2026-10-31")

    @Test
    fun `contract fixture - POST purchasing record-credit-note`() = testApplication {
        val fixture = Fixture(configureAccounts = false, openPeriod = false)
        fixture.seed()
        // 4920 is added here rather than in seed(), so no other fixture (reports list accounts) changes.
        val discounts = Account.create(fixture.company.id, AccountType.REVENUE, null, "4920", "Discounts Received", id = id(13)).also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/purchasing/record-credit-note") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${periodId.value}", "date": "2026-10-12",
                    |"supplierId": "$supplier", "kind": "DISCOUNT", "apControlAccountId": "${id(5).value}",
                    |"discountsReceivedAccountId": "${discounts.id.value}", "vatControlAccountId": "${id(6).value}",
                    |"lines": [{"netAmount": "100.00", "vatCategory": "STANDARD"}, {"netAmount": "50.00", "vatCategory": "REDUCED"}],
                    |"currency": "GBP", "originalSupplyDate": "2026-10-02", "description": "Synthetic"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        // The only non-deterministic field is the new entry's id: pinned to a fixed value so the fixture is reproducible.
        val text = response.bodyAsText().replace(Regex("\"journalEntryId\":\"[0-9a-f-]{36}\""), "\"journalEntryId\":\"00000000-0000-4000-8000-0000000000e1\"")
        ContractFixtures.verifyOrUpdate("contracts/gl/supplier-credit-note.json", text)
    }

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
