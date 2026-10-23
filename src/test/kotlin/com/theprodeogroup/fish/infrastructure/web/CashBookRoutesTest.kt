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
import io.ktor.client.statement.bodyAsText
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
class CashBookRoutesTest {

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
        val computeCashBalanceSeriesUseCase = ComputeCashBalanceSeriesUseCase(companyRepository, accountRepository, journalEntryRepository)
        val cashBookReconciliations = FakeBankReconciliationRepository()
        val computeCashBookUseCase = ComputeCashBookUseCase(companyRepository, accountRepository, journalEntryRepository, periodRepository, cashBookReconciliations)
        val changeCashBookKindUseCase = ChangeCashBookKindUseCase(accountRepository)
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

        fun installInto(app: Application, allowListMode: ServiceAllowListMode = ServiceAllowListMode.ENFORCE) {
            app.fishModule(
                serviceVerifier = TestJwtSupport.serviceVerifier("sop"),
                serviceAllowListMode = allowListMode,
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
                computeCashBookUseCase = computeCashBookUseCase,
                changeCashBookKindUseCase = changeCashBookKindUseCase,
                addMissingStandardAccountsUseCase = addMissingStandardAccountsUseCase,
                recordCashBookEntryUseCase = recordCashBookEntryUseCase,
                listCounterAccountsUseCase = listCounterAccountsUseCase,
                recordCashBookTransferUseCase = recordCashBookTransferUseCase,
                undoCashBookEntryUseCase = undoCashBookEntryUseCase,
                cbbServiceVerifier = TestJwtSupport.serviceVerifier("cbb")
            )
        }
    }


    private fun Fixture.account(code: String, type: AccountType): Account =
        Account.create(company.id, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, "Acct $code").also { accountRepository.save(it) }

    private fun Fixture.cashBook(code: String, kind: CashBookKind?, type: AccountType = AccountType.ASSET): Account =
        Account.create(
            company.id, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, "Acct $code",
            cashBookKind = kind
        ).also { accountRepository.save(it) }

    private fun Fixture.post(debit: Account, credit: Account, amount: String, date: LocalDate = TODAY, description: String? = null): JournalEntry {
        val entry = JournalEntry.create(
            period!!.id, date,
            listOf(
                JournalLine(debit.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
                JournalLine(credit.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, description
        )
        entry.post()
        journalEntryRepository.save(entry)
        return entry
    }

    /** A call as the Cash and Bank Books service, acting for the founding admin: the ONLY caller allowed to undo (Femi, 2026-10-23: "Restrict to CBB now"). */
    private fun io.ktor.client.request.HttpRequestBuilder.asCbb(fixture: Fixture) {
        header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signServiceToken("cbb")}")
        header("X-Acting-User-Id", fixture.adminUser.id.value.toString())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.signedIn(fixture: Fixture, email: String = ADMIN_EMAIL) {
        header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(email)}")
        header("X-Tenant-Id", fixture.tenant.value.toString())
    }

    @Test
    fun `given a cash book and a bank book, when the list is read, then each shows kind, balance, currency, default flag, reconcilable flag and last entry date`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        fixture.cashBook("1100", null) // an ordinary asset account: not a book
        fixture.post(cash, fixture.revenueAccount!!, "120.00", TODAY.minusDays(3))
        fixture.post(bank, fixture.revenueAccount!!, "800.00", TODAY.minusDays(1))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/cash-books") { signedIn(fixture) }

        response.status shouldBe HttpStatusCode.OK
        val items: List<CashBookListItemDto> = response.body()
        items.map { it.code } shouldBe listOf("1000", "1010")
        items.map { it.kind } shouldBe listOf("CASH", "BANK")
        items.map { it.balance } shouldBe listOf("120.00", "800.00")
        items.map { it.isDefault } shouldBe listOf(true, false)
        items.map { it.reconcilable } shouldBe listOf(false, true)
        items.map { it.currency }.distinct() shouldBe listOf("GBP")
        items[0].lastEntryDate shouldBe TODAY.minusDays(3).toString()
        items[1].lastEntryDate shouldBe TODAY.minusDays(1).toString()
    }

    @Test
    fun `given receipts and a payment, when the book is read for a range, then it has the opening balance, rows with counter-accounts and a running balance, totals and canUndo`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        val rent = fixture.account("5200", AccountType.EXPENSE)
        fixture.post(bank, fixture.revenueAccount!!, "50.00", TODAY.minusDays(40)) // before the range
        val first = fixture.post(bank, fixture.revenueAccount!!, "200.00", TODAY.minusDays(5), "Takings")
        fixture.post(rent, bank, "75.00", TODAY.minusDays(2), "Rent")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/cash-books/${bank.id.value}?from=${TODAY.minusDays(10)}&to=$TODAY") { signedIn(fixture) }

        response.status shouldBe HttpStatusCode.OK
        val book: CashBookResponseDto = response.body()
        book.kind shouldBe "BANK"
        book.currency shouldBe "GBP"
        book.from shouldBe TODAY.minusDays(10).toString()
        book.to shouldBe TODAY.toString()
        book.openingBalance shouldBe "50.00"
        book.totalIn shouldBe "200.00"
        book.totalOut shouldBe "75.00"
        book.closingBalance shouldBe "175.00"
        book.rows.map { it.runningBalance } shouldBe listOf("250.00", "175.00")
        book.rows.map { it.moneyIn } shouldBe listOf("200.00", "0.00")
        book.rows.map { it.moneyOut } shouldBe listOf("0.00", "75.00")
        book.rows[0].description shouldBe "Takings"
        book.rows[0].entryId shouldBe first.id.value.toString()
        book.rows[0].counterAccounts.map { it.code } shouldBe listOf("4000")
        book.rows[1].counterAccounts.map { it.code } shouldBe listOf("5200")
        // Entries made by the general journal, not in the book, are reversed where they were made.
        book.rows.map { it.canUndo } shouldBe listOf(false, false)
        book.rows.map { it.reconciled } shouldBe listOf(false, false) // a bank account: not yet in a completed reconciliation
        book.rows[0].reference shouldBe null
    }

    @Test
    fun `given no from and to, when the book is read, then the range defaults to this month to date`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val book: CashBookResponseDto = client.get("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}") { signedIn(fixture) }.body()

        book.from shouldBe LocalDate.now().withDayOfMonth(1).toString()
        book.to shouldBe LocalDate.now().toString()
    }

    @Test
    fun `given an invalid or backwards range, when the book is read, then it is a 400`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val base = "/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}"

        client.get("$base?from=not-a-date&to=2026-10-31") { signedIn(fixture) }.status shouldBe HttpStatusCode.BadRequest
        client.get("$base?from=2026-10-31&to=2026-10-01") { signedIn(fixture) }.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given an account that is not a cash or bank account, when its book is read, then it is a 409`() = testApplication {
        val fixture = Fixture()
        val receivables = fixture.cashBook("1100", null)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/cash-books/${receivables.id.value}") { signedIn(fixture) }

        response.status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `given another Company's cash book id, when it is read through this Company, then it is a 404 and none of its data is returned`() = testApplication {
        val fixture = Fixture()
        val otherCompany = Company.create(fixture.tenant, "Second Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP).also { fixture.companyRepository.save(it) }
        val foreign = Account.create(otherCompany.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Foreign cash", cashBookKind = CashBookKind.CASH)
            .also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/cash-books/${foreign.id.value}") { signedIn(fixture) }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `given a cash book entry and its reversal, when the book is read, then both rows show, the balance nets and neither can be undone`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val original = fixture.post(cash, fixture.revenueAccount!!, "90.00", TODAY)
        val reversal = original.reverse(java.time.Instant.now())!!.also { fixture.journalEntryRepository.save(it) }
        fixture.journalEntryRepository.save(original)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val book: CashBookResponseDto = client.get("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}?from=${TODAY.minusDays(1)}&to=$TODAY") { signedIn(fixture) }.body()

        book.rows.size shouldBe 2
        book.closingBalance shouldBe "0.00"
        book.rows.map { it.canUndo } shouldBe listOf(false, false)
        book.rows.first { it.entryId == original.id.value.toString() }.reversedBy shouldBe reversal.id.value.toString()
        book.rows.first { it.entryId == reversal.id.value.toString() }.reversalOf shouldBe original.id.value.toString()
    }

    @Test
    fun `given a bank account, when its kind is changed through the route, then it is flagged and returned`() = testApplication {
        val fixture = Fixture()
        val extra = fixture.cashBook("1015", null)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.put("/api/companies/${fixture.company.id.value}/accounts/${extra.id.value}/cash-book-kind") {
            signedIn(fixture)
            contentType(ContentType.Application.Json)
            setBody("""{"cashBookKind":"BANK"}""")
        }

        response.status shouldBe HttpStatusCode.OK
        val body: AccountCashBookKindDto = response.body()
        body.cashBookKind shouldBe "BANK"
        fixture.accountRepository.findById(extra.id)!!.cashBookKind shouldBe CashBookKind.BANK
    }

    @Test
    fun `given account 1000, when its kind is changed to BANK, then it is refused with prime_cash_book_kind_fixed`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.put("/api/companies/${fixture.company.id.value}/accounts/${cash.id.value}/cash-book-kind") {
            signedIn(fixture)
            contentType(ContentType.Application.Json)
            setBody("""{"cashBookKind":"BANK"}""")
        }

        response.status shouldBe HttpStatusCode.Conflict
        response.body<ErrorResponseDto>().error shouldBe "prime_cash_book_kind_fixed"
        fixture.accountRepository.findById(cash.id)!!.cashBookKind shouldBe CashBookKind.CASH
    }

    @Test
    fun `given a cash book or a bank account, when its kind is changed to the other, then it is refused with cash_book_kind_fixed and unchanged - GL-T-18, Femi 2026-10-23`() = testApplication {
        val fixture = Fixture()
        val till = fixture.cashBook("1015", CashBookKind.CASH)
        val bank = fixture.cashBook("1016", CashBookKind.BANK)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val base = "/api/companies/${fixture.company.id.value}/accounts"

        val toBank = client.put("$base/${till.id.value}/cash-book-kind") {
            signedIn(fixture); contentType(ContentType.Application.Json); setBody("""{"cashBookKind":"BANK"}""")
        }
        val toCash = client.put("$base/${bank.id.value}/cash-book-kind") {
            signedIn(fixture); contentType(ContentType.Application.Json); setBody("""{"cashBookKind":"CASH"}""")
        }

        toBank.status shouldBe HttpStatusCode.Conflict
        toBank.body<ErrorResponseDto>().error shouldBe "cash_book_kind_fixed"
        toCash.status shouldBe HttpStatusCode.Conflict
        toCash.body<ErrorResponseDto>().error shouldBe "cash_book_kind_fixed"
        fixture.accountRepository.findById(till.id)!!.cashBookKind shouldBe CashBookKind.CASH
        fixture.accountRepository.findById(bank.id)!!.cashBookKind shouldBe CashBookKind.BANK
    }

    @Test
    fun `given a bank book with entries and a balance, when the owner stops using it, then it succeeds and nothing posted is touched - Femi 2026-10-23, the owner's prerogative`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1016", CashBookKind.BANK)
        val entry = fixture.post(bank, fixture.revenueAccount!!, "800.00", TODAY.minusDays(1))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val base = "/api/companies/${fixture.company.id.value}"

        val stop = client.put("$base/accounts/${bank.id.value}/cash-book-kind") {
            signedIn(fixture); contentType(ContentType.Application.Json); setBody("""{"cashBookKind":null}""")
        }

        stop.status shouldBe HttpStatusCode.OK
        val stopped: AccountCashBookKindDto = stop.body()
        stopped.cashBookKind shouldBe null
        stopped.formerCashBookKind shouldBe "BANK"
        stopped.stoppedOn shouldBe java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        fixture.accountRepository.findById(bank.id)!!.cashBookKind shouldBe null
        fixture.accountRepository.findById(bank.id)!!.active shouldBe true
        val stored = fixture.journalEntryRepository.findById(entry.id)!!
        stored.status shouldBe com.theprodeogroup.fish.domain.common.PostingStatus.POSTED
        stored.lines.size shouldBe 2
        client.get("$base/cash-books") { signedIn(fixture) }.body<List<CashBookListItemDto>>().map { it.code } shouldBe emptyList()
        val book = client.get("$base/cash-books/${bank.id.value}") { signedIn(fixture) }
        book.status shouldBe HttpStatusCode.Conflict
        book.body<ErrorResponseDto>().error shouldBe "not_a_cash_or_bank_account"

        // CBB finds a stopped book by these two fields on the chart and the postings header, and its entries by the postings read (no kind needed).
        val chartRow = client.get("$base/accounts") { signedIn(fixture) }.body<List<AccountSummaryDto>>().first { it.accountId == bank.id.value.toString() }
        chartRow.cashBookKind shouldBe null
        chartRow.formerCashBookKind shouldBe "BANK"
        chartRow.stoppedOn shouldBe stopped.stoppedOn
        val postings = client.get("$base/accounts/${bank.id.value}/postings?from=${TODAY.minusDays(7)}&to=$TODAY") { signedIn(fixture) }
        postings.status shouldBe HttpStatusCode.OK
        val header: AccountPostingsResponseDto = postings.body()
        header.formerCashBookKind shouldBe "BANK"
        header.stoppedOn shouldBe stopped.stoppedOn
        header.postings.size shouldBe 1
    }

    @Test
    fun `given a bank account, when its kind is set to BANK again, then it is a 200 and unchanged`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1016", CashBookKind.BANK)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.put("/api/companies/${fixture.company.id.value}/accounts/${bank.id.value}/cash-book-kind") {
            signedIn(fixture); contentType(ContentType.Application.Json); setBody("""{"cashBookKind":"BANK"}""")
        }

        response.status shouldBe HttpStatusCode.OK
        response.body<AccountCashBookKindDto>().cashBookKind shouldBe "BANK"
    }

    @Test
    fun `given a revenue account or an unknown kind, when the kind is changed, then it is a 409 or a 400`() = testApplication {
        val fixture = Fixture()
        val extra = fixture.cashBook("1015", null)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val base = "/api/companies/${fixture.company.id.value}/accounts"

        client.put("$base/${fixture.revenueAccount!!.id.value}/cash-book-kind") {
            signedIn(fixture); contentType(ContentType.Application.Json); setBody("""{"cashBookKind":"BANK"}""")
        }.status shouldBe HttpStatusCode.Conflict
        client.put("$base/${extra.id.value}/cash-book-kind") {
            signedIn(fixture); contentType(ContentType.Application.Json); setBody("""{"cashBookKind":"WALLET"}""")
        }.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a read-only member, when they read a book they may, and when they change a kind they may not`() = testApplication {
        val fixture = Fixture()
        val extra = fixture.cashBook("1015", CashBookKind.BANK)
        val reader = User.create("reader@example.com", "Reader").also { fixture.userRepository.save(it) }
        fixture.membershipRepository.save(
            Membership.grant(reader.id, fixture.tenant, Role.ACCOUNTANT, fixture.company.id, accessLevel = AccessLevel.READ)
        )
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.get("/api/companies/${fixture.company.id.value}/cash-books/${extra.id.value}") { signedIn(fixture, "reader@example.com") }
            .status shouldBe HttpStatusCode.OK
        client.put("/api/companies/${fixture.company.id.value}/accounts/${extra.id.value}/cash-book-kind") {
            signedIn(fixture, "reader@example.com"); contentType(ContentType.Application.Json); setBody("""{"cashBookKind":"CASH"}""")
        }.status shouldBe HttpStatusCode.Forbidden
        fixture.accountRepository.findById(extra.id)!!.cashBookKind shouldBe CashBookKind.BANK
    }

    @Test
    fun `given an older chart, when standard accounts are added through the route, then the gaps are filled once, 1000 becomes CASH, and a second run adds nothing`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val path = "/api/companies/${fixture.company.id.value}/standard-accounts"

        val first = client.post(path) { signedIn(fixture) }
        first.status shouldBe HttpStatusCode.OK
        val firstBody: StandardAccountsResponseDto = first.body()
        firstBody.added.map { it.code }.contains("3900") shouldBe true
        firstBody.added.map { it.code }.contains("1000") shouldBe false
        firstBody.cashBookKindSet shouldBe true
        fixture.accountRepository.findAllByCompany(fixture.company.id).single { it.code == "1000" }.cashBookKind shouldBe CashBookKind.CASH

        val second: StandardAccountsResponseDto = client.post(path) { signedIn(fixture) }.body()
        second.added shouldBe emptyList()
        second.cashBookKindSet shouldBe false
    }

    @Test
    fun `given a read-only member, when they ask for standard accounts to be added, then it is refused and nothing is added`() = testApplication {
        val fixture = Fixture()
        val reader = User.create("reader2@example.com", "Reader").also { fixture.userRepository.save(it) }
        fixture.membershipRepository.save(Membership.grant(reader.id, fixture.tenant, Role.ACCOUNTANT, fixture.company.id, accessLevel = AccessLevel.READ))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val before = fixture.accountRepository.findAllByCompany(fixture.company.id).size

        client.post("/api/companies/${fixture.company.id.value}/standard-accounts") { signedIn(fixture, "reader2@example.com") }
            .status shouldBe HttpStatusCode.Forbidden
        fixture.accountRepository.findAllByCompany(fixture.company.id).size shouldBe before
    }

    // ---- recording money in and out (Release B) ----

    private fun entryBody(counter: Account, amount: String = "100.00", description: String = "Takings", date: LocalDate = TODAY, extra: String = "") =
        """{"date":"$date","amount":"$amount","counterAccountId":"${counter.id.value}","description":"$description"$extra}"""

    private suspend fun io.ktor.client.HttpClient.recordEntry(
        fixture: Fixture, book: Account, segment: String, body: String, key: String?, email: String = ADMIN_EMAIL
    ) = post("/api/companies/${fixture.company.id.value}/cash-books/${book.id.value}/$segment") {
        signedIn(fixture, email)
        if (key != null) header("Idempotency-Key", key)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `given a receipt, when it is recorded, then it is 201 with the entry, the balance after and no warning, and the book shows it as a CASH_BOOK entry`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), "key-1")

        response.status shouldBe HttpStatusCode.Created
        val body: CashBookEntryResponseDto = response.body()
        body.balanceAfter shouldBe "100.00"
        body.currency shouldBe "GBP"
        body.warnings shouldBe emptyList()
        body.replayed shouldBe false
        val book: CashBookResponseDto = client.get("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}?from=${TODAY.minusDays(1)}&to=$TODAY") { signedIn(fixture) }.body()
        book.rows.map { it.entryId } shouldBe listOf(body.entryId)
        book.rows.single().source shouldBe "CASH_BOOK"
        book.rows.single().moneyIn shouldBe "100.00"
    }

    // ---- VAT on money in or out (GL-T-27, GL-FR-TAX-8, GL-UC-19; Femi 2026-10-22, option A) ----

    private fun vatExtra(category: String?, amount: String?) =
        listOfNotNull(category?.let { ""","vatCategory":"$it"""" }, amount?.let { ""","vatAmount":"$it"""" }).joinToString("")

    @Test
    fun `given a receipt with VAT, when it is recorded, then it is 201, the book moves by the gross and the entry splits net and VAT tagged for the return`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "125.00", extra = vatExtra("STANDARD", "25.00")), "vat-1")

        response.status shouldBe HttpStatusCode.Created
        val body: CashBookEntryResponseDto = response.body()
        body.balanceAfter shouldBe "125.00"
        val entry = fixture.journalEntryRepository.findById(com.theprodeogroup.fish.domain.ledger.JournalEntryId(java.util.UUID.fromString(body.entryId)))!!
        entry.lines.map { Triple(it.accountId, it.side, it.amount.amount.toPlainString()) } shouldBe listOf(
            Triple(cash.id, TransactionSide.DEBIT, "125.00"),
            Triple(fixture.revenueAccount!!.id, TransactionSide.CREDIT, "100.00"),
            Triple(fixture.vatAccount!!.id, TransactionSide.CREDIT, "25.00")
        )
        entry.lines.last().dimensions shouldBe mapOf(
            com.theprodeogroup.fish.domain.common.DimensionType.VAT_CATEGORY to "STANDARD",
            com.theprodeogroup.fish.domain.common.DimensionType.VAT_DIRECTION to "OUTPUT"
        )
    }

    @Test
    fun `given a payment with VAT, when it is recorded, then the expense takes the net and the VAT control account the VAT as input`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val rent = Account.create(fixture.company.id, AccountType.EXPENSE, null, "5000", "Rent").also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "300.00"), "fund")

        val response = client.recordEntry(fixture, cash, "payments", entryBody(rent, "125.00", extra = vatExtra("REDUCED", "25.00")), "vat-2")

        response.status shouldBe HttpStatusCode.Created
        response.body<CashBookEntryResponseDto>().balanceAfter shouldBe "175.00"
        val entry = fixture.journalEntryRepository.findAllByAccount(rent.id).single()
        entry.lines.map { Triple(it.accountId, it.side, it.amount.amount.toPlainString()) } shouldBe listOf(
            Triple(cash.id, TransactionSide.CREDIT, "125.00"),
            Triple(rent.id, TransactionSide.DEBIT, "100.00"),
            Triple(fixture.vatAccount!!.id, TransactionSide.DEBIT, "25.00")
        )
        entry.lines.last().dimensions[com.theprodeogroup.fish.domain.common.DimensionType.VAT_DIRECTION] shouldBe "INPUT"
    }

    @Test
    fun `given VAT that is incomplete, unknown, malformed or too large or on the wrong account, then each is refused with its own token and nothing is posted`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val rent = Account.create(fixture.company.id, AccountType.EXPENSE, null, "5000", "Rent").also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val sales = fixture.revenueAccount!!
        suspend fun refusal(segment: String, counter: Account, extra: String, key: String): Pair<HttpStatusCode, String> {
            val response = client.recordEntry(fixture, cash, segment, entryBody(counter, "125.00", extra = extra), key)
            return response.status to response.body<ErrorResponseDto>().error
        }

        refusal("receipts", sales, vatExtra("STANDARD", null), "v1") shouldBe (HttpStatusCode.BadRequest to "vat_incomplete")
        refusal("receipts", sales, vatExtra(null, "25.00"), "v2") shouldBe (HttpStatusCode.BadRequest to "vat_incomplete")
        refusal("receipts", sales, vatExtra("PREMIUM", "25.00"), "v3") shouldBe (HttpStatusCode.BadRequest to "vat_category_not_supported")
        refusal("receipts", sales, vatExtra("STANDARD", "abc"), "v4") shouldBe (HttpStatusCode.BadRequest to "invalid_amount")
        refusal("receipts", sales, vatExtra("STANDARD", "125.00"), "v5") shouldBe (HttpStatusCode.BadRequest to "vat_exceeds_amount")
        refusal("receipts", rent, vatExtra("STANDARD", "25.00"), "v6") shouldBe (HttpStatusCode.BadRequest to "vat_not_applicable")
        refusal("payments", sales, vatExtra("STANDARD", "25.00"), "v7") shouldBe (HttpStatusCode.BadRequest to "vat_not_applicable")
        fixture.journalEntryRepository.findAllByAccount(cash.id) shouldBe emptyList()
    }

    @Test
    fun `given VAT but the Company has no VAT control account, then it is a 409 vat_control_account_not_configured and nothing is posted`() = testApplication {
        val fixture = Fixture(configureAccounts = false)
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val sales = Account.create(fixture.company.id, AccountType.REVENUE, null, "4000", "Revenue").also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordEntry(fixture, cash, "receipts", entryBody(sales, "125.00", extra = vatExtra("STANDARD", "25.00")), "nv")

        response.status shouldBe HttpStatusCode.Conflict
        response.body<ErrorResponseDto>().error shouldBe "vat_control_account_not_configured"
        fixture.journalEntryRepository.findAllByAccount(cash.id) shouldBe emptyList()
    }

    @Test
    fun `given the same VAT request twice, then the second is a replay, and the same key with a different VAT is 422 idempotency_key_reused`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val body = entryBody(fixture.revenueAccount!!, "125.00", extra = vatExtra("STANDARD", "25.00"))
        val first: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", body, "same").body()

        val second: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", body, "same").body()
        val different = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "125.00", extra = vatExtra("STANDARD", "20.00")), "same")
        val plain = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "125.00"), "same")

        second.replayed shouldBe true
        second.entryId shouldBe first.entryId
        different.status shouldBe HttpStatusCode.UnprocessableEntity
        plain.status shouldBe HttpStatusCode.UnprocessableEntity
        fixture.journalEntryRepository.findAllByAccount(cash.id).size shouldBe 1
    }

    @Test
    fun `given a receipt with VAT, when it is undone, then all three lines are reversed and the book returns to zero`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val recorded: CashBookEntryResponseDto =
            client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "125.00", extra = vatExtra("STANDARD", "25.00")), "undo-vat").body()

        val undo = client.post("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}/entries/${recorded.entryId}/undo") { asCbb(fixture) }

        undo.status shouldBe HttpStatusCode.Created
        undo.body<CashBookUndoResponseDto>().balanceAfter shouldBe "0.00"
        fixture.journalEntryRepository.findAllByAccount(fixture.vatAccount!!.id).map { it.lines.size } shouldBe listOf(3, 3)
    }

    // ---- paying VAT to the authority (GL-T-30, GL-FR-TAX-9, GL-UC-20; Femi 2026-10-22) ----

    private fun settlementBody(amount: String = "150.00", direction: String? = "PAID", date: LocalDate = TODAY) =
        """{"date":"$date","amount":"$amount"${direction?.let { ""","direction":"$it"""" } ?: ""},"description":"Q3 VAT"}"""

    private suspend fun io.ktor.client.HttpClient.settleVat(fixture: Fixture, book: Account, body: String, key: String?, email: String = ADMIN_EMAIL) =
        post("/api/companies/${fixture.company.id.value}/cash-books/${book.id.value}/vat-settlement") {
            signedIn(fixture, email)
            if (key != null) header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `given VAT paid to the authority, when it is recorded, then it is 201, debits the VAT control account, credits the bank and tags the VAT line SETTLEMENT`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.settleVat(fixture, bank, settlementBody("150.00", "PAID"), "vs-1")

        response.status shouldBe HttpStatusCode.Created
        val body: CashBookEntryResponseDto = response.body()
        body.balanceAfter shouldBe "-150.00"
        val entry = fixture.journalEntryRepository.findAllByAccount(fixture.vatAccount!!.id).single()
        entry.lines.map { Triple(it.accountId, it.side, it.amount.amount.toPlainString()) } shouldBe listOf(
            Triple(bank.id, TransactionSide.CREDIT, "150.00"),
            Triple(fixture.vatAccount!!.id, TransactionSide.DEBIT, "150.00")
        )
        entry.lines.last().dimensions shouldBe mapOf(com.theprodeogroup.fish.domain.common.DimensionType.VAT_DIRECTION to "SETTLEMENT")
    }

    @Test
    fun `given a VAT refund received, when it is recorded, then the bank is debited and the VAT control account credited`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.settleVat(fixture, bank, settlementBody("75.00", "RECEIVED"), "vs-2")

        response.status shouldBe HttpStatusCode.Created
        response.body<CashBookEntryResponseDto>().balanceAfter shouldBe "75.00"
        fixture.journalEntryRepository.findAllByAccount(fixture.vatAccount!!.id).single().lines.last().side shouldBe TransactionSide.CREDIT
    }

    @Test
    fun `given no Idempotency-Key, a missing or unknown direction, a bad amount or a book that is not a cash book, then each is refused and nothing is posted`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        suspend fun refusal(book: Account, body: String, key: String?): Pair<HttpStatusCode, String> {
            val response = client.settleVat(fixture, book, body, key)
            return response.status to response.body<ErrorResponseDto>().error
        }

        refusal(bank, settlementBody(), null) shouldBe (HttpStatusCode.BadRequest to "idempotency_key_required")
        refusal(bank, settlementBody(direction = null), "r1") shouldBe (HttpStatusCode.BadRequest to "bad_request")
        refusal(bank, settlementBody(direction = "SIDEWAYS"), "r2") shouldBe (HttpStatusCode.BadRequest to "bad_request")
        refusal(bank, settlementBody(amount = "abc"), "r3") shouldBe (HttpStatusCode.BadRequest to "invalid_amount")
        refusal(bank, settlementBody(amount = "0.00"), "r4") shouldBe (HttpStatusCode.BadRequest to "invalid_amount")
        refusal(fixture.revenueAccount!!, settlementBody(), "r5") shouldBe (HttpStatusCode.Conflict to "not_a_cash_or_bank_account")
        fixture.journalEntryRepository.findAllByAccount(fixture.vatAccount!!.id) shouldBe emptyList()
    }

    @Test
    fun `given the Company has no VAT control account, then a settlement is a 409 vat_control_account_not_configured`() = testApplication {
        val fixture = Fixture(configureAccounts = false)
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.settleVat(fixture, bank, settlementBody(), "nc")

        response.status shouldBe HttpStatusCode.Conflict
        response.body<ErrorResponseDto>().error shouldBe "vat_control_account_not_configured"
    }

    @Test
    fun `given the same settlement twice, then the second is a replay, and the same key with another amount is 422 idempotency_key_reused`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val first: CashBookEntryResponseDto = client.settleVat(fixture, bank, settlementBody(), "same").body()

        val second: CashBookEntryResponseDto = client.settleVat(fixture, bank, settlementBody(), "same").body()
        val different = client.settleVat(fixture, bank, settlementBody("151.00"), "same")

        second.replayed shouldBe true
        second.entryId shouldBe first.entryId
        different.status shouldBe HttpStatusCode.UnprocessableEntity
        fixture.journalEntryRepository.findAllByAccount(fixture.vatAccount!!.id).size shouldBe 1
    }

    @Test
    fun `given a read-only member, when they try to settle VAT, then it is refused and nothing is posted`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        val reader = User.create("vat-reader@example.com", "Reader").also { fixture.userRepository.save(it) }
        fixture.membershipRepository.save(Membership.grant(reader.id, fixture.tenant, Role.ACCOUNTANT, fixture.company.id, accessLevel = AccessLevel.READ))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.settleVat(fixture, bank, settlementBody(), "ro", "vat-reader@example.com").status shouldBe HttpStatusCode.Forbidden
        fixture.journalEntryRepository.findAllByAccount(fixture.vatAccount!!.id) shouldBe emptyList()
    }

    @Test
    fun `given the ordinary payment door, when the VAT control account is named as the other side, then it is still refused and points to the VAT return`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordEntry(fixture, bank, "payments", entryBody(fixture.vatAccount!!, "10.00"), "general")

        response.status shouldBe HttpStatusCode.Conflict
        response.body<CounterAccountNotAllowedDto>().useInstead shouldBe "VAT"
    }

    // ---- who, why and what document (V36; GL-T-38, GL-T-31, GL-T-39; standards S4 and S6) ----

    private fun sourceRefExtra(type: String = "CASH_BOOK_ENTRY", id: String = "cb-123", number: String = "RCT-0007") =
        ""","sourceReference":{"type":"$type","id":"$id","number":"$number"},"vatBasis":{"ratePercent":"20","rulebookVersion":41,"effectiveFrom":"2026-04-06"}"""

    @Test
    fun `given a receipt by a person, with a source reference and VAT basis, then the entry records who, and the document and rate row`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "125.00", extra = vatExtra("STANDARD", "25.00") + sourceRefExtra()), "prov-1")

        response.status shouldBe HttpStatusCode.Created
        val provenance = fixture.journalEntryRepository.findAllByAccount(cash.id).single().provenance!!
        provenance.recordedBy shouldBe "USER:${fixture.adminUser.id.value}"
        provenance.actingUser shouldBe null
        provenance.sourceReference shouldBe com.theprodeogroup.fish.domain.ledger.SourceReference(com.theprodeogroup.fish.domain.ledger.SourceReferenceType.CASH_BOOK_ENTRY, "cb-123", "RCT-0007")
        provenance.vatBasis shouldBe com.theprodeogroup.fish.domain.ledger.VatBasis("20", 41, java.time.LocalDate.of(2026, 4, 6))
    }

    @Test
    fun `given each of Payroll's document types as the source reference, then it is accepted and stored, and an unknown one is still refused`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        for (type in listOf("PAY_RUN", "SALARY_ADVANCE", "EXPENSE_CLAIM", "LEAVE_REQUEST")) {
            val response = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "10.00", extra = ""","sourceReference":{"type":"$type","id":"doc-$type"}"""), "key-$type")
            response.status shouldBe HttpStatusCode.Created
        }
        client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "10.00", extra = ""","sourceReference":{"type":"TIMESHEET","id":"x"}"""), "key-unknown").status shouldBe HttpStatusCode.BadRequest
        fixture.journalEntryRepository.findAllByAccount(cash.id).mapNotNull { it.provenance?.sourceReference?.type?.name }.sorted() shouldBe
            listOf("EXPENSE_CLAIM", "LEAVE_REQUEST", "PAY_RUN", "SALARY_ADVANCE")
    }

    @Test
    fun `given a source reference with an unknown type, an over-long id or a VAT basis that is not a percent, then it is 400 and nothing is posted`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val sales = fixture.revenueAccount!!

        client.recordEntry(fixture, cash, "receipts", entryBody(sales, extra = sourceRefExtra(type = "PURCHASE_ORDER")), "p1").status shouldBe HttpStatusCode.BadRequest
        client.recordEntry(fixture, cash, "receipts", entryBody(sales, extra = sourceRefExtra(id = "x".repeat(101))), "p2").status shouldBe HttpStatusCode.BadRequest
        client.recordEntry(fixture, cash, "receipts", entryBody(sales, extra = ""","vatBasis":{"ratePercent":"twenty"}"""), "p3").status shouldBe HttpStatusCode.BadRequest
        client.recordEntry(fixture, cash, "receipts", entryBody(sales, extra = ""","vatBasis":{"ratePercent":"20","effectiveFrom":"06/04/2026"}"""), "p4").status shouldBe HttpStatusCode.BadRequest
        fixture.journalEntryRepository.findAllByAccount(cash.id) shouldBe emptyList()
    }

    @Test
    fun `given an Undo with a reason, then the reversal records who undid it and why, and the original keeps its own record`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val recorded: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), "to-undo-1").body()

        val undo = client.post("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}/entries/${recorded.entryId}/undo") {
            asCbb(fixture)
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"  Posted to the wrong book  "}""")
        }

        undo.status shouldBe HttpStatusCode.Created
        val reversal = fixture.journalEntryRepository.findById(com.theprodeogroup.fish.domain.ledger.JournalEntryId(java.util.UUID.fromString(undo.body<CashBookUndoResponseDto>().reversalEntryId)))!!
        reversal.provenance!!.reason shouldBe "Posted to the wrong book"
        // The undo is CBB's call, made for the signed-in person: GL records the service and the person it acted for.
        reversal.provenance!!.recordedBy shouldBe "SERVICE:cbb"
        reversal.provenance!!.actingUser shouldBe fixture.adminUser.id.value.toString()
        fixture.journalEntryRepository.findById(com.theprodeogroup.fish.domain.ledger.JournalEntryId(java.util.UUID.fromString(recorded.entryId)))!!.provenance!!.reason shouldBe null
    }

    @Test
    fun `given an Undo with no body, a blank reason, too long a reason or a malformed body, then no body and blank work, and the others are 400`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val first: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), "u1").body()
        val second: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "20.00"), "u2").body()
        fun path(id: String) = "/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}/entries/$id/undo"

        client.post(path(first.entryId)) { asCbb(fixture); contentType(ContentType.Application.Json); setBody("""{"reason":"x"""" + "x".repeat(500) + """"}""") }.status shouldBe HttpStatusCode.BadRequest
        client.post(path(first.entryId)) { asCbb(fixture); contentType(ContentType.Application.Json); setBody("not json") }.status shouldBe HttpStatusCode.BadRequest
        client.post(path(first.entryId)) { asCbb(fixture); contentType(ContentType.Application.Json); setBody("""{"reason":"   "}""") }.status shouldBe HttpStatusCode.Created
        client.post(path(second.entryId)) { asCbb(fixture) }.status shouldBe HttpStatusCode.Created
        fixture.journalEntryRepository.findAllByAccount(cash.id).count { it.provenance?.reason != null } shouldBe 0
    }

    @Test
    fun `given the postings read, then the audit fields come only with include=audit and the default answer does not carry them`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "125.00", extra = vatExtra("STANDARD", "25.00") + sourceRefExtra()), "audit-1")
        val base = "/api/companies/${fixture.company.id.value}/accounts/${cash.id.value}/postings?from=${TODAY.minusDays(1)}&to=$TODAY"

        val plain = client.get(base) { signedIn(fixture) }.bodyAsText()
        val audit: AccountPostingsAuditResponseDto = client.get("$base&include=audit") { signedIn(fixture) }.body()
        val bogus = client.get("$base&include=everything") { signedIn(fixture) }

        listOf("recordedBy", "actingUser", "sourceReference", "vatBasis", "reason").forEach { plain.contains("\"$it\"") shouldBe false }
        audit.postings.single().recordedBy shouldBe "USER:${fixture.adminUser.id.value}"
        audit.postings.single().sourceReference shouldBe SourceReferenceReadDto("CASH_BOOK_ENTRY", "cb-123", "RCT-0007")
        audit.postings.single().vatBasis shouldBe VatBasisReadDto("20", 41, "2026-04-06")
        audit.postings.single().reason shouldBe null
        bogus.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given no Idempotency-Key, when an entry is recorded, then it is a 400 and nothing is posted`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), key = null)

        response.status shouldBe HttpStatusCode.BadRequest
        response.body<ErrorResponseDto>().error shouldBe "idempotency_key_required"
        fixture.journalEntryRepository.findAllByAccount(cash.id) shouldBe emptyList()
    }

    @Test
    fun `given the same request and key sent twice, then the second is a replay of the same entry and only one entry exists`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val body = entryBody(fixture.revenueAccount!!)

        val first: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", body, "same-key").body()
        val second: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", body, "same-key").body()

        second.entryId shouldBe first.entryId
        second.replayed shouldBe true
        second.balanceAfter shouldBe "100.00"
        fixture.journalEntryRepository.findAllByAccount(cash.id).size shouldBe 1
    }

    @Test
    fun `given the same key with a different amount, then it is a 422 idempotency_key_reused and the first entry stands`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "100.00"), "reuse")

        val response = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!, "250.00"), "reuse")

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.body<ErrorResponseDto>().error shouldBe "idempotency_key_reused"
        fixture.journalEntryRepository.findAllByAccount(cash.id).size shouldBe 1
    }

    @Test
    fun `given a refused request with a key, then the key is not spent - the corrected request with the same key posts`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val receivables = fixture.cashBook("1100", null)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val refused = client.recordEntry(fixture, cash, "receipts", entryBody(receivables), "retry-key")
        refused.status shouldBe HttpStatusCode.Conflict
        val refusal: CounterAccountNotAllowedDto = refused.body()
        refusal.error shouldBe "counter_account_not_allowed"
        refusal.useInstead shouldBe "SALES_COLLECTION"
        fixture.journalEntryRepository.findAllByAccount(cash.id) shouldBe emptyList()

        val fixed = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), "retry-key")

        fixed.status shouldBe HttpStatusCode.Created
        fixed.body<CashBookEntryResponseDto>().replayed shouldBe false
        fixture.journalEntryRepository.findAllByAccount(cash.id).size shouldBe 1
    }

    @Test
    fun `given several simultaneous requests with the same key, then exactly one entry is posted and every caller gets that entry`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val body = entryBody(fixture.revenueAccount!!)

        val responses = coroutineScope {
            (1..6).map { async { client.recordEntry(fixture, cash, "receipts", body, "double-click").body<CashBookEntryResponseDto>() } }
                .map { it.await() }
        }

        responses.map { it.entryId }.distinct().size shouldBe 1
        responses.count { !it.replayed } shouldBe 1
        fixture.journalEntryRepository.findAllByAccount(cash.id).size shouldBe 1
    }

    @Test
    fun `given the same key for receipts and payments or for two books, then each is its own entry`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        val rent = fixture.account("5200", AccountType.EXPENSE)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val a: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), "k").body()
        val b: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "payments", entryBody(rent, "10.00"), "k").body()
        val c: CashBookEntryResponseDto = client.recordEntry(fixture, bank, "receipts", entryBody(fixture.revenueAccount!!), "k").body()

        setOf(a.entryId, b.entryId, c.entryId).size shouldBe 3
        listOf(a, b, c).map { it.replayed } shouldBe listOf(false, false, false)
    }

    @Test
    fun `given a payment that takes cash below zero, then it posts with a cash_below_zero warning`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val rent = fixture.account("5200", AccountType.EXPENSE)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.recordEntry(fixture, cash, "payments", entryBody(rent, "50.00", "Rent"), "pay-1")

        response.status shouldBe HttpStatusCode.Created
        val body: CashBookEntryResponseDto = response.body()
        body.balanceAfter shouldBe "-50.00"
        body.warnings shouldBe listOf(CashBookWarningDto("cash_below_zero", cash.id.value.toString(), "-50.00"))
    }

    @Test
    fun `given bad input, then each is refused with its own error and nothing is posted`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        val otherCompany = Company.create(fixture.tenant, "Second Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP).also { fixture.companyRepository.save(it) }
        val foreign = Account.create(otherCompany.id, AccountType.REVENUE, null, "4000", "Their sales").also { fixture.accountRepository.save(it) }
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val sales = fixture.revenueAccount!!

        client.recordEntry(fixture, cash, "receipts", entryBody(sales, "0.00"), "b1").status shouldBe HttpStatusCode.BadRequest
        client.recordEntry(fixture, cash, "receipts", entryBody(sales, "abc"), "b2").status shouldBe HttpStatusCode.BadRequest
        client.recordEntry(fixture, cash, "receipts", entryBody(sales).replace(TODAY.toString(), "yesterday"), "b3").status shouldBe HttpStatusCode.BadRequest
        client.recordEntry(fixture, cash, "receipts", entryBody(sales, extra = ""","cashFlowActivity":"WHENEVER""""), "b4").status shouldBe HttpStatusCode.BadRequest
        client.recordEntry(fixture, cash, "receipts", entryBody(foreign), "b5").status shouldBe HttpStatusCode.NotFound
        client.recordEntry(fixture, cash, "receipts", entryBody(bank), "b6").body<CounterAccountNotAllowedDto>().useInstead shouldBe "TRANSFER"
        client.recordEntry(fixture, fixture.revenueAccount!!, "receipts", entryBody(bank), "b7").status shouldBe HttpStatusCode.Conflict
        fixture.journalEntryRepository.findAllByAccount(cash.id) shouldBe emptyList()
    }

    @Test
    fun `given a read-only member, when they try to record money, then it is refused and nothing is posted`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val reader = User.create("reader3@example.com", "Reader").also { fixture.userRepository.save(it) }
        fixture.membershipRepository.save(Membership.grant(reader.id, fixture.tenant, Role.ACCOUNTANT, fixture.company.id, accessLevel = AccessLevel.READ))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), "ro-key", "reader3@example.com").status shouldBe HttpStatusCode.Forbidden
        fixture.journalEntryRepository.findAllByAccount(cash.id) shouldBe emptyList()
    }

    @Test
    fun `given a chart, when counter-accounts are asked for money in, then only recordable accounts come back with plain groups and a control account is never offered`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        fixture.account("5200", AccountType.EXPENSE)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/cash-books/${bank.id.value}/counter-accounts?direction=in") { signedIn(fixture) }

        response.status shouldBe HttpStatusCode.OK
        val options: List<CounterAccountOptionDto> = response.body()
        val byCode = options.associateBy { it.code }
        byCode.getValue("4000").group shouldBe "INCOME"
        byCode.getValue("5200").group shouldBe "EXPENSE_OTHER"
        listOf("1010", "1100", "2150").forEach { (it in byCode) shouldBe false }
    }

    @Test
    fun `given a missing or unknown direction, when counter-accounts are asked for, then it is a 400`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val base = "/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}/counter-accounts"

        client.get(base) { signedIn(fixture) }.status shouldBe HttpStatusCode.BadRequest
        client.get("$base?direction=sideways") { signedIn(fixture) }.status shouldBe HttpStatusCode.BadRequest
    }

    private suspend fun io.ktor.client.HttpClient.transfer(fixture: Fixture, from: Account, to: Account, key: String?, amount: String = "40.00") =
        post("/api/companies/${fixture.company.id.value}/cash-books/${from.id.value}/transfers") {
            signedIn(fixture)
            if (key != null) header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody("""{"toAccountId":"${to.id.value}","date":"$TODAY","amount":"$amount","description":"Banked"}""")
        }

    @Test
    fun `given cash in the till, when it is moved to the bank, then both books show it and a replay posts nothing more`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        fixture.post(cash, fixture.revenueAccount!!, "100.00")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val first = client.transfer(fixture, cash, bank, "move-1")
        first.status shouldBe HttpStatusCode.Created
        val body: CashBookTransferResponseDto = first.body()
        body.fromBalanceAfter shouldBe "60.00"
        body.toBalanceAfter shouldBe "40.00"
        body.warnings shouldBe emptyList()
        val replay: CashBookTransferResponseDto = client.transfer(fixture, cash, bank, "move-1").body()

        replay.replayed shouldBe true
        replay.entryId shouldBe body.entryId
        fixture.journalEntryRepository.findAllByAccount(bank.id).size shouldBe 1
        client.transfer(fixture, cash, bank, "move-1", amount = "41.00").status shouldBe HttpStatusCode.UnprocessableEntity
        client.transfer(fixture, cash, bank, key = null).status shouldBe HttpStatusCode.BadRequest
        client.transfer(fixture, cash, cash, "move-2").status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a recorded receipt, when it is undone, then a reversing entry is posted, a second undo is 409 already_undone, and the book shows both rows`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val recorded: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), "to-undo").body()
        val undoPath = "/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}/entries/${recorded.entryId}/undo"

        val undo = client.post(undoPath) { asCbb(fixture) }

        undo.status shouldBe HttpStatusCode.Created
        val undone: CashBookUndoResponseDto = undo.body()
        undone.balanceAfter shouldBe "0.00"
        undone.undoneEntryId shouldBe recorded.entryId
        val again = client.post(undoPath) { asCbb(fixture) }
        again.status shouldBe HttpStatusCode.Conflict
        again.body<ErrorResponseDto>().error shouldBe "already_undone"
        val book: CashBookResponseDto = client.get("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}?from=${TODAY.minusDays(1)}&to=$TODAY") { signedIn(fixture) }.body()
        book.rows.size shouldBe 2
        book.closingBalance shouldBe "0.00"
        book.rows.map { it.canUndo } shouldBe listOf(false, false)
    }

    // ---- Femi 2026-10-23: "CBB corrects errors in a Bank and Cash Book. Never the GL." GL's undo door is for the CBB credential only. ----

    @Test
    fun `given a person, even the founding admin, when they call GL's undo directly, then it is a 403 saying undo is done in Cash and Bank Books and the entry is not reversed`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val recorded: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), "person-undo").body()
        val before = fixture.journalEntryRepository.saveCalls.size

        val response = client.post("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}/entries/${recorded.entryId}/undo") { signedIn(fixture) }

        response.status shouldBe HttpStatusCode.Forbidden
        val body: ErrorResponseDto = response.body()
        body.error shouldBe "forbidden"
        body.detail shouldBe "Undo is done in Cash and Bank Books"
        fixture.journalEntryRepository.saveCalls.size shouldBe before
    }

    @Test
    fun `given any other service credential, when it calls GL's undo, then it is refused by its allow-list`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val recorded: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), "svc-undo").body()

        for (service in listOf("sop", "pop", "im", "hr", "payroll", "tax")) {
            val response = client.post("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}/entries/${recorded.entryId}/undo") {
                header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signServiceToken(service)}")
            }
            // 401 where the service has no verifier wired in this fixture (it authenticates as nobody), 403 where it does: never a reversal.
            (response.status.value in setOf(401, 403)) shouldBe true
        }
        fixture.journalEntryRepository.findAllByAccount(cash.id).size shouldBe 1
    }

    @Test
    fun `given the endpoint allow-list is only logging, when another service calls GL's undo, then the door itself still refuses it`() = testApplication {
        // Defense in depth: the allow-list is the first wall (ENFORCE in production once CM sets it); the handler is the second.
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this, ServiceAllowListMode.LOG) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val recorded: CashBookEntryResponseDto = client.recordEntry(fixture, cash, "receipts", entryBody(fixture.revenueAccount!!), "log-mode").body()

        val response = client.post("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}/entries/${recorded.entryId}/undo") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signServiceToken("sop")}")
        }

        response.status shouldBe HttpStatusCode.Forbidden
        response.body<ErrorResponseDto>().detail shouldBe "Undo is done in Cash and Bank Books"
        fixture.journalEntryRepository.findAllByAccount(cash.id).size shouldBe 1
    }

    @Test
    fun `given an entry id that is not in this book, when undo is called, then it is a 404 and nothing is reversed`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        val onBank = fixture.post(bank, fixture.revenueAccount!!, "30.00")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}/entries/${onBank.id.value}/undo") { asCbb(fixture) }

        response.status shouldBe HttpStatusCode.NotFound
        fixture.journalEntryRepository.findById(onBank.id)!!.status shouldBe com.theprodeogroup.fish.domain.common.PostingStatus.POSTED
    }

    @Test
    fun `given an entry that was made elsewhere, such as a journal, when undo is called in the book, then it is a 409 undo_elsewhere naming where to reverse it and nothing is reversed`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val journal = fixture.post(cash, fixture.revenueAccount!!, "30.00")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}/entries/${journal.id.value}/undo") { asCbb(fixture) }

        response.status shouldBe HttpStatusCode.Conflict
        val body: CounterAccountNotAllowedDto = response.body()
        body.error shouldBe "undo_elsewhere"
        body.useInstead shouldBe "ORIGINAL_SCREEN"
        fixture.journalEntryRepository.findById(journal.id)!!.status shouldBe com.theprodeogroup.fish.domain.common.PostingStatus.POSTED
    }

    @Test
    fun `given a bank entry matched in a completed reconciliation, when the book is read, then that row is reconciled and the others are not, and a cash book row has no reconciled value`() = testApplication {
        val fixture = Fixture()
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        val cash = fixture.cashBook("1020", CashBookKind.CASH)
        val cleared = fixture.post(bank, fixture.revenueAccount!!, "100.00", TODAY.minusDays(3))
        fixture.post(bank, fixture.revenueAccount!!, "25.00", TODAY.minusDays(2))
        fixture.post(cash, fixture.revenueAccount!!, "10.00", TODAY.minusDays(1))
        val gbpMoney = { amount: String -> Money(BigDecimal(amount), GBP) }
        val line = com.theprodeogroup.fish.domain.ledger.BankStatementLine(
            com.theprodeogroup.fish.domain.ledger.BankStatementLineId.generate(), cleared.date, gbpMoney("100.00"),
            com.theprodeogroup.fish.domain.ledger.CashDirection.RECEIVED, "Statement"
        )
        val rec = com.theprodeogroup.fish.domain.ledger.BankReconciliation.create(bank.id, TODAY, gbpMoney("100.00"), listOf(line), listOf(cleared), GBP)
        rec.match(line.id, cleared.id)
        rec.complete()
        fixture.cashBookReconciliations.save(rec, fixture.company.id)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val range = "?from=${TODAY.minusDays(10)}&to=$TODAY"

        val bankBook: CashBookResponseDto = client.get("/api/companies/${fixture.company.id.value}/cash-books/${bank.id.value}$range") { signedIn(fixture) }.body()
        val cashBook: CashBookResponseDto = client.get("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}$range") { signedIn(fixture) }.body()

        bankBook.rows.map { it.reconciled } shouldBe listOf(true, false)
        cashBook.rows.map { it.reconciled } shouldBe listOf(null)
    }

    @Test
    fun `given an old entry whose description printed a typed id raw, when the book is read, then the text shows the plain id and the stored entry is unchanged`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val raw = "Reversal of JournalEntryId(value=3f2c8a52-1b6e-4c0e-9d3a-7a1d5b9c0e11) and Pay run - PayRunId(value=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee)"
        val entry = fixture.post(cash, fixture.revenueAccount!!, "10.00", TODAY, raw)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val book: CashBookResponseDto = client.get("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}?from=${TODAY.minusDays(1)}&to=$TODAY") { signedIn(fixture) }.body()

        book.rows.single().description shouldBe "Reversal of 3f2c8a52-1b6e-4c0e-9d3a-7a1d5b9c0e11 and Pay run - aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        fixture.journalEntryRepository.findById(entry.id)!!.description shouldBe raw
    }

    // ---- the cash trend for the Business Owner dashboard: GET cash-balance-series ----

    private fun seriesPath(fixture: Fixture, query: String = "") = "/api/companies/${fixture.company.id.value}/cash-balance-series$query"

    @Test
    fun `given a cash book and a bank book, when the series is read with no days, then it has 30 daily points ending today with the combined running balance`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val bank = fixture.cashBook("1010", CashBookKind.BANK)
        fixture.post(cash, fixture.revenueAccount!!, "50.00", TODAY.minusDays(60)) // before the window: the opening balance
        fixture.post(cash, fixture.revenueAccount!!, "120.00", TODAY.minusDays(3))
        fixture.post(bank, fixture.revenueAccount!!, "800.00", TODAY.minusDays(1))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get(seriesPath(fixture)) { signedIn(fixture) }

        response.status shouldBe HttpStatusCode.OK
        val series: CashBalanceSeriesResponseDto = response.body()
        series.currency shouldBe "GBP"
        series.points.size shouldBe 30
        series.to shouldBe java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        series.from shouldBe java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(29).toString()
        series.points.first().date shouldBe series.from
        series.points.last().date shouldBe series.to
        series.points.first().closingBalance shouldBe "50.00"
        series.points.last().closingBalance shouldBe "970.00"
    }

    @Test
    fun `given days, when the series is read, then it has exactly that many points`() = testApplication {
        val fixture = Fixture()
        fixture.cashBook("1000", CashBookKind.CASH)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.get(seriesPath(fixture, "?days=7")) { signedIn(fixture) }.body<CashBalanceSeriesResponseDto>().points.size shouldBe 7
        client.get(seriesPath(fixture, "?days=1")) { signedIn(fixture) }.body<CashBalanceSeriesResponseDto>().points.size shouldBe 1
        client.get(seriesPath(fixture, "?days=366")) { signedIn(fixture) }.body<CashBalanceSeriesResponseDto>().points.size shouldBe 366
    }

    @Test
    fun `given days that are not a whole number from 1 to 366, when the series is read, then it answers 400 invalid_days`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        for (bad in listOf("0", "-5", "367", "abc", "7.5", "")) {
            val response = client.get(seriesPath(fixture, "?days=$bad")) { signedIn(fixture) }
            response.status shouldBe HttpStatusCode.BadRequest
            response.body<ErrorResponseDto>().error shouldBe "invalid_days"
        }
    }

    @Test
    fun `given a Company with no cash or bank books, when the series is read, then every point is zero`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val series: CashBalanceSeriesResponseDto = client.get(seriesPath(fixture, "?days=5")) { signedIn(fixture) }.body()

        series.points.map { it.closingBalance } shouldBe List(5) { "0.00" }
    }

    @Test
    fun `given no bearer token, an unknown Company, or a stranger, when the series is read, then it is refused 401, 404 and refused again, and days are not validated for a caller who may not read`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.get(seriesPath(fixture)).status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/companies/${java.util.UUID.randomUUID()}/cash-balance-series") { signedIn(fixture) }.status shouldBe HttpStatusCode.NotFound
        val stranger = User.create("stranger-series@example.com", "Stranger").also { fixture.userRepository.save(it) }
        val refused = client.get(seriesPath(fixture, "?days=abc")) { signedIn(fixture, stranger.email) }.status
        (refused == HttpStatusCode.Forbidden || refused == HttpStatusCode.Unauthorized) shouldBe true // never 400: bad days are not reported to a caller who may not read
    }

    @Test
    fun `given a read-only member, when the series is read, then it is allowed`() = testApplication {
        val fixture = Fixture()
        fixture.cashBook("1000", CashBookKind.CASH)
        val reader = User.create("reader-series@example.com", "Reader").also { fixture.userRepository.save(it) }
        fixture.membershipRepository.save(Membership.grant(reader.id, fixture.tenant, Role.ACCOUNTANT, fixture.company.id, accessLevel = AccessLevel.READ))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.get(seriesPath(fixture)) { signedIn(fixture, "reader-series@example.com") }.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `given an old reversal that names its original by id, when the book is read, then it reads in words from the original even when the original is outside the range, and the stored entry is unchanged`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val original = fixture.post(cash, fixture.revenueAccount!!, "10.00", TODAY.minusDays(40), "Takings")
        val oldReversal = fixture.post(fixture.revenueAccount!!, cash, "10.00", TODAY.minusDays(1), "Reversal of ${original.id.value}")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val book: CashBookResponseDto = client.get("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}?from=${TODAY.minusDays(10)}&to=$TODAY") { signedIn(fixture) }.body()

        book.rows.single().description shouldBe "Reversal of Takings (${TODAY.minusDays(40)})"
        fixture.journalEntryRepository.findById(oldReversal.id)!!.description shouldBe "Reversal of ${original.id.value}"
    }

    @Test
    fun `given an old reversal whose original is not on this book, when the book is read, then the stored text is shown as it is`() = testApplication {
        val fixture = Fixture()
        val cash = fixture.cashBook("1000", CashBookKind.CASH)
        val unknown = "3f2c8a52-1b6e-4c0e-9d3a-7a1d5b9c0e11"
        fixture.post(fixture.revenueAccount!!, cash, "10.00", TODAY.minusDays(1), "Reversal of $unknown")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val book: CashBookResponseDto = client.get("/api/companies/${fixture.company.id.value}/cash-books/${cash.id.value}?from=${TODAY.minusDays(10)}&to=$TODAY") { signedIn(fixture) }.body()

        book.rows.single().description shouldBe "Reversal of $unknown"
    }
}
