package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.inventory.InventoryStage
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
import com.theprodeogroup.fish.domain.inventory.StockShortageEscalation
import com.theprodeogroup.fish.domain.inventory.StockShortageEscalationId
import com.theprodeogroup.fish.domain.inventory.StockShortageEscalationRepository
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/** Exposed-backed `StockItemRepository` (docs/DDD_Design.md Section 10.4). */
class ExposedStockItemRepository : StockItemRepository {

    override fun save(stockItem: StockItem): Unit = transaction {
        val exists = StockItemsTable.selectAll().where { StockItemsTable.id eq stockItem.id.value }.count() > 0
        if (exists) {
            StockItemsTable.update({ StockItemsTable.id eq stockItem.id.value }) { statement ->
                populate(statement, stockItem)
            }
        } else {
            StockItemsTable.insert { statement ->
                statement[id] = stockItem.id.value
                populate(statement, stockItem)
            }
        }
        Unit
    }

    override fun findById(id: StockItemId): StockItem? = transaction {
        StockItemsTable.selectAll().where { StockItemsTable.id eq id.value }
            .map { it.toStockItem() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<StockItem> = transaction {
        StockItemsTable.selectAll().where { StockItemsTable.companyId eq companyId.value }
            .map { it.toStockItem() }
    }

    private fun populate(statement: UpdateBuilder<*>, stockItem: StockItem) {
        statement[StockItemsTable.companyId] = stockItem.companyId.value
        statement[StockItemsTable.name] = stockItem.name
        statement[StockItemsTable.currency] = stockItem.currency.currencyCode
        statement[StockItemsTable.stage] = stockItem.stage.name
        statement[StockItemsTable.quantityOnHand] = stockItem.quantityOnHand
        statement[StockItemsTable.unitCostAmount] = stockItem.unitCost.amount
        statement[StockItemsTable.nrvWriteDownPerUnitAmount] = stockItem.nrvWriteDownPerUnit.amount
    }

    private fun ResultRow.toStockItem(): StockItem {
        val currency = Currency.getInstance(this[StockItemsTable.currency])
        return StockItem.reconstitute(
            id = StockItemId(this[StockItemsTable.id]),
            companyId = CompanyId(this[StockItemsTable.companyId]),
            name = this[StockItemsTable.name],
            currency = currency,
            stage = InventoryStage.valueOf(this[StockItemsTable.stage]),
            quantityOnHand = this[StockItemsTable.quantityOnHand],
            unitCost = Money(this[StockItemsTable.unitCostAmount], currency),
            nrvWriteDownPerUnit = Money(this[StockItemsTable.nrvWriteDownPerUnitAmount], currency)
        )
    }
}

/** Exposed-backed `StockShortageEscalationRepository` - append-only, plain insert on every [save]. */
class ExposedStockShortageEscalationRepository : StockShortageEscalationRepository {

    override fun save(escalation: StockShortageEscalation): Unit = transaction {
        StockShortageEscalationsTable.insert { statement ->
            statement[id] = escalation.id.value
            statement[companyId] = escalation.companyId.value
            statement[stockItemId] = escalation.stockItemId.value
            statement[requestedQuantity] = escalation.requestedQuantity
            statement[quantityOnHandAtRequest] = escalation.quantityOnHandAtRequest
            statement[requestedByEmail] = escalation.requestedByEmail
            statement[overridden] = escalation.overridden
            statement[requestedAt] = escalation.requestedAt
        }
        Unit
    }

    override fun findAllByCompany(companyId: CompanyId): List<StockShortageEscalation> = transaction {
        StockShortageEscalationsTable.selectAll().where { StockShortageEscalationsTable.companyId eq companyId.value }
            .map { it.toStockShortageEscalation() }
    }

    private fun ResultRow.toStockShortageEscalation(): StockShortageEscalation =
        StockShortageEscalation.reconstitute(
            id = StockShortageEscalationId(this[StockShortageEscalationsTable.id]),
            companyId = CompanyId(this[StockShortageEscalationsTable.companyId]),
            stockItemId = StockItemId(this[StockShortageEscalationsTable.stockItemId]),
            requestedQuantity = this[StockShortageEscalationsTable.requestedQuantity],
            quantityOnHandAtRequest = this[StockShortageEscalationsTable.quantityOnHandAtRequest],
            requestedByEmail = this[StockShortageEscalationsTable.requestedByEmail],
            overridden = this[StockShortageEscalationsTable.overridden],
            requestedAt = this[StockShortageEscalationsTable.requestedAt]
        )
}
