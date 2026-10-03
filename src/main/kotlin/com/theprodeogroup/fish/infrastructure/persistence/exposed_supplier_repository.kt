package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.purchasing.Supplier
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.purchasing.SupplierRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/** Exposed-backed `SupplierRepository` (docs/DDD_Design.md Section 10.4). Same existence-check-then-insert-or-update shape as every other repository in this codebase. */
class ExposedSupplierRepository : SupplierRepository {

    override fun save(supplier: Supplier): Unit = transaction {
        val exists = SuppliersTable.selectAll().where { SuppliersTable.id eq supplier.id.value }.count() > 0
        if (exists) {
            SuppliersTable.update({ SuppliersTable.id eq supplier.id.value }) { statement ->
                populate(statement, supplier)
            }
        } else {
            SuppliersTable.insert { statement ->
                statement[id] = supplier.id.value
                populate(statement, supplier)
            }
        }
        Unit
    }

    override fun findById(id: SupplierId): Supplier? = transaction {
        SuppliersTable.selectAll().where { SuppliersTable.id eq id.value }
            .map { it.toSupplier() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<Supplier> = transaction {
        SuppliersTable.selectAll().where { SuppliersTable.companyId eq companyId.value }
            .map { it.toSupplier() }
    }

    private fun populate(statement: UpdateBuilder<*>, supplier: Supplier) {
        statement[SuppliersTable.companyId] = supplier.companyId.value
        statement[SuppliersTable.name] = supplier.name
        statement[SuppliersTable.currency] = supplier.currency.currencyCode
        statement[SuppliersTable.balanceAmount] = supplier.balance.amount
    }

    private fun ResultRow.toSupplier(): Supplier {
        val currency = Currency.getInstance(this[SuppliersTable.currency])
        return Supplier.reconstitute(
            id = SupplierId(this[SuppliersTable.id]),
            companyId = CompanyId(this[SuppliersTable.companyId]),
            name = this[SuppliersTable.name],
            currency = currency,
            balance = Money(this[SuppliersTable.balanceAmount], currency)
        )
    }
}
