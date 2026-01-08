package com.elintpos.wrapper.printer

import android.content.Context
import android.util.Log
import com.elintpos.wrapper.UnifiedPrinterHandler
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * HTML Invoice Printer - Converts HTML to text and prints as plain text
 * 
 * Flow:
 * 1. HTML Invoice Content
 * 2. ↓ Convert HTML to plain text (remove tags, extract text)
 * 3. ↓ Format text for thermal printer
 * 4. ↓ Printer SDK → printText()
 */
class HtmlInvoicePrinter(private val context: Context) {
    
    private val printerConfigManager = PrinterConfigManager(context)
    private val unifiedPrinterHandler = UnifiedPrinterHandler(context)
    
    companion object {
        private const val TAG = "HtmlInvoicePrinter"
        private const val LOG_DIR_NAME = "html_print_errors"
        private const val MAX_LOG_FILES = 10 // Keep only last 10 log files
    }
    
    /**
     * Print HTML invoice as text
     * 
     * @param htmlContent HTML content to convert and print
     * @param preferType Preferred printer type (optional)
     * @return PrintResult with success status and message
     */
    fun printHtmlInvoice(htmlContent: String, preferType: String? = null): UnifiedPrinterHandler.PrintResult {
        return try {
            // Get printer profile
            val profile = getPrinterProfile(preferType)
            if (profile == null) {
                return UnifiedPrinterHandler.PrintResult(
                    success = false,
                    message = "No printer configured. Please configure a printer in settings."
                )
            }
            
            Log.d(TAG, "Printing HTML invoice as text with profile: ${profile.name}, paper width: ${profile.paperWidth} dots")
            
            // Step 1: Convert HTML to plain text
            val plainText = convertHtmlToText(htmlContent)
            if (plainText.isBlank()) {
                Log.e(TAG, "Failed to convert HTML to text - result is blank")
                Log.e(TAG, "HTML content length: ${htmlContent.length}")
                Log.e(TAG, "HTML preview: ${htmlContent.take(500)}")
                
                // Save error log to file
                saveErrorLog(
                    errorType = "CONVERSION_FAILED",
                    message = "HTML to text conversion returned blank",
                    htmlContent = htmlContent,
                    profile = profile
                )
                
                return UnifiedPrinterHandler.PrintResult(
                    success = false,
                    message = "Failed to convert HTML to text. Error log saved to file."
                )
            }
            
            Log.d(TAG, "Converted HTML to text: ${plainText.length} characters")

            // Step 2: Format for target paper width (58mm / 80mm, etc.)
            val formattedText = formatForPrinter(plainText, profile.paperWidth)
            Log.d(TAG, "Formatted text length after wrapping: ${formattedText.length} characters")
            
            // Step 3: Print text using unified printer handler
            val printResult = unifiedPrinterHandler.print(formattedText, preferType ?: "auto")
            
            if (printResult.success) {
                Log.d(TAG, "HTML invoice printed successfully as text")
            } else {
                Log.e(TAG, "Print failed: ${printResult.message}")
                
                // Save error log to file
                saveErrorLog(
                    errorType = "PRINT_FAILED",
                    message = printResult.message ?: "Print failed",
                    htmlContent = htmlContent,
                    plainText = formattedText,
                    profile = profile
                )
            }
            
            printResult
            
        } catch (e: Exception) {
            Log.e(TAG, "HTML invoice print error: ${e.message}", e)
            
            // Save error log to file
            saveErrorLog(
                errorType = "EXCEPTION",
                message = e.message ?: "Unknown error",
                exception = e,
                htmlContent = htmlContent.take(10000), // Save first 10KB of HTML
                profile = getPrinterProfile(preferType)
            )
            
            UnifiedPrinterHandler.PrintResult(
                success = false,
                message = "Print error: ${e.message}. Error log saved to file."
            )
        }
    }
    
    /**
     * Convert HTML content to plain text
     * Removes HTML tags, decodes entities, and formats for thermal printer
     */
    private fun convertHtmlToText(htmlContent: String): String {
        return try {
            var text = htmlContent
            
            // Remove script and style tags with their content
            text = text.replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            text = text.replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            
            // Replace common block elements with newlines
            // IMPORTANT: do NOT treat <td>/<th> as block elements here, or every
            // table cell becomes its own line which creates huge vertical gaps
            // on 80mm printers. We handle <td>/<th> separately below so that
            // table rows stay on a single line.
            text = text.replace(
                Regex("</?(div|p|br|h[1-6]|li|tr)[^>]*>", RegexOption.IGNORE_CASE),
                "\n"
            )
            
            // Replace table row end with newline
            text = text.replace(Regex("</tr>", RegexOption.IGNORE_CASE), "\n")
            
            // Replace table cell separators with a compact separator so content
            // stays on one line and columns don't get huge gaps. Using a pipe
            // plus single spaces works well on both 58mm and 80mm printers.
            text = text.replace(
                Regex("</?(td|th)[^>]*>", RegexOption.IGNORE_CASE),
                " | "
            )
            
            // Replace list items
            text = text.replace(Regex("</?ul[^>]*>", RegexOption.IGNORE_CASE), "\n")
            text = text.replace(Regex("</?ol[^>]*>", RegexOption.IGNORE_CASE), "\n")
            text = text.replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "  • ")
            
            // Remove all remaining HTML tags
            text = text.replace(Regex("<[^>]+>"), "")
            
            // Decode common HTML entities
            text = text.replace("&nbsp;", " ")
            text = text.replace("&amp;", "&")
            text = text.replace("&lt;", "<")
            text = text.replace("&gt;", ">")
            text = text.replace("&quot;", "\"")
            text = text.replace("&apos;", "'")
            text = text.replace("&copy;", "(c)")
            text = text.replace("&reg;", "(r)")
            text = text.replace("&trade;", "(tm)")
            text = text.replace("&euro;", "EUR")
            text = text.replace("&pound;", "GBP")
            text = text.replace("&yen;", "JPY")
            text = text.replace("&cent;", "cents")
            text = text.replace("&mdash;", "--")
            text = text.replace("&ndash;", "-")
            
            // Decode numeric entities (&#123; and &#x1F;)
            text = text.replace(Regex("&#(\\d+);")) { matchResult ->
                val code = matchResult.groupValues[1].toIntOrNull()
                if (code != null && code in 0..65535) {
                    code.toChar().toString()
                        } else {
                    matchResult.value
                }
            }
            text = text.replace(Regex("&#x([0-9A-Fa-f]+);")) { matchResult ->
                val code = matchResult.groupValues[1].toIntOrNull(16)
                if (code != null && code in 0..65535) {
                    code.toChar().toString()
                } else {
                    matchResult.value
                }
            }
            
            // Clean up whitespace
            // Replace multiple newlines with max 2 newlines
            text = text.replace(Regex("\n{3,}"), "\n\n")
            // Remove leading/trailing whitespace from each line
            text = text.lines().joinToString("\n") { it.trim() }
            // Remove empty lines (but keep at least one newline between sections)
            text = text.replace(Regex("\n\n\n+"), "\n\n")
            
            // Trim overall result
            text = text.trim()

            // Align any table-like lines that were created using the " | " separator
            text = alignTableColumns(text)
            
            Log.d(TAG, "HTML converted to text: ${text.length} characters")
            
            text
            
        } catch (e: Exception) {
            Log.e(TAG, "Error converting HTML to text: ${e.message}", e)
            
            // Fallback: Try to extract text more simply
            try {
                var simpleText = htmlContent
                // Remove all tags
                simpleText = simpleText.replace(Regex("<[^>]+>"), " ")
                // Decode basic entities
                simpleText = simpleText.replace("&nbsp;", " ")
                simpleText = simpleText.replace("&amp;", "&")
                simpleText = simpleText.replace("&lt;", "<")
                simpleText = simpleText.replace("&gt;", ">")
                simpleText = simpleText.replace("&quot;", "\"")
                // Clean whitespace
                simpleText = simpleText.replace(Regex(" +"), " ").trim()
                
                if (simpleText.isNotBlank()) {
                    Log.d(TAG, "Used fallback HTML to text conversion")
                    return simpleText
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback conversion also failed: ${e2.message}", e2)
            }
            
            // Last resort: return empty string
            ""
        }
    }

    /**
     * Take lines that contain " | " separators (our converted table rows)
     * and turn them into nicely aligned, space‑padded columns without the
     * '|' characters, so they look like classic POS receipts.
     */
    private fun alignTableColumns(text: String): String {
        val lines = text.lines()
        if (lines.isEmpty()) return text

        // Collect only the lines that actually look like table rows
        val tableLines = lines.filter { it.contains(" | ") }
        if (tableLines.isEmpty()) return text

        // Split into columns and compute max width per column
        val splitRows = tableLines.map { it.split(" | ") }
        val maxColumns = splitRows.maxOf { it.size }
        val maxWidths = IntArray(maxColumns) { 0 }

        splitRows.forEach { cols ->
            cols.forEachIndexed { index, col ->
                val len = col.trim().length
                if (len > maxWidths[index]) {
                    maxWidths[index] = len
                }
            }
        }

        // Rebuild lines with padded columns (no pipe characters)
        val alignedLines = mutableListOf<String>()
        lines.forEach { line ->
            if (!line.contains(" | ")) {
                alignedLines.add(line)
                return@forEach
            }

            val cols = line.split(" | ")
            val sb = StringBuilder()
            cols.forEachIndexed { index, rawCol ->
                val col = rawCol.trim()
                val targetWidth = maxWidths.getOrNull(index) ?: col.length

                // Left‑align all columns except the last, which we right‑align
                val padded = if (index == cols.lastIndex) {
                    col.padStart(targetWidth)
                } else {
                    col.padEnd(targetWidth)
                }

                sb.append(padded)
                if (index != cols.lastIndex) {
                    sb.append(' ') // single space between columns
                }
            }
            alignedLines.add(sb.toString())
        }

        return alignedLines.joinToString("\n")
    }

    /**
     * Final formatting step before sending to the printer.
     * - Wrap lines according to paper width (in dots)
     * - Avoid letting the printer wrap arbitrarily in the middle of words
     *
     * This keeps receipts compact and readable on both 58mm and 80mm printers.
     */
    private fun formatForPrinter(text: String, paperWidthDots: Int): String {
        // Rough mapping from dots → characters per line for standard ESC/POS fonts
        val maxCharsPerLine = when {
            paperWidthDots >= 832 -> 64   // ~112mm
            paperWidthDots >= 576 -> 48   // ~80mm
            paperWidthDots >= 420 -> 32   // ~58mm
            else -> 32
        }

        if (maxCharsPerLine <= 0) return text

        val result = StringBuilder()

        text.lines().forEachIndexed { index, rawLine ->
            var line = rawLine

            // Simple word‑aware wrapping
            while (line.length > maxCharsPerLine) {
                // Try to break at last space before the limit
                val breakAtSpace = line.lastIndexOf(' ', maxCharsPerLine)

                val splitPos = when {
                    breakAtSpace in 1 until maxCharsPerLine -> breakAtSpace
                    else -> maxCharsPerLine // hard break
                }

                result.append(line.substring(0, splitPos).trimEnd())
                result.append('\n')

                // Skip the space we split on, if any
                line = line.substring(splitPos).trimStart()
            }

            result.append(line)
            if (index != text.lines().lastIndex) {
                result.append('\n')
            }
        }

        return result.toString()
    }
    
    /**
     * Get printer profile
     */
    private fun getPrinterProfile(preferType: String?): PrinterConfigManager.PrinterConfig? {
        if (!preferType.isNullOrBlank()) {
            val type = when (preferType.lowercase()) {
                "bt", "bluetooth" -> PrinterConfigManager.TYPE_BLUETOOTH
                "usb" -> PrinterConfigManager.TYPE_USB
                "lan", "wifi", "network" -> PrinterConfigManager.TYPE_LAN
                "epson" -> PrinterConfigManager.TYPE_EPSON
                "xprinter" -> PrinterConfigManager.TYPE_XPRINTER
                "vendor" -> PrinterConfigManager.TYPE_VENDOR
                "autoreplyprint" -> PrinterConfigManager.TYPE_AUTOREPLYPRINT
                else -> preferType.lowercase()
            }
            
            val profile = printerConfigManager.getDefaultProfile(type)
            if (profile != null && profile.enabled) {
                return profile
            }
        }
        
        val lastUsed = printerConfigManager.getLastUsedProfile()
        if (lastUsed != null && lastUsed.enabled) {
            return lastUsed
        }
        
        val allProfiles = printerConfigManager.getAllProfiles()
        val defaultProfile = allProfiles.firstOrNull { it.isDefault && it.enabled }
        if (defaultProfile != null) {
            return defaultProfile
        }
        
        return allProfiles.firstOrNull { it.enabled }
    }
    
    /**
     * Save error log to file for debugging
     */
    private fun saveErrorLog(
        errorType: String,
        message: String,
        exception: Exception? = null,
        htmlContent: String? = null,
        plainText: String? = null,
        profile: PrinterConfigManager.PrinterConfig? = null
    ) {
        try {
            val logDir = File(context.filesDir, LOG_DIR_NAME)
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val logFile = File(logDir, "error_${timestamp}.log")
            
            FileWriter(logFile).use { fileWriter ->
                PrintWriter(fileWriter).use { writer ->
                    writer.println("=".repeat(80))
                    writer.println("HTML Invoice Print Error Log (Text Mode)")
                    writer.println("=".repeat(80))
                    writer.println("Timestamp: $timestamp")
                    writer.println("Error Type: $errorType")
                    writer.println("Message: $message")
                    writer.println()
                    
                    if (exception != null) {
                        writer.println("Exception Details:")
                        writer.println("-".repeat(80))
                        writer.println("Class: ${exception.javaClass.name}")
                        writer.println("Message: ${exception.message}")
                        writer.println("Stack Trace:")
                        exception.printStackTrace(writer)
                        writer.println()
                    }
                    
                    if (profile != null) {
                        writer.println("Printer Profile:")
                        writer.println("-".repeat(80))
                        writer.println("Name: ${profile.name}")
                        writer.println("Type: ${profile.type}")
                        writer.println("Paper Width: ${profile.paperWidth} dots")
                        writer.println("Enabled: ${profile.enabled}")
                        writer.println()
                    }
                    
                    if (htmlContent != null) {
                        writer.println("HTML Content:")
                        writer.println("-".repeat(80))
                        writer.println("Length: ${htmlContent.length} characters")
                        writer.println("Preview (first 2000 chars):")
                        writer.println(htmlContent.take(2000))
                        writer.println()
                        
                        // Try to extract key HTML elements for debugging
                        try {
                            val hasHtmlTag = htmlContent.contains("<html", ignoreCase = true)
                            val hasBodyTag = htmlContent.contains("<body", ignoreCase = true)
                            val hasStyleTag = htmlContent.contains("<style", ignoreCase = true)
                            val hasScriptTag = htmlContent.contains("<script", ignoreCase = true)
                            
                            writer.println("HTML Structure:")
                            writer.println("  - Has <html> tag: $hasHtmlTag")
                            writer.println("  - Has <body> tag: $hasBodyTag")
                            writer.println("  - Has <style> tag: $hasStyleTag")
                            writer.println("  - Has <script> tag: $hasScriptTag")
                            writer.println()
                        } catch (e: Exception) {
                            writer.println("Error analyzing HTML structure: ${e.message}")
                            writer.println()
                        }
                    }
                    
                    if (plainText != null) {
                        writer.println("Converted Plain Text:")
                        writer.println("-".repeat(80))
                        writer.println("Length: ${plainText.length} characters")
                        writer.println("Preview (first 1000 chars):")
                        writer.println(plainText.take(1000))
                        writer.println()
                    }
                    
                    writer.println("=".repeat(80))
                    writer.println("End of Error Log")
                    writer.println("=".repeat(80))
                }
            }
            
            Log.d(TAG, "Error log saved to: ${logFile.absolutePath}")
            
            // Clean up old log files (keep only last MAX_LOG_FILES)
            cleanupOldLogFiles(logDir)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save error log to file: ${e.message}", e)
        }
    }
    
    /**
     * Clean up old log files, keeping only the most recent ones
     */
    private fun cleanupOldLogFiles(logDir: File) {
        try {
            val logFiles = logDir.listFiles { file ->
                file.isFile && file.name.startsWith("error_") && file.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() }
            
            logFiles?.let { files ->
                if (files.size > MAX_LOG_FILES) {
                    files.drop(MAX_LOG_FILES).forEach { file ->
                        try {
                            file.delete()
                            Log.d(TAG, "Deleted old log file: ${file.name}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete old log file: ${file.name}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up old log files: ${e.message}", e)
        }
    }
}
