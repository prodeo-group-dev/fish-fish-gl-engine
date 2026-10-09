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
 * `POST /sales/record-sale` and `POST /sales/record-collection` via
 * Ktor's `testApplication` (docs/Sales_Order_Processing_DDD_Design.md
 * Section 0) - unlike [SalesOrderRoutesTest], there's no owning
 * aggregate to derive tenant scoping from, so every request body
 * carries `companyId` directly.
 */
class CrossCompanyPostingIsolationRouteTest {

    private class Fixture(role: Role = Role.ACCOUNTANT, jurisdiction: Jurisdiction = Jurisdiction.UK, accessLevel: AccessLevel = Membership.defaultAccessLevelFor(role)) {
        val userRepository = FakeUserRepository()
        val membershipRepository = FakeMembershipRepository()
        val companyRepository = FakeCompanyRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
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

    /**
     * One Company's books: an open Period plus one account of every kind any
     * posting route needs. "A" is the Company the caller is authorized for;
     * "B" is some other Company (another Tenant's, or a second Company in the
     * same Tenant) whose period and accounts the caller has no right to.
     */
    private class World(private val fixture: Fixture, val company: Company) {
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            fixture.periodRepository.save(it)
        }
        private fun account(type: AccountType, code: String, classification: AccountClassification? = null) =
            Account.create(company.id, type, classification, code, "Acct $code").also { fixture.accountRepository.save(it) }

        val ar = account(AccountType.ASSET, "1100", AccountClassification.CURRENT).id.value
        val cash = account(AccountType.ASSET, "1000", AccountClassification.CURRENT).id.value
        val inventory = account(AccountType.ASSET, "1300", AccountClassification.CURRENT).id.value
        val revenue = account(AccountType.REVENUE, "4000").id.value
        val salesReturns = account(AccountType.REVENUE, "4100").id.value
        val expense = account(AccountType.EXPENSE, "5000").id.value
        val expense2 = account(AccountType.EXPENSE, "5100").id.value
        val vat = account(AccountType.LIABILITY, "2150", AccountClassification.CURRENT).id.value
        val ap = account(AccountType.LIABILITY, "2000", AccountClassification.CURRENT).id.value
        val fixedAsset = account(AccountType.ASSET, "1200", AccountClassification.NON_CURRENT).id.value
        val accumulatedDepreciation = account(AccountType.ASSET, "1210", AccountClassification.NON_CURRENT).id.value
        val depreciationExpense = account(AccountType.EXPENSE, "6100").id.value

        fun entries(fixture: Fixture) = fixture.journalEntryRepository.findAllByPeriod(period.id)
    }

    private fun Fixture.worldA() = World(this, company)
    private fun Fixture.worldOfAnotherTenant() =
        World(this, Company.create(TenantId.generate(), "Other Tenant Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP).also { companyRepository.save(it) })
    private fun Fixture.worldOfASiblingCompany() =
        World(this, Company.create(tenantId, "Sibling Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP).also { companyRepository.save(it) })

    /** A posting route: its path and how to build its body from the Company the caller claims, a period and an account set. */
    private class Route(val name: String, val path: String, val body: (companyId: UUID, period: World, accounts: World) -> String)

    private val postingRoutes = listOf(
        Route("record-sale", "/api/sales/record-sale") { c, p, a ->
            """{"companyId": "$c", "periodId": "${p.period.id.value}", "date": "$TODAY", "arControlAccountId": "${a.ar}",
              |"revenueAccountId": "${a.revenue}", "vatControlAccountId": "${a.vat}",
              |"lines": [{"netAmount": "100.00", "vatCategory": "EXEMPT"}], "currency": "GBP", "customerId": "${UUID.randomUUID()}"}""".trimMargin()
        },
        Route("record-collection", "/api/sales/record-collection") { c, p, a ->
            """{"companyId": "$c", "periodId": "${p.period.id.value}", "date": "$TODAY", "settlementAccountId": "${a.cash}",
              |"arControlAccountId": "${a.ar}", "amount": "100.00", "currency": "GBP", "customerId": "${UUID.randomUUID()}"}""".trimMargin()
        },
        Route("record-sales-return", "/api/sales/record-sales-return") { c, p, a ->
            """{"companyId": "$c", "periodId": "${p.period.id.value}", "date": "$TODAY", "salesReturnsAccountId": "${a.salesReturns}",
              |"arControlAccountId": "${a.ar}", "amount": "100.00", "currency": "GBP", "customerId": "${UUID.randomUUID()}"}""".trimMargin()
        },
        Route("record-obligation", "/api/purchasing/record-obligation") { c, p, a ->
            """{"companyId": "$c", "periodId": "${p.period.id.value}", "date": "$TODAY", "expenseOrAssetAccountId": "${a.expense}",
              |"apControlAccountId": "${a.ap}", "vatControlAccountId": "${a.vat}",
              |"lines": [{"netAmount": "100.00", "vatCategory": "EXEMPT"}], "currency": "GBP", "supplierId": "${UUID.randomUUID()}"}""".trimMargin()
        },
        Route("record-payment", "/api/purchasing/record-payment") { c, p, a ->
            """{"companyId": "$c", "periodId": "${p.period.id.value}", "date": "$TODAY", "apControlAccountId": "${a.ap}",
              |"settlementAccountId": "${a.cash}", "amount": "100.00", "currency": "GBP", "supplierId": "${UUID.randomUUID()}"}""".trimMargin()
        },
        Route("record-receipt", "/api/inventory/record-receipt") { c, p, a ->
            """{"companyId": "$c", "periodId": "${p.period.id.value}", "date": "$TODAY", "inventoryAssetAccountId": "${a.inventory}",
              |"contraAccountId": "${a.ap}", "committedCost": "100.00", "committedCostCurrency": "GBP", "itemId": "${UUID.randomUUID()}"}""".trimMargin()
        },
        Route("record-issue", "/api/inventory/record-issue") { c, p, a ->
            """{"companyId": "$c", "periodId": "${p.period.id.value}", "date": "$TODAY", "contraAccountId": "${a.expense}",
              |"inventoryAssetAccountId": "${a.inventory}", "committedCost": "100.00", "committedCostCurrency": "GBP", "itemId": "${UUID.randomUUID()}"}""".trimMargin()
        },
        Route("create-fixed-asset", "/api/fixed-assets") { c, p, a ->
            """{"companyId": "$c", "name": "Van", "category": "VEHICLES", "cost": "1000.00", "currency": "GBP",
              |"acquisitionDate": "$TODAY", "usefulLifeYears": 5, "periodId": "${p.period.id.value}", "fixedAssetAccountId": "${a.fixedAsset}",
              |"fundingMethod": "CASH", "cashAccountId": "${a.cash}"}""".trimMargin()
        },
        Route("record-pay-run", "/api/payroll/record-pay-run") { c, p, a ->
            """{"companyId": "$c", "periodId": "${p.period.id.value}", "date": "$TODAY", "totalWages": "100.00", "totalSalaries": "200.00",
              |"currency": "GBP", "wagesExpenseAccountId": "${a.expense}", "salariesExpenseAccountId": "${a.expense2}", "cashAccountId": "${a.cash}"}""".trimMargin()
        }
    )

    private suspend fun io.ktor.client.HttpClient.postJson(path: String, tenant: TenantId, token: String, body: String) =
        post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Tenant-Id", tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun every(variant: String, token: (Route) -> String, world: Fixture.() -> World, routes: List<Route> = postingRoutes) {
        for (route in routes) {
            testApplication {
                val fixture = Fixture()
                application { fixture.installInto(this) }
                val client = createClient { install(ContentNegotiation) { json() } }
                val a = fixture.worldA()
                val b = fixture.world()

                // Control: the caller's own period and accounts still post.
                val own = client.postJson(route.path, fixture.tenantId, token(route), route.body(fixture.company.id.value, a, a))
                withClue("${route.name} control ($variant) -> ${own.bodyAsText()}") { own.status shouldBe HttpStatusCode.OK }

                // Attack: authorized at A, but B's period and B's accounts.
                val response = client.postJson(route.path, fixture.tenantId, token(route), route.body(fixture.company.id.value, b, b))
                val text = response.bodyAsText()
                withClue("${route.name} cross-company ($variant) -> $text") {
                    response.status shouldBe HttpStatusCode.NotFound
                    text shouldContain "period_not_found"
                    b.entries(fixture).shouldBeEmpty()
                }
            }
        }
    }

    /** The posting routes a service credential may call at all (T15 / G3): the ones on some service's allow-list. */
    private fun routesServicesUse(): List<Route> = postingRoutes.filter { route -> postingRoutesByService.values.any { route.name in it } }

    /** The token of the one service whose allow-list holds [route]. */
    private fun tokenOfTheServiceThatOwns(route: Route): String =
        TestJwtSupport.signServiceToken(postingRoutesByService.entries.first { route.name in it.value }.key)

    @Test
    fun `a person authorized at Company A cannot post into another Tenant's period via any posting route`() =
        every("person, other tenant", { TestJwtSupport.signToken(TEST_EMAIL) }, { worldOfAnotherTenant() })

    @Test
    fun `a person authorized at Company A cannot post into a sibling Company's period via any posting route`() =
        every("person, sibling company", { TestJwtSupport.signToken(TEST_EMAIL) }, { worldOfASiblingCompany() })

    @Test
    fun `a service credential authorized at Company A cannot post into another Tenant's period via any posting route`() =
        every("service, other tenant", ::tokenOfTheServiceThatOwns, { worldOfAnotherTenant() }, routesServicesUse())

    @Test
    fun `a service credential cannot post into a sibling Company's period via any posting route`() =
        every("service, sibling company", ::tokenOfTheServiceThatOwns, { worldOfASiblingCompany() }, routesServicesUse())

    /**
     * The posting routes each service credential actually calls (the G3 allow-list in
     * docs/T15_GL_G2_G3_Design.md, read from each service's GL client).
     */
    private val postingRoutesByService = mapOf(
        "sop" to listOf("record-sale", "record-collection", "record-sales-return"),
        "pop" to listOf("record-obligation", "record-payment"),
        "im" to listOf("record-receipt", "record-issue"),
        "hr" to listOf("record-pay-run")
    )

    @Test
    fun `every service credential is held to the claimed Tenant on the posting routes it uses`() = testApplication {
        // T15 / G2 (Femi's D5): service logins are valid for all Tenants, but a Tenant a request CLAIMS in
        // X-Tenant-Id must own the Company it names: either wrong pairing 403, unknown Company 404.
        // T15 / G4: a service may also OMIT the header; GL then derives the Tenant from the Company and posts.
        // Each credential is exercised on the routes it really calls.
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val a = fixture.worldA()
        val b = fixture.worldOfAnotherTenant()
        val tenantA = fixture.tenantId.value
        val tenantB = b.company.tenantId.value
        val failures = mutableListOf<String>()

        suspend fun send(route: Route, token: String, header: java.util.UUID?, body: String, rawHeader: String? = null) = client.post(route.path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (header != null) header("X-Tenant-Id", header.toString())
            if (rawHeader != null) header("X-Tenant-Id", rawHeader)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        for ((service, names) in postingRoutesByService) {
            val token = TestJwtSupport.signServiceToken(service)
            for (route in postingRoutes.filter { it.name in names }) {
                fun expect(what: String, expected: HttpStatusCode, actual: HttpStatusCode) {
                    if (actual != expected) failures += "$service ${route.name}: $what -> expected ${expected.value}, got ${actual.value}"
                }
                val bodyA = route.body(fixture.company.id.value, a, a)
                val bodyB = route.body(b.company.id.value, b, b)
                expect("Company A with Tenant A's header (control)", HttpStatusCode.OK, send(route, token, tenantA, bodyA).status)
                expect("no tenant header (G4: derived from the Company)", HttpStatusCode.OK, send(route, token, null, route.body(fixture.company.id.value, a, a)).status)
                expect("no tenant header, unknown Company", HttpStatusCode.NotFound, send(route, token, null, route.body(java.util.UUID.randomUUID(), a, a)).status)
                expect("malformed tenant header", HttpStatusCode.BadRequest, send(route, token, null, bodyA, rawHeader = "not-a-uuid").status)
                expect("Company of Tenant A, header of Tenant B", HttpStatusCode.Forbidden, send(route, token, tenantB, bodyA).status)
                expect("Company of Tenant B, header of Tenant A", HttpStatusCode.Forbidden, send(route, token, tenantA, bodyB).status)
                expect("unknown Company", HttpStatusCode.NotFound, send(route, token, tenantA, route.body(java.util.UUID.randomUUID(), a, a)).status)
            }
        }

        b.entries(fixture).shouldBeEmpty()
        withClue("${failures.size} service-credential Tenant checks failed:\n" + failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `a person must still send X-Tenant-Id on every posting route, and nothing is posted without it`() {
        for (route in postingRoutes) {
            testApplication {
                val fixture = Fixture()
                application { fixture.installInto(this) }
                val client = createClient { install(ContentNegotiation) { json() } }
                val a = fixture.worldA()
                val response = client.post(route.path) {
                    header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
                    contentType(ContentType.Application.Json)
                    setBody(route.body(fixture.company.id.value, a, a))
                }
                withClue("${route.name} -> ${response.bodyAsText()}") { response.status shouldBe HttpStatusCode.BadRequest }
                a.entries(fixture).shouldBeEmpty()
            }
        }
    }

    @Test
    fun `A's own period with B's accounts is refused and nothing is posted`() {
        for (route in postingRoutes) {
            testApplication {
                val fixture = Fixture()
                application { fixture.installInto(this) }
                val client = createClient { install(ContentNegotiation) { json() } }
                val a = fixture.worldA()
                val b = fixture.worldOfAnotherTenant()

                val response = client.postJson(route.path, fixture.tenantId, TestJwtSupport.signToken(TEST_EMAIL), route.body(fixture.company.id.value, a, b))
                withClue("${route.name} -> ${response.bodyAsText()}") { response.status.value shouldBe 404 }
                a.entries(fixture).shouldBeEmpty()
                b.entries(fixture).shouldBeEmpty()
            }
        }
    }

    private fun assetOf(fixture: Fixture, w: World): String =
        FixedAsset.create(w.company.id, "Van", AssetCategory.VEHICLES, Money(java.math.BigDecimal("10000.00"), GBP), TODAY, 5)
            .also { fixture.fixedAssetRepository.save(it) }.id.value.toString()

    @Test
    fun `a fixed-asset action cannot pair B's asset with A's period`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val a = fixture.worldA()
        val b = fixture.worldOfAnotherTenant()
        val assetOfB = assetOf(fixture, b)

        val response = client.postJson(
            "/api/fixed-assets/$assetOfB/record-depreciation", fixture.tenantId, TestJwtSupport.signToken(TEST_EMAIL),
            """{"depreciationExpenseAccountId": "${b.depreciationExpense}", "accumulatedDepreciationAccountId": "${b.accumulatedDepreciation}",
              |"periodId": "${a.period.id.value}", "date": "$TODAY"}""".trimMargin()
        )

        withClue(response.bodyAsText()) { response.status shouldBe HttpStatusCode.NotFound }
        a.entries(fixture).shouldBeEmpty()
    }
}
