package com.theprodeogroup.fish.domain.tax

import java.util.UUID

/**
 * Identity of a TaxRule - global reference data (docs/DDD_Design.md
 * Section 10.11), not scoped to any Tenant/Company. A jurisdiction's
 * statutory tax rate is real government policy, the same for every
 * Company operating there regardless of which Tenant it belongs to.
 */
@JvmInline
value class TaxRuleId(val value: UUID) {
    companion object {
        fun generate(): TaxRuleId = TaxRuleId(UUID.randomUUID())
    }
}

/**
 * Identity of a TaxComputation - unlike `TrialBalance`/`ProfitAndLoss`
 * (ephemeral, recomputed on demand, never persisted), `TaxComputation`
 * is persisted for audit/compliance purposes (docs/DDD_Design.md Section
 * 10.11): a government-ready tax figure needs a preserved "as computed"
 * record, not just whatever a live recomputation happens to produce
 * later after more entries post.
 */
@JvmInline
value class TaxComputationId(val value: UUID) {
    companion object {
        fun generate(): TaxComputationId = TaxComputationId(UUID.randomUUID())
    }
}
