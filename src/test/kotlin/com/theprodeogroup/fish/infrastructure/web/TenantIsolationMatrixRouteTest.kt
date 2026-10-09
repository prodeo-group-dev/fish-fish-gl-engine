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
        val vatReturnRepository = FakeVatReturnRepository()
        val openingImportBatchRepository = FakeOpeningImportBatchRepository()
        val openingImportRowResultRepository = FakeOpeningImportRowResultRepository()
        val importGlBalancesUseCase = ImportGlBalancesUseCase(companyRepository, accountRepository, recordOpeningBalanceUseCase, openingImportBatchRepository, openingImportRowResultRepository)
        val importFixedAssetsUseCase = ImportFixedAssetsUseCase(companyRepository, periodRepository, accountRepository, fixedAssetRepository, createFixedAssetUseCase, openingImportBatchRepository, openingImportRowResultRepository)
        val bankReconciliationRepository = FakeBankReconciliationRepository()



        fun installInto(app: Application) {
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
                popServiceVerifier = TestJwtSupport.popServiceVerifier(),
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

    // ---- guessing ids, and reading across Tenants --------------------------------------------------

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
}
