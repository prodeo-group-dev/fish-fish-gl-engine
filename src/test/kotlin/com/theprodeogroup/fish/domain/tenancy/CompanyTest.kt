package com.theprodeogroup.fish.domain.tenancy

import com.theprodeogroup.fish.domain.common.ClientType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val SLE: Currency = Currency.getInstance("SLE")

class CompanyTest {

    @Test
    fun `given a valid Tenant, name, ClientType, jurisdiction, and currency, when a Company is created, then it defaults to Assumed going concern`() {
        val company = Company.create(
            tenantId = TenantId.generate(),
            name = "Acme Trading Ltd",
            clientType = ClientType.COMPANY_LIMITED,
            jurisdiction = "UK",
            baseCurrency = GBP
        )

        company.goingConcernStatus shouldBe GoingConcernStatus.ASSUMED
    }

    @Test
    fun `given Purse's Sierra Leone entity, when created, then it stores NON_PROFIT and SLE as designed`() {
        val company = Company.create(
            tenantId = TenantId.generate(),
            name = "Purse Sierra Leone",
            clientType = ClientType.NON_PROFIT,
            jurisdiction = "Sierra Leone",
            baseCurrency = SLE
        )

        company.clientType shouldBe ClientType.NON_PROFIT
        company.baseCurrency shouldBe SLE
        company.jurisdiction shouldBe "Sierra Leone"
    }

    @Test
    fun `given a Company in Assumed status, when flagged for substantial doubt, then its going concern status changes`() {
        val company = readyCompany()

        val result = company.flagSubstantialDoubt()

        result.isValid shouldBe true
        company.goingConcernStatus shouldBe GoingConcernStatus.SUBSTANTIAL_DOUBT
    }

    @Test
    fun `given a Company already flagged for substantial doubt, when flagged again, then it fails`() {
        val company = readyCompany()
        company.flagSubstantialDoubt()

        val result = company.flagSubstantialDoubt()

        result.isValid shouldBe false
        company.goingConcernStatus shouldBe GoingConcernStatus.SUBSTANTIAL_DOUBT
    }

    private fun readyCompany(): Company = Company.create(
        tenantId = TenantId.generate(),
        name = "Acme Trading Ltd",
        clientType = ClientType.COMPANY_LIMITED,
        jurisdiction = "UK",
        baseCurrency = GBP
    )
}
