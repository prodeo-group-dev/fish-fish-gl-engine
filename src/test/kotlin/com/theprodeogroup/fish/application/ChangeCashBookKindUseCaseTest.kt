package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ChangeCashBookKindUseCaseTest {

    private val accounts = FakeAccountRepository()
    private val useCase = ChangeCashBookKindUseCase(accounts)
    private val companyId = CompanyId.generate()

    private fun asset(code: String = "1010") =
        Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, code, "Cash").also { accounts.save(it) }

    @Test
    fun `given an Asset account of the Company, when it is flagged BANK, then the kind is saved`() {
        val account = asset()

        val result = useCase.execute(companyId, account.id, CashBookKind.BANK)

        result.shouldBeInstanceOf<ChangeCashBookKindUseCase.Result.Success>()
        accounts.findById(account.id)!!.cashBookKind shouldBe CashBookKind.BANK
    }

    @Test
    fun `given an account of another Company, when its kind is changed, then it is not found and unchanged`() {
        val foreign = Account.create(CompanyId.generate(), AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash")
            .also { accounts.save(it) }

        val result = useCase.execute(companyId, foreign.id, CashBookKind.BANK)

        result shouldBe ChangeCashBookKindUseCase.Result.AccountNotFound
        accounts.findById(foreign.id)!!.cashBookKind shouldBe null
    }

    @Test
    fun `given a Revenue account, when a kind is set, then it is refused as not an asset`() {
        val revenue = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }

        val result = useCase.execute(companyId, revenue.id, CashBookKind.CASH)

        result.shouldBeInstanceOf<ChangeCashBookKindUseCase.Result.NotAnAssetAccount>()
        accounts.findById(revenue.id)!!.cashBookKind shouldBe null
    }

    @Test
    fun `given a cash book, when it is flagged BANK, or a bank account flagged CASH, then it is refused as KindFixed and keeps its kind - GL-T-18, Femi 2026-10-23`() {
        val cash = asset("1020").also { it.changeCashBookKind(CashBookKind.CASH); accounts.save(it) }
        val bank = asset("1010").also { it.changeCashBookKind(CashBookKind.BANK); accounts.save(it) }

        useCase.execute(companyId, cash.id, CashBookKind.BANK) shouldBe ChangeCashBookKindUseCase.Result.KindFixed
        useCase.execute(companyId, bank.id, CashBookKind.CASH) shouldBe ChangeCashBookKindUseCase.Result.KindFixed
        accounts.findById(cash.id)!!.cashBookKind shouldBe CashBookKind.CASH
        accounts.findById(bank.id)!!.cashBookKind shouldBe CashBookKind.BANK
    }

    @Test
    fun `given a bank account, when it is flagged BANK again, then it is a success and unchanged`() {
        val bank = asset("1010").also { it.changeCashBookKind(CashBookKind.BANK); accounts.save(it) }

        useCase.execute(companyId, bank.id, CashBookKind.BANK).shouldBeInstanceOf<ChangeCashBookKindUseCase.Result.Success>()
        accounts.findById(bank.id)!!.cashBookKind shouldBe CashBookKind.BANK
    }

    @Test
    fun `given a BANK account, when its kind is cleared (clearing is held for Femi, this test flips if he rules to refuse it), then it is cleared`() {
        val bank = asset("1010").also { it.changeCashBookKind(CashBookKind.BANK); accounts.save(it) }

        useCase.execute(companyId, bank.id, null).shouldBeInstanceOf<ChangeCashBookKindUseCase.Result.Success>()
        accounts.findById(bank.id)!!.cashBookKind shouldBe null
    }

    @Test
    fun `given account 1000, when it is flagged BANK or cleared, then it is refused - 1000 is always the Cash Book`() {
        val prime = asset("1000").also { it.changeCashBookKind(CashBookKind.CASH); accounts.save(it) }

        useCase.execute(companyId, prime.id, CashBookKind.BANK) shouldBe ChangeCashBookKindUseCase.Result.PrimeCashBookKindFixed
        useCase.execute(companyId, prime.id, null) shouldBe ChangeCashBookKindUseCase.Result.PrimeCashBookKindFixed
        accounts.findById(prime.id)!!.cashBookKind shouldBe CashBookKind.CASH
    }

    @Test
    fun `given account 1000 with no kind yet, when it is flagged CASH, then it is allowed`() {
        val prime = asset("1000")

        useCase.execute(companyId, prime.id, CashBookKind.CASH).shouldBeInstanceOf<ChangeCashBookKindUseCase.Result.Success>()
        accounts.findById(prime.id)!!.cashBookKind shouldBe CashBookKind.CASH
    }
}
