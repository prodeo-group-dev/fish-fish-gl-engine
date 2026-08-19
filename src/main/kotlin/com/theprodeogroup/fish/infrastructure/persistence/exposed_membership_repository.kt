package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.MembershipId
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.MembershipStatus
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.UserId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Exposed-backed `MembershipRepository` (docs/DDD_Design.md Section 10.3). Same existence-check-then-insert-or-update shape as the Ledger repositories. */
class ExposedMembershipRepository : MembershipRepository {

    override fun save(membership: Membership): Unit = transaction {
        val exists = MembershipsTable.selectAll().where { MembershipsTable.id eq membership.id.value }.count() > 0
        if (exists) {
            MembershipsTable.update({ MembershipsTable.id eq membership.id.value }) { statement ->
                populate(statement, membership)
            }
        } else {
            MembershipsTable.insert { statement ->
                statement[id] = membership.id.value
                populate(statement, membership)
            }
        }
        Unit
    }

    override fun findById(id: MembershipId): Membership? = transaction {
        MembershipsTable.selectAll().where { MembershipsTable.id eq id.value }
            .map { it.toMembership() }
            .singleOrNull()
    }

    override fun findAllByTenant(tenantId: TenantId): List<Membership> = transaction {
        MembershipsTable.selectAll().where { MembershipsTable.tenantId eq tenantId.value }
            .map { it.toMembership() }
    }

    override fun findAllByUser(userId: UserId): List<Membership> = transaction {
        MembershipsTable.selectAll().where { MembershipsTable.userId eq userId.value }
            .map { it.toMembership() }
    }

    private fun populate(statement: UpdateBuilder<*>, membership: Membership) {
        statement[MembershipsTable.userId] = membership.userId.value
        statement[MembershipsTable.tenantId] = membership.tenantId.value
        statement[MembershipsTable.role] = membership.role.name
        statement[MembershipsTable.status] = membership.status.name
    }

    private fun ResultRow.toMembership(): Membership = Membership.reconstitute(
        id = MembershipId(this[MembershipsTable.id]),
        userId = UserId(this[MembershipsTable.userId]),
        tenantId = TenantId(this[MembershipsTable.tenantId]),
        role = Role.valueOf(this[MembershipsTable.role]),
        status = MembershipStatus.valueOf(this[MembershipsTable.status])
    )
}
