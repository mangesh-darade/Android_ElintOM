package com.elintpos.wrapper.printer

/**
 * Decides whether a printed bill should pulse the cash drawer.
 * "Cashier" does not count. Card, UPI, and wallet tenders do not open the drawer.
 * A bill that mentions cash still opens it, including a split tender.
 */
object CashSale {
    private val cashTender = Regex(
        "(?<![\\p{L}\\p{N}])(cash|cod)(?![\\p{L}\\p{N}])|रोख|नकद",
        RegexOption.IGNORE_CASE
    )

    fun shouldOpenDrawer(text: String): Boolean {
        if (text.isBlank()) return false
        return cashTender.containsMatchIn(text)
    }
}
