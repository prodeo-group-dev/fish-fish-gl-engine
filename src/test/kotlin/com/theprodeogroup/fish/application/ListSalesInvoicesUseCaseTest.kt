package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.sales.SaleMethod
import com.theprodeogroup.fish.domain.sales.SaleType
import com.theprodeogroup.fish.domain.sales.SalesInvoiceRecord
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.common.Money
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/** The "listing of sales (each timestamped)" behind the SOP dashboard tab (2026-08-29). */
class ListSalesInvoicesUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val salesInvoiceRecordRepository = FakeSalesInvoiceRecordRepository()
    private val useCase = ListSalesInvoicesUseCase(companyRepository, salesInvoiceRecordRepository)

    private val company = Company.create(TenantId.generate(), "Test Co", ClientType.SOLE_TRADER, Jurisdiction.UK, GBP)
        .also { companyRepository.save(it) }

    private fun record(invoiceNumber: String, recordedAt: Instant) = SalesInvoiceRecord.create(
        company.id, JournalEntryId.generate(), invoiceNumber, CustomerId.generate(), "Jane Doe",
        SaleType.GOODS, SaleMethod.CASH, Money(BigDecimal("10.00"), GBP), paid = true, description = null,
        recordedAt = recordedAt
    )

    @Test
    fun `given several sales recorded at different times, when executed, then it returns them newest first`() {
        val older = record("INV-1", Instant.parse("2026-08-29T09:00:00Z")).also { salesInvoiceRecordRepository.save(it) }
        val newer = record("INV-2", Instant.parse("2026-08-29T10:00:00Z")).also { salesInvoiceRecordRepository.save(it) }

        val result = useCase.execute(company.id)

        val success = result.shouldBeInstanceOf<ListSalesInvoicesUseCase.Result.Success>()
        success.records.map { it.invoiceNumber } shouldBe listOf(newer.invoiceNumber, older.invoiceNumber)
    }

    @Test
    fun `given no sales recorded, when executed, then it returns an empty list`() {
        val result = useCase.execute(company.id)

        val success = result.shouldBeInstanceOf<ListSalesInvoicesUseCase.Result.Success>()
        success.records shouldBe emptyList()
    }

    @Test
    fun `given a nonexistent Company, when executed, then it returns CompanyNotFound`() {
        val result = useCase.execute(CompanyId.generate())

        result.shouldBeInstanceOf<ListSalesInvoicesUseCase.Result.CompanyNotFound>()
    }
}
