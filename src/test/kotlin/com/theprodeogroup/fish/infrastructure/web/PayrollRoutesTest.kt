package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.FakeAccountRepository
import com.theprodeogroup.fish.application.FakeCompanyRepository
import com.theprodeogroup.fish.application.FakeCreditorRepository
import com.theprodeogroup.fish.application.FakeCustomerRepository
import com.theprodeogroup.fish.application.FakeJournalEntryRepository
import com.theprodeogroup.fish.application.FakeLeaveAccrualRepository
import com.theprodeogroup.fish.application.FakeMembershipRepository
import com.theprodeogroup.fish.application.FakePayRunRepository
import com.theprodeogroup.fish.application.FakePeriodRepository
import com.theprodeogroup.fish.application.FakePurchaseOrderRepository
import com.theprodeogroup.fish.application.FakeSalesOrderRepository
import com.theprodeogroup.fish.application.FakeStockItemRepository
import com.theprodeogroup.fish.application.FakeUserRepository
import com.theprodeogroup.fish.application.PostInventoryIssueUseCase
import com.theprodeogroup.fish.application.PostInventoryReceiptUseCase
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.application.PostPayRunUseCase
import com.theprodeogroup.fish.application.PostPurchaseOrderUseCase
import com.theprodeogroup.fish.application.PostSalesOrderUseCase
import com.theprodeogroup.fish.application.RemeasureLeaveAccrualUseCase
import com.theprodeogroup.fish.application.UtilizeLeaveAccrualUseCase
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.payroll.EmployeeId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.payroll.PayRun
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.User
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

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 26)
private const val TEST_EMAIL = "payroll-caller@example.com"

/**
 * The HR/Payroll posting interface's HTTP surface (docs/DDD_Design.md
 * Section 10.20) via Ktor's `testApplication` - mirrors
 * [JournalEntryRoutesTest]/[PurchaseOrderRoutesTest]'s structure,
 * covering `POST /pay-runs/{id}/post`,
 * `POST /leave-accruals/{id}/remeasure`, and
 * `POST /leave-accruals/{id}/utilize`.
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
        val purchaseOrderRepository = FakePurchaseOrderRepository()
        val postPurchaseOrderUseCase = PostPurchaseOrderUseCase(
            purchaseOrderRepository, FakeCreditorRepository(), FakeStockItemRepository(),
            periodRepository, accountRepository, journalEntryRepository
        )
        val payRunRepository = FakePayRunRepository()
        val postPayRunUseCase = PostPayRunUseCase(payRunRepository, periodRepository, accountRepository, journalEntryRepository)
        val leaveAccrualRepository = FakeLeaveAccrualRepository()
        val remeasureLeaveAccrualUseCase = RemeasureLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
        val utilizeLeaveAccrualUseCase = UtilizeLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
        val stockItemRepository = FakeStockItemRepository()
        val postInventoryReceiptUseCase = PostInventoryReceiptUseCase(stockItemRepository, periodRepository, accountRepository, journalEntryRepository)
        val postInventoryIssueUseCase = PostInventoryIssueUseCase(stockItemRepository, periodRepository, accountRepository, journalEntryRepository)
        val salesOrderRepository = FakeSalesOrderRepository()
        val postSalesOrderUseCase = PostSalesOrderUseCase(
            salesOrderRepository, FakeCustomerRepository(), stockItemRepository, periodRepository, accountRepository, journalEntryRepository
        )

        val tenantId = TenantId.generate()
        val user = User.create(TEST_EMAIL, "Test Payroll Caller").also { userRepository.save(it) }
        val membership = Membership.grant(user.id, tenantId, role).also { membershipRepository.save(it) }
        val company = Company.create(tenantId, "Test Co", ClientType.NON_PROFIT, "GB", GBP).also { companyRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also {
            it.open()
            periodRepository.save(it)
        }
        val wagesExpenseAccount = Account.create(company.id, AccountType.EXPENSE, null, "6000", "Wages Expense").also { accountRepository.save(it) }
        val salariesExpenseAccount = Account.create(company.id, AccountType.EXPENSE, null, "6010", "Salaries Expense").also { accountRepository.save(it) }
        val cashAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }
        val accruedLeaveLiabilityAccount = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2200", "Accrued Leave Liability").also { accountRepository.save(it) }
        val leaveExpenseAccount = Account.create(company.id, AccountType.EXPENSE, null, "6020", "Leave Expense").also { accountRepository.save(it) }

        val payRun = PayRun.create(company.id, TODAY, Money(BigDecimal("1000.00"), GBP), Money(BigDecimal("500.00"), GBP))
            .also { payRunRepository.save(it) }
        val leaveAccrual = LeaveAccrual.create(company.id, EmployeeId.generate(), GBP)
            .also { leaveAccrualRepository.save(it) }

        fun installInto(app: Application) {
            app.fishModule(
                verifier = TestJwtSupport.verifier(),
                userRepository = userRepository,
                membershipRepository = membershipRepository,
                companyRepository = companyRepository,
                periodRepository = periodRepository,
                postJournalEntryUseCase = postJournalEntryUseCase,
                purchaseOrderRepository = purchaseOrderRepository,
                postPurchaseOrderUseCase = postPurchaseOrderUseCase,
                payRunRepository = payRunRepository,
                postPayRunUseCase = postPayRunUseCase,
                leaveAccrualRepository = leaveAccrualRepository,
                remeasureLeaveAccrualUseCase = remeasureLeaveAccrualUseCase,
                utilizeLeaveAccrualUseCase = utilizeLeaveAccrualUseCase,
                stockItemRepository = stockItemRepository,
                postInventoryReceiptUseCase = postInventoryReceiptUseCase,
                postInventoryIssueUseCase = postInventoryIssueUseCase,
                salesOrderRepository = salesOrderRepository,
                postSalesOrderUseCase = postSalesOrderUseCase
            )
        }
    }

    // -- POST /pay-runs/{id}/post --

    @Test
    fun `given a valid PayRun post request with a bearer token, when posted, then it returns 200 with the JournalEntry id`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/pay-runs/${fixture.payRun.id.value}/post") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"periodId": "${fixture.period.id.value}", "wagesExpenseAccountId": "${fixture.wagesExpenseAccount.id.value}",
                    |"salariesExpenseAccountId": "${fixture.salariesExpenseAccount.id.value}", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.OK
        val body: PostPayRunResponseDto = response.body()
        body.journalEntryStatus shouldBe "POSTED"
    }

    @Test
    fun `given no bearer token, when a PayRun is posted, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/pay-runs/${fixture.payRun.id.value}/post") {
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"periodId": "${fixture.period.id.value}", "wagesExpenseAccountId": "${fixture.wagesExpenseAccount.id.value}",
                    |"salariesExpenseAccountId": "${fixture.salariesExpenseAccount.id.value}", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a nonexistent PayRun id, when posted, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/pay-runs/${java.util.UUID.randomUUID()}/post") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"periodId": "${fixture.period.id.value}", "wagesExpenseAccountId": "${fixture.wagesExpenseAccount.id.value}",
                    |"salariesExpenseAccountId": "${fixture.salariesExpenseAccount.id.value}", "cashAccountId": "${fixture.cashAccount.id.value}"}""".trimMargin()
            )
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    // -- POST /leave-accruals/{id}/remeasure --

    @Test
    fun `given a target amount above the current balance, when remeasured, then it returns 200 with a posted JournalEntry`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/leave-accruals/${fixture.leaveAccrual.id.value}/remeasure") {
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

        val response = client.post("/leave-accruals/${fixture.leaveAccrual.id.value}/remeasure") {
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

        val response = client.post("/leave-accruals/${fixture.leaveAccrual.id.value}/remeasure") {
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
        client.post("/leave-accruals/${fixture.leaveAccrual.id.value}/remeasure") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(TEST_EMAIL)}")
            header("X-Tenant-Id", fixture.tenantId.value.toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"targetAmount": "300.00", "currency": "GBP", "leaveExpenseAccountId": "${fixture.leaveExpenseAccount.id.value}",
                    |"accruedLeaveLiabilityAccountId": "${fixture.accruedLeaveLiabilityAccount.id.value}", "periodId": "${fixture.period.id.value}", "date": "$TODAY"}""".trimMargin()
            )
        }

        val response = client.post("/leave-accruals/${fixture.leaveAccrual.id.value}/utilize") {
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

        val response = client.post("/leave-accruals/${fixture.leaveAccrual.id.value}/utilize") {
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

        val response = client.post("/leave-accruals/${fixture.leaveAccrual.id.value}/utilize") {
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
}
