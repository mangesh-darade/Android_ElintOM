package com.elintpos.wrapper.viewer

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream

class ExcelViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val file = File(path)
        if (!file.exists()) { finish(); return }

        val scroll = ScrollView(this)
        val table = TableLayout(this)
        scroll.addView(table)
        setContentView(scroll)

        try {
            FileInputStream(file).use { fis ->
                val wb = XSSFWorkbook(fis)
                val sheet = wb.getSheetAt(0)
                for (row in sheet) {
                    val tr = TableRow(this)
                    for (cell in row) {
                        val tv = TextView(this)
                        val text = try {
                            // POI 4/5 style (enum)
                            when (cell.cellTypeEnum) {
                                CellType.STRING -> cell.stringCellValue
                                CellType.NUMERIC -> cell.numericCellValue.toString()
                                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                                else -> ""
                            }
                        } catch (_: Throwable) {
                            // Legacy POI (int)
                            @Suppress("DEPRECATION")
                            when (cell.cellType) {
                                Cell.CELL_TYPE_STRING -> cell.stringCellValue
                                Cell.CELL_TYPE_NUMERIC -> cell.numericCellValue.toString()
                                Cell.CELL_TYPE_BOOLEAN -> cell.booleanCellValue.toString()
                                else -> ""
                            }
                        }
                        tv.text = text
                        tv.setPadding(16, 8, 16, 8)
                        tr.addView(tv)
                    }
                    table.addView(tr)
                }
                wb.close()
            }
        } catch (_: Exception) {
            finish()
            return
        }
    }
}


