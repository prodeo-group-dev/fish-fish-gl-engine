package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatFilingPeriod
import com.theprodeogroup.fish.domain.tax.VatReturnDirection
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val EUR: Currency = Currency.getInstance("EUR")
private val JAN_FEB_2026 = VatFilingPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28))

/**
 * `ComputeVatReturnUseCase` - the actual MVP deliverable
 * (docs/IE/IE_VAT_MVP_Design.md #5). Assembles the inputs `VatReturn.of()`
 * needs and persists the result, the same "no new calculation logic
 * lives here" shape `ComputeTaxUseCase` already established for CIT.
 */
class ComputeVatReturnUseCaseTest {

    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val vatReturnRepository = FakeVatReturnRepository()
    private val useCase = ComputeVatReturnUseCase(accountRepository, journalEntryRepository, vatReturnRepository)

    private val companyId = CompanyId.generate()

    private fun vatAccount(): Account {
        val account = Account.create(companyId, AccountType.LIABILITY, AccountClassification.CURRENT, "2150", "VAT Control Account")
        accountRepository.save(account)
        return account
    }

    private fun postedSale(vatAccountId: AccountId, vatAmount: Money, category: VatCategory, date: LocalDate): JournalEntry {
        val entry = JournalEntry.create(
            PeriodId.generate(), date,
            listOf(
                JournalLine(AccountId.generate(), vatAmount, TransactionSide.DEBIT),
                JournalLine(vatAccountId, vatAmount, TransactionSide.CREDIT, mapOf(DimensionType.VAT_CATEGORY to category.name))
            ),
            JournalSource.INTEGRATION
        )
        entry.post()
        journalEntryRepository.save(entry)
        return entry
    }

    @Test
    fun `given posted output VAT within the filing window, when executed, then it computes and persists a payable VatReturn`() {
        val vat = vatAccount()
        postedSale(vat.id, Money(BigDecimal("230.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 1, 15))

        val result = useCase.execute(ComputeVatReturnUseCase.Request(companyId, JAN_FEB_2026, vat.id, EUR))

        val success = result.shouldBeInstanceOf<ComputeVatReturnResult.Success>()
        success.vatReturn.netVatDue shouldBe Money(BigDecimal("230.00"), EUR)
        success.vatReturn.direction shouldBe VatReturnDirection.PAYABLE
        vatReturnRepository.saveCalls shouldContain success.vatReturn.id
    }

    @Test
    fun `given a VAT Control Account that does not exist, when executed, then it returns VatControlAccountNotFound`() {
        val result = useCase.execute(ComputeVatReturnUseCase.Request(companyId, JAN_FEB_2026, AccountId.generate(), EUR))

        result.shouldBeInstanceOf<ComputeVatReturnResult.VatControlAccountNotFound>()
    }
}
