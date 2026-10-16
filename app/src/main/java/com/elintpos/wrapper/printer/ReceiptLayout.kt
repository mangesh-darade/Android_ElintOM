package com.elintpos.wrapper.printer

/**
 * Turns a tab-separated POS invoice into the same blocks as the on-screen
 * bill: centered shop lines, invoice/date on one row, and columns.
 * No rule characters are kept.
 */
object ReceiptLayout {
    private val shortInvoice = Regex("""(?i)^invoice no\.?\s+\d+\s*$""")
    private val numeric = Regex("""[0-9][0-9.,]*\s*%?|[0-9.]+\s*%""")

    sealed class Row {
        data class Center(
            val text: String,
            val bold: Boolean = false,
            val large: Boolean = false
        ) : Row()

        data class Left(val text: String, val bold: Boolean = false) : Row()
        data class Right(val text: String) : Row()
        data class Pair(val left: String, val right: String, val bold: Boolean = false) : Row()
        data class Triple(val left: String, val mid: String, val right: String) : Row()
        data class Table(val rows: List<List<String>>) : Row()
    }

    fun hasTable(raw: String): Boolean {
        return build(raw).any { it is Row.Table && (it.rows.firstOrNull()?.size ?: 0) >= 2 }
    }

    fun build(raw: String): List<Row> {
        val lines = ReceiptText.prepare(raw)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        val rows = mutableListOf<Row>()
        var index = 0
        var customerStarted = false
        var dateAt = lines.indexOfFirst { it.startsWith("Date", ignoreCase = true) }
        var invoiceAt = lines.indexOfFirst { it.contains("Invoice", ignoreCase = true) && it.contains(":") }
        if (dateAt < 0 || invoiceAt < 0) {
            dateAt = -1
            invoiceAt = -1
        }
        val pairAt = if (dateAt >= 0) minOf(dateAt, invoiceAt) else -1
        val tableStart = lines.indexOfFirst { isTableHeader(it) }
        if (pairAt >= tableStart && tableStart >= 0) {
            dateAt = -1
            invoiceAt = -1
        }

        while (index < lines.size) {
            val line = lines[index]
            if (isTableHeader(line)) {
                val (table, next) = readTable(lines, index)
                if (table.rows.isNotEmpty()) rows.add(table)
                index = next
                customerStarted = true
                continue
            }
            if (line.startsWith("Grand Total", ignoreCase = true)) {
                val grand = readGrandTotal(lines, index)
                rows.addAll(grand.first)
                index = grand.second
                continue
            }
            if (line.startsWith("Paid Amount", ignoreCase = true) ||
                line.startsWith("Paid by", ignoreCase = true) ||
                line.startsWith("Balance", ignoreCase = true)
            ) {
                val (paid, next) = readPaid(lines, index)
                rows.add(paid)
                index = next
                continue
            }
            if (index == pairAt) {
                rows.add(Row.Pair(lines[invoiceAt].trim(), lines[dateAt].trim()))
                index++
                continue
            }
            if (index == dateAt || index == invoiceAt) {
                index++
                continue
            }
            if (shortInvoice.matches(line)) {
                rows.add(Row.Right(line))
                index++
                continue
            }
            if (line.equals("GSTIN:", ignoreCase = true) && index + 1 < lines.size && !lines[index + 1].contains('\t')) {
                rows.add(Row.Center("GSTIN: ${lines[index + 1].trim()}"))
                index += 2
                continue
            }
            if (line.startsWith("Customer", ignoreCase = true) || line.startsWith(",")) {
                customerStarted = true
            }
            rows.add(plainRow(line, customerStarted || tableStart in 0..index))
            index++
        }
        return rows
    }

    private fun plainRow(line: String, afterHeader: Boolean): Row {
        val trimmed = line.replace('\t', ' ').replace(Regex(""" {2,}"""), " ").trim()
        if (!afterHeader && looksCentered(trimmed)) {
            val large = trimmed.length in 12..32 &&
                trimmed == trimmed.uppercase() &&
                !trimmed.contains("INVOICE") &&
                !trimmed.contains("SALE")
            val bold = large || trimmed.equals("TAX INVOICE", ignoreCase = true)
            return Row.Center(trimmed, bold = bold, large = large)
        }
        if (trimmed.equals("TOTAL SAVINGS: 0.0", ignoreCase = true) ||
            trimmed.startsWith("TOTAL SAVINGS", ignoreCase = true) ||
            trimmed.equals("Tax Summary", ignoreCase = true) ||
            trimmed.equals("TAX INVOICE", ignoreCase = true)
        ) {
            return Row.Center(trimmed, bold = true)
        }
        if (trimmed.startsWith("Thank", ignoreCase = true) || trimmed.startsWith("*")) {
            return Row.Center(trimmed, bold = trimmed.startsWith("*"))
        }
        return Row.Left(trimmed)
    }

    private fun looksCentered(line: String): Boolean {
        if (line.startsWith("Customer", ignoreCase = true) || line.startsWith(",")) return false
        if (line.startsWith("Date", ignoreCase = true) || line.startsWith("Invoice", ignoreCase = true)) return false
        return true
    }

    private fun isTableHeader(line: String): Boolean {
        if (!line.contains('\t')) return false
        val lower = line.lowercase()
        return lower.contains("item name") || lower.contains("hsn") || lower.contains("tax rate")
    }

    private fun readTable(lines: List<String>, start: Int): Pair<Row.Table, Int> {
        val header = cells(lines[start])
        val body = mutableListOf(header)
        var index = start + 1
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("Grand Total", ignoreCase = true) ||
                line.startsWith("Paid ", ignoreCase = true) ||
                line.startsWith("Paid by", ignoreCase = true) ||
                line.startsWith("Balance", ignoreCase = true) ||
                line.startsWith("TOTAL SAVINGS", ignoreCase = true) ||
                line.equals("Tax Summary", ignoreCase = true) ||
                line.startsWith("Thank", ignoreCase = true) ||
                line.startsWith("(This Sale", ignoreCase = true) ||
                line.startsWith("Total Award", ignoreCase = true) ||
                line.startsWith("*")
            ) {
                break
            }
            if (isTableHeader(line) && index != start) break
            if (line.contains('\t')) {
                body.add(normalize(header, cells(line)))
                index++
                continue
            }
            if (index + 1 < lines.size &&
                lines[index + 1].contains('\t') &&
                line.length <= 24 &&
                !line.contains(":")
            ) {
                val next = cells(lines[index + 1]).toMutableList()
                val first = next.firstOrNull().orEmpty()
                next[0] = if (first.isBlank()) line else line + "\n" + first
                body.add(normalize(header, next))
                index += 2
                continue
            }
            break
        }
        return Row.Table(body) to index
    }

    private fun readGrandTotal(lines: List<String>, start: Int): Pair<List<Row>, Int> {
        var index = start + 1
        var words = ""
        var amount = ""
        val parts = cells(lines[start]).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isNotEmpty()) {
            val head = parts[0]
                .replace(Regex("(?i)^grand total"), "")
                .trim()
                .trim('(', ')')
                .trim()
            val tail = parts.drop(1)
            if (tail.isNotEmpty() && numeric.matches(tail.last())) {
                amount = tail.last()
                words = (listOf(head) + tail.dropLast(1)).filter { it.isNotBlank() }.joinToString(" ")
            } else {
                words = (listOf(head) + tail).filter { it.isNotBlank() }.joinToString(" ")
            }
        }
        if (amount.isBlank() && index < lines.size && !lines[index].contains('\t') && !lines[index].startsWith("Paid", ignoreCase = true)) {
            words = lines[index].trim()
            index++
        }
        if (amount.isBlank() && index < lines.size && lines[index].contains('\t')) {
            val amountCells = cells(lines[index]).filter { it.isNotBlank() }
            if (words.isBlank() && amountCells.size >= 2) {
                words = amountCells.dropLast(1).joinToString(" ")
            }
            amount = amountCells.lastOrNull().orEmpty()
            index++
        }
        val rows = mutableListOf<Row>(Row.Left("Grand Total", bold = true))
        if (words.isNotBlank() || amount.isNotBlank()) {
            rows.add(Row.Pair(words, amount, bold = true))
        }
        return rows to index
    }

    private fun readPaid(lines: List<String>, start: Int): Pair<Row, Int> {
        var paid = ""
        var by = ""
        var balance = ""
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("TOTAL SAVINGS", ignoreCase = true) ||
                line.equals("Tax Summary", ignoreCase = true) ||
                isTableHeader(line) ||
                line.startsWith("Thank", ignoreCase = true)
            ) {
                break
            }
            when {
                line.startsWith("Paid Amount", ignoreCase = true) -> {
                    if (paid.isBlank()) {
                        val value = cells(line).lastOrNull { it.isNotBlank() && !it.startsWith("Paid", ignoreCase = true) }
                            .orEmpty()
                        paid = if (value.isBlank()) line.replace('\t', ' ') else "Paid Amount $value"
                    }
                }
                line.startsWith("Paid by", ignoreCase = true) -> {
                    val rest = line.substringAfter(":", "").trim()
                    if (rest.isBlank() && index + 1 < lines.size && !lines[index + 1].contains('\t')) {
                        index++
                        by = "Paid by: ${lines[index].trim()}"
                    } else {
                        by = if (rest.isBlank()) "Paid by" else "Paid by: $rest"
                    }
                }
                line.startsWith("Balance", ignoreCase = true) -> {
                    val value = cells(line.replace(":", "\t")).lastOrNull {
                        it.isNotBlank() && !it.equals("Balance", ignoreCase = true)
                    }.orEmpty()
                    balance = if (value.isBlank()) line.replace('\t', ' ') else "Balance: $value"
                }
                else -> break
            }
            index++
        }
        return Row.Triple(paid, by, balance) to index
    }

    private fun cells(line: String): List<String> = line.split('\t').map { it.trim() }

    private fun normalize(header: List<String>, source: List<String>): List<String> {
        val width = header.size.coerceAtLeast(1)
        val trimmed = source.map { it.trim() }
        val filled = trimmed.withIndex().filter { it.value.isNotEmpty() }
        if (trimmed.firstOrNull().equals("Rounding", ignoreCase = true) && filled.size == 2) {
            val placed = MutableList(width) { "" }
            placed[0] = trimmed.first()
            val discount = header.indexOfFirst { it.contains("discount", ignoreCase = true) }
            placed[if (discount >= 0) discount else width - 1] = filled.last().value
            return placed
        }
        if (filled.size == 1 && filled.first().index > 0 && numeric.matches(filled.first().value)) {
            val placed = MutableList(width) { "" }
            placed[width - 1] = filled.first().value
            return placed
        }
        val padded = trimmed.toMutableList()
        while (padded.size < width) padded.add("")
        return padded
    }
}
