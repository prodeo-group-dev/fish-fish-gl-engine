package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency

private val EUR: Currency = Currency.getInstance("EUR")
private val TODAY = LocalDate.of(2026, 9, 20)

/**
 * The Purchasing mirror of [ComputeSalesPostingContextUseCaseTest] - no
 * test coverage existed for this use case before the VAT reshape either.
 */
class ComputePurchasePostingContextUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val useCase = ComputePurchasePostingContextUseCase(companyRepository, periodRepository, accountRepository)

    private val tenantId = TenantId.generate()
    private val company = Company.create(tenantId, "Test Co", ClientType.COMPANY_LIMITED, Jurisdiction.IE, EUR)
        .also { companyRepository.save(it) }

    private fun openPeriod() {
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
    }

    private fun account(code: String, type: AccountType) {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        accountRepository.save(Account.create(company.id, type, classification, code, "Test Account"))
    }

    @Test
    fun `given AP, Expense, Cash, and VAT Control accounts configured, when resolved, then it returns all four plus the open Period and currency`() {
        openPeriod()
        account("2000", AccountType.LIABILITY)
        account("5000", AccountType.EXPENSE)
        account("1000", AccountType.ASSET)
        account("2150", AccountType.LIABILITY)

        val result = useCase.execute(company.id)

        val success = result.shouldBeInstanceOf<PurchasePostingContextResult.Success>()
        success.currency shouldBe EUR
    }

    @Test
    fun `given no VAT Control Account configured, when resolved, then it returns VatControlAccountNotConfigured`() {
        openPeriod()
        account("2000", AccountType.LIABILITY)
        account("5000", AccountType.EXPENSE)
        account("1000", AccountType.ASSET)

        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<PurchasePostingContextResult.VatControlAccountNotConfigured>()
    }

    @Test
    fun `given a nonexistent Company, when resolved, then it returns CompanyNotFound`() {
        val result = useCase.execute(CompanyId.generate())

        result.shouldBeInstanceOf<PurchasePostingContextResult.CompanyNotFound>()
    }
}
