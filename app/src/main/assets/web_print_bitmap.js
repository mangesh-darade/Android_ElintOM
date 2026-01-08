/**
 * Global Android Printing Functions for POS - Bitmap Printing Version
 * This file provides bitmap-based printing functionality with Android native bridge support
 * Usage: Include this file in POS views and call printInvoice() or printInvoiceWithElementId('element-id')
 * 
 * Flow: HTML Element → HTML Content → ElintPOSNative.printHtmlInvoice() → Bitmap → Printer
 */

// Polyfill for padStart and padEnd (for older browsers)
if (!String.prototype.padStart) {
    String.prototype.padStart = function(targetLength, padString) {
        targetLength = targetLength >> 0;
        padString = String(padString || ' ');
        if (this.length > targetLength) {
            return String(this);
        } else {
            targetLength = targetLength - this.length;
            if (targetLength > padString.length) {
                padString += padString.repeat(targetLength / padString.length);
            }
            return padString.slice(0, targetLength) + String(this);
        }
    };
}

if (!String.prototype.padEnd) {
    String.prototype.padEnd = function(targetLength, padString) {
        targetLength = targetLength >> 0;
        padString = String(padString || ' ');
        if (this.length > targetLength) {
            return String(this);
        } else {
            targetLength = targetLength - this.length;
            if (targetLength > padString.length) {
                padString += padString.repeat(targetLength / padString.length);
            }
            return String(this) + padString.slice(0, targetLength);
        }
    };
}

// Platform Detection Functions
function isAndroid() {
    return /Android/i.test(navigator.userAgent);
}

function isWindows() {
    return /Win/i.test(navigator.userAgent) || /Windows/i.test(navigator.userAgent);
}

function isIOS() {
    return /iPhone|iPad|iPod/i.test(navigator.userAgent);
}

// Update Date/Time in Invoice (can be customized per view)
function updateDateTime() {
    var now = new Date();
    var dateTimeStr = now.toLocaleString();
    // Update any date/time elements if needed
    var dateTimeElements = document.querySelectorAll('.invoice-date-time, .receipt-date-time, #invoice-date-time');
    dateTimeElements.forEach(function(el) {
        if (el) {
            el.textContent = dateTimeStr;
        }
    });
}

/**
 * Auto-detect receipt element ID from common IDs
 * @returns {string} The detected receipt element ID or null
 */
function detectReceiptElement() {
    var commonIds = ['receipt-data', 'receiptData', 'receipt', 'invoice-data', 'invoiceData', 'bill-data', 'billData', 'print-content', 'printContent'];
    for (var i = 0; i < commonIds.length; i++) {
        var element = document.getElementById(commonIds[i]);
        if (element) {
            return commonIds[i];
        }
    }
    // Try to find any element with receipt/invoice/bill in class or id
    var allElements = document.querySelectorAll('[id*="receipt"], [id*="invoice"], [id*="bill"], [class*="receipt"], [class*="invoice"], [class*="bill"]');
    if (allElements.length > 0) {
        return allElements[0].id || null;
    }
    return null;
}

/**
 * Get HTML content of an element with all styles and formatting
 * This preserves CSS, images, and layout for bitmap rendering
 * @param {string} elementId - The ID of the element containing receipt data
 * @returns {string} Complete HTML content with styles
 */
function getHtmlContentForBitmap(elementId) {
    // Auto-detect if no elementId provided
    if (!elementId) {
        elementId = detectReceiptElement() || 'receipt-data';
    }
    
    var receiptElement = document.getElementById(elementId);
    
    if (!receiptElement) {
        // Try to use body content as fallback
        console.warn('Receipt element with ID "' + elementId + '" not found, using body content');
        receiptElement = document.body;
        if (!receiptElement) {
            return '';
        }
    }
    
    // Clone the element to avoid modifying the original
    var clonedElement = receiptElement.cloneNode(true);
    
    // Remove elements with class 'no-print'
    var noPrintElements = clonedElement.querySelectorAll('.no-print');
    noPrintElements.forEach(function(el) {
        el.remove();
    });
    
    // Get all computed styles from the original element and its children
    function copyStyles(source, target) {
        var computedStyle = window.getComputedStyle(source);
        var styleString = '';
        
        // Copy all relevant styles
        var styleProps = [
            'color', 'background-color', 'font-family', 'font-size', 'font-weight',
            'font-style', 'text-align', 'padding', 'margin', 'border', 'width',
            'height', 'display', 'line-height', 'letter-spacing', 'text-decoration'
        ];
        
        styleProps.forEach(function(prop) {
            var value = computedStyle.getPropertyValue(prop);
            if (value && value !== 'initial' && value !== 'normal') {
                styleString += prop + ':' + value + ';';
            }
        });
        
        if (styleString) {
            target.setAttribute('style', styleString);
        }
    }
    
    // Copy styles from original to cloned element
    copyStyles(receiptElement, clonedElement);
    
    // Copy styles for all child elements
    var originalElements = receiptElement.querySelectorAll('*');
    var clonedElements = clonedElement.querySelectorAll('*');
    
    for (var i = 0; i < originalElements.length && i < clonedElements.length; i++) {
        copyStyles(originalElements[i], clonedElements[i]);
    }
    
    // Get all stylesheets from the document
    var stylesheets = Array.from(document.styleSheets);
    var allStyles = '';
    
    stylesheets.forEach(function(sheet) {
        try {
            var rules = sheet.cssRules || sheet.rules;
            if (rules) {
                for (var j = 0; j < rules.length; j++) {
                    try {
                        allStyles += rules[j].cssText + '\n';
                    } catch (e) {
                        // Skip rules that can't be accessed (cross-origin)
                    }
                }
            }
        } catch (e) {
            // Skip stylesheets that can't be accessed
        }
    });
    
    // Get inline styles from the document
    var inlineStyles = Array.from(document.querySelectorAll('style'));
    inlineStyles.forEach(function(styleEl) {
        if (styleEl.textContent) {
            allStyles += styleEl.textContent + '\n';
        }
    });
    
    // Build complete HTML document with styles
    var htmlContent = '<!DOCTYPE html>\n';
    htmlContent += '<html>\n';
    htmlContent += '<head>\n';
    htmlContent += '<meta charset="UTF-8">\n';
    htmlContent += '<meta name="viewport" content="width=640, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">\n';
    htmlContent += '<base href="' + (window.location.href || '') + '">\n';
    htmlContent += '<style>\n';
    htmlContent += allStyles;
    htmlContent += '\n/* Additional print styles for thermal printer */\n';
    htmlContent += '* { box-sizing: border-box; margin: 0; padding: 0; }\n';
    htmlContent += 'html, body { width: 100%; overflow-x: hidden; }\n';
    htmlContent += 'body { margin: 0; padding: 10px; font-family: Arial, sans-serif; background: white; font-size: 12px; line-height: 1.4; }\n';
    htmlContent += 'table { border-collapse: collapse; width: 100%; margin: 5px 0; table-layout: fixed; }\n';
    htmlContent += 'th, td { padding: 4px 2px; text-align: left; border: 1px solid #000; font-size: 11px; word-wrap: break-word; overflow-wrap: break-word; }\n';
    htmlContent += 'th { background-color: #f2f2f2; font-weight: bold; }\n';
    htmlContent += '.text-center { text-align: center; }\n';
    htmlContent += '.text-right { text-align: right; }\n';
    htmlContent += '.text-left { text-align: left; }\n';
    htmlContent += 'img { max-width: 100%; height: auto; display: block; }\n';
    htmlContent += 'h1, h2, h3, h4, h5, h6 { margin: 5px 0; font-size: 14px; }\n';
    htmlContent += 'p { margin: 3px 0; }\n';
    htmlContent += '@media print { body { padding: 0; } }\n';
    htmlContent += '</style>\n';
    htmlContent += '</head>\n';
    htmlContent += '<body>\n';
    htmlContent += clonedElement.outerHTML;
    htmlContent += '\n</body>\n';
    htmlContent += '</html>';
    
    return htmlContent;
}

/**
 * Get HTML content with base URL for images and resources
 * This ensures images and external resources load correctly
 * @param {string} elementId - The ID of the element containing receipt data
 * @returns {string} Complete HTML content with base URL
 */
function getHtmlContentWithBaseUrl(elementId) {
    var htmlContent = getHtmlContentForBitmap(elementId);
    
    if (!htmlContent) {
        return '';
    }
    
    // Get current page URL for base URL
    var currentUrl = window.location.href;
    var baseUrl = window.location.origin;
    var basePath = window.location.pathname;
    var baseDir = basePath.substring(0, basePath.lastIndexOf('/') + 1);
    
    // If baseDir is empty or just '/', use root
    if (!baseDir || baseDir === '/') {
        baseDir = '/';
    }
    
    // Build full base URL
    var fullBaseUrl = baseUrl + baseDir;
    
    // Replace relative image URLs with absolute URLs
    htmlContent = htmlContent.replace(/src=["']([^"']+)["']/gi, function(match, url) {
        url = url.trim();
        if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:') || url.startsWith('blob:')) {
            return match; // Already absolute or data URI
        }
        if (url.startsWith('//')) {
            return 'src="' + window.location.protocol + url + '"';
        }
        if (url.startsWith('/')) {
            return 'src="' + baseUrl + url + '"';
        }
        // Relative URL
        return 'src="' + fullBaseUrl + url + '"';
    });
    
    // Replace relative CSS URLs in style attributes
    htmlContent = htmlContent.replace(/style=["']([^"']*)url\(['"]?([^'"]+)['"]?\)([^"']*)["']/gi, function(match, before, url, after) {
        url = url.trim();
        if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:')) {
            return match; // Already absolute or data URI
        }
        if (url.startsWith('//')) {
            return 'style="' + before + 'url("' + window.location.protocol + url + '")' + after + '"';
        }
        if (url.startsWith('/')) {
            return 'style="' + before + 'url("' + baseUrl + url + '")' + after + '"';
        }
        return 'style="' + before + 'url("' + fullBaseUrl + url + '")' + after + '"';
    });
    
    // Replace relative CSS URLs in style tags
    htmlContent = htmlContent.replace(/url\(['"]?([^'"]+)['"]?\)/g, function(match, url) {
        url = url.trim();
        if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:') || url.startsWith('blob:')) {
            return match; // Already absolute or data URI
        }
        if (url.startsWith('//')) {
            return 'url("' + window.location.protocol + url + '")';
        }
        if (url.startsWith('/')) {
            return 'url("' + baseUrl + url + '")';
        }
        return 'url("' + fullBaseUrl + url + '")';
    });
    
    // Add base tag to ensure relative URLs work
    htmlContent = htmlContent.replace(/<head>/, '<head>\n<base href="' + currentUrl + '">');
    
    return htmlContent;
}

/**
 * Main Print Invoice Function - Bitmap Printing Version
 * Renders HTML as bitmap and prints to thermal printer
 * @param {string} elementId - Optional: The ID of the element containing receipt data (default: auto-detect)
 * @param {boolean} updateDateTime - Optional: Whether to update date/time before printing (default: true)
 * @param {string} preferType - Optional: Preferred printer type (default: 'auto')
 */
function printInvoice(elementId, updateDateTime, preferType) {
    // Update date/time before printing if requested (default: true)
    if (updateDateTime !== false) {
        // Call the global updateDateTime function (not the parameter)
        if (typeof window.updateDateTime === 'function') {
            window.updateDateTime();
        } else if (typeof updateDateTime === 'function') {
            // If updateDateTime parameter is actually a function, call it
            updateDateTime();
        }
    }
    
    if (isAndroid()) {
        // Android: Use bitmap printing via native bridge
        try {
            var htmlContent = getHtmlContentWithBaseUrl(elementId);
            
            if (!htmlContent || htmlContent.trim().length === 0) {
                console.error('No content to print. Element ID:', elementId);
                console.warn('Falling back to window.print()');
                window.print();
                return;
            }
            
            // Debug: Log HTML content length
            console.log('HTML content length:', htmlContent.length);
            console.log('HTML preview:', htmlContent.substring(0, 200) + '...');
            
            // Print using bitmap printing interface
            // Try printHtmlInvoice first, then fallback to printFromWeb (which now detects HTML)
            if (typeof ElintPOSNative !== 'undefined') {
                try {
                    var printerType = preferType || 'auto';
                    
                    // Method 1: Try printHtmlInvoice (preferred for bitmap)
                    if (ElintPOSNative.printHtmlInvoice) {
                        console.log('Calling ElintPOSNative.printHtmlInvoice with printer type:', printerType);
                        var result = ElintPOSNative.printHtmlInvoice(htmlContent, printerType);
                        
                        // Parse result
                        try {
                            var resultObj = JSON.parse(result);
                            if (resultObj.ok) {
                                console.log('Print initiated successfully via printHtmlInvoice:', resultObj.msg);
                                return; // Success, exit
                            } else {
                                console.warn('printHtmlInvoice failed:', resultObj.msg);
                                // Fall through to try printFromWeb
                            }
                        } catch (e) {
                            console.warn('Error parsing printHtmlInvoice result:', e);
                            // Fall through to try printFromWeb
                        }
                    }
                    
                    // Method 2: Try printFromWeb (now detects HTML and uses bitmap automatically)
                    if (ElintPOSNative.printFromWeb) {
                        console.log('Calling ElintPOSNative.printFromWeb (will auto-detect HTML for bitmap printing)');
                        var result = ElintPOSNative.printFromWeb(htmlContent, printerType);
                        
                        // Parse result
                        try {
                            var resultObj = JSON.parse(result);
                            if (resultObj.ok) {
                                console.log('Print initiated successfully via printFromWeb:', resultObj.msg);
                                return; // Success, exit
                            } else {
                                console.error('Print failed:', resultObj.msg);
                                console.warn('Falling back to window.print()');
                                window.print();
                            }
                        } catch (e) {
                            console.error('Error parsing printFromWeb result:', e);
                            console.warn('Falling back to window.print()');
                            window.print();
                        }
                    } else {
                        console.warn('ElintPOSNative.printFromWeb not available, falling back to window.print()');
                        window.print();
                    }
                } catch (e) {
                    console.error('Error calling native print methods:', e);
                    console.error('Error stack:', e.stack);
                    console.warn('Falling back to window.print()');
                    window.print();
                }
            } else {
                // Fallback to window.print() if bridge not available
                console.warn('ElintPOSNative bridge not available, falling back to window.print()');
                window.print();
            }
        } catch (e) {
            console.error('Error in printInvoice:', e);
            console.error('Error stack:', e.stack);
            window.print();
        }
    } else {
        // Windows/Desktop/iOS: Use standard browser print
        window.print();
    }
}

/**
 * Print Invoice with specific element ID (convenience function)
 * @param {string} elementId - The ID of the element containing receipt data
 * @param {string} preferType - Optional: Preferred printer type (default: 'auto')
 */
function printInvoiceWithElementId(elementId, preferType) {
    printInvoice(elementId, true, preferType);
}

/**
 * Check if Android native bridge is available for bitmap printing
 * @returns {boolean} True if ElintPOSNative.printHtmlInvoice bridge is available
 */
function isAndroidBridgeAvailable() {
    return typeof ElintPOSNative !== 'undefined' && ElintPOSNative.printHtmlInvoice;
}

/**
 * Legacy function for text-based printing (for backward compatibility)
 * This function is kept for compatibility but uses bitmap printing internally
 * @param {string} elementId - Optional: The ID of the element containing receipt data
 */
function formatForThermalPrinter(elementId) {
    // This function is kept for backward compatibility
    // But we now use bitmap printing, so this just returns a note
    console.warn('formatForThermalPrinter() is deprecated. Use printInvoice() for bitmap printing.');
    return 'Bitmap printing is now used. Use printInvoice() instead.';
}

// Export functions to global scope
window.ElintPOSPrint = {
    printInvoice: printInvoice,
    printInvoiceWithElementId: printInvoiceWithElementId,
    formatForThermalPrinter: formatForThermalPrinter, // Deprecated but kept for compatibility
    getHtmlContentForBitmap: getHtmlContentForBitmap,
    getHtmlContentWithBaseUrl: getHtmlContentWithBaseUrl,
    isAndroid: isAndroid,
    isWindows: isWindows,
    isIOS: isIOS,
    isAndroidBridgeAvailable: isAndroidBridgeAvailable,
    updateDateTime: updateDateTime
};

