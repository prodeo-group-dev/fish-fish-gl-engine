package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.AddMissingStandardAccountsUseCase
import com.theprodeogroup.fish.application.RecordCashBookEntryUseCase
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
        val computeCashBookUseCase = ComputeCashBookUseCase(companyRepository, accountRepository, journalEntryRepository, periodRepository)
        val changeCashBookKindUseCase = ChangeCashBookKindUseCase(accountRepository, FakeBankReconciliationRepository())
        val addMissingStandardAccountsUseCase = AddMissingStandardAccountsUseCase(companyRepository, accountRepository)
        val recordCashBookEntryUseCase = RecordCashBookEntryUseCase(companyRepository, accountRepository, periodRepository, journalEntryRepository, postJournalEntryUseCase)

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
                computeCashBookUseCase = computeCashBookUseCase,
                changeCashBookKindUseCase = changeCashBookKindUseCase,
                addMissingStandardAccountsUseCase = addMissingStandardAccountsUseCase,
                recordCashBookEntryUseCase = recordCashBookEntryUseCase
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
        book.rows.map { it.canUndo } shouldBe listOf(true, true)
        book.rows[0].reconciled shouldBe null
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
}
