package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.purchasing.DeliveryTerms
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrder
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderId
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderLine
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderRepository
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderStatus
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
 * Exposed-backed `PurchaseOrderRepository` (docs/DDD_Design.md Section
 * 10.4). [save] deletes and reinserts all of an order's `PurchaseOrderLine`s
 * on every call, same precedent as `ExposedJournalEntryRepository`'s
 * `journal_lines` handling - `PurchaseOrderLine`s are immutable once set
 * at `create()`, only the parent's `status` mutates afterward (`send()`).
 */
class ExposedPurchaseOrderRepository : PurchaseOrderRepository {

    override fun save(purchaseOrder: PurchaseOrder): Unit = transaction {
        val exists = PurchaseOrdersTable.selectAll().where { PurchaseOrdersTable.id eq purchaseOrder.id.value }.count() > 0
        if (exists) {
            PurchaseOrdersTable.update({ PurchaseOrdersTable.id eq purchaseOrder.id.value }) { statement ->
                populate(statement, purchaseOrder)
            }
        } else {
            PurchaseOrdersTable.insert { statement ->
                statement[id] = purchaseOrder.id.value
                populate(statement, purchaseOrder)
            }
        }

        PurchaseOrderLinesTable.deleteWhere { PurchaseOrderLinesTable.purchaseOrderId eq purchaseOrder.id.value }
        purchaseOrder.lines.forEachIndexed { index, line ->
            PurchaseOrderLinesTable.insert { statement ->
                statement[purchaseOrderId] = purchaseOrder.id.value
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
    }

    override fun findById(id: PurchaseOrderId): PurchaseOrder? = transaction {
        PurchaseOrdersTable.selectAll().where { PurchaseOrdersTable.id eq id.value }
            .singleOrNull()
            ?.toPurchaseOrder(loadLines(id))
    }

    override fun findAllByCompany(companyId: CompanyId): List<PurchaseOrder> = transaction {
        PurchaseOrdersTable.selectAll().where { PurchaseOrdersTable.companyId eq companyId.value }
            .map { row -> row.toPurchaseOrder(loadLines(PurchaseOrderId(row[PurchaseOrdersTable.id]))) }
    }

    private fun loadLines(purchaseOrderId: PurchaseOrderId): List<PurchaseOrderLine> =
        PurchaseOrderLinesTable.selectAll().where { PurchaseOrderLinesTable.purchaseOrderId eq purchaseOrderId.value }
            .orderBy(PurchaseOrderLinesTable.lineIndex)
            .map { row ->
                PurchaseOrderLine(
                    description = row[PurchaseOrderLinesTable.description],
                    accountId = AccountId(row[PurchaseOrderLinesTable.accountId]),
                    amount = Money(
                        row[PurchaseOrderLinesTable.amount],
                        Currency.getInstance(row[PurchaseOrderLinesTable.currency])
                    ),
                    itemType = LineItemType.valueOf(row[PurchaseOrderLinesTable.itemType]),
                    quantity = row[PurchaseOrderLinesTable.quantity],
                    stockItemId = row[PurchaseOrderLinesTable.stockItemId]?.let { StockItemId(it) }
                )
            }

    private fun populate(statement: UpdateBuilder<*>, purchaseOrder: PurchaseOrder) {
        statement[PurchaseOrdersTable.companyId] = purchaseOrder.companyId.value
        statement[PurchaseOrdersTable.creditorId] = purchaseOrder.creditorId.value
        statement[PurchaseOrdersTable.orderDate] = purchaseOrder.date
        statement[PurchaseOrdersTable.status] = purchaseOrder.status.name
        statement[PurchaseOrdersTable.deliveryTerms] = purchaseOrder.deliveryTerms.name
        statement[PurchaseOrdersTable.goodsInTransitAccountId] = purchaseOrder.goodsInTransitAccountId?.value
        statement[PurchaseOrdersTable.grniAccountId] = purchaseOrder.grniAccountId?.value
    }

    private fun ResultRow.toPurchaseOrder(lines: List<PurchaseOrderLine>): PurchaseOrder = PurchaseOrder.reconstitute(
        id = PurchaseOrderId(this[PurchaseOrdersTable.id]),
        companyId = CompanyId(this[PurchaseOrdersTable.companyId]),
        creditorId = CreditorId(this[PurchaseOrdersTable.creditorId]),
        date = this[PurchaseOrdersTable.orderDate],
        lines = lines,
        status = PurchaseOrderStatus.valueOf(this[PurchaseOrdersTable.status]),
        deliveryTerms = DeliveryTerms.valueOf(this[PurchaseOrdersTable.deliveryTerms]),
        goodsInTransitAccountId = this[PurchaseOrdersTable.goodsInTransitAccountId]?.let { AccountId(it) },
        grniAccountId = this[PurchaseOrdersTable.grniAccountId]?.let { AccountId(it) }
    )
}
