package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Paying VAT to the authority, or receiving a refund from it (GL-T-30, GL-FR-TAX-9, GL-UC-20; Femi 2026-10-22: "at the end of
 * the quarter VAT is PAID to the Government: Dr VAT, Cr Bank"). It is its own entry: [CashDirection.PAID] debits the Company's VAT
 * control account (2150) and credits the cash or bank book; [CashDirection.RECEIVED] is the reverse. The VAT line is tagged
 * `VAT_DIRECTION = SETTLEMENT`, so the VAT return does not count it as VAT charged or suffered.
 *
 * It reuses [RecordCashBookEntryUseCase] whole (the book must be a cash or bank book, an open Period, the entry id derived from the
 * Idempotency-Key, replay and key reuse, the below-zero warnings, Undo), and is the only way to post to the VAT control account
 * from a book: the ordinary receipt and payment doors keep refusing it. Interest and penalties on late VAT are expenses, recorded
 * as ordinary payments, not here.
 */
class RecordVatSettlementUseCase(
    private val accountRepository: AccountRepository,
    private val recordCashBookEntryUseCase: RecordCashBookEntryUseCase
) {
    data class Request(
        val companyId: CompanyId,
        val bookAccountId: com.theprodeogroup.fish.domain.ledger.AccountId,
        val direction: CashDirection,
        val amount: BigDecimal,
        val date: LocalDate,
        val description: String?,
        val entryId: JournalEntryId
    )

    fun execute(request: Request): RecordCashBookEntryUseCase.Result {
        val control = accountRepository.findAllByCompany(request.companyId)
            .firstOrNull { it.type == AccountType.LIABILITY && it.code == ChartOfAccountsTemplate.VAT_CONTROL_ACCOUNT_CODE }
            ?: return RecordCashBookEntryUseCase.Result.VatControlAccountNotConfigured
        return recordCashBookEntryUseCase.execute(
            RecordCashBookEntryUseCase.Request(
                request.companyId, request.bookAccountId, request.direction, request.amount, request.date, control.id,
                request.description, CashFlowActivity.OPERATING, request.entryId, settlesVat = true
            )
        )
    }
}
