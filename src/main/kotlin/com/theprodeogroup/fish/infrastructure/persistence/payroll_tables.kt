package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

/** Exposed table definition for Payroll's GL-facing posting interface (`V4__ecosystem_tables.sql`, docs/DDD_Design.md Section 10.4). */
object PayRunsTable : Table("pay_runs") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val payDate = date("pay_date")
    val totalWagesAmount = decimal("total_wages_amount", 19, 4)
    val totalSalariesAmount = decimal("total_salaries_amount", 19, 4)
    val currency = varchar("currency", 3)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed table definition for `LeaveAccrual` (`V6__leave_accrual_table.sql`,
 * docs/DDD_Design.md Section 10.18) - the embedded `Provision`'s state
 * (`provision_id`/`provision_description`/`balance_amount`) is inlined
 * as columns rather than a separate `provisions` table, confirmed with
 * the user: `Provision` has no repository of its own, only ever used as
 * `LeaveAccrual`'s private delegate.
 */
object LeaveAccrualsTable : Table("leave_accruals") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val employeeId = uuid("employee_id")
    val provisionId = uuid("provision_id")
    val provisionDescription = text("provision_description")
    val balanceAmount = decimal("balance_amount", 19, 4)
    val currency = varchar("currency", 3)

    override val primaryKey = PrimaryKey(id)
}
