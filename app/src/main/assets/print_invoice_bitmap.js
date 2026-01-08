/**
 * Print Invoice with Bitmap Printing
 * 
 * This file provides functions to print invoices using bitmap printing
 * instead of plain text printing for better formatting and quality.
 * 
 * WHY USE BITMAP PRINTING:
 * 1. Better formatting - preserves fonts, colors, images, logos
 * 2. Consistent rendering - same on screen and print
 * 3. Complex layouts - supports CSS styling, borders, spacing
 * 4. Works with all printer types - ESC/POS, AutoReplyPrint, Epson, XPrinter
 * 
 * CHANGES NEEDED:
 * - Replace formatForThermalPrinter() with formatForBitmapPrinting()
 * - Pass HTML content instead of plain text to printFromWeb()
 */

/**
 * Convert DOM element to HTML for bitmap printing
 * @param {string} elementId - ID of the element to print
 * @returns {string} HTML content ready for bitmap printing
 */
function formatForBitmapPrinting(elementId) {
    const element = document.getElementById(elementId);
    if (!element) {
        console.error('Element not found:', elementId);
        return null;
    }
    
    // Clone the element to avoid modifying the original
    const clone = element.cloneNode(true);
    
    // Remove print buttons and other UI elements that shouldn't be printed
    const printButtons = clone.querySelectorAll('.elintpos-print-btn, .print-btn, button[onclick*="print"]');
    printButtons.forEach(btn => btn.remove());
    
    // Get computed styles and inline them for better rendering
    const styles = getComputedStylesForElement(clone);
    
    // Build complete HTML document
    const html = `<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }
        body {
            font-family: Arial, sans-serif;
            font-size: 12px;
            line-height: 1.4;
            padding: 10px;
            background: white;
        }
        table {
            border-collapse: collapse;
            width: 100%;
            margin: 5px 0;
        }
        th, td {
            padding: 4px;
            border: 1px solid #000;
            text-align: left;
        }
        th {
            background-color: #f2f2f2;
            font-weight: bold;
        }
        .text-center {
            text-align: center;
        }
        .text-right {
            text-align: right;
        }
        .text-left {
            text-align: left;
        }
        ${styles}
    </style>
</head>
<body>
    ${clone.innerHTML}
</body>
</html>`;
    
    return html;
}

/**
 * Get computed styles for an element and its children
 * @param {HTMLElement} element - Element to get styles for
 * @returns {string} CSS string with computed styles
 */
function getComputedStylesForElement(element) {
    let styles = '';
    const allElements = element.querySelectorAll('*');
    allElements.forEach((el, index) => {
        const computed = window.getComputedStyle(el);
        const tagName = el.tagName.toLowerCase();
        const className = el.className ? `.${el.className.split(' ').join('.')}` : '';
        const id = el.id ? `#${el.id}` : '';
        const selector = `${tagName}${id}${className}`;
        
        // Only include important styles
        const importantStyles = [];
        if (computed.color && computed.color !== 'rgb(0, 0, 0)') {
            importantStyles.push(`color: ${computed.color}`);
        }
        if (computed.fontSize) {
            importantStyles.push(`font-size: ${computed.fontSize}`);
        }
        if (computed.fontWeight && computed.fontWeight !== '400') {
            importantStyles.push(`font-weight: ${computed.fontWeight}`);
        }
        if (computed.textAlign && computed.textAlign !== 'start') {
            importantStyles.push(`text-align: ${computed.textAlign}`);
        }
        if (computed.backgroundColor && computed.backgroundColor !== 'rgba(0, 0, 0, 0)') {
            importantStyles.push(`background-color: ${computed.backgroundColor}`);
        }
        if (computed.border) {
            importantStyles.push(`border: ${computed.border}`);
        }
        if (computed.padding) {
            importantStyles.push(`padding: ${computed.padding}`);
        }
        if (computed.margin) {
            importantStyles.push(`margin: ${computed.margin}`);
        }
        
        if (importantStyles.length > 0) {
            styles += `${selector} { ${importantStyles.join('; ')}; }\n`;
        }
    });
    
    return styles;
}

/**
 * Print invoice using bitmap printing (HTML → Bitmap → Printer)
 * @param {string} elementId - ID of the element to print
 * @param {boolean} updateDateTime - Whether to update date/time before printing (default: true)
 */
function printInvoice(elementId, updateDateTime = true) {
    // Update date/time before printing if requested
    if (updateDateTime && typeof updateDateTime === 'function') {
        updateDateTime();
    } else if (updateDateTime && typeof updateDateTime === 'boolean' && updateDateTime) {
        // Try to find and call updateDateTime function
        if (typeof window.updateDateTime === 'function') {
            window.updateDateTime();
        }
    }
    
    if (isAndroid()) {
        // Android: Use bitmap printing via native bridge
        var htmlContent = formatForBitmapPrinting(elementId);
        
        if (!htmlContent) {
            console.warn('No HTML content to print. Falling back to window.print()');
            window.print();
            return;
        }
        
        // Print using native interface with HTML content (will use bitmap printing)
        if (typeof ElintPOSNative !== 'undefined' && ElintPOSNative.printFromWeb) {
            try {
                // Pass HTML content - Android will detect it's HTML and use bitmap printing
                const result = ElintPOSNative.printFromWeb(htmlContent, 'auto');
                const response = JSON.parse(result || '{}');
                
                if (response.ok) {
                    console.log('Bitmap print initiated successfully');
                } else {
                    console.error('Print failed:', response.msg);
                    // Fallback to window.print() if bridge call fails
                    window.print();
                }
            } catch (e) {
                console.error('Error calling ElintPOSNative.printFromWeb:', e);
                // Fallback to window.print() if bridge call fails
                console.warn('Falling back to window.print()');
                window.print();
            }
        } else {
            // Fallback to window.print() if bridge not available
            console.warn('ElintPOSNative bridge not available, falling back to window.print()');
            window.print();
        }
    } else {
        // Windows/Desktop/iOS: Use standard browser print
        window.print();
    }
}

/**
 * Check if running on Android
 * @returns {boolean}
 */
function isAndroid() {
    return /Android/i.test(navigator.userAgent);
}

/**
 * Alternative: Force bitmap printing even for plain text
 * This wraps plain text in HTML and uses bitmap printing
 * @param {string} text - Plain text content
 * @param {string} prefer - Printer preference (default: "auto")
 */
function printTextAsBitmap(text, prefer = 'auto') {
    if (!isAndroid() || typeof ElintPOSNative === 'undefined') {
        console.warn('Bitmap printing only available on Android with ElintPOSNative');
        return;
    }
    
    // Wrap plain text in HTML for bitmap printing
    const htmlContent = `<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        body {
            font-family: 'Courier New', monospace;
            font-size: 12px;
            line-height: 1.4;
            padding: 10px;
            white-space: pre-wrap;
            background: white;
        }
    </style>
</head>
<body>
${text.replace(/</g, '&lt;').replace(/>/g, '&gt;')}
</body>
</html>`;
    
    try {
        const result = ElintPOSNative.printFromWeb(htmlContent, prefer);
        const response = JSON.parse(result || '{}');
        return response;
    } catch (e) {
        console.error('Error in printTextAsBitmap:', e);
        return { ok: false, msg: e.message };
    }
}

// Export functions to global scope
if (typeof window !== 'undefined') {
    window.formatForBitmapPrinting = formatForBitmapPrinting;
    window.printInvoice = printInvoice;
    window.printTextAsBitmap = printTextAsBitmap;
    window.isAndroid = isAndroid;
}

