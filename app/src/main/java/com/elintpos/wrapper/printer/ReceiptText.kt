package com.elintpos.wrapper.printer

import org.json.JSONTokener

/**
 * The POS sometimes sends the invoice as one quoted string with the
 * characters \n and \t still in it. Those must become real line breaks
 * and column separators before they reach the printer.
 */
object ReceiptText {
    private val horizontalRule = Regex("""^[=\-_.*~─━═]{3,}$""")
    private val verticalBar = Regex("""[|│┃]""")
    private val extraSpaces = Regex(""" {2,}""")
    private val codeLine = Regex(
        """(?i)^(<\?php|<\?=|<\?|\?>|function\s+\w+|public\s+|private\s+|protected\s+|echo\s+|print\s+|var\s+|let\s+|const\s+|\$\w+|//|/\*|\*|import\s+|export\s+|class\s+\w+|namespace\s+)"""
    )

    fun looksLikeSource(text: String): Boolean {
        if (text.contains("<?php") || text.contains("<?=")) return true
        val codeLines = text.lineSequence().count { line ->
            val trimmed = line.trim()
            codeLine.containsMatchIn(trimmed) ||
                trimmed.contains("document.") ||
                trimmed.contains("console.") ||
                trimmed.contains("function(") ||
                trimmed.contains("function (")
        }
        return codeLines >= 2
    }

    /** Unescape the POS string and keep tab characters so columns can be aligned. */
    fun prepare(raw: String): String {
        var text = extractInvoice(raw).trim()
        if (text.length >= 2 && text.first() == '"' && text.last() == '"') {
            text = try {
                val value = JSONTokener(text).nextValue()
                if (value is String) value else text.substring(1, text.length - 1)
            } catch (_: Exception) {
                text.substring(1, text.length - 1)
            }
        }
        if (text.contains("\\n") || text.contains("\\t") || text.contains("\\r")) {
            text = text
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\\t", "\t")
        }
        val lines = text
            .lines()
            .map { stripCodeWrapper(verticalBar.replace(it, " ")) }
            .map { unglueLine(it) }
            .flatMap { it.lines() }
            .filterNot { horizontalRule.matches(it.trim()) }
            .filterNot { isCodeLine(it) }
            .filterNot { isPrintChrome(it) }
        return dropBrowserHelp(lines)
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }

    private val printChrome = setOf("web print", "email", "sms", "whatsapp", "back to pos")

    private fun isPrintChrome(line: String): Boolean = printChrome.contains(line.trim().lowercase())

    private fun dropBrowserHelp(lines: List<String>): List<String> {
        val cut = lines.indexOfFirst {
            it.contains("don't forget to disable", ignoreCase = true) ||
                it.contains("header and footer", ignoreCase = true) ||
                it.contains("Available SMS limit", ignoreCase = true)
        }
        return if (cut < 0) lines else lines.subList(0, cut)
    }

    private val fieldLabels = listOf(
        "Net Unit Price", "Unit Price", "Item Discount", "Item Name", "Grand Total",
        "Paid Amount", "Tax Summary", "Total Savings", "TOTAL SAVINGS", "Tax Rate",
        "Tax Excl", "Tax Amt", "CGST (%)", "SGST (%)", "Invoice No", "Rounding",
        "Balance", "Amount", "Paid by", "Total", "HSN", "MRP", "QTY", "Qty"
    ).sortedByDescending { it.length }

    private fun unglueLine(line: String): String {
        val cleaned = line
            .replace(Regex("""(?i):a\s*(?=\d|Cash)"""), ": ")
            .replace(Regex(""":-a(?=\d)"""), ": ")
        if (cleaned.contains('\t')) return cleaned
        val labelHits = fieldLabels.count { cleaned.contains(it, ignoreCase = true) }
        val decimalHits = Regex("""\d+\.\d+""").findAll(cleaned).count()
        val gluedRun = Regex("""\d+\.\d{1,2}(?:\d+\.\d{1,2})+""").containsMatchIn(cleaned)
        val shouldSplit = labelHits >= 2 || decimalHits >= 2 || gluedRun || (labelHits >= 1 && decimalHits >= 1)
        if (!shouldSplit) return cleaned
        val tokens = StringBuilder()
        var index = 0
        while (index < cleaned.length) {
            val label = fieldLabels.firstOrNull {
                cleaned.regionMatches(index, it, 0, it.length, ignoreCase = true)
            }
            if (label != null) {
                putBreak(tokens, label)
                tokens.append(cleaned.substring(index, index + label.length))
                index += label.length
                continue
            }
            if (cleaned[index].isDigit()) {
                val run = cleaned.substring(index).takeWhile { it.isDigit() || it == '.' || it == '%' }
                val amounts = parseAmounts(run)
                if (amounts != null) {
                    amounts.forEach { amount ->
                        putBreak(tokens, amount)
                        tokens.append(amount)
                    }
                    index += run.length
                    continue
                }
            }
            if (cleaned.startsWith("(This Sale", index) || cleaned.startsWith("Total Award", index)) {
                if (tokens.isNotEmpty() && tokens.last() != '\n') tokens.append('\n')
                val marker = if (cleaned.startsWith("(This Sale", index)) "(This Sale" else "Total Award"
                tokens.append(cleaned.substring(index, index + marker.length))
                index += marker.length
                continue
            }
            val name = Regex("""^[A-Z]{2,}[a-z]+""").find(cleaned.substring(index))
            if (name != null && index > 0) {
                val parts = Regex("""^([A-Z]{2,})([a-z].*)""").find(name.value)
                if (parts != null) {
                    putBreak(tokens, parts.groupValues[1])
                    tokens.append(parts.groupValues[1]).append('\n').append(parts.groupValues[2])
                    index += parts.value.length
                    continue
                }
            }
            tokens.append(cleaned[index])
            index++
        }
        return splitItemHeader(tokens.toString())
    }

    private fun putBreak(out: StringBuilder, next: String) {
        if (out.isEmpty()) return
        val section = next.equals("Grand Total", true) || next.equals("Paid Amount", true) ||
            next.equals("Paid by", true) || next.equals("Balance", true) ||
            next.equals("Total Savings", true) || next.equals("TOTAL SAVINGS", true) ||
            next.equals("Tax Summary", true) || next.equals("Rounding", true) ||
            next.equals("Total", true) || next.equals("HSN", true)
        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) {
            out.setLength(out.length - 1)
        }
        out.append(if (section) '\n' else '\t')
    }

    private fun splitItemHeader(tokenized: String): String {
        val amount = Regex("""(?i)Amount\t""").find(tokenized) ?: return tokenized
        if (!tokenized.contains("Item Name", ignoreCase = true)) return tokenized
        val header = tokenized.substring(0, amount.range.last + 1).trimEnd('\t')
        val rest = tokenized.substring(amount.range.last + 1).trimStart('\t', ' ')
        return if (rest.isEmpty()) header else header + "\n" + rest
    }

    private fun parseAmounts(raw: String): List<String>? {
        if (raw.isEmpty() || raw.any { !it.isDigit() && it != '.' && it != '%' }) return null
        val memo = HashMap<Int, List<String>?>()
        fun score(tokens: List<String>): Int {
            var total = 0
            for (token in tokens) {
                total += when {
                    token.matches(Regex("""\d+\.\d""")) -> 10
                    token.matches(Regex("""\d+\.\d{2}""")) -> 4
                    token.endsWith("%") -> 6
                    else -> 1
                }
            }
            return total
        }
        fun best(start: Int): List<String>? {
            if (start == raw.length) return emptyList()
            if (memo.containsKey(start)) return memo[start]
            var winner: List<String>? = null
            for (pattern in listOf(Regex("""\d+\.\d{2}"""), Regex("""\d+\.\d"""), Regex("""\d+%"""), Regex("""\d+"""))) {
                val match = pattern.find(raw.substring(start)) ?: continue
                if (match.range.first != 0) continue
                val intPart = match.value.substringBefore('.').substringBefore('%')
                if (match.value == "0" || (intPart.length > 1 && intPart.startsWith("0"))) continue
                val rest = best(start + match.value.length) ?: continue
                val candidate = listOf(match.value) + rest
                if (winner == null || score(candidate) > score(winner)) winner = candidate
            }
            memo[start] = winner
            return winner
        }
        return best(0)
    }

    private fun extractInvoice(raw: String): String {
        if (!looksLikeSource(raw)) return raw
        val start = listOf("Invoice No", "TAX INVOICE", "Item Name")
            .map { raw.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: return ""
        val tail = raw.substring(start)
        val end = listOf("Only Exchange", "come again", "Award Points", "TOTAL SAVINGS", "RECHAGRE", "MERCHANT PANEL")
            .map { marker ->
                val at = tail.indexOf(marker, ignoreCase = true)
                if (at < 0) -1 else {
                    val lineBreak = tail.indexOf('\n', at)
                    if (lineBreak < 0) tail.length else lineBreak + 1
                }
            }
            .filter { it >= 0 }
            .maxOrNull() ?: tail.length
        return tail.substring(0, end.coerceAtMost(tail.length))
    }

    private fun stripCodeWrapper(line: String): String {
        var text = line.trim()
        text = text.replace(Regex("""(?i)^(echo|print|return)\s+"""), "")
        if ((text.startsWith("\"") && text.endsWith("\";")) || (text.startsWith("'") && text.endsWith("';"))) {
            text = text.substring(1, text.length - 2)
        }
        return text.removeSuffix("\";").removeSuffix("';").trim()
    }

    private fun isCodeLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "{" || trimmed == "}" || trimmed == "};" || trimmed == ");") return true
        if (trimmed.startsWith("<script", ignoreCase = true) || trimmed.startsWith("</script", ignoreCase = true)) {
            return true
        }
        if (containsInvoiceText(trimmed)) return false
        return trimmed.contains("<?php") ||
            trimmed.contains("<?=") ||
            trimmed.contains("?>") ||
            trimmed.contains("document.") ||
            trimmed.contains("console.") ||
            trimmed.contains("function(") ||
            trimmed.contains("function (") ||
            codeLine.containsMatchIn(trimmed)
    }

    private fun containsInvoiceText(line: String): Boolean {
        return line.contains("Invoice", ignoreCase = true) ||
            line.contains("TAX", ignoreCase = true) ||
            line.contains("Item Name", ignoreCase = true) ||
            line.contains("Grand Total", ignoreCase = true) ||
            line.contains("Customer", ignoreCase = true) ||
            line.contains("GSTIN", ignoreCase = true) ||
            line.contains("Thank", ignoreCase = true) ||
            line.contains("Exchange", ignoreCase = true) ||
            line.contains("Paid", ignoreCase = true) ||
            line.contains("Amount", ignoreCase = true) ||
            line.contains("Total", ignoreCase = true)
    }

    fun flatten(text: String): String {
        return text.replace('\t', ' ')
            .lines()
            .joinToString("\n") { extraSpaces.replace(it, " ").trimEnd() }
            .trim()
    }

    fun decode(raw: String): String = flatten(prepare(raw))
}
