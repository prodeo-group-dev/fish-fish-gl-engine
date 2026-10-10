package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.AddMissingStandardAccountsUseCase
import com.theprodeogroup.fish.application.CancelBankReconciliationUseCase
import com.theprodeogroup.fish.application.CompleteBankReconciliationUseCase
import com.theprodeogroup.fish.application.ComputeBankReconciliationUseCase
import com.theprodeogroup.fish.application.ListBankReconciliationsUseCase
import com.theprodeogroup.fish.application.MatchBankReconciliationLineUseCase
import com.theprodeogroup.fish.application.StartBankReconciliationUseCase
import com.theprodeogroup.fish.application.UnmatchBankReconciliationLineUseCase
import com.theprodeogroup.fish.application.RecordCashBookEntryUseCase
import com.theprodeogroup.fish.application.RecordCashBookTransferUseCase
import com.theprodeogroup.fish.application.UndoCashBookEntryUseCase
import com.theprodeogroup.fish.application.ListCounterAccountsUseCase
import com.theprodeogroup.fish.application.ChangeCashBookKindUseCase
import com.theprodeogroup.fish.application.ComputeCashBookUseCase
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

private val GBP: Currency = Currency.getInstance("GBP")
private const val ADMIN_EMAIL = "founder@example.com"
private val TODAY: LocalDate = LocalDate.now()

/** The cash and bank book routes (CashBookRoutes.kt, docs/GL_Cash_And_Bank_Books_SRS.md). */
class CashBookPoliciesRouteTest {

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
        val cashBookReconciliations = FakeBankReconciliationRepository()
        val startBankReconciliationUseCase = StartBankReconciliationUseCase(companyRepository, accountRepository, journalEntryRepository, cashBookReconciliations)
        val matchBankReconciliationLineUseCase = MatchBankReconciliationLineUseCase(companyRepository, journalEntryRepository, cashBookReconciliations)
        val unmatchBankReconciliationLineUseCase = UnmatchBankReconciliationLineUseCase(companyRepository, journalEntryRepository, cashBookReconciliations)
        val completeBankReconciliationUseCase = CompleteBankReconciliationUseCase(companyRepository, journalEntryRepository, cashBookReconciliations, enforceBalanceTieOut = false)
        val cancelBankReconciliationUseCase = CancelBankReconciliationUseCase(companyRepository, journalEntryRepository, cashBookReconciliations)
        val computeBankReconciliationUseCase = ComputeBankReconciliationUseCase(companyRepository, journalEntryRepository, cashBookReconciliations)
        val listBankReconciliationsUseCase = ListBankReconciliationsUseCase(companyRepository, journalEntryRepository, cashBookReconciliations)
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

        fun installInto(app: Application, policies: CashBookPolicies = CashBookPolicies()) {
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
                computeCashBookUseCase = computeCashBookUseCase,
                changeCashBookKindUseCase = changeCashBookKindUseCase,
                addMissingStandardAccountsUseCase = addMissingStandardAccountsUseCase,
                cashBookPolicies = policies,
                startBankReconciliationUseCase = startBankReconciliationUseCase,
                matchBankReconciliationLineUseCase = matchBankReconciliationLineUseCase,
                unmatchBankReconciliationLineUseCase = unmatchBankReconciliationLineUseCase,
                completeBankReconciliationUseCase = completeBankReconciliationUseCase,
                cancelBankReconciliationUseCase = cancelBankReconciliationUseCase,
                computeBankReconciliationUseCase = computeBankReconciliationUseCase,
                listBankReconciliationsUseCase = listBankReconciliationsUseCase,
                recordCashBookEntryUseCase = recordCashBookEntryUseCase,
                listCounterAccountsUseCase = listCounterAccountsUseCase,
                recordCashBookTransferUseCase = recordCashBookTransferUseCase,
                undoCashBookEntryUseCase = undoCashBookEntryUseCase
            )
        }
    }


    private fun Fixture.account(code: String, type: AccountType): Account =
        Account.create(company.id, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, "Acct $code").also { accountRepository.save(it) }

    private fun Fixture.cashBook(code: String, kind: CashBookKind): Account =
        Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, code, "Acct $code", cashBookKind = kind).also { accountRepository.save(it) }

    private fun io.ktor.client.request.HttpRequestBuilder.signedIn(fixture: Fixture) {
        header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
        header("X-Tenant-Id", fixture.tenant.value.toString())
    }

    private class Logged {
        val lines = mutableListOf<String>()
        fun policies(settlement: PolicyMode, reconciliation: PolicyMode) = CashBookPolicies(settlement, reconciliation) { lines += it }
    }

    private suspend fun io.ktor.client.HttpClient.collect(fixture: Fixture, settlement: Account) =
        post("/api/sales/record-collection") {
            signedIn(fixture)
            contentType(ContentType.Application.Json)
            setBody("""{"companyId":"${fixture.company.id.value}","periodId":"${fixture.period!!.id.value}","date":"$TODAY","settlementAccountId":"${settlement.id.value}","arControlAccountId":"${fixture.arAccount!!.id.value}","amount":"10.00","currency":"GBP","customerId":"${UUID.randomUUID()}"}""")
        }

    private suspend fun io.ktor.client.HttpClient.pay(fixture: Fixture, settlement: Account, ap: Account) =
        post("/api/purchasing/record-payment") {
            signedIn(fixture)
            contentType(ContentType.Application.Json)
            setBody("""{"companyId":"${fixture.company.id.value}","periodId":"${fixture.period!!.id.value}","date":"$TODAY","apControlAccountId":"${ap.id.value}","settlementAccountId":"${settlement.id.value}","amount":"10.00","currency":"GBP","supplierId":"${UUID.randomUUID()}"}""")
        }

    private suspend fun io.ktor.client.HttpClient.payRun(fixture: Fixture, cash: Account, wages: Account, salaries: Account) =
        post("/api/payroll/record-pay-run") {
            signedIn(fixture)
            contentType(ContentType.Application.Json)
            setBody("""{"companyId":"${fixture.company.id.value}","periodId":"${fixture.period!!.id.value}","date":"$TODAY","totalWages":"10.00","totalSalaries":"20.00","currency":"GBP","wagesExpenseAccountId":"${wages.id.value}","salariesExpenseAccountId":"${salaries.id.value}","cashAccountId":"${cash.id.value}"}""")
        }

    private suspend fun io.ktor.client.HttpClient.startReconciliation(fixture: Fixture, account: Account) =
        post("/api/companies/${fixture.company.id.value}/bank-reconciliations") {
            signedIn(fixture)
            contentType(ContentType.Application.Json)
            setBody("""{"accountId":"${account.id.value}","statementDate":"$TODAY","statementEndingBalance":"0.00","currency":"GBP","lines":[]}""")
        }

    // ---- settlement account (T15), log-first ----

    @Test
    fun `given log mode, when a collection settles into an account that is not a cash or bank book, then it posts exactly as before and one WOULD REFUSE line is logged`() = testApplication {
        val fixture = Fixture()
        val logged = Logged()
        application { fixture.installInto(this, logged.policies(PolicyMode.LOG, PolicyMode.LOG)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.collect(fixture, fixture.cashAccount!!)

        response.status shouldBe HttpStatusCode.OK
        fixture.journalEntryRepository.findAllByAccount(fixture.cashAccount!!.id).size shouldBe 1
        logged.lines.size shouldBe 1
        logged.lines.single() shouldBe
            "WOULD REFUSE settlement_account_not_a_cash_book service=person company=${fixture.company.id.value} account=${fixture.cashAccount!!.id.value} route=POST /sales/record-collection"
    }

    private fun settleIntoABook(mode: PolicyMode) = testApplication {
        val fixture = Fixture()
        val logged = Logged()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        val ap = fixture.account("2000", AccountType.LIABILITY)
        val wages = fixture.account("5200", AccountType.EXPENSE)
        val salaries = fixture.account("5300", AccountType.EXPENSE)
        application { fixture.installInto(this, logged.policies(mode, mode)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.collect(fixture, bank).status shouldBe HttpStatusCode.OK
        client.pay(fixture, bank, ap).status shouldBe HttpStatusCode.OK
        client.payRun(fixture, bank, wages, salaries).status shouldBe HttpStatusCode.OK
        logged.lines shouldBe emptyList()
    }

    @Test
    fun `given a cash or bank book, when a collection, a payment and a pay run settle into it, then nothing is logged in log mode`() = settleIntoABook(PolicyMode.LOG)

    @Test
    fun `given a cash or bank book, when a collection, a payment and a pay run settle into it, then nothing is logged and nothing is refused in enforce mode`() = settleIntoABook(PolicyMode.ENFORCE)

    @Test
    fun `given log mode, when a payment and a pay run settle into an ordinary account, then both post and are logged with their own route`() = testApplication {
        val fixture = Fixture()
        val logged = Logged()
        val ap = fixture.account("2000", AccountType.LIABILITY)
        val wages = fixture.account("5200", AccountType.EXPENSE)
        val salaries = fixture.account("5300", AccountType.EXPENSE)
        application { fixture.installInto(this, logged.policies(PolicyMode.LOG, PolicyMode.LOG)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.pay(fixture, fixture.cashAccount!!, ap).status shouldBe HttpStatusCode.OK
        client.payRun(fixture, fixture.cashAccount!!, wages, salaries).status shouldBe HttpStatusCode.OK

        logged.lines.map { it.substringAfter("route=") } shouldBe listOf("POST /purchasing/record-payment", "POST /payroll/record-pay-run")
    }

    @Test
    fun `given enforce mode, when a collection, a payment or a pay run settles into an ordinary account, then each is a 409 settlement_account_not_a_cash_book and nothing is posted`() = testApplication {
        val fixture = Fixture()
        val logged = Logged()
        val ap = fixture.account("2000", AccountType.LIABILITY)
        val wages = fixture.account("5200", AccountType.EXPENSE)
        val salaries = fixture.account("5300", AccountType.EXPENSE)
        application { fixture.installInto(this, logged.policies(PolicyMode.ENFORCE, PolicyMode.LOG)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        for (response in listOf(client.collect(fixture, fixture.cashAccount!!), client.pay(fixture, fixture.cashAccount!!, ap), client.payRun(fixture, fixture.cashAccount!!, wages, salaries))) {
            response.status shouldBe HttpStatusCode.Conflict
            response.body<ErrorResponseDto>().error shouldBe "settlement_account_not_a_cash_book"
        }
        fixture.journalEntryRepository.findAllByAccount(fixture.cashAccount!!.id) shouldBe emptyList()
        logged.lines.all { it.startsWith("REFUSED settlement_account_not_a_cash_book") } shouldBe true
    }

    @Test
    fun `given enforce mode, when the settlement account does not exist, then the policy does not judge it - the use case refuses it as it always did`() = testApplication {
        val fixture = Fixture()
        val logged = Logged()
        application { fixture.installInto(this, logged.policies(PolicyMode.ENFORCE, PolicyMode.ENFORCE)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val ghost = Account.create(fixture.company.id, AccountType.ASSET, AccountClassification.CURRENT, "1999", "Never saved")

        val response = client.collect(fixture, ghost)

        response.status shouldBe HttpStatusCode.NotFound
        logged.lines shouldBe emptyList()
    }

    // ---- bank-only reconciliation (T14), log-first ----

    @Test
    fun `given log mode, when a reconciliation is started on a cash account, then it starts as before and a WOULD REFUSE line is logged`() = testApplication {
        val fixture = Fixture()
        val logged = Logged()
        val cash = fixture.cashBook("1020", CashBookKind.CASH)
        application { fixture.installInto(this, logged.policies(PolicyMode.LOG, PolicyMode.LOG)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.startReconciliation(fixture, cash)

        response.status shouldBe HttpStatusCode.OK
        logged.lines.single() shouldBe
            "WOULD REFUSE reconciliation_requires_bank_account service=person company=${fixture.company.id.value} account=${cash.id.value} route=POST /companies/{companyId}/bank-reconciliations"
    }

    private fun reconcileABank(mode: PolicyMode) = testApplication {
        val fixture = Fixture()
        val logged = Logged()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        application { fixture.installInto(this, logged.policies(mode, mode)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.startReconciliation(fixture, bank).status shouldBe HttpStatusCode.OK
        logged.lines shouldBe emptyList()
    }

    @Test
    fun `given a bank account, when a reconciliation is started, then nothing is logged in log mode`() = reconcileABank(PolicyMode.LOG)

    @Test
    fun `given a bank account, when a reconciliation is started, then nothing is logged and it is not refused in enforce mode`() = reconcileABank(PolicyMode.ENFORCE)

    @Test
    fun `given enforce mode, when a reconciliation is started on a cash or plain account, then it is a 409 reconciliation_requires_bank_account and none is created`() = testApplication {
        val fixture = Fixture()
        val logged = Logged()
        val cash = fixture.cashBook("1020", CashBookKind.CASH)
        application { fixture.installInto(this, logged.policies(PolicyMode.LOG, PolicyMode.ENFORCE)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        for (account in listOf(cash, fixture.cashAccount!!)) {
            val response = client.startReconciliation(fixture, account)
            response.status shouldBe HttpStatusCode.Conflict
            response.body<ErrorResponseDto>().error shouldBe "reconciliation_requires_bank_account"
        }
        client.get("/api/companies/${fixture.company.id.value}/bank-reconciliations") { signedIn(fixture) }.body<ListBankReconciliationsResponseDto>().reconciliations shouldBe emptyList()
    }

    @Test
    fun `given the environment values, then only the word enforce switches a policy on`() {
        PolicyMode.fromEnvironment(null) shouldBe PolicyMode.LOG
        PolicyMode.fromEnvironment("") shouldBe PolicyMode.LOG
        PolicyMode.fromEnvironment("log") shouldBe PolicyMode.LOG
        PolicyMode.fromEnvironment("true") shouldBe PolicyMode.LOG
        PolicyMode.fromEnvironment("enforce") shouldBe PolicyMode.ENFORCE
        PolicyMode.fromEnvironment(" ENFORCE ") shouldBe PolicyMode.ENFORCE
    }
}
