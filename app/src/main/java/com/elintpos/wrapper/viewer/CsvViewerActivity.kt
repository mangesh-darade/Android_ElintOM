package com.elintpos.wrapper.viewer

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File

class CsvViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val file = File(path)
        if (!file.exists()) { finish(); return }

        val scroll = ScrollView(this)
        val table = TableLayout(this)
        scroll.addView(table)
        setContentView(scroll)

        val lines = file.readLines()
        lines.forEach { line ->
            val row = TableRow(this)
            parseCsvLine(line).forEach { cell ->
                val tv = TextView(this)
                tv.text = cell
                tv.setPadding(16, 8, 16, 8)
                row.addView(tv)
            }
            table.addView(row)
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var inQuotes = false
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when (c) {
                '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ',' -> {
                    if (inQuotes) sb.append(',') else { result.add(sb.toString()); sb.setLength(0) }
                }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }
}


