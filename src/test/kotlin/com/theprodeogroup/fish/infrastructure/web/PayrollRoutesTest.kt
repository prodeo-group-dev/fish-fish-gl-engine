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
import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
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
import com.theprodeogroup.fish.domain.payroll.EmployeeId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.application.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.application.User
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.get
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
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 26)
private const val TEST_EMAIL = "payroll-caller@example.com"

/**
 * The HR/Payroll posting interface's HTTP surface (docs/DDD_Design.md
 * Section 10.20) via Ktor's `testApplication` - mirrors
 * [JournalEntryRoutesTest]'s structure, covering
 * `POST /leave-accruals/{id}/remeasure`, `POST /leave-accruals/{id}/utilize`,
 * `POST /payroll/record-pay-run`, and `POST /leave-accruals`.
 *
 * **`POST /pay-runs/{id}/post`'s own test coverage was removed
 * 2026-09-01** alongside the route itself (`PostPayRunUseCase` retired -
 * confirmed dead, `fish-hr-payroll` only ever calls `record-pay-run`).
 */
class PayrollRoutesTest {

    private class Fixture(role: Role = Role.ACCOUNTANT) {
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
        val user = User.create(TEST_EMAIL, "Test Payroll Caller").also { userRepository.save(it) }
        val company = Company.create(tenantId, "Test Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP).also { companyRepository.save(it) }
        val membership = Membership.grant(user.id, tenantId, role, company.id).also { membershipRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            periodRepository.save(it)
        }
        val wagesExpenseAccount = Account.create(company.id, AccountType.EXPENSE, null, "6000", "Wages Expense").also { accountRepository.save(it) }
        val salariesExpenseAccount = Account.create(company.id, AccountType.EXPENSE, null, "6010", "Salaries Expense").also { accountRepository.save(it) }
        val cashAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }
        val accruedLeaveLiabilityAccount = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2200", "Accrued Leave Liability").also { accountRepository.save(it) }
        val leaveExpenseAccount = Account.create(company.id, AccountType.EXPENSE, null, "6020", "Leave Expense").also { accountRepository.save(it) }

        val leaveAccrual = LeaveAccrual.create(company.id, EmployeeId.generate(), GBP)
            .also { leaveAccrualRepository.save(it) }


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
                addCompanyToTenantUseCase = addCompanyToTenantUseCase,
                computeTaxUseCase = computeTaxUseCase,
                taxRuleRepository = taxRuleRepository,
                taxComputationRepository = taxComputationRepository
            )
        }
    }

    // -- POST /leave-accruals/{id}/remeasure --

    @Test
    fun `given a target amount above the current balance, when remeasured, then it returns 200 with a posted JournalEntry`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/leave-accruals/${fixture.leaveAccrual.id.value}/remeasure") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"targetAmount": "300.00", "currency": "GBP", "leaveExpenseAccountId": "${fixture.leaveExpenseAccount.id.value}",
                    |"accruedLeaveLiabilityAccountId": "${fixture.accruedLeaveLiabilityAccount.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: LeaveAccrualResponseDto = response.body()
        body.balanceAmount shouldBe "300.00"
        body.journalEntryStatus shouldBe "POSTED"
    }

    @Test
    fun `given a target amount equal to the current zero balance, when remeasured, then it returns 200 with no JournalEntry posted`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/leave-accruals/${fixture.leaveAccrual.id.value}/remeasure") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"targetAmount": "0.00", "currency": "GBP", "leaveExpenseAccountId": "${fixture.leaveExpenseAccount.id.value}",
                    |"accruedLeaveLiabilityAccountId": "${fixture.accruedLeaveLiabilityAccount.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: LeaveAccrualResponseDto = response.body()
        body.journalEntryId shouldBe null
    }

    @Test
    fun `given a claimed X-Tenant-Id that does not own the LeaveAccrual, when remeasured, then it returns 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/leave-accruals/${fixture.leaveAccrual.id.value}/remeasure") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", TenantId.generate().value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"targetAmount": "300.00", "currency": "GBP", "leaveExpenseAccountId": "${fixture.leaveExpenseAccount.id.value}",
                    |"accruedLeaveLiabilityAccountId": "${fixture.accruedLeaveLiabilityAccount.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    // -- POST /leave-accruals/{id}/utilize --

    @Test
    fun `given leave taken against a positive balance, when utilized, then it returns 200 with a reduced balance`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.post("/api/leave-accruals/${fixture.leaveAccrual.id.value}/remeasure") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"targetAmount": "300.00", "currency": "GBP", "leaveExpenseAccountId": "${fixture.leaveExpenseAccount.id.value}",
                    |"accruedLeaveLiabilityAccountId": "${fixture.accruedLeaveLiabilityAccount.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        val response = client.post("/api/leave-accruals/${fixture.leaveAccrual.id.value}/utilize") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"amount": "100.00", "currency": "GBP", "cashAccountId": "${fixture.cashAccount.id.value}",
                    |"accruedLeaveLiabilityAccountId": "${fixture.accruedLeaveLiabilityAccount.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: LeaveAccrualResponseDto = response.body()
        body.balanceAmount shouldBe "200.00"
    }

    @Test
    fun `given a non-positive utilize amount, when utilized, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/leave-accruals/${fixture.leaveAccrual.id.value}/utilize") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"amount": "0.00", "currency": "GBP", "cashAccountId": "${fixture.cashAccount.id.value}",
                    |"accruedLeaveLiabilityAccountId": "${fixture.accruedLeaveLiabilityAccount.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given leave taken against a zero balance, when utilized, then it returns 409`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/leave-accruals/${fixture.leaveAccrual.id.value}/utilize") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"amount": "100.00", "currency": "GBP", "cashAccountId": "${fixture.cashAccount.id.value}",
                    |"accruedLeaveLiabilityAccountId": "${fixture.accruedLeaveLiabilityAccount.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Conflict
    }

    // -- POST /payroll/record-pay-run --

    @Test
    fun `given a valid record-pay-run request, when posted, then it returns 200 with a posted JournalEntry`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/payroll/record-pay-run") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY",
                    |"totalWages": "5000.00", "totalSalaries": "8000.00", "currency": "GBP",
                    |"wagesExpenseAccountId": "${fixture.wagesExpenseAccount.id.value}",
                    |"salariesExpenseAccountId": "${fixture.salariesExpenseAccount.id.value}", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: RecordPayRunResponseDto = response.body()
        body.status shouldBe "POSTED"
    }

    @Test
    fun `given no bearer token, when a pay run is recorded, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/payroll/record-pay-run") {
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY",
                    |"totalWages": "5000.00", "totalSalaries": "8000.00", "currency": "GBP",
                    |"wagesExpenseAccountId": "${fixture.wagesExpenseAccount.id.value}",
                    |"salariesExpenseAccountId": "${fixture.salariesExpenseAccount.id.value}", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given both totals are zero, when a pay run is recorded, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/payroll/record-pay-run") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY",
                    |"totalWages": "0.00", "totalSalaries": "0.00", "currency": "GBP",
                    |"wagesExpenseAccountId": "${fixture.wagesExpenseAccount.id.value}",
                    |"salariesExpenseAccountId": "${fixture.salariesExpenseAccount.id.value}", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a claimed X-Tenant-Id that does not own the Company, when a pay run is recorded, then it returns 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/payroll/record-pay-run") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", TenantId.generate().value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY",
                    |"totalWages": "5000.00", "totalSalaries": "8000.00", "currency": "GBP",
                    |"wagesExpenseAccountId": "${fixture.wagesExpenseAccount.id.value}",
                    |"salariesExpenseAccountId": "${fixture.salariesExpenseAccount.id.value}", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Forbidden
    }

    // -- POST /leave-accruals --

    @Test
    fun `given no existing LeaveAccrual for this Employee, when posted, then it returns 200 with a new zero-balance LeaveAccrual`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val employeeId = EmployeeId.generate()

        val response = client.post("/api/leave-accruals") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"companyId": "${fixture.company.id.value}", "employeeId": "${employeeId.value}", "currency": "GBP"}""")
        }

        response.status shouldBe HttpStatusCode.OK
        val body: LeaveAccrualResponseDto = response.body()
        body.balanceAmount shouldBe "0.00"
        body.journalEntryId shouldBe null
    }

    @Test
    fun `given an existing LeaveAccrual for this Employee, when posted again, then it returns 200 with the same LeaveAccrual id`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val employeeId = EmployeeId.generate()
        val requestBody = """{"companyId": "${fixture.company.id.value}", "employeeId": "${employeeId.value}", "currency": "GBP"}"""

        val first = client.post("/api/leave-accruals") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val second = client.post("/api/leave-accruals") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        val firstBody: LeaveAccrualResponseDto = first.body()
        val secondBody: LeaveAccrualResponseDto = second.body()
        secondBody.leaveAccrualId shouldBe firstBody.leaveAccrualId
    }

    @Test
    fun `given no bearer token, when a LeaveAccrual is requested, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/leave-accruals") {
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"companyId": "${fixture.company.id.value}", "employeeId": "${EmployeeId.generate().value}", "currency": "GBP"}""")
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    // --- POST /payroll/record-pay-run-journal (2026-10-19) ---

    private fun Fixture.account(code: String, type: AccountType, name: String, kind: com.theprodeogroup.fish.domain.ledger.CashBookKind? = null) =
        Account.create(company.id, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, name, cashBookKind = kind)
            .also { accountRepository.save(it) }

    private fun journalBody(fixture: Fixture, lines: String, reference: String = "RUN-1") =
        """{"companyId": "${fixture.company.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY", "currency": "GBP",
            |"reference": "$reference", "description": "Pay run", "lines": $lines}""".trimMargin()

    private suspend fun io.ktor.client.HttpClient.postJournal(fixture: Fixture, body: String, key: String? = null, email: String? = TEST_EMAIL) =
        post("/api/payroll/record-pay-run-journal") {
            if (email != null) header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(email)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            if (key != null) header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `given a liability-first pay run, when posted, then it returns 200 with the total and the lines carry their element`() = testApplication {
        val fixture = Fixture()
        val netPay = fixture.account("2210", AccountType.LIABILITY, "Net Pay Payable")
        val paye = fixture.account("2220", AccountType.LIABILITY, "PAYE Payable")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.postJournal(
            fixture,
            journalBody(
                fixture,
                """[{"element": "WAGES", "kind": "EXPENSE", "side": "DEBIT", "amount": "1000.00", "accountId": "${fixture.wagesExpenseAccount.id.value}"},
                   |{"element": "NET_PAY_PAYABLE", "kind": "LIABILITY", "side": "CREDIT", "amount": "800.00", "accountId": "${netPay.id.value}"},
                   |{"element": "PAYE_PAYABLE", "kind": "LIABILITY", "side": "CREDIT", "amount": "200.00", "accountId": "${paye.id.value}"}]""".trimMargin()
            )
        )

        response.status shouldBe HttpStatusCode.OK
        val body: RecordPayRunJournalResponseDto = response.body()
        body.status shouldBe "POSTED"
        body.total shouldBe MoneyAmountDto("1000.00", "GBP")
        val posted = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single()
        posted.lines.single { it.accountId == paye.id }.dimensions[com.theprodeogroup.fish.domain.common.DimensionType.PAYROLL_ELEMENT] shouldBe "PAYE_PAYABLE"
        posted.description shouldBe "Pay run - run RUN-1"
    }

    @Test
    fun `given a disbursement from a bank book, when posted, then it returns 200, and a non-cash account on a settlement line is 400`() = testApplication {
        val fixture = Fixture()
        val netPay = fixture.account("2210", AccountType.LIABILITY, "Net Pay Payable")
        val bank = fixture.account("1010", AccountType.ASSET, "Current Account", com.theprodeogroup.fish.domain.ledger.CashBookKind.BANK)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        fun lines(settlement: String) =
            """[{"element": "NET_PAY_PAYABLE", "kind": "LIABILITY", "side": "DEBIT", "amount": "800.00", "accountId": "${netPay.id.value}"},
               |{"element": "DISBURSEMENT", "kind": "SETTLEMENT", "side": "CREDIT", "amount": "800.00", "accountId": "$settlement"}]""".trimMargin()

        client.postJournal(fixture, journalBody(fixture, lines(bank.id.value.toString()))).status shouldBe HttpStatusCode.OK
        val refused = client.postJournal(fixture, journalBody(fixture, lines(fixture.leaveExpenseAccount.id.value.toString())))
        refused.status shouldBe HttpStatusCode.BadRequest
        refused.body<ErrorResponseDto>().error shouldBe "line_account_not_allowed"
    }

    @Test
    fun `given refusals, when posted, then the codes and statuses are the platform ones and nothing is posted`() = testApplication {
        val fixture = Fixture()
        val netPay = fixture.account("2210", AccountType.LIABILITY, "Net Pay Payable")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val good = """{"element": "NET_PAY_PAYABLE", "kind": "LIABILITY", "side": "CREDIT", "amount": "10.00", "accountId": "${netPay.id.value}"}"""
        val debit = """{"element": "WAGES", "kind": "EXPENSE", "side": "DEBIT", "amount": "10.00", "accountId": "${fixture.wagesExpenseAccount.id.value}"}"""

        client.postJournal(fixture, journalBody(fixture, "[$debit, ${good.replace("10.00", "9.00")}]")).let {
            it.status shouldBe HttpStatusCode.BadRequest
            it.body<ErrorResponseDto>().error shouldBe "unbalanced_journal"
        }
        val unmapped = """{"element": "BASIC_PAY", "kind": "EXPENSE", "side": "DEBIT", "amount": "10.00"}"""
        client.postJournal(fixture, journalBody(fixture, "[$unmapped, $good]")).let {
            it.status shouldBe HttpStatusCode.Conflict
            it.body<ErrorResponseDto>().error shouldBe "suspense_account_not_configured"
        }
        client.postJournal(fixture, journalBody(fixture, """[{"element": "D", "kind": "SETTLEMENT", "side": "CREDIT", "amount": "10.00"}, ${good.replace("CREDIT", "DEBIT")}]"""))
            .body<ErrorResponseDto>().error shouldBe "settlement_account_required"
        for (bad in listOf(good.replace("LIABILITY", "BOGUS"), good.replace("CREDIT", "SIDEWAYS"), good.replace("10.00", "ten"), good.replace(netPay.id.value.toString(), "not-a-uuid"))) {
            client.postJournal(fixture, journalBody(fixture, "[$debit, $bad]")).status shouldBe HttpStatusCode.BadRequest
        }
        client.postJournal(fixture, journalBody(fixture, "[$debit, $good]").replace(fixture.period.id.value.toString(), java.util.UUID.randomUUID().toString()))
            .status shouldBe HttpStatusCode.NotFound
        fixture.journalEntryRepository.saveCalls.size shouldBe 0
    }

    @Test
    fun `given no sign-in or a claimed Tenant that does not own the Company, when posted, then it is 401 or 403`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val body = journalBody(fixture, "[]")

        client.postJournal(fixture, body, email = null).status shouldBe HttpStatusCode.Unauthorized
        client.post("/api/payroll/record-pay-run-journal") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", java.util.UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `given the same Idempotency-Key and body twice, when posted, then it posts once and replays the answer`() = testApplication {
        val fixture = Fixture()
        val netPay = fixture.account("2210", AccountType.LIABILITY, "Net Pay Payable")
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val body = journalBody(
            fixture,
            """[{"element": "WAGES", "kind": "EXPENSE", "side": "DEBIT", "amount": "100.00", "accountId": "${fixture.wagesExpenseAccount.id.value}"},
               |{"element": "NET_PAY_PAYABLE", "kind": "LIABILITY", "side": "CREDIT", "amount": "100.00", "accountId": "${netPay.id.value}"}]""".trimMargin()
        )

        val first: RecordPayRunJournalResponseDto = client.postJournal(fixture, body, key = "run-1:approve").body()
        val second: RecordPayRunJournalResponseDto = client.postJournal(fixture, body, key = "run-1:approve").body()

        second.journalEntryId shouldBe first.journalEntryId
        fixture.journalEntryRepository.saveCalls.size shouldBe 1
    }

    @Test
    fun `given the account picker, when usableFor is a payroll or settlement kind, then it lists only what the route accepts`() = testApplication {
        val fixture = Fixture()
        fixture.account("2210", AccountType.LIABILITY, "Net Pay Payable")
        fixture.account("2000", AccountType.LIABILITY, "Accounts Payable")
        fixture.account("2300", AccountType.LIABILITY, "Trade Finance Facility Payable")
        val bank = fixture.account("1010", AccountType.ASSET, "Current Account", com.theprodeogroup.fish.domain.ledger.CashBookKind.BANK)
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        suspend fun codes(kind: String): List<String> =
            client.get("/api/companies/${fixture.company.id.value}/accounts?usableFor=$kind") {
                header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
                header("X-Tenant-Id", fixture.tenantId.value.toString())
            }.body<List<AccountSummaryDto>>().map { it.code }

        codes("payroll-liability-line") shouldBe listOf("2200", "2210")
        codes("settlement-line") shouldBe listOf(bank.code)
    }
}
