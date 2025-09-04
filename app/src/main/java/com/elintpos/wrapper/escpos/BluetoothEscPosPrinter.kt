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

class BluetoothEscPosPrinter(private val context: Context) {
	private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
	private var socket: BluetoothSocket? = null
	private var output: OutputStream? = null

	fun isBluetoothAvailable(): Boolean = adapter != null

	fun getPairedPrinters(): List<BluetoothDevice> =
		adapter?.bondedDevices?.filter { it.bluetoothClass != null }?.toList() ?: emptyList()

	@RequiresPermission(allOf = [Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT])
	@Throws(IOException::class)
	fun connect(device: BluetoothDevice) {
		close()
		val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
		socket = device.createRfcommSocketToServiceRecord(spp)
		adapter?.cancelDiscovery()
		socket?.connect()
		output = socket?.outputStream
	}

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
		val os = output ?: throw IOException("Not connected")

		// ESC/POS init
		os.write(byteArrayOf(0x1B, 0x40))
		// Line spacing
		os.write(byteArrayOf(0x1B, 0x33, lineSpacing.toByte()))
		// Font scale via GS ! n with independent multipliers (0..7 usually, commonly 0..3)
		val w = widthMultiplier.coerceIn(0, 7)
		val h = heightMultiplier.coerceIn(0, 7)
		val n = (h shl 4) or w
		os.write(byteArrayOf(0x1D, 0x21, n.toByte()))

		val left = leftMarginDots.coerceAtLeast(0).coerceAtMost(255)
		os.write(byteArrayOf(0x1D, 0x4C, (left and 0xFF).toByte(), ((left shr 8) and 0xFF).toByte()))

		val contentWidthDots = pageWidthDots?.let { it - leftMarginDots - rightMarginDots }?.coerceAtLeast(48)
		val baseCharWidthDots = 12 // approx for 12x24 font width at scale 0
		val charWidthDots = baseCharWidthDots * (w + 1)
		val maxCharsPerLine = contentWidthDots?.let { if (charWidthDots > 0) it / charWidthDots else null }

		fun wrapLines(src: String): List<String> {
			if (maxCharsPerLine == null || maxCharsPerLine <= 0) return src.replace("\r\n", "\n").split("\n")
			val out = mutableListOf<String>()
			src.replace("\r\n", "\n").split("\n").forEach { line ->
				var idx = 0
				while (idx < line.length) {
					val end = (idx + maxCharsPerLine).coerceAtMost(line.length)
					out.add(line.substring(idx, end))
					idx = end
				}
			}
			return out
		}

		val lines = wrapLines(text)
		var lineCounter = 0
		for (line in lines) {
			os.write(line.toByteArray(charset))
			os.write(byteArrayOf(0x0A))
			lineCounter++
			if (linesPerPage != null && linesPerPage > 0 && lineCounter >= linesPerPage) {
				// Cut between pages
				os.write(byteArrayOf(0x1B, 0x64, 0x02))
				os.write(byteArrayOf(0x1D, 0x56, 0x42, 0x03))
				lineCounter = 0
				// Re-apply settings post cut (some printers reset scale)
				os.write(byteArrayOf(0x1B, 0x40))
				os.write(byteArrayOf(0x1B, 0x33, lineSpacing.toByte()))
				os.write(byteArrayOf(0x1D, 0x21, n.toByte()))
				os.write(byteArrayOf(0x1D, 0x4C, (left and 0xFF).toByte(), ((left shr 8) and 0xFF).toByte()))
			}
		}
		// Final feed and cut
		os.write(byteArrayOf(0x1B, 0x64, 0x02))
		os.write(byteArrayOf(0x1D, 0x56, 0x42, 0x03))
		os.flush()
	}

	fun close() {
		try { output?.flush() } catch (_: Exception) {}
		try { output?.close() } catch (_: Exception) {}
		try { socket?.close() } catch (_: Exception) {}
		output = null
		socket = null
	}
}
