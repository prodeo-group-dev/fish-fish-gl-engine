package com.theprodeogroup.fish.infrastructure.audit

import com.theprodeogroup.fish.domain.ledger.EntryProvenance
import com.theprodeogroup.fish.domain.ledger.SourceReference
import com.theprodeogroup.fish.domain.ledger.VatBasis
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/**
 * What GL records about who and why for the entries one HTTP request stores (standards S4 and S6, GL-T-38). A route sets the parts it
 * knows ([actor] after authorisation, [reason] and the source reference from the request body); the journal repository reads
 * [snapshot] when it INSERTS an entry and writes it beside the accounting facts. So no posting use case carries an actor: they stay
 * unaware, and one place (the repository) stamps every door, including doors not yet written.
 *
 * A request that stores two entries (a sale and a collection in one call) gives both the same record, because they describe one
 * document. Outside a request (a test, a migration job) there is no context, and [snapshot] is null: nothing is recorded, never a guess.
 */
class AuditContext {
    @Volatile var recordedBy: String? = null
    @Volatile var actingUser: String? = null
    @Volatile var reason: String? = null
    @Volatile var sourceReference: SourceReference? = null
    @Volatile var vatBasis: VatBasis? = null

    fun snapshot(): EntryProvenance? =
        EntryProvenance(recordedBy, actingUser, reason, sourceReference, vatBasis).takeUnless { it.isEmpty }
}

/** The current request's [AuditContext], carried across coroutine thread switches so the blocking repository sees what the route set. */
object AuditContexts {
    private val holder = ThreadLocal<AuditContext?>()

    fun element(context: AuditContext): ThreadContextElement<AuditContext?> = holder.asContextElement(context)

    fun current(): AuditContext? = holder.get()

    /** Runs [block] with [context] current on this thread (tests and non-request callers). */
    fun <T> runWith(context: AuditContext, block: () -> T): T {
        val previous = holder.get()
        holder.set(context)
        try {
            return block()
        } finally {
            holder.set(previous)
        }
    }
}
