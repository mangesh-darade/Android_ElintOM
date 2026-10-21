package com.elintpos.wrapper.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class K8PrintRulesTest {

    @Test
    fun cashTenderOpensTheDrawer() {
        assertTrue(CashSale.shouldOpenDrawer("Paid by: Cash"))
        assertTrue(CashSale.shouldOpenDrawer("पेमेंट: रोख"))
        assertTrue(CashSale.shouldOpenDrawer("Paid by: नकद"))
        assertTrue(CashSale.shouldOpenDrawer("Paid by: Cash and Card"))
    }

    @Test
    fun cashierAndCardDoNotOpenTheDrawer() {
        assertFalse(CashSale.shouldOpenDrawer("Cashier: Amit"))
        assertFalse(CashSale.shouldOpenDrawer("<p><strong>Cashier:</strong> Amit</p>"))
        assertFalse(CashSale.shouldOpenDrawer("Paid by: Card"))
        assertFalse(CashSale.shouldOpenDrawer("Paid by: UPI"))
        assertFalse(CashSale.shouldOpenDrawer("TEST PRINT"))
        assertFalse(CashSale.shouldOpenDrawer(""))
    }

    @Test
    fun billHeightUsesTheLargestRealMeasurement() {
        assertEquals(2400, K8BillLayout.heightPx(800, 1200, 2f))
        assertEquals(900, K8BillLayout.heightPx(900, 0, 2.75f))
        assertEquals(K8BillLayout.MAX_HEIGHT_PX, K8BillLayout.heightPx(10, 90000, 2f))
        assertEquals(0, K8BillLayout.heightPx(0, 0, 0f))
    }

    @Test
    fun documentHeightFollowsTheBillNotAZeroViewport() {
        assertEquals(800, K8BillLayout.documentHeight(800, 0, 1f))
        assertEquals(400, K8BillLayout.documentHeight(800, 0, 0.5f))
        assertEquals(900, K8BillLayout.documentHeight(100, 900, 1f))
        assertEquals(0, K8BillLayout.documentHeight(0, 0, 1f))
        assertEquals(10, K8BillLayout.documentHeight(10, 0, 0f))
    }

    @Test
    fun zeroHeightIsNeverCaptured() {
        assertFalse(K8BillLayout.readyToCapture(0, 5, 40, false))
        assertTrue(K8BillLayout.readyToCapture(400, 3, 4, false))
        assertFalse(K8BillLayout.readyToCapture(400, 3, 4, true))
        assertTrue(K8BillLayout.readyToCapture(400, 3, 18, true))
        assertTrue(K8BillLayout.readyToCapture(400, 0, 40, false))
    }

    @Test
    fun eightyMillimetreBillFillsTheRoll() {
        val narrow = "<style>#wrapper { max-width: 320px; margin: 0 auto; }</style>"
        val wide = "<style>#wrapper { max-width: 800px; }</style>"
        assertEquals(576, K8BillLayout.layoutWidthForPaper(narrow, 576))
        assertEquals(576, K8BillLayout.layoutWidthForPaper(wide, 576))
        assertEquals(384, K8BillLayout.layoutWidthForPaper(narrow, 384))
        assertEquals(34, K8BillLayout.bodyPx(12, 576))
        assertEquals(29, K8BillLayout.bodyPx(10, 576))
    }

    @Test
    fun heightMustStayPutBeforeCapture() {
        assertEquals(0, K8BillLayout.nextStableCount(-1, 0, 0))
        assertEquals(0, K8BillLayout.nextStableCount(100, 400, 2))
        assertEquals(0, K8BillLayout.nextStableCount(-1, 400, 0))
        assertEquals(1, K8BillLayout.nextStableCount(400, 400, 0))
        assertEquals(3, K8BillLayout.nextStableCount(400, 400, 2))
    }

    @Test
    fun serialOrderPrefersUsbThenKeepsConsoleLast() {
        val ports = K8SerialPorts.candidates(
            listOf("/dev/ttyS0", "/dev/ttyS1", "/dev/ttyUSB0", "/dev/ttyGS0", "/dev/ttyS1")
        )
        assertEquals(listOf("/dev/ttyUSB0", "/dev/ttyS1", "/dev/ttyS0"), ports)
    }

    @Test
    fun escapedInvoiceBecomesRealLines() {
        val raw = "\"Invoice No 80\\n================\\nItem Name\\tMRP\\tQTY\\nABS\\nSet |\\t123.0\\t1.00\\n\""
        val decoded = ReceiptText.decode(raw)
        assertTrue(decoded.startsWith("Invoice No 80\nItem Name"))
        assertFalse(decoded.contains("\\n"))
        assertFalse(decoded.contains("\\t"))
        assertFalse(decoded.contains("="))
        assertFalse(decoded.contains("|"))
        assertTrue(decoded.contains("Item Name MRP QTY"))
        assertTrue(decoded.contains("Set 123.0 1.00"))
    }

    @Test
    fun invoiceColumnsLineUpOn80mmWithoutRules() {
        val raw = "\"Invoice No 80\\n================\\nVISHAL UNIFORMS\\nGSTIN:\\n803APGPK3828M1ZR\\n" +
            "TAX INVOICE\\nDate : 16/10/2026 13.00\\nInvoice No.: 7866\\nCustomer: Walk in Customer\\n" +
            "Item Name\\tMRP\\tQTY\\tUnit Price\\tNet Unit Price\\tItem Discount\\tAmount\\n" +
            "ABS\\nSet |\\t123.0\\t1.00\\t123.0\\t123.0\\t0.0\\t123.0\\n" +
            "Total\\t123.0\\t1\\t123.0\\nRounding\\t0.0\\nGrand Total\\n" +
            "ONE HUNDRED TWENTY-THREE RUPEE ONLY\\n\\t123.0\\n" +
            "Paid Amount\\t123.0\\nPaid by:\\nCash\\nBalance:\\t0.0\\n" +
            "HSN\\tTax Rate\\tCGST (%)\\tSGST (%)\\tQty\\tTax Excl\\tTax Amt\\n" +
            "\\t0%\\t0 %\\t0 %\\t1\\t123.0\\t0.0\\n\""
        val rows = ReceiptLayout.build(raw)
        val itemTable = rows.filterIsInstance<ReceiptLayout.Row.Table>().first()
        val header = itemTable.rows.first()
        assertEquals(
            listOf("Item Name", "MRP", "QTY", "Unit Price", "Net Unit Price", "Item Discount", "Amount"),
            header
        )
        val item = itemTable.rows.first { it[0].contains("ABS") }
        assertEquals("ABS\nSet", item[0])
        assertEquals("123.0", item[1])
        assertEquals("1.00", item[2])
        assertEquals("123.0", item.last())
        val rounding = itemTable.rows.first { it[0] == "Rounding" }
        assertEquals("0.0", rounding[5])
        assertEquals("", rounding.last())
        val pair = rows.filterIsInstance<ReceiptLayout.Row.Pair>().first()
        assertTrue(pair.left.contains("7866"))
        assertTrue(pair.right.contains("16/10/2026"))
        val paid = rows.filterIsInstance<ReceiptLayout.Row.Triple>().first()
        assertEquals("Paid Amount 123.0", paid.left)
        assertEquals("Paid by: Cash", paid.mid)
        assertEquals("Balance: 0.0", paid.right)
        val dumped = rows.joinToString("\n")
        assertFalse(dumped.contains("|"))
        assertFalse(dumped.contains("===="))
        assertTrue(ReceiptLayout.hasTable(raw))
    }

    @Test
    fun phpAndJavaScriptSourceIsNotTheInvoice() {
        val raw = "<?php\nfunction buildBill() {\n  echo \"Invoice No 80\\nItem Name\\tMRP\\tQTY\\nABS\\t123.0\\t1.00\\nThank you\";\n}\n?>\n" +
            "<script>\nfunction printBill(){ document.write('x'); }\n</script>\n"
        val prepared = ReceiptText.prepare(raw)
        assertFalse(prepared.contains("<?php"))
        assertFalse(prepared.contains("function"))
        assertFalse(prepared.contains("document."))
        assertTrue(prepared.contains("Invoice No 80"))
        assertTrue(prepared.contains("Item Name"))
        assertTrue(prepared.contains("Thank you"))
    }

    @Test
    fun gluedInvoiceTextBecomesColumns() {
        val raw = "TAX INVOICE\nDate : 16/10/2026 15:25\nInvoice No.:a 7908\n" +
            "Item NameMRPQTYUnit PriceNet Unit PriceItem DiscountAmountABSset " +
            "123.01.00123.0123.00.0123.0Total123.01123.0Rounding0.0" +
            "Grand Total one hundred twenty-three Rupee Only 123.0" +
            "Paid Amount123.0Paid by:aCashBalance:a0.0\n" +
            "Thank you for shopping with us."
        val rows = ReceiptLayout.build(raw)
        val table = rows.filterIsInstance<ReceiptLayout.Row.Table>().first()
        assertEquals("Item Name", table.rows.first()[0])
        assertEquals("Amount", table.rows.first().last())
        val item = table.rows.first { it[0].contains("ABS") }
        assertEquals("123.0", item[1])
        assertEquals("1.00", item[2])
        assertEquals("123.0", item.last())
        assertTrue(rows.filterIsInstance<ReceiptLayout.Row.Pair>().any { it.right == "123.0" })
        val paid = rows.filterIsInstance<ReceiptLayout.Row.Triple>().first()
        assertTrue(paid.mid.contains("Cash"))
        assertFalse(rows.joinToString("\n").contains("Item NameMRP"))
    }

    @Test
    fun spacedSlipBecomesColumnsWithoutPageButtons() {
        val raw = """
            TAX INVOICE
            Date : 16/10/2026 16:14
            Invoice No.:a 7969
            Customer: Walk in Customer, Email:-a4, Punjab
            Item Name MRP QTY Unit Price Net Unit Price Item Discount Amount
            ABS
            set 1123.0 1.00 123.0 123.0 123.0 0.0 123.0
            Total 123.0 1123.0 123.0 0.0 123.0
            Rounding 0.0
            Grand Total ( one hundred twenty-three Rupee Only ) 123.0
            Paid Amount 123.0
            Paid by:aCash
            Paid Amount:a 123.0
            Balance:a 0.0
            Total Savings: 0.0
            Tax Summary
            HSN Tax Rate CGST (%) SGST (%) Qty Tax Excl Tax Amt
            1123.0 0.0 (This Sale:a 1)
            Total Award Points:a 120159
            Thank you for shopping with us.
            Please come again
            Web Print
            Email
            SMS
            Whatsapp
            Back to POS
            Please don't forget to disable the header and footer in browser print settings.
        """.trimIndent()
        val prepared = ReceiptText.prepare(raw)
        assertFalse(prepared.contains(":a"))
        assertFalse(prepared.contains("Web Print"))
        assertFalse(prepared.contains("don't forget"))
        assertFalse(prepared.contains("Back to POS"))
        val rows = ReceiptLayout.build(raw)
        val table = rows.filterIsInstance<ReceiptLayout.Row.Table>().first()
        assertEquals("Item Name", table.rows.first().first())
        assertEquals("Amount", table.rows.first().last())
        val item = table.rows.first { it[0].contains("ABS") }
        assertEquals("1.00", item[2])
        assertEquals("123.0", item.last())
        val grand = rows.filterIsInstance<ReceiptLayout.Row.Pair>().first { it.right == "123.0" }
        assertTrue(grand.left.contains("one hundred twenty-three"))
        val paid = rows.filterIsInstance<ReceiptLayout.Row.Triple>().first()
        assertTrue(paid.mid.contains("Cash"))
        assertFalse(rows.joinToString("\n").contains("Web Print"))
    }

    @Test
    fun rememberedSerialPortIsTriedFirst() {
        val ports = K8SerialPorts.candidates(
            listOf("/dev/ttyUSB0", "/dev/ttyS3"),
            remembered = "/dev/ttyS3"
        )
        assertEquals("/dev/ttyS3", ports.first())
    }
}
