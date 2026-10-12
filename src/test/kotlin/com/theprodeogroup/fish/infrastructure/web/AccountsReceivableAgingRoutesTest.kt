package com.theprodeogroup.fish.infrastructure.web

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.sales.CustomerId
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
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

private val GBP: Currency = Currency.getInstance("GBP")
private const val ADMIN_EMAIL = "founder@example.com"
private val TODAY: LocalDate = LocalDate.now()

/** `POST /companies/{companyId}/accounts-receivable-aging` - see AccountsReceivableAgingRoutes.kt's own KDoc. */
class AccountsReceivableAgingRoutesTest {

    private class Fixture(configureAccounts: Boolean = true) {
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

        val period = Period.create(company.id, PeriodType.MONTH, TODAY.minusDays(100), TODAY.plusDays(20)).also {
            it.open()
            periodRepository.save(it)
        }

        val arAccount = if (configureAccounts) {
            Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable").also { accountRepository.save(it) }
        } else null
        val revenueAccount = if (configureAccounts) {
            Account.create(company.id, AccountType.REVENUE, null, "4000", "Revenue").also { accountRepository.save(it) }
        } else null
        val apAccount = if (configureAccounts) {
            Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2000", "Accounts Payable").also { accountRepository.save(it) }
        } else null

        fun postCharge(supplierId: UUID, amount: String, chargeDate: LocalDate) {
            val entry = JournalEntry.create(
                period.id, chargeDate,
                listOf(
                    JournalLine(revenueAccount!!.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
                    JournalLine(apAccount!!.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT, mapOf(DimensionType.VENDOR to supplierId.toString()))
                ),
                JournalSource.INTEGRATION, "Purchase"
            )
            entry.post()
            journalEntryRepository.save(entry)
        }

        fun postSale(customerId: CustomerId, amount: String, saleDate: LocalDate = TODAY) {
            val lines = listOf(
                JournalLine(arAccount!!.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT, mapOf(DimensionType.CUSTOMER to customerId.value.toString())),
                JournalLine(revenueAccount!!.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
            )
            val entry = JournalEntry.create(period.id, saleDate, lines, JournalSource.INTEGRATION, "Sale")
            entry.post()
            journalEntryRepository.save(entry)
        }

        fun postReceipt(customerId: CustomerId, amount: String, receiptDate: LocalDate = TODAY) {
            val lines = listOf(
                JournalLine(revenueAccount!!.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
                JournalLine(arAccount!!.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT, mapOf(DimensionType.CUSTOMER to customerId.value.toString()))
            )
            val entry = JournalEntry.create(period.id, receiptDate, lines, JournalSource.INTEGRATION, "Receipt")
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
    fun `given a sale over 90 days old, when POST accounts-receivable-aging is called, then it reports the OVER_90 bucket`() = testApplication {
        val fixture = Fixture()
        val customerId = CustomerId.generate()
        fixture.postSale(customerId, "450.00", TODAY.minusDays(100))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/accounts-receivable-aging") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"customerIds": ["${customerId.value}"]}""")
        }

        response.status shouldBe HttpStatusCode.OK
        val body: ComputeAccountsReceivableAgingResponseDto = response.body()
        val customerAging = body.aging.single()
        customerAging.customerId shouldBe customerId.value.toString()
        customerAging.buckets.first { it.label == "OVER_90" }.amount shouldBe "450.00"
        customerAging.buckets.first { it.label == "CURRENT" }.amount shouldBe "0.00"
    }

    @Test
    fun `given no bearer token, when POST accounts-receivable-aging is called, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/accounts-receivable-aging") {
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"customerIds": []}""")
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a nonexistent Company, when POST accounts-receivable-aging is called, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${UUID.randomUUID()}/accounts-receivable-aging") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"customerIds": []}""")
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `given no Chart of Accounts, when POST accounts-receivable-aging is called, then it returns 409`() = testApplication {
        val fixture = Fixture(configureAccounts = false)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/accounts-receivable-aging") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"customerIds": ["${UUID.randomUUID()}"]}""")
        }

        response.status shouldBe HttpStatusCode.Conflict
    }

    // The byte-for-byte shape of the response, pinned before the aging was refactored to expose its unpaid lines
    // (receivables-payables-summary): the existing aging responses must not change in any way.
    @Test
    fun `the accounts-receivable-aging response is byte-for-byte what it was, across buckets, boundaries, partial payments and empty customers`() = testApplication {
        val fixture = Fixture()
        val c1 = CustomerId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
        val c2 = CustomerId(UUID.fromString("00000000-0000-0000-0000-0000000000c2"))
        val c3 = CustomerId(UUID.fromString("00000000-0000-0000-0000-0000000000c3"))
        val c4 = CustomerId(UUID.fromString("00000000-0000-0000-0000-0000000000c4"))
        fixture.postSale(c1, "100.00", TODAY.minusDays(100)); fixture.postSale(c1, "200.00", TODAY.minusDays(70))
        fixture.postSale(c1, "300.00", TODAY.minusDays(40)); fixture.postSale(c1, "400.00", TODAY.minusDays(5))
        fixture.postReceipt(c1, "250.00", TODAY.minusDays(2))
        for ((age, amount) in listOf(30 to "10.00", 31 to "20.00", 60 to "30.00", 61 to "40.00", 90 to "50.00", 91 to "60.00")) {
            fixture.postSale(c2, amount, TODAY.minusDays(age.toLong()))
        }
        fixture.postSale(c4, "70.00", TODAY.minusDays(10)); fixture.postReceipt(c4, "70.00", TODAY.minusDays(1))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/accounts-receivable-aging") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"customerIds": ["${c1.value}", "${c2.value}", "${c3.value}", "${c4.value}"]}""")
        }

        response.status shouldBe HttpStatusCode.OK
        val text = response.bodyAsText()
        text shouldBe """{"aging":[{"customerId":"00000000-0000-0000-0000-0000000000c1","buckets":[{"label":"CURRENT","amount":"400.00","currency":"GBP"},{"label":"DAYS_31_TO_60","amount":"300.00","currency":"GBP"},{"label":"DAYS_61_TO_90","amount":"50.00","currency":"GBP"},{"label":"OVER_90","amount":"0.00","currency":"GBP"}]},{"customerId":"00000000-0000-0000-0000-0000000000c2","buckets":[{"label":"CURRENT","amount":"10.00","currency":"GBP"},{"label":"DAYS_31_TO_60","amount":"50.00","currency":"GBP"},{"label":"DAYS_61_TO_90","amount":"90.00","currency":"GBP"},{"label":"OVER_90","amount":"60.00","currency":"GBP"}]},{"customerId":"00000000-0000-0000-0000-0000000000c3","buckets":[{"label":"CURRENT","amount":"0.00","currency":"GBP"},{"label":"DAYS_31_TO_60","amount":"0.00","currency":"GBP"},{"label":"DAYS_61_TO_90","amount":"0.00","currency":"GBP"},{"label":"OVER_90","amount":"0.00","currency":"GBP"}]},{"customerId":"00000000-0000-0000-0000-0000000000c4","buckets":[{"label":"CURRENT","amount":"0.00","currency":"GBP"},{"label":"DAYS_31_TO_60","amount":"0.00","currency":"GBP"},{"label":"DAYS_61_TO_90","amount":"0.00","currency":"GBP"},{"label":"OVER_90","amount":"0.00","currency":"GBP"}]}]}"""
    }

    // ---- GET receivables-payables-summary (the Business Owner dashboard) ----

    private fun summaryPath(fixture: Fixture) = "/api/companies/${fixture.company.id.value}/receivables-payables-summary"

    private fun io.ktor.client.request.HttpRequestBuilder.asAdmin(fixture: Fixture, email: String = ADMIN_EMAIL) {
        header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(email)}")
        header("X-Tenant-Id", fixture.tenant.value.toString())
    }

    @Test
    fun `given receivables and payables, when the summary is read, then each side has its total, its overdue part older than 30 days and the count, and the shape is exactly the contract`() = testApplication {
        val fixture = Fixture()
        val customer = CustomerId.generate()
        fixture.postSale(customer, "100.00", TODAY.minusDays(100))
        fixture.postSale(customer, "200.00", TODAY.minusDays(40))
        fixture.postSale(customer, "400.00", TODAY.minusDays(5))
        fixture.postReceipt(customer, "100.00", TODAY.minusDays(2)) // clears the 100 sale
        fixture.postCharge(UUID.randomUUID(), "50.00", TODAY.minusDays(31))
        fixture.postCharge(UUID.randomUUID(), "70.00", TODAY.minusDays(2))
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get(summaryPath(fixture)) { asAdmin(fixture) }

        response.status shouldBe HttpStatusCode.OK
        val text = response.bodyAsText()
        text shouldBe """{"currency":"GBP","asOf":"${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}","receivables":{"total":"600.00","overdueTotal":"200.00","overdueCount":1},"payables":{"total":"120.00","overdueTotal":"50.00","overdueCount":1}}"""
    }

    @Test
    fun `given a Company with no receivables or payables control account, when the summary is read, then it is 200 with zeros`() = testApplication {
        val fixture = Fixture(configureAccounts = false)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get(summaryPath(fixture)) { asAdmin(fixture) }

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe """{"currency":"GBP","asOf":"${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}","receivables":{"total":"0.00","overdueTotal":"0.00","overdueCount":0},"payables":{"total":"0.00","overdueTotal":"0.00","overdueCount":0}}"""
    }

    @Test
    fun `given no bearer token, an unknown Company, or a stranger, when the summary is read, then it is refused`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.get(summaryPath(fixture)).status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/companies/${UUID.randomUUID()}/receivables-payables-summary") { asAdmin(fixture) }.status shouldBe HttpStatusCode.NotFound
        val stranger = User.create("stranger-summary@example.com", "Stranger").also { fixture.userRepository.save(it) }
        val refused = client.get(summaryPath(fixture)) { asAdmin(fixture, stranger.email) }.status
        (refused == HttpStatusCode.Forbidden || refused == HttpStatusCode.Unauthorized) shouldBe true
    }

    @Test
    fun `given a read-only member, when the summary is read, then it is allowed`() = testApplication {
        val fixture = Fixture()
        val reader = User.create("reader-summary@example.com", "Reader").also { fixture.userRepository.save(it) }
        fixture.membershipRepository.save(
            Membership.grant(reader.id, fixture.tenant, Role.ACCOUNTANT, fixture.company.id, accessLevel = com.theprodeogroup.fish.domain.tenancy.AccessLevel.READ)
        )
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.get(summaryPath(fixture)) { asAdmin(fixture, "reader-summary@example.com") }.status shouldBe HttpStatusCode.OK
    }
}
