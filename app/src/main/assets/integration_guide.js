/**
 * ElintPOS Android Printing Integration Guide
 * 
 * This file contains comprehensive examples for integrating printing
 * functionality into your POS web application.
 */

// ============================================================================
// BASIC PRINTING FUNCTIONS
// ============================================================================

/**
 * Print simple text to any connected printer
 */
function printText(text, preferPrinter = 'auto') {
    try {
        const result = JSON.parse(ElintPOSNative.printFromWeb(text, preferPrinter));
        if (!result.ok) {
            console.error('Print failed:', result.msg);
            return false;
        }
        return true;
    } catch (e) {
        console.error('Print error:', e);
        return false;
    }
}

/**
 * Check if any printer is connected
 */
function isPrinterConnected() {
    try {
        const status = JSON.parse(ElintPOSNative.getPrinterStatus());
        return status.any || false;
    } catch (e) {
        console.error('Status check error:', e);
        return false;
    }
}

/**
 * Get detailed printer status
 */
function getPrinterStatus() {
    try {
        return JSON.parse(ElintPOSNative.getPrinterStatus());
    } catch (e) {
        console.error('Status check error:', e);
        return { bt: false, usb: false, lan: false, any: false };
    }
}

// ============================================================================
// PRINTER CONNECTION FUNCTIONS
// ============================================================================

/**
 * Connect to Bluetooth printer
 */
function connectBluetoothPrinter(macAddress) {
    try {
        const result = JSON.parse(ElintPOSNative.connectPrinter(macAddress));
        if (result.ok) {
            console.log('Bluetooth printer connected');
            return true;
        } else {
            console.error('Bluetooth connection failed:', result.msg);
            return false;
        }
    } catch (e) {
        console.error('Bluetooth connection error:', e);
        return false;
    }
}

/**
 * Connect to USB printer
 */
function connectUsbPrinter(deviceName) {
    try {
        const result = JSON.parse(ElintPOSNative.connectUsbPrinter(deviceName));
        if (result.ok) {
            console.log('USB printer connected');
            return true;
        } else {
            console.error('USB connection failed:', result.msg);
            return false;
        }
    } catch (e) {
        console.error('USB connection error:', e);
        return false;
    }
}

/**
 * Connect to LAN/WiFi printer
 */
function connectLanPrinter(ipAddress, port = 9100) {
    try {
        const result = JSON.parse(ElintPOSNative.connectLanPrinter(ipAddress, port));
        if (result.ok) {
            console.log('LAN printer connected');
            return true;
        } else {
            console.error('LAN connection failed:', result.msg);
            return false;
        }
    } catch (e) {
        console.error('LAN connection error:', e);
        return false;
    }
}

// ============================================================================
// BARCODE AND QR CODE PRINTING
// ============================================================================

/**
 * Print barcode
 * @param {string} data - Barcode data
 * @param {number} type - Barcode type (73=Code128, 69=Code39, etc.)
 * @param {number} height - Barcode height in dots
 * @param {number} width - Barcode width multiplier
 * @param {number} position - Text position (0=none, 1=above, 2=below, 3=both)
 * @param {string} preferPrinter - Preferred printer ('bt', 'usb', 'lan', 'auto')
 */
function printBarcode(data, type = 73, height = 50, width = 2, position = 0, preferPrinter = 'auto') {
    try {
        const result = JSON.parse(ElintPOSNative.printBarcode(data, type, height, width, position, preferPrinter));
        if (!result.ok) {
            console.error('Barcode print failed:', result.msg);
            return false;
        }
        return true;
    } catch (e) {
        console.error('Barcode print error:', e);
        return false;
    }
}

/**
 * Print QR code
 * @param {string} data - QR code data
 * @param {number} size - QR code size (1-16)
 * @param {number} errorCorrection - Error correction level (0=L, 1=M, 2=Q, 3=H)
 * @param {string} preferPrinter - Preferred printer
 */
function printQRCode(data, size = 3, errorCorrection = 0, preferPrinter = 'auto') {
    try {
        const result = JSON.parse(ElintPOSNative.printQRCode(data, size, errorCorrection, preferPrinter));
        if (!result.ok) {
            console.error('QR code print failed:', result.msg);
            return false;
        }
        return true;
    } catch (e) {
        console.error('QR code print error:', e);
        return false;
    }
}

// ============================================================================
// ENHANCED RECEIPT FORMATTING
// ============================================================================

/**
 * Print formatted invoice
 * @param {Object} saleData - Sale data object
 * @param {Object} config - Print configuration
 * @param {string} preferPrinter - Preferred printer
 */
function printInvoice(saleData, config = {}, preferPrinter = 'auto') {
    try {
        const result = JSON.parse(ElintPOSNative.formatAndPrintInvoice(
            JSON.stringify(saleData),
            JSON.stringify(config),
            preferPrinter
        ));
        
        if (!result.ok) {
            console.error('Invoice print failed:', result.msg);
            return false;
        }
        return true;
    } catch (e) {
        console.error('Invoice print error:', e);
        return false;
    }
}

/**
 * Print formatted receipt
 * @param {Object} saleData - Sale data object
 * @param {Object} config - Print configuration
 * @param {string} preferPrinter - Preferred printer
 */
function printReceipt(saleData, config = {}, preferPrinter = 'auto') {
    try {
        const result = JSON.parse(ElintPOSNative.formatAndPrintReceipt(
            JSON.stringify(saleData),
            JSON.stringify(config),
            preferPrinter
        ));
        
        if (!result.ok) {
            console.error('Receipt print failed:', result.msg);
            return false;
        }
        return true;
    } catch (e) {
        console.error('Receipt print error:', e);
        return false;
    }
}

/**
 * Print kitchen order
 * @param {Object} orderData - Order data object
 * @param {Object} config - Print configuration
 * @param {string} preferPrinter - Preferred printer
 */
function printKitchenOrder(orderData, config = {}, preferPrinter = 'auto') {
    try {
        const result = JSON.parse(ElintPOSNative.formatAndPrintKitchenOrder(
            JSON.stringify(orderData),
            JSON.stringify(config),
            preferPrinter
        ));
        
        if (!result.ok) {
            console.error('Kitchen order print failed:', result.msg);
            return false;
        }
        return true;
    } catch (e) {
        console.error('Kitchen order print error:', e);
        return false;
    }
}

// ============================================================================
// PRINT CONFIGURATION
// ============================================================================

/**
 * Configure default print settings
 * @param {Object} config - Configuration object
 */
function configurePrinter(config) {
    try {
        const result = JSON.parse(ElintPOSNative.setDefaultPrintConfig(
            config.leftMargin || 0,
            config.rightMargin || 0,
            config.lineSpacing || 30,
            config.widthMultiplier || 0,
            config.heightMultiplier || 0,
            config.pageWidthDots || 576,
            config.linesPerPage || 0
        ));
        
        if (!result.ok) {
            console.error('Configuration failed:', result.msg);
            return false;
        }
        return true;
    } catch (e) {
        console.error('Configuration error:', e);
        return false;
    }
}

// ============================================================================
// PRINTER MANAGEMENT
// ============================================================================

/**
 * Open printer management UI
 */
function openPrinterManagement() {
    try {
        const result = JSON.parse(ElintPOSNative.openPrinterManagement());
        if (!result.ok) {
            console.error('Failed to open printer management:', result.msg);
            return false;
        }
        return true;
    } catch (e) {
        console.error('Printer management error:', e);
        return false;
    }
}

/**
 * Get list of available printers
 */
function getAvailablePrinters() {
    try {
        return JSON.parse(ElintPOSNative.getAvailablePrinters());
    } catch (e) {
        console.error('Get printers error:', e);
        return { bluetooth: [], usb: [], status: { bt: false, usb: false, lan: false, any: false } };
    }
}

// ============================================================================
// EXAMPLE USAGE
// ============================================================================

/**
 * Example: Complete POS checkout with printing
 */
function processCheckout(saleData) {
    // Check if printer is available
    if (!isPrinterConnected()) {
        alert('No printer connected. Please connect a printer first.');
        return false;
    }
    
    // Configure printer for 58mm paper
    configurePrinter({
        pageWidthDots: 384,  // 58mm paper
        leftMargin: 0,
        rightMargin: 0,
        lineSpacing: 30,
        widthMultiplier: 0,
        heightMultiplier: 0
    });
    
    // Print customer receipt
    const receiptConfig = {
        paperWidth: 384,
        compactLayout: true,
        includeBarcode: true
    };
    
    if (!printReceipt(saleData, receiptConfig)) {
        alert('Failed to print receipt');
        return false;
    }
    
    // Print kitchen order if it's a restaurant
    if (saleData.orderType === 'restaurant') {
        const kitchenConfig = {
            paperWidth: 384,
            widthMultiplier: 1,
            heightMultiplier: 1
        };
        
        printKitchenOrder(saleData, kitchenConfig);
    }
    
    return true;
}

/**
 * Example: Print invoice with barcode and QR code
 */
function printInvoiceWithCodes(saleData) {
    const config = {
        paperWidth: 576,  // 80mm paper
        includeBarcode: true,
        includeQR: true,
        compactLayout: false
    };
    
    return printInvoice(saleData, config);
}

/**
 * Example: Print shipping label with barcode
 */
function printShippingLabel(orderData) {
    // Print order details
    const labelText = `
SHIPPING LABEL
================
Order: ${orderData.orderNumber}
Customer: ${orderData.customerName}
Address: ${orderData.shippingAddress}
Phone: ${orderData.phone}
    `;
    
    if (!printText(labelText)) {
        return false;
    }
    
    // Print tracking barcode
    return printBarcode(orderData.trackingNumber, 73, 60, 2, 2);
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
 * Generate invoice number
 */
function generateInvoiceNumber() {
    const now = new Date();
    const timestamp = now.getTime().toString().slice(-8);
    return `INV-${timestamp}`;
}

// ============================================================================
// INITIALIZATION
// ============================================================================

/**
 * Initialize printing system
 */
function initializePrinting() {
    // Check printer status
    const status = getPrinterStatus();
    
    if (status.any) {
        console.log('Printer system ready');
        showToast('Printer connected and ready');
    } else {
        console.log('No printer connected');
        showToast('No printer connected');
    }
    
    // Set default configuration for 80mm paper
    configurePrinter({
        pageWidthDots: 576,
        leftMargin: 0,
        rightMargin: 0,
        lineSpacing: 30,
        widthMultiplier: 0,
        heightMultiplier: 0
    });
}

// Auto-initialize when script loads
document.addEventListener('DOMContentLoaded', initializePrinting);

// Export functions for use in other scripts
window.ElintPOSPrinting = {
    printText,
    printBarcode,
    printQRCode,
    printInvoice,
    printReceipt,
    printKitchenOrder,
    connectBluetoothPrinter,
    connectUsbPrinter,
    connectLanPrinter,
    configurePrinter,
    isPrinterConnected,
    getPrinterStatus,
    openPrinterManagement,
    getAvailablePrinters,
    showToast,
    processCheckout,
    printInvoiceWithCodes,
    printShippingLabel
};
