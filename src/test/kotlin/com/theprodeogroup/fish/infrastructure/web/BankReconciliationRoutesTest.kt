package com.theprodeogroup.fish.infrastructure.web

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
import com.theprodeogroup.fish.application.ComputeBankReconciliationUseCase
import com.theprodeogroup.fish.application.FakeBankReconciliationRepository
import com.theprodeogroup.fish.application.AssessFixedAssetImpairmentUseCase
import com.theprodeogroup.fish.application.ComputeFixedAssetRegisterUseCase
import com.theprodeogroup.fish.application.CreateFixedAssetUseCase
import com.theprodeogroup.fish.application.DisposeFixedAssetUseCase
import com.theprodeogroup.fish.application.FakeFixedAssetRepository
import com.theprodeogroup.fish.application.MatchBankReconciliationLineUseCase
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
import com.theprodeogroup.fish.application.StartBankReconciliationUseCase
import com.theprodeogroup.fish.application.UnmatchBankReconciliationLineUseCase
import com.theprodeogroup.fish.application.UtilizeLeaveAccrualUseCase
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.application.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.application.User
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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

/** `/companies/{companyId}/bank-reconciliations` - see BankReconciliationRoutes.kt's own KDoc. */
class BankReconciliationRoutesTest {

    private class Fixture {
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
        val bankReconciliationRepository = FakeBankReconciliationRepository()
        val startBankReconciliationUseCase = StartBankReconciliationUseCase(companyRepository, accountRepository, journalEntryRepository, bankReconciliationRepository)
        val matchBankReconciliationLineUseCase = MatchBankReconciliationLineUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository)
        val unmatchBankReconciliationLineUseCase = UnmatchBankReconciliationLineUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository)
        val computeBankReconciliationUseCase = ComputeBankReconciliationUseCase(companyRepository, journalEntryRepository, bankReconciliationRepository)

        val tenant = TenantId.generate()
        val company = Company.create(tenant, "Purse UK", ClientType.NON_PROFIT, Jurisdiction.UK, GBP)
        val adminUser = User.create(ADMIN_EMAIL, "Founding Admin").also { userRepository.save(it) }
        val adminSetup = run {
            companyRepository.save(company)
            val membership = Membership.grant(adminUser.id, tenant, Role.OWNER_ADMIN, company.id)
            membershipRepository.save(membership)
        }

        val period = Period.create(company.id, PeriodType.MONTH, TODAY.minusDays(5), TODAY.plusDays(25)).also {
            it.open()
            periodRepository.save(it)
        }
        val cashAccount = Account.create(company.id, AccountType.ASSET, com.theprodeogroup.fish.domain.ledger.AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }
        val revenueAccount = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales Revenue").also { accountRepository.save(it) }

        /** A 500.00 cash receipt, postable as the matching JournalEntry for a 500.00 RECEIVED statement line. */
        fun postCashReceipt(amount: String = "500.00"): JournalEntry {
            val entry = JournalEntry.create(
                period.id, TODAY,
                listOf(
                    JournalLine(cashAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
                    JournalLine(revenueAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
                ),
                JournalSource.MANUAL
            )
            entry.post()
            journalEntryRepository.save(entry)
            return entry
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
                computeFixedAssetRegisterUseCase = computeFixedAssetRegisterUseCase,
                startBankReconciliationUseCase = startBankReconciliationUseCase,
                matchBankReconciliationLineUseCase = matchBankReconciliationLineUseCase,
                unmatchBankReconciliationLineUseCase = unmatchBankReconciliationLineUseCase,
                computeBankReconciliationUseCase = computeBankReconciliationUseCase
            )
        }
    }

    private val startBody = """{"accountId": "%s", "statementDate": "%s", "statementEndingBalance": "500.00", "currency": "GBP", "lines": [{"date": "%s", "amount": "500.00", "direction": "RECEIVED", "description": "Card settlement"}]}"""

    @Test
    fun `given a valid statement, when started, then it returns the reconciliation with one unmatched line`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(startBody.format(fixture.cashAccount.id.value, TODAY, TODAY))
        }

        response.status shouldBe HttpStatusCode.OK
        val body: BankReconciliationResponseDto = response.body()
        body.statementLines.size shouldBe 1
        body.unmatchedStatementLineIds.size shouldBe 1
        body.isFullyReconciled shouldBe false
    }

    @Test
    fun `given no bearer token, when started, then it returns 401`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations") {
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(startBody.format(fixture.cashAccount.id.value, TODAY, TODAY))
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `given a currency that does not match the Company's base currency, when started, then it returns 400`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"accountId": "${fixture.cashAccount.id.value}", "statementDate": "$TODAY", "statementEndingBalance": "500.00", "currency": "USD", "lines": []}""")
        }

        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `given a nonexistent Account, when started, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(startBody.format(UUID.randomUUID(), TODAY, TODAY))
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `given a reconciliation and an eligible entry, when matched, then the pair comes back in matches`() = testApplication {
        val fixture = Fixture()
        val entry = fixture.postCashReceipt()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val startResponse = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(startBody.format(fixture.cashAccount.id.value, TODAY, TODAY))
        }
        val reconciliation: BankReconciliationResponseDto = startResponse.body()
        val statementLineId = reconciliation.statementLines.single().id

        val matchResponse = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations/${reconciliation.id}/match") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"statementLineId": "$statementLineId", "journalEntryId": "${entry.id.value}"}""")
        }

        matchResponse.status shouldBe HttpStatusCode.OK
        val matched: BankReconciliationResponseDto = matchResponse.body()
        matched.isFullyReconciled shouldBe true
        matched.matches.single().statementLineId shouldBe statementLineId
        matched.matches.single().journalEntryId shouldBe entry.id.value.toString()
    }

    @Test
    fun `given a matched pair, when unmatched, then it becomes unmatched again`() = testApplication {
        val fixture = Fixture()
        val entry = fixture.postCashReceipt()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val startResponse = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(startBody.format(fixture.cashAccount.id.value, TODAY, TODAY))
        }
        val reconciliation: BankReconciliationResponseDto = startResponse.body()
        val statementLineId = reconciliation.statementLines.single().id
        client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations/${reconciliation.id}/match") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"statementLineId": "$statementLineId", "journalEntryId": "${entry.id.value}"}""")
        }

        val unmatchResponse = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations/${reconciliation.id}/unmatch") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"statementLineId": "$statementLineId", "journalEntryId": "${entry.id.value}"}""")
        }

        unmatchResponse.status shouldBe HttpStatusCode.OK
        val unmatched: BankReconciliationResponseDto = unmatchResponse.body()
        unmatched.isFullyReconciled shouldBe false
        unmatched.matches shouldBe emptyList()
    }

    @Test
    fun `given a never-matched pair, when unmatched, then it returns 409`() = testApplication {
        val fixture = Fixture()
        val entry = fixture.postCashReceipt()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val startResponse = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(startBody.format(fixture.cashAccount.id.value, TODAY, TODAY))
        }
        val reconciliation: BankReconciliationResponseDto = startResponse.body()
        val statementLineId = reconciliation.statementLines.single().id

        val response = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations/${reconciliation.id}/unmatch") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"statementLineId": "$statementLineId", "journalEntryId": "${entry.id.value}"}""")
        }

        response.status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `given an existing reconciliation, when read, then it returns the current state`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val startResponse = client.post("/api/companies/${fixture.company.id.value}/bank-reconciliations") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
            contentType(ContentType.Application.Json)
            setBody(startBody.format(fixture.cashAccount.id.value, TODAY, TODAY))
        }
        val reconciliation: BankReconciliationResponseDto = startResponse.body()

        val response = client.get("/api/companies/${fixture.company.id.value}/bank-reconciliations/${reconciliation.id}") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

        response.status shouldBe HttpStatusCode.OK
        val body: BankReconciliationResponseDto = response.body()
        body.id shouldBe reconciliation.id
    }

    @Test
    fun `given a nonexistent reconciliation, when read, then it returns 404`() = testApplication {
        val fixture = Fixture()
        application { fixture.installInto(this) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/companies/${fixture.company.id.value}/bank-reconciliations/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken(ADMIN_EMAIL)}")
            header("X-Tenant-Id", fixture.tenant.value.toString())
        }

        response.status shouldBe HttpStatusCode.NotFound
    }
}
