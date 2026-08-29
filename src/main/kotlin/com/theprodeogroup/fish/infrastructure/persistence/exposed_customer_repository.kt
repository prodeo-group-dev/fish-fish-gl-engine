package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.sales.Customer
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.sales.CustomerRepository
import com.theprodeogroup.fish.domain.sales.SaleMethod
import com.theprodeogroup.fish.domain.sales.SaleType
import com.theprodeogroup.fish.domain.sales.SalesInvoiceRecord
import com.theprodeogroup.fish.domain.sales.SalesInvoiceRecordId
import com.theprodeogroup.fish.domain.sales.SalesInvoiceRecordRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/** Exposed-backed `CustomerRepository` (docs/DDD_Design.md Section 10.4) - the AR mirror of `ExposedCreditorRepository`. */
class ExposedCustomerRepository : CustomerRepository {

    override fun save(customer: Customer): Unit = transaction {
        val exists = CustomersTable.selectAll().where { CustomersTable.id eq customer.id.value }.count() > 0
        if (exists) {
            CustomersTable.update({ CustomersTable.id eq customer.id.value }) { statement ->
                populate(statement, customer)
            }
        } else {
            CustomersTable.insert { statement ->
                statement[id] = customer.id.value
                populate(statement, customer)
            }
        }
        Unit
    }

    override fun findById(id: CustomerId): Customer? = transaction {
        CustomersTable.selectAll().where { CustomersTable.id eq id.value }
            .map { it.toCustomer() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<Customer> = transaction {
        CustomersTable.selectAll().where { CustomersTable.companyId eq companyId.value }
            .map { it.toCustomer() }
    }

    private fun populate(statement: UpdateBuilder<*>, customer: Customer) {
        statement[CustomersTable.companyId] = customer.companyId.value
        statement[CustomersTable.name] = customer.name
        statement[CustomersTable.currency] = customer.currency.currencyCode
        statement[CustomersTable.balanceAmount] = customer.balance.amount
        statement[CustomersTable.allowanceForExpectedCreditLossAmount] = customer.allowanceForExpectedCreditLoss.amount
    }

    private fun ResultRow.toCustomer(): Customer {
        val currency = Currency.getInstance(this[CustomersTable.currency])
        return Customer.reconstitute(
            id = CustomerId(this[CustomersTable.id]),
            companyId = CompanyId(this[CustomersTable.companyId]),
            name = this[CustomersTable.name],
            currency = currency,
            balance = Money(this[CustomersTable.balanceAmount], currency),
            allowanceForExpectedCreditLoss = Money(this[CustomersTable.allowanceForExpectedCreditLossAmount], currency)
        )
    }
}

/**
 * Exposed-backed `SalesInvoiceRecordRepository` - append-only, plain
 * insert on every `save`. Sorted newest-first in Kotlin rather than via
 * SQL `ORDER BY` - one small, infrequent table, not worth the extra
 * Exposed API surface for.
 */
class ExposedSalesInvoiceRecordRepository : SalesInvoiceRecordRepository {

    override fun save(record: SalesInvoiceRecord): Unit = transaction {
        SalesInvoiceRecordsTable.insert { statement ->
            statement[id] = record.id.value
            statement[companyId] = record.companyId.value
            statement[journalEntryId] = record.journalEntryId.value
            statement[invoiceNumber] = record.invoiceNumber
            statement[customerId] = record.customerId.value
            statement[customerName] = record.customerName
            statement[saleType] = record.saleType.name
            statement[saleMethod] = record.saleMethod.name
            statement[amount] = record.amount.amount
            statement[currency] = record.amount.currency.currencyCode
            statement[paid] = record.paid
            statement[description] = record.description
            statement[recordedAt] = record.recordedAt
        }
        Unit
    }

    override fun findAllByCompany(companyId: CompanyId): List<SalesInvoiceRecord> = transaction {
        SalesInvoiceRecordsTable.selectAll().where { SalesInvoiceRecordsTable.companyId eq companyId.value }
            .map { it.toSalesInvoiceRecord() }
            .sortedByDescending { it.recordedAt }
    }

    private fun ResultRow.toSalesInvoiceRecord(): SalesInvoiceRecord {
        val currency = Currency.getInstance(this[SalesInvoiceRecordsTable.currency])
        return SalesInvoiceRecord.reconstitute(
            id = SalesInvoiceRecordId(this[SalesInvoiceRecordsTable.id]),
            companyId = CompanyId(this[SalesInvoiceRecordsTable.companyId]),
            journalEntryId = JournalEntryId(this[SalesInvoiceRecordsTable.journalEntryId]),
            invoiceNumber = this[SalesInvoiceRecordsTable.invoiceNumber],
            customerId = CustomerId(this[SalesInvoiceRecordsTable.customerId]),
            customerName = this[SalesInvoiceRecordsTable.customerName],
            saleType = SaleType.valueOf(this[SalesInvoiceRecordsTable.saleType]),
            saleMethod = SaleMethod.valueOf(this[SalesInvoiceRecordsTable.saleMethod]),
            amount = Money(this[SalesInvoiceRecordsTable.amount], currency),
            paid = this[SalesInvoiceRecordsTable.paid],
            description = this[SalesInvoiceRecordsTable.description],
            recordedAt = this[SalesInvoiceRecordsTable.recordedAt]
        )
    }
}
