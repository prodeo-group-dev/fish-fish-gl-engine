package com.theprodeogroup.fish.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

/**
 * Connection configuration for the persistence layer (docs/DDD_Design.md
 * Section 10) - PostgreSQL, read entirely from environment variables, no
 * credentials ever committed to source control.
 *
 * [FISH_DB_HOST]/[FISH_DB_PORT]/[FISH_DB_NAME] default to the local dev
 * setup (`fish_dev` on `localhost:5432`) - safe to default, since none of
 * these are secrets. [FISH_DB_USER]/[FISH_DB_PASSWORD] have **no
 * default** and throw if missing - there's no safe default for a
 * credential, unlike a hostname.
 */
object DatabaseConfig {
    fun dataSource(): DataSource {
        val host = System.getenv("FISH_DB_HOST") ?: "localhost"
        val port = System.getenv("FISH_DB_PORT") ?: "5432"
        val database = System.getenv("FISH_DB_NAME") ?: "fish_dev"
        val user = System.getenv("FISH_DB_USER")
            ?: error("FISH_DB_USER environment variable is required - no default credential")
        val password = System.getenv("FISH_DB_PASSWORD")
            ?: error("FISH_DB_PASSWORD environment variable is required - no default credential")

        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host:$port/$database"
            username = user
            this.password = password
            maximumPoolSize = System.getenv("FISH_DB_POOL_SIZE")?.toIntOrNull() ?: 10
            driverClassName = "org.postgresql.Driver"
        }
        return HikariDataSource(config)
    }

    /** Connects Exposed to [dataSource] - call once per process, typically at startup. */
    fun connectExposed(dataSource: DataSource = dataSource()): Database =
        Database.connect(dataSource)
}
