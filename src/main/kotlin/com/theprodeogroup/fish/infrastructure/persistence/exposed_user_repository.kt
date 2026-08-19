package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.tenancy.User
import com.theprodeogroup.fish.domain.tenancy.UserId
import com.theprodeogroup.fish.domain.tenancy.UserRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Exposed-backed `UserRepository` (docs/DDD_Design.md Section 10.3).
 * Reconstitution reuses `User.create()` directly rather than adding an
 * `internal reconstitute()` - User has no mutable state beyond identity
 * fields, and `create()`'s only validation (a non-blank, `@`-containing
 * email) is a check any already-persisted row will always still pass.
 */
class ExposedUserRepository : UserRepository {

    override fun save(user: User): Unit = transaction {
        val exists = UsersTable.selectAll().where { UsersTable.id eq user.id.value }.count() > 0
        if (exists) {
            UsersTable.update({ UsersTable.id eq user.id.value }) { statement ->
                statement[email] = user.email
                statement[name] = user.name
            }
        } else {
            UsersTable.insert { statement ->
                statement[id] = user.id.value
                statement[email] = user.email
                statement[name] = user.name
            }
        }
        Unit
    }

    override fun findById(id: UserId): User? = transaction {
        UsersTable.selectAll().where { UsersTable.id eq id.value }
            .map { it.toUser() }
            .singleOrNull()
    }

    override fun findByEmail(email: String): User? = transaction {
        UsersTable.selectAll().where { UsersTable.email eq email }
            .map { it.toUser() }
            .singleOrNull()
    }

    private fun ResultRow.toUser(): User = User.create(
        email = this[UsersTable.email],
        name = this[UsersTable.name],
        id = UserId(this[UsersTable.id])
    )
}
