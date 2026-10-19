package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.Currency

/**
 * The shared account rule behind the per-line routes and the account list (docs/GL_Per_Line_Account_Routing_Audit_And_Design.md),
 * pinned by the cases CM's review of step 1 (2026-10-19) asked for.
 */
class LineAccountResolverTest {

    private val accounts = FakeAccountRepository()
    private val companies = FakeCompanyRepository()
    private val resolver = LineAccountResolver(accounts, companies)

    private fun company(type: ClientType = ClientType.COMPANY_LIMITED): Company =
        Company.create(TenantId.generate(), "Co", type, Jurisdiction.UK, Currency.getInstance("GBP")).also { companies.save(it) }

    private fun account(company: Company, type: AccountType, code: String, kind: CashBookKind? = null): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        return Account.create(company.id, type, classification, code, "Account $code", cashBookKind = kind).also { accounts.save(it) }
    }

    @Test
    fun `given a cash or bank book named on a purchase line, then it is refused as module-owned, not accepted as an asset`() {
        val co = company()
        val bank = account(co, AccountType.ASSET, "1010", CashBookKind.BANK)

        val result = resolver.resolve(co.id, LineAccountResolver.Kind.PURCHASE, bank.id)

        result.shouldBeInstanceOf<LineAccountResolver.Result.NotAllowed>().reason shouldBe LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED
    }

    @Test
    fun `given an inactive Suspense account named on a line, then it is refused as inactive`() {
        val co = company()
        val suspense = account(co, AccountType.EQUITY, "3910")
        suspense.deactivate()

        val result = resolver.resolve(co.id, LineAccountResolver.Kind.PURCHASE, suspense.id)

        result.shouldBeInstanceOf<LineAccountResolver.Result.NotAllowed>().reason shouldBe LineAccountResolver.Reason.INACTIVE
    }

    @Test
    fun `given the only Suspense account is inactive, when a line names no account, then Suspense counts as not configured`() {
        val co = company()
        account(co, AccountType.EQUITY, "3910").deactivate()

        resolver.suspense(co.id) shouldBe LineAccountResolver.Result.SuspenseNotConfigured
    }

    @Test
    fun `given an inactive Suspense account, then the account list does not offer it`() {
        val co = company()
        val suspense = account(co, AccountType.EQUITY, "3910").also { it.deactivate() }
        val expense = account(co, AccountType.EXPENSE, "5000")

        resolver.usableAccounts(accounts.findAllByCompany(co.id), LineAccountResolver.Kind.PURCHASE, co.clientType)
            .map { it.id } shouldBe listOf(expense.id)
        resolver.usableAccounts(accounts.findAllByCompany(co.id), LineAccountResolver.Kind.PURCHASE, co.clientType)
            .none { it.id == suspense.id } shouldBe true
    }

    @Test
    fun `given an individual client, then code 1100 is an ordinary asset on a purchase line, while on a business chart it is Accounts Receivable and refused`() {
        val individual = company(ClientType.INDIVIDUAL)
        val business = company(ClientType.COMPANY_LIMITED)
        val personal1100 = account(individual, AccountType.ASSET, "1100")
        val business1100 = account(business, AccountType.ASSET, "1100")

        resolver.resolve(individual.id, LineAccountResolver.Kind.PURCHASE, personal1100.id)
            .shouldBeInstanceOf<LineAccountResolver.Result.Resolved>()
        resolver.resolve(business.id, LineAccountResolver.Kind.PURCHASE, business1100.id)
            .shouldBeInstanceOf<LineAccountResolver.Result.NotAllowed>().reason shouldBe LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED
    }

    @Test
    fun `a module-owned account is refused for every caller - the owner exception for Payroll, POP and SOP is not implemented yet`() {
        val co = company()
        val payables = account(co, AccountType.LIABILITY, "2000")
        val receivables = account(co, AccountType.ASSET, "1100")

        resolver.resolve(co.id, LineAccountResolver.Kind.PAYROLL_LIABILITY, payables.id)
            .shouldBeInstanceOf<LineAccountResolver.Result.NotAllowed>().reason shouldBe LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED
        resolver.resolve(co.id, LineAccountResolver.Kind.PURCHASE, receivables.id)
            .shouldBeInstanceOf<LineAccountResolver.Result.NotAllowed>().reason shouldBe LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED
    }
}
