package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.sales.SalesOrder
import com.theprodeogroup.fish.domain.sales.SalesOrderId
import com.theprodeogroup.fish.domain.sales.SalesOrderLine
import com.theprodeogroup.fish.domain.sales.SalesOrderRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/**
 * Exposed-backed `SalesOrderRepository` (docs/DDD_Design.md Section
 * 10.4). [save] also reconciles `sales_order_delivered_lines` - delete
 * and reinsert on every call, same precedent as `journal_lines`/
 * `purchase_order_lines`/the Tenancy join tables. Lines themselves are
 * also deleted and reinserted, same reasoning as `ExposedPurchaseOrderRepository`.
 */
class ExposedSalesOrderRepository : SalesOrderRepository {

    override fun save(salesOrder: SalesOrder): Unit = transaction {
        val exists = SalesOrdersTable.selectAll().where { SalesOrdersTable.id eq salesOrder.id.value }.count() > 0
        if (exists) {
            SalesOrdersTable.update({ SalesOrdersTable.id eq salesOrder.id.value }) { statement ->
                populate(statement, salesOrder)
            }
        } else {
            SalesOrdersTable.insert { statement ->
                statement[id] = salesOrder.id.value
                populate(statement, salesOrder)
            }
        }

        SalesOrderLinesTable.deleteWhere { SalesOrderLinesTable.salesOrderId eq salesOrder.id.value }
        salesOrder.lines.forEachIndexed { index, line ->
            SalesOrderLinesTable.insert { statement ->
                statement[salesOrderId] = salesOrder.id.value
                statement[lineIndex] = index
                statement[description] = line.description
                statement[accountId] = line.accountId.value
                statement[amount] = line.amount.amount
                statement[currency] = line.amount.currency.currencyCode
                statement[itemType] = line.itemType.name
                statement[quantity] = line.quantity
                statement[stockItemId] = line.stockItemId?.value
            }
        }

        SalesOrderDeliveredLinesTable.deleteWhere { SalesOrderDeliveredLinesTable.salesOrderId eq salesOrder.id.value }
        salesOrder.deliveredLineIndicesSnapshot().forEach { index ->
            SalesOrderDeliveredLinesTable.insert { statement ->
                statement[salesOrderId] = salesOrder.id.value
                statement[lineIndex] = index
            }
        }
    }

    override fun findById(id: SalesOrderId): SalesOrder? = transaction {
        SalesOrdersTable.selectAll().where { SalesOrdersTable.id eq id.value }
            .singleOrNull()
            ?.toSalesOrder(loadLines(id), loadDeliveredLineIndices(id))
    }

    override fun findAllByCompany(companyId: CompanyId): List<SalesOrder> = transaction {
        SalesOrdersTable.selectAll().where { SalesOrdersTable.companyId eq companyId.value }
            .map { row ->
                val id = SalesOrderId(row[SalesOrdersTable.id])
                row.toSalesOrder(loadLines(id), loadDeliveredLineIndices(id))
            }
    }

    private fun loadLines(salesOrderId: SalesOrderId): List<SalesOrderLine> =
        SalesOrderLinesTable.selectAll().where { SalesOrderLinesTable.salesOrderId eq salesOrderId.value }
            .orderBy(SalesOrderLinesTable.lineIndex)
            .map { row ->
                SalesOrderLine(
                    description = row[SalesOrderLinesTable.description],
                    accountId = AccountId(row[SalesOrderLinesTable.accountId]),
                    amount = Money(
                        row[SalesOrderLinesTable.amount],
                        Currency.getInstance(row[SalesOrderLinesTable.currency])
                    ),
                    itemType = LineItemType.valueOf(row[SalesOrderLinesTable.itemType]),
                    quantity = row[SalesOrderLinesTable.quantity],
                    stockItemId = row[SalesOrderLinesTable.stockItemId]?.let { StockItemId(it) }
                )
            }

    private fun loadDeliveredLineIndices(salesOrderId: SalesOrderId): Set<Int> =
        SalesOrderDeliveredLinesTable.selectAll().where { SalesOrderDeliveredLinesTable.salesOrderId eq salesOrderId.value }
            .map { it[SalesOrderDeliveredLinesTable.lineIndex] }
            .toSet()

    private fun populate(statement: UpdateBuilder<*>, salesOrder: SalesOrder) {
        statement[SalesOrdersTable.companyId] = salesOrder.companyId.value
        statement[SalesOrdersTable.customerId] = salesOrder.customerId.value
        statement[SalesOrdersTable.orderDate] = salesOrder.date
    }

    private fun ResultRow.toSalesOrder(lines: List<SalesOrderLine>, deliveredLineIndices: Set<Int>): SalesOrder =
        SalesOrder.reconstitute(
            id = SalesOrderId(this[SalesOrdersTable.id]),
            companyId = CompanyId(this[SalesOrdersTable.companyId]),
            customerId = CustomerId(this[SalesOrdersTable.customerId]),
            date = this[SalesOrdersTable.orderDate],
            lines = lines,
            deliveredLineIndices = deliveredLineIndices
        )
}
