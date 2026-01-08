package com.elintpos.wrapper.escpos

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import androidx.annotation.RequiresPermission
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.UUID

/**
 * BluetoothEscPosPrinter - Bluetooth ESC/POS Thermal Printer Driver
 * 
 * Purpose: Provides Bluetooth connectivity and ESC/POS command generation for thermal
 * receipt printers. Supports standard ESC/POS commands for text printing, formatting,
 * and paper cutting.
 * 
 * ESC/POS Protocol:
 * - Industry-standard command set for POS printers
 * - Supports text formatting, margins, scaling, line spacing
 * - Compatible with most thermal receipt printers (Epson, Star, Bixolon, etc.)
 * 
 * Features:
 * - Bluetooth SPP (Serial Port Profile) connection
 * - Automatic text wrapping based on paper width
 * - Font scaling (width and height multipliers 0-7)
 * - Configurable margins and line spacing
 * - Automatic paper cutting
 * - Multi-page printing with page breaks
 * 
 * Supported Paper Widths:
 * - 58mm (384 dots)
 * - 80mm (576 dots)
 * - 112mm (832 dots)
 * 
 * @param context Android context for accessing Bluetooth services
 */
class BluetoothEscPosPrinter(private val context: Context) {
	/** Bluetooth adapter for device Bluetooth operations */
	private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
	
	/** Active Bluetooth socket connection to printer */
	private var socket: BluetoothSocket? = null
	
	/** Output stream for sending data to printer */
	private var output: OutputStream? = null

	/**
	 * Checks if Bluetooth is available on this device
	 * @return true if Bluetooth adapter exists, false otherwise
	 */
	fun isBluetoothAvailable(): Boolean = adapter != null

	/**
	 * Gets list of paired Bluetooth devices that could be printers
	 * 
	 * Note: Returns all paired devices with a Bluetooth class.
	 * Caller should filter by device name or let user select.
	 * 
	 * @return List of paired Bluetooth devices
	 */
	fun getPairedPrinters(): List<BluetoothDevice> =
		adapter?.bondedDevices?.filter { it.bluetoothClass != null }?.toList() ?: emptyList()

	/**
	 * Connects to a Bluetooth printer device
	 * 
	 * Connection Process:
	 * 1. Closes any existing connection
	 * 2. Creates RFCOMM socket using SPP UUID
	 * 3. Cancels device discovery (improves connection reliability)
	 * 4. Establishes connection
	 * 5. Gets output stream for sending data
	 * 
	 * SPP UUID: 00001101-0000-1000-8000-00805F9B34FB (Serial Port Profile)
	 * This is the standard UUID for Bluetooth serial communication
	 * 
	 * @param device The paired Bluetooth device to connect to
	 * @throws IOException if connection fails
	 */
	@RequiresPermission(allOf = [Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT])
	@Throws(IOException::class)
	fun connect(device: BluetoothDevice) {
		// Close any existing connection first
		close()
		
		// SPP (Serial Port Profile) UUID - standard for Bluetooth serial communication
		val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
		
		// Create RFCOMM socket
		socket = device.createRfcommSocketToServiceRecord(spp)
		
		// Cancel discovery to improve connection reliability
		adapter?.cancelDiscovery()
		
		// Connect to the device (blocking call)
		socket?.connect()
		
		// Get output stream for sending print data
		output = socket?.outputStream
	}

	/**
	 * Prints text to the Bluetooth printer using ESC/POS commands
	 * 
	 * ESC/POS Commands Used:
	 * - ESC @ (0x1B 0x40): Initialize printer
	 * - ESC 3 n (0x1B 0x33 n): Set line spacing to n dots
	 * - GS ! n (0x1D 0x21 n): Set character size (width and height multipliers)
	 * - GS L nL nH (0x1D 0x4C): Set left margin
	 * - LF (0x0A): Line feed
	 * - ESC d n (0x1B 0x64 n): Feed n lines
	 * - GS V B n (0x1D 0x56 0x42 n): Cut paper
	 * 
	 * @param text The text to print
	 * @param charset Character encoding (default: UTF-8)
	 * @param leftMarginDots Left margin in dots (0-255)
	 * @param rightMarginDots Right margin in dots (used for text wrapping calculation)
	 * @param lineSpacing Line spacing in dots (default: 30)
	 * @param widthMultiplier Width scale factor 0-7 (0=normal, 1=2x, 2=3x, etc.)
	 * @param heightMultiplier Height scale factor 0-7 (0=normal, 1=2x, 2=3x, etc.)
	 * @param pageWidthDots Total paper width in dots (e.g., 576 for 80mm)
	 * @param linesPerPage Lines per page (if set, cuts paper after this many lines)
	 * @throws IOException if not connected or write fails
	 */
	@Throws(IOException::class)
	fun printText(
		text: String,
		charset: Charset = Charset.forName("UTF-8"),
		leftMarginDots: Int = 0,
		rightMarginDots: Int = 0,
		lineSpacing: Int = 30,
		widthMultiplier: Int = 0,
		heightMultiplier: Int = 0,
		pageWidthDots: Int? = null,
		linesPerPage: Int? = null
	) {
		// Ensure we're connected
		val os = output ?: throw IOException("Not connected")

		// ESC @ - Initialize printer (reset to default state)
		os.write(byteArrayOf(0x1B, 0x40))
		
		// ESC 3 n - Set line spacing to n dots
		os.write(byteArrayOf(0x1B, 0x33, lineSpacing.toByte()))
		
		// GS ! n - Set character size (font scale)
		// n = (height << 4) | width, where width and height are 0-7
		// Example: n=0x00 (normal), n=0x11 (2x2), n=0x22 (3x3)
		val w = widthMultiplier.coerceIn(0, 7)
		val h = heightMultiplier.coerceIn(0, 7)
		val n = (h shl 4) or w
		os.write(byteArrayOf(0x1D, 0x21, n.toByte()))

		// GS L nL nH - Set left margin (16-bit value, little-endian)
		val left = leftMarginDots.coerceAtLeast(0).coerceAtMost(255)
		os.write(byteArrayOf(0x1D, 0x4C, (left and 0xFF).toByte(), ((left shr 8) and 0xFF).toByte()))

		// Calculate text wrapping parameters
		// Content width = total width - margins
		val contentWidthDots = pageWidthDots?.let { it - leftMarginDots - rightMarginDots }?.coerceAtLeast(48)
		
		// Base character width is ~12 dots for standard font
		val baseCharWidthDots = 12 // approx for 12x24 font width at scale 0
		
		// Actual character width depends on scale multiplier
		val charWidthDots = baseCharWidthDots * (w + 1)
		
		// Calculate maximum characters per line
		val maxCharsPerLine = contentWidthDots?.let { if (charWidthDots > 0) it / charWidthDots else null }

		/**
		 * Wraps text lines to fit within printer width
		 * 
		 * If maxCharsPerLine is set, splits long lines into multiple lines.
		 * Preserves existing line breaks.
		 * 
		 * @param src Source text with potential line breaks
		 * @return List of lines that fit within printer width
		 */
		fun wrapLines(src: String): List<String> {
			// If no wrapping needed, just split on line breaks
			if (maxCharsPerLine == null || maxCharsPerLine <= 0) return src.replace("\r\n", "\n").split("\n")
			
			val out = mutableListOf<String>()
			// Process each line
			src.replace("\r\n", "\n").split("\n").forEach { line ->
				var idx = 0
				// Split line into chunks of maxCharsPerLine
				while (idx < line.length) {
					val end = (idx + maxCharsPerLine).coerceAtMost(line.length)
					out.add(line.substring(idx, end))
					idx = end
				}
			}
			return out
		}

		// Wrap text to fit printer width
		val lines = wrapLines(text)
		var lineCounter = 0
		
		// Print each line
		for (line in lines) {
			// Send line text
			os.write(line.toByteArray(charset))
			// LF - Line feed
			os.write(byteArrayOf(0x0A))
			lineCounter++
			
			// If we've reached lines per page, cut paper and start new page
			if (linesPerPage != null && linesPerPage > 0 && lineCounter >= linesPerPage) {
				// ESC d 2 - Feed 2 lines
				os.write(byteArrayOf(0x1B, 0x64, 0x02))
				// GS V B 3 - Cut paper (partial cut)
				os.write(byteArrayOf(0x1D, 0x56, 0x42, 0x03))
				lineCounter = 0
				
				// Re-apply settings after cut (some printers reset)
				os.write(byteArrayOf(0x1B, 0x40)) // Initialize
				os.write(byteArrayOf(0x1B, 0x33, lineSpacing.toByte())) // Line spacing
				os.write(byteArrayOf(0x1D, 0x21, n.toByte())) // Font scale
				os.write(byteArrayOf(0x1D, 0x4C, (left and 0xFF).toByte(), ((left shr 8) and 0xFF).toByte())) // Left margin
			}
		}
		
		// Final feed and cut
		// ESC d 2 - Feed 2 lines before cutting
		os.write(byteArrayOf(0x1B, 0x64, 0x02))
		// GS V B 3 - Cut paper (partial cut)
		os.write(byteArrayOf(0x1D, 0x56, 0x42, 0x03))
		// Flush output stream to ensure all data is sent
		os.flush()
	}

	/**
	 * Print bitmap image using ESC/POS commands
	 * 
	 * @param bitmap The bitmap to print
	 * @param width Target width in dots (e.g., 384 for 58mm, 576 for 80mm)
	 */
	@Throws(IOException::class)
	fun printImage(bitmap: android.graphics.Bitmap, width: Int = 384) {
		val os = output ?: throw IOException("Not connected")
		
		// Scale bitmap to target width
		val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
		val height = (width * aspectRatio).toInt()
		val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
		
		// Convert to 1-bit bitmap
		val widthBytes = (width + 7) / 8
		val imageData = ByteArray(widthBytes * height)
		
		for (y in 0 until height) {
			for (x in 0 until width) {
				val pixel = scaledBitmap.getPixel(x, y)
				val gray = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3
				if (gray < 128) { // Black pixel
					val byteIndex = y * widthBytes + x / 8
					val bitIndex = 7 - (x % 8)
					imageData[byteIndex] = (imageData[byteIndex].toInt() or (1 shl bitIndex)).toByte()
				}
			}
		}
		
		// Print image using ESC * command
		os.write(byteArrayOf(0x1B, 0x40)) // Initialize
		os.write(byteArrayOf(0x1B, 0x33, 0x18)) // Set line spacing
		for (y in 0 until height) {
			os.write(byteArrayOf(0x1B, 0x2A, 0x00, widthBytes.toByte(), (widthBytes and 0xFF).toByte()))
			os.write(imageData, y * widthBytes, widthBytes)
			os.write(byteArrayOf(0x0A)) // Line feed
		}
		os.write(byteArrayOf(0x1B, 0x33, 0x30)) // Reset line spacing
		os.write(byteArrayOf(0x1B, 0x64, 0x02)) // Feed 2 lines
		os.write(byteArrayOf(0x1D, 0x56, 0x42, 0x03)) // Cut paper
		os.flush()
		
		scaledBitmap.recycle()
	}

	/**
	 * Closes the Bluetooth connection and releases resources
	 * 
	 * Safe to call multiple times. Silently handles any errors during cleanup.
	 */
	fun close() {
		// Flush any remaining data
		try { output?.flush() } catch (_: Exception) {}
		// Close output stream
		try { output?.close() } catch (_: Exception) {}
		// Close Bluetooth socket
		try { socket?.close() } catch (_: Exception) {}
		// Clear references
		output = null
		socket = null
	}
}
