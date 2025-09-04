package com.elintpos.wrapper.escpos

import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class ReceiptFormatter {
	
	companion object {
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
	
	fun formatInvoice(saleData: JSONObject, config: ReceiptConfig = ReceiptConfig()): String {
		val sb = StringBuilder()
		val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
		
		// Header
		sb.appendLine(centerText("INVOICE", config.paperWidth))
		sb.appendLine(centerText("=" * 32, config.paperWidth))
		sb.appendLine()
		
		// Store info
		val storeName = saleData.optString("store_name", "Store")
		val storeAddress = saleData.optString("store_address", "")
		val storePhone = saleData.optString("store_phone", "")
		
		sb.appendLine(centerText(storeName, config.paperWidth))
		if (storeAddress.isNotEmpty()) {
			sb.appendLine(centerText(storeAddress, config.paperWidth))
		}
		if (storePhone.isNotEmpty()) {
			sb.appendLine(centerText(storePhone, config.paperWidth))
		}
		sb.appendLine()
		
		// Invoice details
		val invoiceNumber = saleData.optString("invoice_number", "")
		val saleDate = saleData.optString("sale_date", "")
		val cashierName = saleData.optString("cashier_name", "")
		
		if (invoiceNumber.isNotEmpty()) {
			sb.appendLine("Invoice: $invoiceNumber")
		}
		if (saleDate.isNotEmpty()) {
			sb.appendLine("Date: $saleDate")
		}
		if (cashierName.isNotEmpty()) {
			sb.appendLine("Cashier: $cashierName")
		}
		sb.appendLine("-" * 32)
		
		// Items
		val items = saleData.optJSONArray("items") ?: JSONArray()
		var totalAmount = 0.0
		
		for (i in 0 until items.length()) {
			val item = items.getJSONObject(i)
			val name = item.optString("name", "")
			val quantity = item.optDouble("quantity", 0.0)
			val price = item.optDouble("price", 0.0)
			val total = quantity * price
			totalAmount += total
			
			if (config.compactLayout) {
				sb.appendLine("$name")
				sb.appendLine("${quantity}x $price = $total")
			} else {
				sb.appendLine("$name")
				sb.appendLine("  Qty: $quantity x $price = $total")
			}
			sb.appendLine()
		}
		
		sb.appendLine("-" * 32)
		
		// Totals
		val subtotal = saleData.optDouble("subtotal", totalAmount)
		val taxAmount = saleData.optDouble("tax_amount", 0.0)
		val discountAmount = saleData.optDouble("discount_amount", 0.0)
		val finalTotal = saleData.optDouble("total", totalAmount)
		
		sb.appendLine("Subtotal: $subtotal")
		if (taxAmount > 0) {
			sb.appendLine("Tax: $taxAmount")
		}
		if (discountAmount > 0) {
			sb.appendLine("Discount: -$discountAmount")
		}
		sb.appendLine("TOTAL: $finalTotal")
		sb.appendLine()
		
		// Payment info
		val paymentMethod = saleData.optString("payment_method", "")
		val amountPaid = saleData.optDouble("amount_paid", finalTotal)
		val change = amountPaid - finalTotal
		
		if (paymentMethod.isNotEmpty()) {
			sb.appendLine("Payment: $paymentMethod")
		}
		sb.appendLine("Paid: $amountPaid")
		if (change > 0) {
			sb.appendLine("Change: $change")
		}
		sb.appendLine()
		
		// Footer
		sb.appendLine(centerText("Thank you for your business!", config.paperWidth))
		sb.appendLine()
		
		// Barcode/QR Code
		if (config.includeBarcode && invoiceNumber.isNotEmpty()) {
			sb.appendLine(centerText("Invoice Barcode:", config.paperWidth))
			// Barcode will be printed separately using printBarcode()
		}
		
		if (config.includeQR && invoiceNumber.isNotEmpty()) {
			sb.appendLine(centerText("QR Code:", config.paperWidth))
			// QR Code will be printed separately using printQRCode()
		}
		
		return sb.toString()
	}
	
	fun formatReceipt(saleData: JSONObject, config: ReceiptConfig = ReceiptConfig()): String {
		val sb = StringBuilder()
		
		// Header
		sb.appendLine(centerText("RECEIPT", config.paperWidth))
		sb.appendLine(centerText("=" * 32, config.paperWidth))
		sb.appendLine()
		
		// Items (simplified for receipt)
		val items = saleData.optJSONArray("items") ?: JSONArray()
		var totalAmount = 0.0
		
		for (i in 0 until items.length()) {
			val item = items.getJSONObject(i)
			val name = item.optString("name", "")
			val quantity = item.optDouble("quantity", 0.0)
			val price = item.optDouble("price", 0.0)
			val total = quantity * price
			totalAmount += total
			
			sb.appendLine("$name")
			sb.appendLine("  $quantity x $price = $total")
		}
		
		sb.appendLine("-" * 32)
		sb.appendLine("TOTAL: $totalAmount")
		sb.appendLine()
		
		// Payment
		val paymentMethod = saleData.optString("payment_method", "Cash")
		sb.appendLine("Payment: $paymentMethod")
		sb.appendLine()
		
		// Footer
		sb.appendLine(centerText("Thank you!", config.paperWidth))
		
		return sb.toString()
	}
	
	fun formatKitchenOrder(orderData: JSONObject, config: ReceiptConfig = ReceiptConfig()): String {
		val sb = StringBuilder()
		
		// Header
		sb.appendLine(centerText("KITCHEN ORDER", config.paperWidth))
		sb.appendLine(centerText("=" * 32, config.paperWidth))
		sb.appendLine()
		
		// Order details
		val orderNumber = orderData.optString("order_number", "")
		val tableNumber = orderData.optString("table_number", "")
		val orderTime = orderData.optString("order_time", "")
		
		if (orderNumber.isNotEmpty()) {
			sb.appendLine("Order #: $orderNumber")
		}
		if (tableNumber.isNotEmpty()) {
			sb.appendLine("Table: $tableNumber")
		}
		if (orderTime.isNotEmpty()) {
			sb.appendLine("Time: $orderTime")
		}
		sb.appendLine("-" * 32)
		
		// Items
		val items = orderData.optJSONArray("items") ?: JSONArray()
		
		for (i in 0 until items.length()) {
			val item = items.getJSONObject(i)
			val name = item.optString("name", "")
			val quantity = item.optDouble("quantity", 0.0)
			val notes = item.optString("notes", "")
			
			sb.appendLine("$name")
			sb.appendLine("  Qty: $quantity")
			if (notes.isNotEmpty()) {
				sb.appendLine("  Notes: $notes")
			}
			sb.appendLine()
		}
		
		// Special instructions
		val specialInstructions = orderData.optString("special_instructions", "")
		if (specialInstructions.isNotEmpty()) {
			sb.appendLine("Special Instructions:")
			sb.appendLine(specialInstructions)
		}
		
		return sb.toString()
	}
	
	private fun centerText(text: String, paperWidth: Int): String {
		val maxChars = paperWidth / 12 // Approximate characters per line
		if (text.length >= maxChars) return text
		
		val padding = (maxChars - text.length) / 2
		return " ".repeat(padding) + text
	}
	
	private operator fun String.times(n: Int): String = this.repeat(n)
}
