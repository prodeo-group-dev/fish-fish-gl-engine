package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeSupplierRepository
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.fish.application.FakeCustomerRepository
import com.theprodeogroup.fish.application.FakeJournalEntryRepository
import com.theprodeogroup.fish.application.FakeLeaveAccrualRepository
import com.theprodeogroup.fish.application.FakeMembershipRepository
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakeSalesInvoiceRecordRepository
import com.theprodeogroup.fish.application.FakeEaMembershipGateway
import com.theprodeogroup.fish.application.FakeUserRepository
import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.AddMissingStandardAccountsUseCase
import com.theprodeogroup.fish.application.RecordCashBookEntryUseCase
import com.theprodeogroup.fish.application.RecordCashBookTransferUseCase
import com.theprodeogroup.fish.application.UndoCashBookEntryUseCase
import com.theprodeogroup.fish.application.ListCounterAccountsUseCase
import com.theprodeogroup.fish.application.ChangeCashBookKindUseCase
import com.theprodeogroup.fish.application.ComputeCashBookUseCase
import com.theprodeogroup.fish.application.ListCashBooksUseCase
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.application.FakeTaxRuleRepository
import com.theprodeogroup.fish.application.FakeTaxComputationRepository
import com.theprodeogroup.fish.application.CreateAccountUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.RecordOpeningBalanceUseCase
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.application.FakeVatRateRepository
import com.theprodeogroup.fish.application.FakeVatReturnRepository
import com.theprodeogroup.fish.application.FakeOpeningImportBatchRepository
import com.theprodeogroup.fish.application.FakeOpeningImportRowResultRepository
import com.theprodeogroup.fish.application.FakeBankReconciliationRepository
import com.theprodeogroup.fish.application.ImportGlBalancesUseCase
import com.theprodeogroup.fish.application.ImportFixedAssetsUseCase
import com.theprodeogroup.fish.application.StartBankReconciliationUseCase
import com.theprodeogroup.fish.application.MatchBankReconciliationLineUseCase
import com.theprodeogroup.fish.application.UnmatchBankReconciliationLineUseCase
import com.theprodeogroup.fish.application.CompleteBankReconciliationUseCase
import com.theprodeogroup.fish.application.CancelBankReconciliationUseCase
import com.theprodeogroup.fish.application.ComputeBankReconciliationUseCase
import com.theprodeogroup.fish.application.ListBankReconciliationsUseCase
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
import com.theprodeogroup.fish.application.GetOrCreateLeaveAccrualUseCase
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
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.payroll.EmployeeId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import java.math.BigDecimal
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.application.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.application.User
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import java.io.File
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.ktor.client.statement.bodyAsText
import io.kotest.assertions.withClue
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 21)
private const val TEST_EMAIL = "sop-caller@example.com"
/**
 * T15 / G1: a Tenant's data is reachable only by that Tenant. This generates its checks from the
 * routes that are actually declared in `infrastructure/web`, so a new Company-scoped route is covered
 * the day it is added, and a new route that is NOT scoped by a Company in its path fails an inventory
 * test until someone decides how it is isolated (docs/T15_GL_Statement.md).
 */
class TenantIsolationMatrixRouteTest {

    private class Fixture(role: Role = Role.ACCOUNTANT, jurisdiction: Jurisdiction = Jurisdiction.UK, accessLevel: AccessLevel = Membership.defaultAccessLevelFor(role)) {
        val userRepository = FakeUserRepository()
        val membershipRepository = FakeMembershipRepository()
        val companyRepository = FakeCompanyRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository().also { it.periodSource = periodRepository }
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
        val addCompanyToTenantUseCase = AddCompanyToTenantUseCase(companyRepository, accountRepository, periodRepository, journalEntryRepository)
        val taxRuleRepository = FakeTaxRuleRepository()
        val taxComputationRepository = FakeTaxComputationRepository()
        val computeTaxUseCase = ComputeTaxUseCase(periodRepository, accountRepository, journalEntryRepository, taxComputationRepository)
        val recordPayRunUseCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)
        val getOrCreateLeaveAccrualUseCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

        val tenantId = TenantId.generate()
        val user = User.create(TEST_EMAIL, "Test SOP Caller").also { userRepository.save(it) }
        val company = Company.create(tenantId, "Test Co", ClientType.NON_PROFIT, jurisdiction, GBP).also { companyRepository.save(it) }
        val membership = Membership.grant(user.id, tenantId, role, company.id, accessLevel = accessLevel).also { membershipRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            periodRepository.save(it)
        }
        val arControlAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable").also { accountRepository.save(it) }
        val revenueAccount = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales Revenue").also { accountRepository.save(it) }
        val cashAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }
        val vatControlAccount = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2150", "VAT Control Account").also { accountRepository.save(it) }


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
        val listCounterAccountsUseCase = ListCounterAccountsUseCase(companyRepository, accountRepository)
        val recordCashBookTransferUseCase = RecordCashBookTransferUseCase(companyRepository, accountRepository, periodRepository, journalEntryRepository, postJournalEntryUseCase)
        val undoCashBookEntryUseCase = UndoCashBookEntryUseCase(companyRepository, accountRepository, journalEntryRepository, periodRepository)
        val vatReturnRepository = FakeVatReturnRepository()
        val openingImportBatchRepository = FakeOpeningImportBatchRepository()
        val openingImportRowResultRepository = FakeOpeningImportRowResultRepository()
        val importGlBalancesUseCase = ImportGlBalancesUseCase(companyRepository, accountRepository, recordOpeningBalanceUseCase, openingImportBatchRepository, openingImportRowResultRepository)
        val importFixedAssetsUseCase = ImportFixedAssetsUseCase(companyRepository, periodRepository, accountRepository, fixedAssetRepository, createFixedAssetUseCase, openingImportBatchRepository, openingImportRowResultRepository)
        val bankReconciliationRepository = FakeBankReconciliationRepository()



        fun installInto(app: Application, serviceAllowListMode: ServiceAllowListMode = ServiceAllowListMode.ENFORCE) {
            app.fishModule(
                verifier = TestJwtSupport.verifier(),
                eaMembershipGateway = FakeEaMembershipGateway(userRepository, membershipRepository),
                companyRepository = companyRepository,
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
                recordCashBookEntryUseCase = recordCashBookEntryUseCase,
                listCounterAccountsUseCase = listCounterAccountsUseCase,
                recordCashBookTransferUseCase = recordCashBookTransferUseCase,
                undoCashBookEntryUseCase = undoCashBookEntryUseCase,
                vatRateRepository = FakeVatRateRepository(),
                vatReturnRepository = vatReturnRepository,
                importGlBalancesUseCase = importGlBalancesUseCase,
                importFixedAssetsUseCase = importFixedAssetsUseCase,
                startBankReconciliationUseCase = StartBankReconciliationUseCase(companyRepository, accountRepository, journalEntryRepository, bankReconciliationRepository),
                matchBankReconciliationLineUseCase = MatchBankReconciliationLineUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository),
                unmatchBankReconciliationLineUseCase = UnmatchBankReconciliationLineUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository),
                completeBankReconciliationUseCase = CompleteBankReconciliationUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository),
                cancelBankReconciliationUseCase = CancelBankReconciliationUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository),
                computeBankReconciliationUseCase = ComputeBankReconciliationUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository),
                listBankReconciliationsUseCase = ListBankReconciliationsUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository),
                serviceAllowListMode = serviceAllowListMode,
                serviceVerifier = TestJwtSupport.serviceVerifier("sop"),
                popServiceVerifier = TestJwtSupport.serviceVerifier("pop"),
                imServiceVerifier = TestJwtSupport.serviceVerifier("im"),
                hrServiceVerifier = TestJwtSupport.serviceVerifier("hr"),
                addCompanyToTenantUseCase = addCompanyToTenantUseCase,
                computeTaxUseCase = computeTaxUseCase,
                taxRuleRepository = taxRuleRepository,
                taxComputationRepository = taxComputationRepository
            )
        }
    }

    // ---- the routes, read from the source so the lists cannot go stale ------------------------------

    private data class RouteDeclaration(val method: String, val path: String)

    private val declarations: List<RouteDeclaration> by lazy {
        val dir = File("src/main/kotlin/com/theprodeogroup/fish/infrastructure/web")
        val pattern = Regex("""^\s*(get|post|put|patch|delete)\("([^"]+)"""", RegexOption.MULTILINE)
        dir.listFiles { f -> f.extension == "kt" }!!.flatMap { file ->
            pattern.findAll(file.readText()).map { RouteDeclaration(it.groupValues[1].uppercase(), it.groupValues[2]) }.toList()
        }.distinct()
    }

    private val companyScoped get() = declarations.filter { it.path.startsWith("/companies/{companyId}") }

    /**
     * Every route that is not scoped by a Company in its path, with why it is acceptable. A NEW route
     * outside /companies/{companyId} fails [every route outside a Company path is known] until it is
     * added here with a reason AND given its own isolation checks (the 17 body-carrying posting routes
     * are covered in CrossCompanyPostingIsolationRouteTest; /health, /jurisdictions and /me hold no
     * Company data).
     */
    private val knownUnscopedRoutes = setOf(
        "GET /health", "GET /jurisdictions", "GET /me",
        "POST /tenants/{tenantId}/companies",
        "POST /sales/record-sale", "POST /sales/record-collection", "POST /sales/record-sales-return", "POST /sales/create-invoice",
        "POST /purchasing/record-obligation", "POST /purchasing/record-payment",
        "POST /inventory/record-receipt", "POST /inventory/record-issue",
        "POST /payroll/record-pay-run", "POST /leave-accruals",
        "POST /leave-accruals/{leaveAccrualId}/remeasure", "POST /leave-accruals/{leaveAccrualId}/utilize",
        "POST /journal-entries", "POST /fixed-assets",
        "POST /fixed-assets/{fixedAssetId}/record-depreciation", "POST /fixed-assets/{fixedAssetId}/assess-impairment",
        "POST /fixed-assets/{fixedAssetId}/dispose"
    )

    @Test
    fun `every route outside a Company path is known`() {
        declarations.size shouldBeGreaterThanOrEqual 40 // the scan found the routes at all

        val unknown = declarations.filterNot { it.path.startsWith("/companies/{companyId}") }
            .map { "${it.method} ${it.path}" }.filterNot { it in knownUnscopedRoutes }
        val gone = knownUnscopedRoutes.filterNot { known -> declarations.any { "${it.method} ${it.path}" == known } }

        withClue("routes not scoped by a Company in the path and not in the known list: $unknown") { unknown.shouldBeEmpty() }
        withClue("known unscoped routes that no longer exist (remove them from the list): $gone") { gone.shouldBeEmpty() }
    }

    /**
     * T15 / G4: the rule "a person must send X-Tenant-Id, a service may omit it" lives in ONE place,
     * `verifyClaimedTenant`. A route that read the header itself (JournalEntryRoutes once did) would keep
     * demanding it of services, or skip the mismatch check, without any test noticing.
     */
    @Test
    fun `only verifyClaimedTenant reads the X-Tenant-Id header`() {
        val dir = File("src/main/kotlin/com/theprodeogroup/fish/infrastructure/web")
        val readers = dir.listFiles { f -> f.extension == "kt" && f.name != "Auth.kt" }!!
            .filter { Regex("""header\(\s*"X-Tenant-Id"\s*\)""").containsMatchIn(it.readText()) }
            .map { it.name }
        withClue("files reading X-Tenant-Id themselves instead of calling verifyClaimedTenant: $readers") { readers.shouldBeEmpty() }
    }

    // ---- a second Tenant ---------------------------------------------------------------------------

    private class OtherTenant(val tenantId: TenantId, val company: Company, val email: String)

    private fun Fixture.otherTenant(): OtherTenant {
        val otherTenantId = TenantId.generate()
        val email = "member-of-b@example.com"
        val userB = User.create(email, "Member of B").also { userRepository.save(it) }
        val companyB = Company.create(otherTenantId, "Tenant B Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP).also { companyRepository.save(it) }
        membershipRepository.save(Membership.grant(userB.id, otherTenantId, Role.OWNER_ADMIN, companyB.id))
        return OtherTenant(otherTenantId, companyB, email)
    }

    private fun fill(template: String, companyId: UUID): String =
        template.replace("{companyId}", companyId.toString()).replace(Regex("""\{[A-Za-z]+\}""")) { UUID.randomUUID().toString() }

    private suspend fun io.ktor.client.HttpClient.call(
        route: RouteDeclaration, companyId: UUID, token: String?, tenantHeader: UUID?
    ) = request(fill(route.path, companyId).let { "/api$it" }) {
        method = HttpMethod.parse(route.method)
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        if (tenantHeader != null) header("X-Tenant-Id", tenantHeader.toString())
        if (route.method != "GET") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
    }

    /**
     * For every Company-scoped route, whatever its method: no token is 401; a missing tenant header is
     * 400; another Tenant's header is 403; a person whose only membership is in ANOTHER Tenant is 403
     * even with the right header; and an unknown Company is 404. Authorization runs before the body is
     * read, so an empty body is enough to prove it.
     */
    @Test
    fun `every Company-scoped route enforces the Tenant boundary`() = testApplication {
        val fixture = Fixture(role = Role.OWNER_ADMIN)
        val other = fixture.otherTenant()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val mine = TestJwtSupport.signToken(TEST_EMAIL)
        val theirs = TestJwtSupport.signToken(other.email)
        val failures = mutableListOf<String>()

        fun expect(route: RouteDeclaration, what: String, expected: HttpStatusCode, actual: HttpStatusCode) {
            if (actual != expected) failures += "${route.method} ${route.path}: $what -> expected ${expected.value}, got ${actual.value}"
        }

        companyScoped.shouldBeEmptyNot()
        for (route in companyScoped) {
            expect(route, "no token", HttpStatusCode.Unauthorized, client.call(route, fixture.company.id.value, null, fixture.tenantId.value).status)
            expect(route, "no tenant header", HttpStatusCode.BadRequest, client.call(route, fixture.company.id.value, mine, null).status)
            expect(route, "another Tenant's header", HttpStatusCode.Forbidden, client.call(route, fixture.company.id.value, mine, other.tenantId.value).status)
            expect(route, "member of another Tenant only", HttpStatusCode.Forbidden, client.call(route, fixture.company.id.value, theirs, fixture.tenantId.value).status)
            expect(route, "unknown Company", HttpStatusCode.NotFound, client.call(route, UUID.randomUUID(), mine, fixture.tenantId.value).status)
        }

        withClue("${failures.size} of ${companyScoped.size * 5} isolation checks failed:\n" + failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    private fun List<RouteDeclaration>.shouldBeEmptyNot() {
        size shouldBeGreaterThanOrEqual 35 // the scan found the Company-scoped routes at all
    }

    /**
     * The Company-in-path routes each service credential actually calls (the G3 allow-list in
     * docs/T15_GL_G2_G3_Design.md, read from each service's GL client).
     */
    private val pathRoutesByService = mapOf(
        "sop" to listOf(
            RouteDeclaration("GET", "/companies/{companyId}/sales-posting-context"),
            RouteDeclaration("GET", "/companies/{companyId}/sales-invoices"),
            RouteDeclaration("POST", "/companies/{companyId}/customer-balances")
        ),
        "pop" to listOf(RouteDeclaration("GET", "/companies/{companyId}/purchase-posting-context")),
        "im" to listOf(RouteDeclaration("GET", "/companies/{companyId}/inventory-posting-context")),
        "hr" to listOf(RouteDeclaration("GET", "/companies/{companyId}/payroll-posting-context"))
    )

    @Test
    fun `every service credential is held to the claimed Tenant on the Company-in-path routes it uses`() = testApplication {
        // T15 / G2 (Femi's D5): valid for all Tenants, but a Tenant CLAIMED in X-Tenant-Id must own the Company.
        // T15 / G4: the header may be omitted; GL then derives the Tenant from the Company.
        val fixture = Fixture(role = Role.OWNER_ADMIN)
        val other = fixture.otherTenant()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val failures = mutableListOf<String>()
        fun expect(service: String, route: RouteDeclaration, what: String, expected: Set<Int>, actual: HttpStatusCode) {
            if (actual.value !in expected) failures += "$service ${route.method} ${route.path}: $what -> expected $expected, got ${actual.value}"
        }

        for ((service, routes) in pathRoutesByService) {
            val token = TestJwtSupport.signServiceToken(service)
            for (route in routes) {
                withClue("$service ${route.path} is no longer a declared route") { (route in declarations) shouldBe true }
                // The only Company-in-path POST a service uses is customer-balances; give it a valid (empty) body.
                val noHeader = if (route.method == "GET") client.call(route, fixture.company.id.value, token, null)
                else client.request("/api" + fill(route.path, fixture.company.id.value)) {
                    method = HttpMethod.Post
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"customerIds": []}""")
                }
                expect(service, route, "no tenant header (G4: derived from the Company)", setOf(200, 409), noHeader.status)
                expect(service, route, "no tenant header, unknown Company", setOf(404), client.call(route, UUID.randomUUID(), token, null).status)
                expect(service, route, "malformed tenant header", setOf(400), client.rawHeader(route, fixture.company.id.value, token, "not-a-uuid").status)
                expect(service, route, "Company of Tenant A, header of Tenant B", setOf(403), client.call(route, fixture.company.id.value, token, other.tenantId.value).status)
                expect(service, route, "Company of Tenant B, header of Tenant A", setOf(403), client.call(route, other.company.id.value, token, fixture.tenantId.value).status)
                expect(service, route, "unknown Company", setOf(404), client.call(route, UUID.randomUUID(), token, fixture.tenantId.value).status)
                if (route.method == "GET") {
                    // Control: the right pairing passes the Tenant check (200, or 409 where the Company has no chart yet).
                    expect(service, route, "right Company and Tenant (control)", setOf(200, 409), client.call(route, fixture.company.id.value, token, fixture.tenantId.value).status)
                }
            }
        }

        withClue("${failures.size} service-credential Tenant checks failed:\n" + failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    // ---- guessing ids, and reading across Tenants --------------------------------------------------

    private suspend fun io.ktor.client.HttpClient.rawHeader(route: RouteDeclaration, companyId: UUID, token: String, tenantHeader: String) =
        request(fill(route.path, companyId).let { "/api$it" }) {
            method = HttpMethod.parse(route.method)
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Tenant-Id", tenantHeader)
            if (route.method != "GET") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }

    @Test
    fun `G4 - omitting the header never widens a service beyond its allow-list`() = testApplication {
        val fixture = Fixture(role = Role.OWNER_ADMIN)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val failures = mutableListOf<String>()
        for ((service, listed) in ServiceEndpointAllowList.allowed) {
            val token = TestJwtSupport.signServiceToken(service)
            for (route in companyScoped.filter { r -> "${r.method} ${r.path}" !in listed }) {
                val response = client.call(route, fixture.company.id.value, token, null)
                if (response.status != HttpStatusCode.Forbidden || "forbidden_endpoint" !in response.bodyAsText()) {
                    failures += "$service ${route.method} ${route.path}: off its list and no header -> ${response.status.value}"
                }
            }
        }
        withClue("${failures.size} off-list calls were not refused:\n" + failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    private suspend fun io.ktor.client.HttpClient.raw(method: HttpMethod, path: String, token: String, tenantHeader: UUID, body: String? = null) =
        request("/api$path") {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Tenant-Id", tenantHeader.toString())
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private fun money(amount: String) = Money(BigDecimal(amount), GBP)

    @Test
    fun `Tenant B cannot reach Tenant A's resources by guessing their ids`() = testApplication {
        val fixture = Fixture(role = Role.OWNER_ADMIN)
        val other = fixture.otherTenant()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val theirs = TestJwtSupport.signToken(other.email)
        val failures = mutableListOf<String>()
        fun check(what: String, expected: Set<Int>, actual: HttpStatusCode) {
            if (actual.value !in expected) failures += "$what -> expected $expected, got ${actual.value}"
        }

        // Tenant A's bank reconciliation, leave accrual and fixed asset.
        val cashA = Account.create(fixture.company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash").also { fixture.accountRepository.save(it) }
        val recA = BankReconciliation.create(cashA.id, TODAY, money("0.00"), emptyList(), emptyList(), GBP)
            .also { fixture.bankReconciliationRepository.save(it, fixture.company.id) }
        val accrualA = LeaveAccrual.create(fixture.company.id, EmployeeId.generate(), GBP).also { fixture.leaveAccrualRepository.save(it) }
        val assetA = FixedAsset.create(fixture.company.id, "Van", AssetCategory.VEHICLES, money("1000.00"), TODAY, 5).also { fixture.fixedAssetRepository.save(it) }

        // Tenant B's own Company, Period and accounts, so its requests are well formed.
        val periodB = Period.create(other.company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also { it.open(); fixture.periodRepository.save(it) }
        val expenseB = Account.create(other.company.id, AccountType.EXPENSE, null, "6100", "Depreciation").also { fixture.accountRepository.save(it) }
        val accumB = Account.create(other.company.id, AccountType.ASSET, AccountClassification.NON_CURRENT, "1210", "Accumulated").also { fixture.accountRepository.save(it) }

        // 1. A's reconciliation id, through B's OWN Company path and header: not found, never the row.
        val recPath = "/companies/${other.company.id.value}/bank-reconciliations/${recA.id.value}"
        check("GET A's reconciliation via B's Company", setOf(404), client.raw(HttpMethod.Get, recPath, theirs, other.tenantId.value).status)
        for (action in listOf("match", "unmatch", "complete", "cancel")) {
            check(
                "POST $action on A's reconciliation via B's Company", setOf(404),
                client.raw(
                    HttpMethod.Post, "$recPath/$action", theirs, other.tenantId.value,
                    """{"statementLineId": "${UUID.randomUUID()}", "journalEntryId": "${UUID.randomUUID()}"}"""
                ).status
            )
        }

        // 2. Through A's Company path, with B's identity: refused whichever Tenant header is sent.
        val recPathA = "/companies/${fixture.company.id.value}/bank-reconciliations/${recA.id.value}"
        check("GET A's reconciliation, B's header", setOf(403), client.raw(HttpMethod.Get, recPathA, theirs, other.tenantId.value).status)
        check("GET A's reconciliation, A's header, B's identity", setOf(403), client.raw(HttpMethod.Get, recPathA, theirs, fixture.tenantId.value).status)

        // 3. A's leave accrual by id: the accrual's own Company decides, so B is refused either way.
        val remeasure = """{"targetAmount": "100.00", "currency": "GBP", "periodId": "${periodB.id.value}", "leaveExpenseAccountId": "${expenseB.id.value}", "accruedLeaveLiabilityAccountId": "${accumB.id.value}", "date": "$TODAY"}"""
        val remeasurePath = "/leave-accruals/${accrualA.id.value}/remeasure"
        check("remeasure A's accrual, B's header", setOf(403), client.raw(HttpMethod.Post, remeasurePath, theirs, other.tenantId.value, remeasure).status)
        check("remeasure A's accrual, A's header, B's identity", setOf(403), client.raw(HttpMethod.Post, remeasurePath, theirs, fixture.tenantId.value, remeasure).status)

        // 4. A's fixed asset id with B's Period and accounts (B is authorized at B): not found, nothing posted.
        val depreciation = """{"depreciationExpenseAccountId": "${expenseB.id.value}", "accumulatedDepreciationAccountId": "${accumB.id.value}", "periodId": "${periodB.id.value}", "date": "$TODAY"}"""
        check("depreciate A's asset with B's Period", setOf(404), client.raw(HttpMethod.Post, "/fixed-assets/${assetA.id.value}/record-depreciation", theirs, other.tenantId.value, depreciation).status)
        fixture.journalEntryRepository.findAllByPeriod(periodB.id).shouldBeEmpty()

        withClue("${failures.size} guess-the-id checks failed:\n" + failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `Tenant B's reads never contain Tenant A's postings`() = testApplication {
        val fixture = Fixture(role = Role.OWNER_ADMIN)
        val other = fixture.otherTenant()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val theirs = TestJwtSupport.signToken(other.email)

        // A has real activity; B has a chart and no postings.
        val periodA = Period.create(fixture.company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also { it.open(); fixture.periodRepository.save(it) }
        val cashA = Account.create(fixture.company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "A Cash").also { fixture.accountRepository.save(it) }
        val salesA = Account.create(fixture.company.id, AccountType.REVENUE, null, "4000", "A Sales").also { fixture.accountRepository.save(it) }
        JournalEntry.create(
            periodA.id, TODAY,
            listOf(JournalLine(cashA.id, money("999.00"), TransactionSide.DEBIT), JournalLine(salesA.id, money("999.00"), TransactionSide.CREDIT)),
            JournalSource.MANUAL
        ).also { it.post(); fixture.journalEntryRepository.save(it) }
        Account.create(other.company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "B Cash").also { fixture.accountRepository.save(it) }
        Account.create(other.company.id, AccountType.REVENUE, null, "4000", "B Sales").also { fixture.accountRepository.save(it) }
        Period.create(other.company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also { it.open(); fixture.periodRepository.save(it) }

        val base = "/companies/${other.company.id.value}"
        val trial = client.raw(HttpMethod.Get, "$base/reports/trial-balance", theirs, other.tenantId.value)
        trial.status shouldBe HttpStatusCode.OK
        val trialBody: TrialBalanceResponseDto = trial.body()
        trialBody.totalDebits shouldBe "0.00"
        trialBody.lines.map { it.name } shouldBe listOf("B Cash", "B Sales")

        client.raw(HttpMethod.Get, "$base/journal-entries", theirs, other.tenantId.value).bodyAsText() shouldBe "[]"
        val accounts = client.raw(HttpMethod.Get, "$base/accounts", theirs, other.tenantId.value).bodyAsText()
        withClue("B's account list: $accounts") { (accounts.contains("A Cash") || accounts.contains("A Sales")) shouldBe false }
        client.raw(HttpMethod.Get, "$base/reports/balance-sheet", theirs, other.tenantId.value).body<BalanceSheetResponseDto>().totalAssets shouldBe "0.00"
        client.raw(HttpMethod.Get, "$base/reports/profit-and-loss", theirs, other.tenantId.value).body<ProfitAndLossResponseDto>().totalRevenue shouldBe "0.00"
        client.raw(HttpMethod.Get, "$base/reports/cash-flow", theirs, other.tenantId.value).body<CashFlowResponseDto>().netCashFlow shouldBe "0.00"
    }

    // ---- T15 G3: each service credential may call only the GL endpoints it needs --------------------

    /**
     * Declared routes that no service credential may call: reachable by people only, or not by anyone
     * authenticated at all. A NEW route has to be added to a service's allow-list in `ServiceEndpointAllowList`
     * or to this set, so no route is ever left unclassified.
     */
    private val peopleOnlyRoutes = setOf(
        "GET /health", "GET /me", "GET /jurisdictions", "POST /tenants/{tenantId}/companies",
        "POST /sales/create-invoice", "POST /fixed-assets",
        "POST /fixed-assets/{fixedAssetId}/record-depreciation", "POST /fixed-assets/{fixedAssetId}/assess-impairment",
        "POST /fixed-assets/{fixedAssetId}/dispose",
        "GET /companies/{companyId}/accounts", "POST /companies/{companyId}/accounts",
        "POST /companies/{companyId}/accounts/{accountId}/opening-balance",
        "PUT /companies/{companyId}/accounts/{accountId}/expense-classification",
        "PUT /companies/{companyId}/accounts/{accountId}/cash-book-kind",
        "GET /companies/{companyId}/cash-books", "GET /companies/{companyId}/cash-books/{accountId}",
        "POST /companies/{companyId}/standard-accounts",
        "POST /companies/{companyId}/cash-books/{accountId}/receipts", "POST /companies/{companyId}/cash-books/{accountId}/payments",
        "GET /companies/{companyId}/cash-books/{accountId}/counter-accounts",
        "POST /companies/{companyId}/cash-books/{accountId}/transfers", "POST /companies/{companyId}/cash-books/{accountId}/entries/{entryId}/undo",
        "GET /companies/{companyId}/journal-entries", "GET /companies/{companyId}/customers",
        "GET /companies/{companyId}/fixed-assets", "GET /companies/{companyId}/money-velocity",
        "GET /companies/{companyId}/expense-velocity", "GET /companies/{companyId}/sales-to-expense-ratio",
        "GET /companies/{companyId}/vat-categories",
        "GET /companies/{companyId}/reports/balance-sheet", "GET /companies/{companyId}/reports/profit-and-loss",
        "GET /companies/{companyId}/reports/cash-flow", "GET /companies/{companyId}/reports/working-capital",
        "GET /companies/{companyId}/reports/trial-balance", "GET /companies/{companyId}/reports/trading-profit-and-loss",
        "GET /companies/{companyId}/reports/fixed-asset-register",
        "POST /companies/{companyId}/accounts-payable-aging", "POST /companies/{companyId}/accounts-receivable-aging",
        "POST /companies/{companyId}/supplier-balances",
        "GET /companies/{companyId}/tax", "POST /companies/{companyId}/tax", "POST /companies/{companyId}/vat-return",
        "GET /companies/{companyId}/bank-reconciliations", "POST /companies/{companyId}/bank-reconciliations",
        "GET /companies/{companyId}/bank-reconciliations/{id}", "POST /companies/{companyId}/bank-reconciliations/{id}/match",
        "POST /companies/{companyId}/bank-reconciliations/{id}/unmatch", "POST /companies/{companyId}/bank-reconciliations/{id}/complete",
        "POST /companies/{companyId}/bank-reconciliations/{id}/cancel",
        "POST /companies/{companyId}/opening-imports/gl-balances", "POST /companies/{companyId}/opening-imports/gl-balances/validate",
        "POST /companies/{companyId}/opening-imports/fixed-assets", "POST /companies/{companyId}/opening-imports/fixed-assets/validate"
    )

    private val allowListedRoutes get() = ServiceEndpointAllowList.allowed.values.flatten().toSet()

    @Test
    fun `every declared route is on a service allow-list or classified people-only, and every allow-list entry is a real route`() {
        val declared = declarations.map { "${it.method} ${it.path}" }.toSet()

        val unclassified = declared - allowListedRoutes - peopleOnlyRoutes
        val notDeclared = (allowListedRoutes + peopleOnlyRoutes) - declared

        withClue("declared routes on no service allow-list and not classified people-only (add each to one): $unclassified") { unclassified.shouldBeEmpty() }
        withClue("allow-list or people-only entries that are not declared routes (stale): $notDeclared") { notDeclared.shouldBeEmpty() }
        withClue("a route cannot be both allow-listed for a service and people-only") { (allowListedRoutes intersect peopleOnlyRoutes).shouldBeEmpty() }
    }

    @Test
    fun `a service credential reaches exactly the routes on its list and is refused everywhere else`() = testApplication {
        val fixture = Fixture(role = Role.OWNER_ADMIN)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val failures = mutableListOf<String>()

        for (service in TestJwtSupport.SERVICES) {
            val token = TestJwtSupport.signServiceToken(service)
            val allowed = ServiceEndpointAllowList.allowed.getValue(service)
            for (route in declarations.filter { it.path != "/health" }) {
                val key = "${route.method} ${route.path}"
                val response = client.call(route, fixture.company.id.value, token, fixture.tenantId.value)
                val refused = response.status == HttpStatusCode.Forbidden && response.bodyAsText().contains("forbidden_endpoint")
                if (key in allowed && refused) failures += "$service was refused on its own route $key"
                if (key !in allowed && !refused) failures += "$service was NOT refused on $key (got ${response.status.value})"
            }
        }
        withClue("${failures.size} allow-list checks failed:\n" + failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `people are not affected by the service allow-list`() = testApplication {
        val fixture = Fixture(role = Role.OWNER_ADMIN)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val person = TestJwtSupport.signToken(TEST_EMAIL)

        // A route no service may call, used by a person: reaches the handler (a report, 200 or 409 for no chart).
        val response = client.call(RouteDeclaration("GET", "/companies/{companyId}/reports/trial-balance"), fixture.company.id.value, person, fixture.tenantId.value)
        (response.status.value in setOf(200, 409)) shouldBe true
        response.bodyAsText().contains("forbidden_endpoint") shouldBe false
    }

    @Test
    fun `in log mode an off-list service call is answered normally instead of refused`() = testApplication {
        val fixture = Fixture(role = Role.OWNER_ADMIN)
        application { fixture.installInto(this, ServiceAllowListMode.LOG) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.call(
            RouteDeclaration("GET", "/companies/{companyId}/reports/trial-balance"),
            fixture.company.id.value, TestJwtSupport.signServiceToken("hr"), fixture.tenantId.value
        )

        response.bodyAsText().contains("forbidden_endpoint") shouldBe false
    }

    @Test
    fun `the would-block log line names the service, method and path template, and carries no id or token`() {
        val id = UUID.randomUUID()
        val line = ServiceEndpointAllowList.wouldBlockMessage("sop", "GET", "/api/companies/$id/reports/trial-balance")

        line.contains("sop") shouldBe true
        line.contains("GET") shouldBe true
        line.contains("/companies/{id}/reports/trial-balance") shouldBe true
        line.contains(id.toString()) shouldBe false
    }

    @Test
    fun `an unknown service name, or a service caller with no name, is never allowed`() {
        ServiceEndpointAllowList.isAllowed("nobody", "GET", "/api/companies/${UUID.randomUUID()}/sales-posting-context") shouldBe false
        ServiceEndpointAllowList.isAllowed(null, "GET", "/api/companies/${UUID.randomUUID()}/sales-posting-context") shouldBe false
        ServiceEndpointAllowList.isAllowed("sop", "GET", "/api/companies/${UUID.randomUUID()}/sales-posting-context") shouldBe true
        ServiceEndpointAllowList.isAllowed("sop", "POST", "/api/companies/${UUID.randomUUID()}/sales-posting-context") shouldBe false
        ServiceEndpointAllowList.isAllowed("pop", "GET", "/api/companies/${UUID.randomUUID()}/sales-posting-context") shouldBe false
    }

    // ---- the second wall: Company against Company inside ONE Tenant -------------------------------
    //
    // Femi's two-walls definition (via CM, 2026-10-09): Tenant against Tenant, AND Company against Company
    // inside one Tenant - each business has its own GL, consolidation comes later and on purpose. The same
    // owner holds Companies A and B; nothing of A may show under B, and B's path with A's id finds nothing.

    @Test
    fun `one owner's second Company never shows the first Company's records`() = testApplication {
        val fixture = Fixture(role = Role.OWNER_ADMIN)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = TestJwtSupport.signToken(TEST_EMAIL)

        // The owner's Company A has real records.
        val periodA = Period.create(fixture.company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also { it.open(); fixture.periodRepository.save(it) }
        val cashA = Account.create(fixture.company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "A Cash").also { fixture.accountRepository.save(it) }
        val salesA = Account.create(fixture.company.id, AccountType.REVENUE, null, "4000", "A Sales").also { fixture.accountRepository.save(it) }
        JournalEntry.create(
            periodA.id, TODAY,
            listOf(JournalLine(cashA.id, money("777.00"), TransactionSide.DEBIT), JournalLine(salesA.id, money("777.00"), TransactionSide.CREDIT)),
            JournalSource.MANUAL
        ).also { it.post(); fixture.journalEntryRepository.save(it) }
        FixedAsset.create(fixture.company.id, "A Van", AssetCategory.VEHICLES, money("5000.00"), TODAY, 5).also { fixture.fixedAssetRepository.save(it) }
        BankReconciliation.create(cashA.id, TODAY, money("0.00"), emptyList(), emptyList(), GBP)
            .also { fixture.bankReconciliationRepository.save(it, fixture.company.id) }

        // The same owner's Company B, in the same Tenant, with a chart but no activity.
        val companyB = Company.create(fixture.tenantId, "Sibling Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP).also { fixture.companyRepository.save(it) }
        Account.create(companyB.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "B Cash").also { fixture.accountRepository.save(it) }
        Account.create(companyB.id, AccountType.REVENUE, null, "4000", "B Sales").also { fixture.accountRepository.save(it) }
        Period.create(companyB.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also { it.open(); fixture.periodRepository.save(it) }

        val base = "/companies/${companyB.id.value}"
        val trial: TrialBalanceResponseDto = client.raw(HttpMethod.Get, "$base/reports/trial-balance", owner, fixture.tenantId.value).body()
        trial.totalDebits shouldBe "0.00"
        trial.lines.map { it.name } shouldBe listOf("B Cash", "B Sales")
        client.raw(HttpMethod.Get, "$base/journal-entries", owner, fixture.tenantId.value).bodyAsText() shouldBe "[]"
        val accounts = client.raw(HttpMethod.Get, "$base/accounts", owner, fixture.tenantId.value).bodyAsText()
        withClue("B's account list: $accounts") { accounts.contains("A Cash") shouldBe false }
        client.raw(HttpMethod.Get, "$base/reports/balance-sheet", owner, fixture.tenantId.value).body<BalanceSheetResponseDto>().totalAssets shouldBe "0.00"
        client.raw(HttpMethod.Get, "$base/reports/profit-and-loss", owner, fixture.tenantId.value).body<ProfitAndLossResponseDto>().totalRevenue shouldBe "0.00"
        client.raw(HttpMethod.Get, "$base/reports/cash-flow", owner, fixture.tenantId.value).body<CashFlowResponseDto>().netCashFlow shouldBe "0.00"
        client.raw(HttpMethod.Get, "$base/reports/fixed-asset-register", owner, fixture.tenantId.value).bodyAsText().contains("A Van") shouldBe false
        client.raw(HttpMethod.Get, "$base/bank-reconciliations", owner, fixture.tenantId.value).bodyAsText().contains("reconciliations\":[]") shouldBe true
    }

    @Test
    fun `under the second Company, the first Company's record ids find nothing and nothing is posted`() = testApplication {
        val fixture = Fixture(role = Role.OWNER_ADMIN)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = TestJwtSupport.signToken(TEST_EMAIL)
        val failures = mutableListOf<String>()
        fun check(what: String, expected: Set<Int>, actual: HttpStatusCode) {
            if (actual.value !in expected) failures += "$what -> expected $expected, got ${actual.value}"
        }

        val cashA = Account.create(fixture.company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "A Cash").also { fixture.accountRepository.save(it) }
        val recA = BankReconciliation.create(cashA.id, TODAY, money("0.00"), emptyList(), emptyList(), GBP)
            .also { fixture.bankReconciliationRepository.save(it, fixture.company.id) }
        val assetA = FixedAsset.create(fixture.company.id, "A Van", AssetCategory.VEHICLES, money("1000.00"), TODAY, 5).also { fixture.fixedAssetRepository.save(it) }
        val periodA = Period.create(fixture.company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also { it.open(); fixture.periodRepository.save(it) }

        val companyB = Company.create(fixture.tenantId, "Sibling Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP).also { fixture.companyRepository.save(it) }
        // The owner holds B too, with WRITE there: otherwise every write below would stop at 403 before it
        // reached the data wall this test is about.
        fixture.membershipRepository.save(Membership.grant(fixture.user.id, fixture.tenantId, Role.OWNER_ADMIN, companyB.id))
        val periodB = Period.create(companyB.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also { it.open(); fixture.periodRepository.save(it) }
        val expenseB = Account.create(companyB.id, AccountType.EXPENSE, null, "6100", "B Depreciation").also { fixture.accountRepository.save(it) }
        val accumB = Account.create(companyB.id, AccountType.ASSET, AccountClassification.NON_CURRENT, "1210", "B Accumulated").also { fixture.accountRepository.save(it) }

        // A's bank reconciliation id through B's Company path: the owner may use B, but there is no such record there.
        val recViaB = "/companies/${companyB.id.value}/bank-reconciliations/${recA.id.value}"
        check("GET A's reconciliation via B", setOf(404), client.raw(HttpMethod.Get, recViaB, owner, fixture.tenantId.value).status)
        for (action in listOf("match", "unmatch", "complete", "cancel")) {
            check(
                "POST $action on A's reconciliation via B", setOf(403, 404),
                client.raw(
                    HttpMethod.Post, "$recViaB/$action", owner, fixture.tenantId.value,
                    """{"statementLineId": "${UUID.randomUUID()}", "journalEntryId": "${UUID.randomUUID()}"}"""
                ).status
            )
        }
        // A's fixed asset with B's Period and accounts, and B's posting built from A's Period: not found, nothing posted.
        val depreciation = """{"depreciationExpenseAccountId": "${expenseB.id.value}", "accumulatedDepreciationAccountId": "${accumB.id.value}", "periodId": "${periodB.id.value}", "date": "$TODAY"}"""
        check("depreciate A's asset with B's Period", setOf(403, 404), client.raw(HttpMethod.Post, "/fixed-assets/${assetA.id.value}/record-depreciation", owner, fixture.tenantId.value, depreciation).status)
        // A well-formed sale for Company B built entirely from A's Period and A's accounts: the only thing
        // that can stop it is the wall between the two Companies.
        val arA = Account.create(fixture.company.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "A Receivable").also { fixture.accountRepository.save(it) }
        val revenueA = Account.create(fixture.company.id, AccountType.REVENUE, null, "4000", "A Sales").also { fixture.accountRepository.save(it) }
        val vatA = Account.create(fixture.company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2150", "A VAT").also { fixture.accountRepository.save(it) }
        val saleInB = """{"companyId": "${companyB.id.value}", "periodId": "${periodA.id.value}", "date": "$TODAY", "arControlAccountId": "${arA.id.value}", "revenueAccountId": "${revenueA.id.value}", "vatControlAccountId": "${vatA.id.value}", "lines": [{"netAmount": "10.00", "vatCategory": "EXEMPT"}], "currency": "GBP", "customerId": "${UUID.randomUUID()}"}"""
        check("sale for B using A's Period and accounts", setOf(404), client.raw(HttpMethod.Post, "/sales/record-sale", owner, fixture.tenantId.value, saleInB).status)
        // Control: the same sale for A itself is fine, so the 404 above is the wall and not a malformed body.
        val saleInA = saleInB.replace(companyB.id.value.toString(), fixture.company.id.value.toString())
        check("the same sale for A itself", setOf(200), client.raw(HttpMethod.Post, "/sales/record-sale", owner, fixture.tenantId.value, saleInA).status)

        // A holds only the control sale made for A itself; B holds nothing.
        fixture.journalEntryRepository.findAllByPeriod(periodA.id).size shouldBe 1
        fixture.journalEntryRepository.findAllByPeriod(periodB.id).shouldBeEmpty()
        withClue("${failures.size} same-owner cross-Company checks failed:\n" + failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }
}
