package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide

/**
 * One debit or credit within a JournalEntry (docs/DDD_Design.md Section
 * 3.1/1) - a value object, part of the JournalEntry aggregate, not its
 * own root.
 */
data class JournalLine(
    val accountId: AccountId,
    val amount: Money,
    val side: TransactionSide,
    val dimensions: Map<DimensionType, String> = emptyMap()
)
