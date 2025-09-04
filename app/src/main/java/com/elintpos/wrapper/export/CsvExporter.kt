package com.elintpos.wrapper.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvExporter(private val context: Context) {
    private fun getDownloadsDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun saveCsv(contents: String, fileName: String? = null): String {
        return try {
            val safeName = fileName?.takeIf { it.endsWith(".csv", true) } ?: defaultFileName()
            val outFile = File(getDownloadsDir(), safeName)
            FileOutputStream(outFile).use { it.write(contents.toByteArray(Charset.forName("UTF-8"))) }
            jsonOk(outFile)
        } catch (e: Exception) {
            jsonErr(e)
        }
    }

    fun jsonArrayToCsv(jsonArray: JSONArray, fileName: String? = null): String {
        return try {
            if (jsonArray.length() == 0) return jsonErrMsg("Empty data")

            val headers = collectHeaders(jsonArray)
            val sb = StringBuilder()
            // header row
            sb.append(headers.joinToString(",") { escapeCsv(it) }).append('\n')
            // data rows
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val row = headers.map { key -> escapeCsv(valueToString(obj.opt(key))) }
                sb.append(row.joinToString(",")).append('\n')
            }
            saveCsv(sb.toString(), fileName)
        } catch (e: Exception) {
            jsonErr(e)
        }
    }

    fun jsonObjectArrayFieldToCsv(container: JSONObject, arrayField: String, fileName: String? = null): String {
        val arr = container.optJSONArray(arrayField) ?: JSONArray()
        return jsonArrayToCsv(arr, fileName)
    }

    fun openCsv(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/csv")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "No app to open CSV", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun shareCsv(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share CSV")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun listCsv(prefix: String = "ElintPOS_"): JSONArray {
        val result = JSONArray()
        try {
            getDownloadsDir().listFiles()?.filter { it.isFile && it.name.endsWith(".csv", true) && it.name.startsWith(prefix) }?.forEach { f ->
                val o = JSONObject()
                o.put("name", f.name)
                o.put("path", f.absolutePath)
                o.put("size", f.length())
                o.put("lastModified", f.lastModified())
                result.put(o)
            }
        } catch (_: Exception) {}
        return result
    }

    fun deleteCsv(filePath: String): Boolean = try { File(filePath).delete() } catch (_: Exception) { false }

    private fun defaultFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "ElintPOS_Export_${'$'}ts.csv"
    }

    private fun collectHeaders(arr: JSONArray): List<String> {
        val set = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            obj.keys().forEachRemaining { set.add(it) }
        }
        return set.toList()
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        var v = value.replace("\"", "\"\"")
        if (needsQuotes) v = "\"${'$'}v\""
        return v
    }

    private fun valueToString(any: Any?): String = when (any) {
        null -> ""
        is JSONObject -> any.toString()
        is JSONArray -> any.toString()
        else -> any.toString()
    }

    private fun jsonOk(file: File): String {
        val o = JSONObject()
        o.put("ok", true)
        o.put("filePath", file.absolutePath)
        o.put("fileName", file.name)
        return o.toString()
    }

    private fun jsonErr(e: Exception): String = jsonErrMsg(e.message ?: "Error")

    private fun jsonErrMsg(msg: String): String {
        val o = JSONObject()
        o.put("ok", false)
        o.put("msg", msg)
        return o.toString()
    }
}


