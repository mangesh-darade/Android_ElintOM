package com.elintpos.wrapper.escpos

import android.content.Context
import android.util.Log
import com.elintpos.wrapper.UnifiedPrinterHandler
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class ReceiptFormatter(private val context: Context? = null) {
	
	companion object {
		private const val TAG = "ReceiptFormatter"
		
		const val PAPER_WIDTH_58MM = 384
		const val PAPER_WIDTH_80MM = 576
		
		// Barcode types
		const val BARCODE_CODE128 = 73
		const val BARCODE_CODE39 = 69
		const val BARCODE_EAN13 = 67
		const val BARCODE_EAN8 = 68
		const val BARCODE_UPC_A = 65
		const val BARCODE_UPC_E = 66
		
		// QR Code error correction levels
		const val QR_ERROR_L = 0
		const val QR_ERROR_M = 1
		const val QR_ERROR_Q = 2
		const val QR_ERROR_H = 3
	}
	
	data class ReceiptConfig(
		val paperWidth: Int = PAPER_WIDTH_80MM,
		val leftMargin: Int = 0,
		val rightMargin: Int = 0,
		val lineSpacing: Int = 30,
		val widthMultiplier: Int = 0,
		val heightMultiplier: Int = 0,
		val includeBarcode: Boolean = false,
		val includeQR: Boolean = false,
		val compactLayout: Boolean = false
	)
	
	// Get currency symbol from saleData; fallback to "Rs."
	private fun getCurrency(saleData: JSONObject): String {
		// Try common keys used by backend / PHP
		val fromSymbol = saleData.optString("currency_symbol", "")
		if (fromSymbol.isNotBlank()) return fromSymbol
		
		val fromCode = saleData.optString("currency", "")
		if (fromCode.isNotBlank()) return fromCode
		
		val fromCode2 = saleData.optString("currency_code", "")
		if (fromCode2.isNotBlank()) return fromCode2
		
		return "Rs."
	}
	
	// Text-based formatting methods removed - using bitmap printing only
	// All printing now goes through formatAndPrintInvoiceAsBitmap()
	
	/**
	 * Format and print invoice as bitmap using WebView rendering
	 * This method converts JSON invoice data to HTML and prints as bitmap
	 * 
	 * Flow: JSON Data → HTML → WebView → Bitmap → Printer SDK
	 * 
	 * @param saleData JSON invoice data
	 * @param config Receipt configuration
	 * @param preferType Preferred printer type (optional)
	 * @return PrintResult with success status and message
	 */
	fun formatAndPrintInvoiceAsBitmap(
		saleData: JSONObject,
		config: ReceiptConfig = ReceiptConfig(),
		preferType: String? = null
	): UnifiedPrinterHandler.PrintResult {
		return try {
			if (context == null) {
				return UnifiedPrinterHandler.PrintResult(
					success = false,
					message = "Context required for bitmap printing"
				)
			}
			
			// Convert JSON to HTML
			val htmlContent = convertInvoiceToHtml(saleData, config)
			
			// Use HtmlInvoicePrinter for bitmap printing
			val htmlInvoicePrinter = com.elintpos.wrapper.printer.HtmlInvoicePrinter(context)
			htmlInvoicePrinter.printHtmlInvoice(htmlContent, preferType)
			
		} catch (e: Exception) {
			Log.e(TAG, "Error formatting and printing invoice as bitmap", e)
			UnifiedPrinterHandler.PrintResult(
				success = false,
				message = "Bitmap print error: ${e.message}"
			)
		}
	}
	
	/**
	 * Convert JSON invoice data to HTML for bitmap rendering
	 */
	private fun convertInvoiceToHtml(saleData: JSONObject, config: ReceiptConfig): String {
		val paperWidth = config.paperWidth
		val currency = getCurrency(saleData)
		val renderWidth = when (paperWidth) {
			PAPER_WIDTH_58MM -> 464  // 58mm ≈ 464px at 200 DPI
			PAPER_WIDTH_80MM -> 640  // 80mm ≈ 640px at 200 DPI
			else -> 640
		}
		
		val sb = StringBuilder()
		sb.append("<!DOCTYPE html>\n")
		sb.append("<html>\n")
		sb.append("<head>\n")
		sb.append("<meta charset=\"UTF-8\">\n")
		sb.append("<meta name=\"viewport\" content=\"width=$renderWidth, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n")
		sb.append("<style>\n")
		sb.append("* { box-sizing: border-box; margin: 0; padding: 0; }\n")
		sb.append("html, body { width: 100%; overflow-x: hidden; }\n")
		sb.append("body { margin: 0; padding: 10px; font-family: Arial, sans-serif; background: white; font-size: 12px; line-height: 1.4; }\n")
		sb.append("table { border-collapse: collapse; width: 100%; margin: 5px 0; table-layout: fixed; }\n")
		sb.append("th, td { padding: 4px 2px; text-align: left; border: 1px solid #000; font-size: 11px; word-wrap: break-word; overflow-wrap: break-word; }\n")
		sb.append("th { background-color: #f2f2f2; font-weight: bold; }\n")
		sb.append(".text-center { text-align: center; }\n")
		sb.append(".text-right { text-align: right; }\n")
		sb.append(".text-left { text-align: left; }\n")
		sb.append("h1, h2, h3, h4, h5, h6 { margin: 5px 0; font-size: 14px; }\n")
		sb.append("p { margin: 3px 0; }\n")
		sb.append("hr { border: none; border-top: 1px solid #000; margin: 5px 0; }\n")
		sb.append("</style>\n")
		sb.append("</head>\n")
		sb.append("<body>\n")
		
		// Header
		sb.append("<div class=\"text-center\">\n")
		sb.append("<h1>TAX INVOICE</h1>\n")
		sb.append("</div>\n")
		sb.append("<hr>\n")
		
		// Store info
		val storeName = saleData.optString("store_name", "Store")
		val storeAddress = saleData.optString("store_address", "")
		val storePhone = saleData.optString("store_phone", "")
		
		sb.append("<div class=\"text-center\">\n")
		sb.append("<h2>$storeName</h2>\n")
		if (storeAddress.isNotEmpty()) {
			sb.append("<p>$storeAddress</p>\n")
		}
		if (storePhone.isNotEmpty()) {
			sb.append("<p>$storePhone</p>\n")
		}
		sb.append("</div>\n")
		sb.append("<hr>\n")
		
		// Invoice details
		val invoiceNumber = saleData.optString("invoice_number", "")
		val saleDate = saleData.optString("sale_date", "")
		val cashierName = saleData.optString("cashier_name", "")
		
		if (invoiceNumber.isNotEmpty()) {
			sb.append("<p><strong>Invoice No.:</strong> $invoiceNumber</p>\n")
		}
		if (saleDate.isNotEmpty()) {
			sb.append("<p><strong>Date:</strong> $saleDate</p>\n")
		}
		if (cashierName.isNotEmpty()) {
			sb.append("<p><strong>Cashier:</strong> $cashierName</p>\n")
		}
		sb.append("<hr>\n")
		
		// Items table - build columns dynamically from item JSON keys
		val items = saleData.optJSONArray("items") ?: JSONArray()
		var totalAmount = 0.0
		
		// Determine columns from the first item object (dynamic, no hard-coded headers)
		val columns = mutableListOf<String>()
		if (items.length() > 0) {
			val firstItem = items.getJSONObject(0)
			val keys = firstItem.keys()
			while (keys.hasNext()) {
				val key = keys.next()
				columns.add(key)
			}
		}
		
		sb.append("<table>\n")
		sb.append("<thead>\n")
		sb.append("<tr>\n")
		columns.forEach { key ->
			// Convert key like "unit_price" -> "Unit Price"
			val label = key.replace('_', ' ')
				.lowercase(Locale.getDefault())
				.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
			sb.append("<th class=\"text-left\">$label</th>\n")
		}
		sb.append("</tr>\n")
		sb.append("</thead>\n")
		sb.append("<tbody>\n")
		
		for (i in 0 until items.length()) {
			val item = items.getJSONObject(i)
			
			// Preserve existing total calculation logic (quantity * price) if present
			val quantity = item.optDouble("quantity", 0.0)
			val price = item.optDouble("price", 0.0)
			val rowTotal = quantity * price
			if (quantity != 0.0 || price != 0.0) {
				totalAmount += rowTotal
			}
			
			sb.append("<tr>\n")
			columns.forEach { key ->
				val value = item.opt(key)
				val text = when (value) {
					is Number -> String.format("%.2f", value.toDouble())
					else -> value?.toString() ?: ""
				}
				sb.append("<td class=\"text-left\">$text</td>\n")
			}
			sb.append("</tr>\n")
		}
		
		sb.append("</tbody>\n")
		sb.append("</table>\n")
		sb.append("<hr>\n")
		
		// Totals
		val subtotal = saleData.optDouble("subtotal", totalAmount)
		val taxAmount = saleData.optDouble("tax_amount", 0.0)
		val discountAmount = saleData.optDouble("discount_amount", 0.0)
		val rounding = saleData.optDouble("rounding", 0.0)
		val finalTotal = saleData.optDouble("total", totalAmount)
		
		sb.append("<table>\n")
		sb.append("<tr><td class=\"text-right\"><strong>Total</strong></td><td class=\"text-right\">$currency${String.format("%.2f", subtotal)}</td></tr>\n")
		if (rounding != 0.0) {
			sb.append("<tr><td class=\"text-right\">Rounding</td><td class=\"text-right\">$currency${String.format("%.2f", rounding)}</td></tr>\n")
		}
		if (taxAmount > 0) {
			sb.append("<tr><td class=\"text-right\">Tax</td><td class=\"text-right\">$currency${String.format("%.2f", taxAmount)}</td></tr>\n")
		}
		if (discountAmount > 0) {
			sb.append("<tr><td class=\"text-right\">Discount</td><td class=\"text-right\">-$currency${String.format("%.2f", discountAmount)}</td></tr>\n")
		}
		sb.append("<tr><td class=\"text-right\"><strong>GRAND TOTAL</strong></td><td class=\"text-right\"><strong>$currency${String.format("%.2f", finalTotal)}</strong></td></tr>\n")
		sb.append("</table>\n")
		sb.append("<hr>\n")
		
		// Payment info
		val paymentMethod = saleData.optString("payment_method", "")
		val amountPaid = saleData.optDouble("amount_paid", finalTotal)
		val change = amountPaid - finalTotal
		
		if (paymentMethod.isNotEmpty()) {
			sb.append("<p><strong>Paid by:</strong> $paymentMethod</p>\n")
			sb.append("<p><strong>Paid Amount:</strong> $currency${String.format("%.2f", amountPaid)}</p>\n")
			sb.append("<p><strong>Balance:</strong> $currency${String.format("%.2f", if (change > 0) change else 0.0)}</p>\n")
		}
		
		sb.append("<p class=\"text-center\">Thank you for your business!</p>\n")
		sb.append("</body>\n")
		sb.append("</html>")
		
		return sb.toString()
	}
}
