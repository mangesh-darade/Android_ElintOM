/**
 * ElintPOS PDF Download Integration Guide
 * 
 * This file contains examples for implementing PDF download and export
 * functionality in your POS web application.
 */

// ============================================================================
// PDF DOWNLOAD FUNCTIONS
// ============================================================================

/**
 * Download current page as PDF
 */
function downloadCurrentPageAsPdf(fileName = null) {
    try {
        const result = JSON.parse(ElintPOSNative.downloadCurrentPageAsPdf(fileName));
        if (result.ok) {
            console.log('PDF downloaded successfully:', result.filePath);
            showToast('PDF downloaded: ' + result.fileName);
            return result;
        } else {
            console.error('PDF download failed:', result.msg);
            showToast('PDF download failed: ' + result.msg);
            return null;
        }
    } catch (e) {
        console.error('PDF download error:', e);
        showToast('PDF download error: ' + e.message);
        return null;
    }
}

/**
 * Download URL content as PDF
 */
function downloadUrlAsPdf(url, fileName = null) {
    try {
        const result = JSON.parse(ElintPOSNative.downloadUrlAsPdf(url, fileName));
        if (result.ok) {
            console.log('URL PDF download initiated:', result.fileName);
            showToast('PDF download initiated: ' + result.fileName);
            return result;
        } else {
            console.error('URL PDF download failed:', result.msg);
            showToast('URL PDF download failed: ' + result.msg);
            return null;
        }
    } catch (e) {
        console.error('URL PDF download error:', e);
        showToast('URL PDF download error: ' + e.message);
        return null;
    }
}

/**
 * Export HTML content as PDF
 */
function exportHtmlAsPdf(htmlContent, fileName = null) {
    try {
        const result = JSON.parse(ElintPOSNative.exportHtmlAsPdf(htmlContent, fileName));
        if (result.ok) {
            console.log('HTML PDF export successful:', result.filePath);
            showToast('PDF exported: ' + result.fileName);
            return result;
        } else {
            console.error('HTML PDF export failed:', result.msg);
            showToast('HTML PDF export failed: ' + result.msg);
            return null;
        }
    } catch (e) {
        console.error('HTML PDF export error:', e);
        showToast('HTML PDF export error: ' + e.message);
        return null;
    }
}

/**
 * Open PDF file with system default app
 */
function openPdfFile(filePath) {
    try {
        const result = JSON.parse(ElintPOSNative.openPdfFile(filePath));
        if (result.ok) {
            console.log('PDF opened successfully');
            return true;
        } else {
            console.error('Failed to open PDF');
            return false;
        }
    } catch (e) {
        console.error('PDF open error:', e);
        return false;
    }
}

/**
 * Share PDF file
 */
function sharePdfFile(filePath) {
    try {
        const result = JSON.parse(ElintPOSNative.sharePdfFile(filePath));
        if (result.ok) {
            console.log('PDF shared successfully');
            return true;
        } else {
            console.error('Failed to share PDF');
            return false;
        }
    } catch (e) {
        console.error('PDF share error:', e);
        return false;
    }
}

/**
 * Get list of downloaded PDF files
 */
function getDownloadedPdfs() {
    try {
        const result = JSON.parse(ElintPOSNative.getDownloadedPdfs());
        if (result.ok) {
            return result.files;
        } else {
            console.error('Failed to get PDF list:', result.msg);
            return [];
        }
    } catch (e) {
        console.error('Get PDF list error:', e);
        return [];
    }
}

/**
 * Delete PDF file
 */
function deletePdfFile(filePath) {
    try {
        const result = JSON.parse(ElintPOSNative.deletePdfFile(filePath));
        if (result.ok) {
            console.log('PDF deleted successfully');
            showToast('PDF file deleted');
            return true;
        } else {
            console.error('Failed to delete PDF');
            return false;
        }
    } catch (e) {
        console.error('PDF delete error:', e);
        return false;
    }
}

/**
 * Request storage permissions
 */
function requestStoragePermissions() {
    try {
        const result = JSON.parse(ElintPOSNative.requestStoragePermissions());
        if (result.ok) {
            console.log('Storage permission request initiated');
            showToast('Storage permission request sent');
            return true;
        } else {
            console.error('Failed to request storage permissions:', result.msg);
            return false;
        }
    } catch (e) {
        console.error('Storage permission request error:', e);
        return false;
    }
}

// ============================================================================
// EXAMPLE USAGE
// ============================================================================

/**
 * Example: Export invoice as PDF
 */
function exportInvoiceAsPdf(invoiceData) {
    const htmlContent = generateInvoiceHtml(invoiceData);
    const fileName = `Invoice_${invoiceData.invoiceNumber}_${new Date().toISOString().split('T')[0]}.pdf`;
    
    return exportHtmlAsPdf(htmlContent, fileName);
}

/**
 * Example: Export sales report as PDF
 */
function exportSalesReportAsPdf(reportData) {
    const htmlContent = generateSalesReportHtml(reportData);
    const fileName = `SalesReport_${new Date().toISOString().split('T')[0]}.pdf`;
    
    return exportHtmlAsPdf(htmlContent, fileName);
}

/**
 * Example: Export customer receipt as PDF
 */
function exportReceiptAsPdf(receiptData) {
    const htmlContent = generateReceiptHtml(receiptData);
    const fileName = `Receipt_${receiptData.receiptNumber}_${new Date().toISOString().split('T')[0]}.pdf`;
    
    return exportHtmlAsPdf(htmlContent, fileName);
}

/**
 * Example: Download current page as PDF
 */
function downloadCurrentPage() {
    const currentUrl = window.location.href;
    const fileName = `ElintPOS_${new Date().toISOString().split('T')[0]}.pdf`;
    
    return downloadCurrentPageAsPdf(fileName);
}

/**
 * Example: Export inventory report as PDF
 */
function exportInventoryReportAsPdf(inventoryData) {
    const htmlContent = generateInventoryReportHtml(inventoryData);
    const fileName = `InventoryReport_${new Date().toISOString().split('T')[0]}.pdf`;
    
    return exportHtmlAsPdf(htmlContent, fileName);
}

// ============================================================================
// HTML GENERATION HELPERS
// ============================================================================

/**
 * Generate invoice HTML content
 */
function generateInvoiceHtml(invoiceData) {
    return `
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>Invoice ${invoiceData.invoiceNumber}</title>
            <style>
                body { font-family: Arial, sans-serif; margin: 20px; }
                .header { text-align: center; border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 20px; }
                .invoice-details { margin-bottom: 20px; }
                .items-table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }
                .items-table th, .items-table td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                .items-table th { background-color: #f2f2f2; }
                .total-section { text-align: right; margin-top: 20px; }
                .footer { margin-top: 30px; text-align: center; font-size: 12px; color: #666; }
            </style>
        </head>
        <body>
            <div class="header">
                <h1>INVOICE</h1>
                <p>Invoice #: ${invoiceData.invoiceNumber}</p>
                <p>Date: ${invoiceData.date}</p>
            </div>
            
            <div class="invoice-details">
                <h3>Bill To:</h3>
                <p>${invoiceData.customerName}</p>
                <p>${invoiceData.customerAddress}</p>
                <p>${invoiceData.customerPhone}</p>
            </div>
            
            <table class="items-table">
                <thead>
                    <tr>
                        <th>Item</th>
                        <th>Quantity</th>
                        <th>Price</th>
                        <th>Total</th>
                    </tr>
                </thead>
                <tbody>
                    ${invoiceData.items.map(item => `
                        <tr>
                            <td>${item.name}</td>
                            <td>${item.quantity}</td>
                            <td>$${item.price}</td>
                            <td>$${item.total}</td>
                        </tr>
                    `).join('')}
                </tbody>
            </table>
            
            <div class="total-section">
                <p><strong>Subtotal: $${invoiceData.subtotal}</strong></p>
                <p><strong>Tax: $${invoiceData.tax}</strong></p>
                <p><strong>Total: $${invoiceData.total}</strong></p>
            </div>
            
            <div class="footer">
                <p>Thank you for your business!</p>
                <p>Generated by ElintPOS</p>
            </div>
        </body>
        </html>
    `;
}

/**
 * Generate sales report HTML content
 */
function generateSalesReportHtml(reportData) {
    return `
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>Sales Report</title>
            <style>
                body { font-family: Arial, sans-serif; margin: 20px; }
                .header { text-align: center; border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 20px; }
                .report-details { margin-bottom: 20px; }
                .summary-table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }
                .summary-table th, .summary-table td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                .summary-table th { background-color: #f2f2f2; }
                .footer { margin-top: 30px; text-align: center; font-size: 12px; color: #666; }
            </style>
        </head>
        <body>
            <div class="header">
                <h1>SALES REPORT</h1>
                <p>Period: ${reportData.startDate} to ${reportData.endDate}</p>
                <p>Generated: ${new Date().toLocaleString()}</p>
            </div>
            
            <div class="report-details">
                <h3>Summary</h3>
                <table class="summary-table">
                    <tr>
                        <th>Total Sales</th>
                        <td>$${reportData.totalSales}</td>
                    </tr>
                    <tr>
                        <th>Total Orders</th>
                        <td>${reportData.totalOrders}</td>
                    </tr>
                    <tr>
                        <th>Average Order Value</th>
                        <td>$${reportData.averageOrderValue}</td>
                    </tr>
                    <tr>
                        <th>Top Selling Item</th>
                        <td>${reportData.topSellingItem}</td>
                    </tr>
                </table>
            </div>
            
            <div class="footer">
                <p>Generated by ElintPOS</p>
            </div>
        </body>
        </html>
    `;
}

/**
 * Generate receipt HTML content
 */
function generateReceiptHtml(receiptData) {
    return `
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>Receipt ${receiptData.receiptNumber}</title>
            <style>
                body { font-family: Arial, sans-serif; margin: 20px; }
                .header { text-align: center; border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 20px; }
                .receipt-details { margin-bottom: 20px; }
                .items-list { margin-bottom: 20px; }
                .total-section { text-align: right; margin-top: 20px; }
                .footer { margin-top: 30px; text-align: center; font-size: 12px; color: #666; }
            </style>
        </head>
        <body>
            <div class="header">
                <h1>RECEIPT</h1>
                <p>Receipt #: ${receiptData.receiptNumber}</p>
                <p>Date: ${receiptData.date}</p>
            </div>
            
            <div class="receipt-details">
                <p><strong>Customer:</strong> ${receiptData.customerName}</p>
                <p><strong>Payment Method:</strong> ${receiptData.paymentMethod}</p>
            </div>
            
            <div class="items-list">
                <h3>Items Purchased:</h3>
                ${receiptData.items.map(item => `
                    <p>${item.name} - Qty: ${item.quantity} - $${item.total}</p>
                `).join('')}
            </div>
            
            <div class="total-section">
                <p><strong>Total: $${receiptData.total}</strong></p>
            </div>
            
            <div class="footer">
                <p>Thank you for your business!</p>
                <p>Generated by ElintPOS</p>
            </div>
        </body>
        </html>
    `;
}

/**
 * Generate inventory report HTML content
 */
function generateInventoryReportHtml(inventoryData) {
    return `
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>Inventory Report</title>
            <style>
                body { font-family: Arial, sans-serif; margin: 20px; }
                .header { text-align: center; border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 20px; }
                .inventory-table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }
                .inventory-table th, .inventory-table td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                .inventory-table th { background-color: #f2f2f2; }
                .footer { margin-top: 30px; text-align: center; font-size: 12px; color: #666; }
            </style>
        </head>
        <body>
            <div class="header">
                <h1>INVENTORY REPORT</h1>
                <p>Generated: ${new Date().toLocaleString()}</p>
            </div>
            
            <table class="inventory-table">
                <thead>
                    <tr>
                        <th>Item Name</th>
                        <th>SKU</th>
                        <th>Current Stock</th>
                        <th>Min Stock</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    ${inventoryData.items.map(item => `
                        <tr>
                            <td>${item.name}</td>
                            <td>${item.sku}</td>
                            <td>${item.currentStock}</td>
                            <td>${item.minStock}</td>
                            <td>${item.currentStock <= item.minStock ? 'LOW STOCK' : 'OK'}</td>
                        </tr>
                    `).join('')}
                </tbody>
            </table>
            
            <div class="footer">
                <p>Generated by ElintPOS</p>
            </div>
        </body>
        </html>
    `;
}

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

/**
 * Show toast message
 */
function showToast(message) {
    try {
        ElintPOSNative.showToast(message);
    } catch (e) {
        console.error('Toast error:', e);
    }
}

/**
 * Format currency
 */
function formatCurrency(amount) {
    return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: 'USD'
    }).format(amount);
}

/**
 * Format date
 */
function formatDate(date) {
    return new Date(date).toLocaleDateString();
}

// ============================================================================
// INITIALIZATION
// ============================================================================

/**
 * Initialize PDF download system
 */
function initializePdfDownload() {
    console.log('PDF download system initialized');
    
    // Request storage permissions if needed
    requestStoragePermissions();
    
    // Show available PDF files
    const pdfFiles = getDownloadedPdfs();
    if (pdfFiles.length > 0) {
        console.log('Available PDF files:', pdfFiles);
    }
}

// Auto-initialize when script loads
document.addEventListener('DOMContentLoaded', initializePdfDownload);

// Export functions for use in other scripts
window.ElintPOSPdfDownload = {
    downloadCurrentPageAsPdf,
    downloadUrlAsPdf,
    exportHtmlAsPdf,
    openPdfFile,
    sharePdfFile,
    getDownloadedPdfs,
    deletePdfFile,
    requestStoragePermissions,
    exportInvoiceAsPdf,
    exportSalesReportAsPdf,
    exportReceiptAsPdf,
    downloadCurrentPage,
    exportInventoryReportAsPdf
};
