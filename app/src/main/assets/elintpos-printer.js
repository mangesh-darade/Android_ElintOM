/**
 * ElintPOS Printer Integration Library
 * This library provides printer selection and printing functionality for web pages
 * loaded in the ElintPOS Android WebView.
 */

class ElintPOSPrinter {
    constructor() {
        this.isAvailable = typeof ElintPOSNative !== 'undefined';
        this.selectedPrinter = null;
        this.printSettings = {
            paperWidth: 384,
            lineSpacing: 30,
            fontSize: 1
        };
        
        if (this.isAvailable) {
            this.initializePrinterIntegration();
        }
    }

    /**
     * Initialize printer integration by adding print buttons and event listeners
     */
    initializePrinterIntegration() {
        // Add print button to the page if it doesn't exist
        this.addPrintButton();
        // Add settings button to the page if it doesn't exist
        this.addSettingsButton();
        
        // Override the default print function
        this.overridePrintFunction();
        
        // Add keyboard shortcut (Ctrl+P)
        this.addKeyboardShortcut();
        
        // Add context menu for printing
        this.addContextMenu();
        
        // Monitor for popups, modals, and alerts
        this.monitorForDialogs();
        
        // Add print buttons to existing dialogs
        this.addPrintButtonsToExistingDialogs();
        
        // Set up periodic printer status updates
        this.setupPrinterStatusUpdates();
    }

    /**
     * Add a floating print button to the page
     */
    addPrintButton() {
        // Check if print button already exists
        if (document.getElementById('elintpos-print-btn')) {
            return;
        }

        const printBtn = document.createElement('div');
        printBtn.id = 'elintpos-print-btn';
        printBtn.innerHTML = '🖨️ Print';
        printBtn.style.cssText = `
            position: fixed;
            top: 20px;
            right: 20px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 12px 20px;
            border-radius: 25px;
            cursor: pointer;
            font-family: 'Roboto', Arial, sans-serif;
            font-size: 14px;
            font-weight: 600;
            box-shadow: 0 4px 15px rgba(102, 126, 234, 0.3);
            z-index: 9999;
            transition: all 0.3s ease;
            user-select: none;
        `;

        // Add hover effect
        printBtn.addEventListener('mouseenter', function() {
            this.style.transform = 'translateY(-2px)';
            this.style.boxShadow = '0 6px 20px rgba(102, 126, 234, 0.4)';
        });

        printBtn.addEventListener('mouseleave', function() {
            this.style.transform = 'translateY(0)';
            this.style.boxShadow = '0 4px 15px rgba(102, 126, 234, 0.3)';
        });

        // Add click event
        printBtn.addEventListener('click', () => {
            this.showPrinterSelector();
        });

        document.body.appendChild(printBtn);
    }

    /**
     * Add a floating settings button to the page
     */
    addSettingsButton() {
        if (!this.isAvailable) return;

        if (document.getElementById('elintpos-settings-btn')) {
            return;
        }

        const settingsBtn = document.createElement('div');
        settingsBtn.id = 'elintpos-settings-btn';
        settingsBtn.innerHTML = '⚙ Settings';
        settingsBtn.style.cssText = `
            position: fixed;
            top: 20px;
            right: 120px;
            background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
            color: white;
            padding: 12px 16px;
            border-radius: 25px;
            cursor: pointer;
            font-family: 'Roboto', Arial, sans-serif;
            font-size: 14px;
            font-weight: 600;
            box-shadow: 0 4px 15px rgba(37, 99, 235, 0.3);
            z-index: 9999;
            transition: all 0.3s ease;
            user-select: none;
        `;

        settingsBtn.addEventListener('mouseenter', function() {
            this.style.transform = 'translateY(-2px)';
            this.style.boxShadow = '0 6px 20px rgba(37, 99, 235, 0.4)';
        });

        settingsBtn.addEventListener('mouseleave', function() {
            this.style.transform = 'translateY(0)';
            this.style.boxShadow = '0 4px 15px rgba(37, 99, 235, 0.3)';
        });

        // Short click: open printer settings UI
        settingsBtn.addEventListener('click', () => {
            try {
                if (typeof ElintPOSNative !== 'undefined' && typeof ElintPOSNative.openPrinterSettingsUI === 'function') {
                    ElintPOSNative.openPrinterSettingsUI();
                } else if (typeof ElintPOSNative !== 'undefined' && typeof ElintPOSNative.showPrinterSettingsPopup === 'function') {
                    ElintPOSNative.showPrinterSettingsPopup();
                }
            } catch (e) {
                // no-op
            }
        });

        // Long press: show quick settings if available
        let pressTimer;
        settingsBtn.addEventListener('mousedown', () => {
            pressTimer = setTimeout(() => {
                try {
                    if (typeof ElintPOSNative !== 'undefined' && typeof ElintPOSNative.showQuickSettingsPopup === 'function') {
                        ElintPOSNative.showQuickSettingsPopup();
                    }
                } catch (e) {}
            }, 650);
        });
        settingsBtn.addEventListener('mouseup', () => clearTimeout(pressTimer));
        settingsBtn.addEventListener('mouseleave', () => clearTimeout(pressTimer));

        document.body.appendChild(settingsBtn);
    }

    /**
     * Override the default print function to use ElintPOS printer
     */
    overridePrintFunction() {
        const originalPrint = window.print;
        
        window.print = () => {
            // Try to show native Android print dialog first
            if (this.isAvailable && typeof ElintPOSNative.showNativePrintDialog === 'function') {
                try {
                    const result = ElintPOSNative.showNativePrintDialog();
                    const response = JSON.parse(result);
                    if (response.ok) {
                        return; // Native dialog opened successfully
                    }
                } catch (e) {
                    console.log('Native print dialog failed, falling back to custom selector:', e);
                }
            }
            
            // Fallback to custom printer selector
            this.showPrinterSelector();
        };
    }

    /**
     * Add keyboard shortcut (Ctrl+P) for printing
     */
    addKeyboardShortcut() {
        document.addEventListener('keydown', (e) => {
            if (e.ctrlKey && e.key === 'p') {
                e.preventDefault();
                this.showPrinterSelector();
            }
        });
    }

    /**
     * Add context menu for printing
     */
    addContextMenu() {
        document.addEventListener('contextmenu', (e) => {
            // Add print option to context menu
            const selection = window.getSelection().toString();
            if (selection.trim()) {
                this.addContextMenuOption(e, 'Print Selection', () => {
                    this.printSelection(selection);
                });
            }
        });
    }

    /**
     * Add option to context menu
     */
    addContextMenuOption(event, text, callback) {
        // This is a simplified context menu implementation
        // In a real implementation, you might want to create a custom context menu
        const menu = document.createElement('div');
        menu.style.cssText = `
            position: fixed;
            left: ${event.clientX}px;
            top: ${event.clientY}px;
            background: white;
            border: 1px solid #ccc;
            border-radius: 4px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
            z-index: 10000;
            padding: 8px 0;
        `;
        
        const option = document.createElement('div');
        option.textContent = text;
        option.style.cssText = `
            padding: 8px 16px;
            cursor: pointer;
            font-size: 14px;
        `;
        
        option.addEventListener('click', () => {
            callback();
            document.body.removeChild(menu);
        });
        
        option.addEventListener('mouseenter', function() {
            this.style.backgroundColor = '#f0f0f0';
        });
        
        option.addEventListener('mouseleave', function() {
            this.style.backgroundColor = 'transparent';
        });
        
        menu.appendChild(option);
        document.body.appendChild(menu);
        
        // Remove menu when clicking elsewhere
        setTimeout(() => {
            document.addEventListener('click', function removeMenu() {
                if (document.body.contains(menu)) {
                    document.body.removeChild(menu);
                }
                document.removeEventListener('click', removeMenu);
            });
        }, 100);
    }

    /**
     * Show the printer selector overlay
     */
    showPrinterSelector() {
        if (!this.isAvailable) {
            alert('Printer functionality not available');
            return;
        }

        try {
            // Get the content to print
            const content = this.getPageContent();
            
            // Try native selector first
            let openedNative = false;
            try {
                if (typeof ElintPOSNative.showPrinterSelector === 'function') {
                    const result = ElintPOSNative.showPrinterSelector();
                    const response = JSON.parse(result || '{}');
                    openedNative = !!response.ok;
                }
            } catch (_) {}

            // Always ensure a visible selector inline as a fallback
            if (!openedNative) {
                this.renderInlinePrinterSelector(content);
            }
        } catch (e) {
            console.error('Error opening printer selector:', e);
            // Last-resort inline fallback
            this.renderInlinePrinterSelector('');
        }
    }

    /**
     * Get the content of the current page for printing
     */
    getPageContent() {
        // Remove the print button from content
        const printBtn = document.getElementById('elintpos-print-btn');
        if (printBtn) {
            printBtn.style.display = 'none';
        }

        // Get the main content
        let content = '';
        
        // Try to get content from common content areas
        const contentSelectors = [
            'main',
            '.content',
            '.main-content',
            '#content',
            '#main',
            'article',
            '.article',
            '.post',
            '.page-content'
        ];

        let mainContent = null;
        for (const selector of contentSelectors) {
            mainContent = document.querySelector(selector);
            if (mainContent) break;
        }

        if (mainContent) {
            content = mainContent.innerText || mainContent.textContent;
        } else {
            // Fallback to body content
            content = document.body.innerText || document.body.textContent;
        }

        // Clean up the content
        content = content.replace(/\s+/g, ' ').trim();
        
        // Add page title
        const title = document.title || 'Web Page';
        content = `\n${title}\n${'='.repeat(title.length)}\n\n${content}\n\nPrinted on: ${new Date().toLocaleString()}\n`;

        // Restore print button
        if (printBtn) {
            printBtn.style.display = 'block';
        }

        return content;
    }

    /**
     * Print selected text
     */
    printSelection(selection) {
        if (!this.isAvailable) {
            alert('Printer functionality not available');
            return;
        }

        const content = `\nSelected Text:\n${'='.repeat(20)}\n\n${selection}\n\nPrinted on: ${new Date().toLocaleString()}\n`;
        this.printContent(content);
    }

    /**
     * Print content directly
     */
    printContent(content, printerType = 'auto') {
        if (!this.isAvailable) {
            alert('Printer functionality not available');
            return;
        }

        try {
            const result = ElintPOSNative.printWebContent(content, printerType);
            const response = JSON.parse(result);
            
            if (response.ok) {
                // Show success message
                this.showToast('Print job sent successfully!');
            } else {
                alert('Print failed: ' + response.msg);
            }
        } catch (e) {
            console.error('Print error:', e);
            alert('Print error: ' + e.message);
        }
    }

    /**
     * Set print content in the printer selector
     */
    setPrintContent(content) {
        try {
            // This would be called from the printer selector page
            if (typeof setPrintContent === 'function') {
                setPrintContent(content);
            }
        } catch (e) {
            console.error('Error setting print content:', e);
        }
    }

    /**
     * Render an inline printer selector overlay in the current page
     */
    renderInlinePrinterSelector(content) {
        try {
            // Remove existing overlay if any
            const existing = document.getElementById('elintpos-printer-overlay');
            if (existing) existing.remove();

            const overlay = document.createElement('div');
            overlay.id = 'elintpos-printer-overlay';
            overlay.style.cssText = `
                position: fixed;
                inset: 0;
                background: rgba(0,0,0,0.45);
                display: flex;
                align-items: center;
                justify-content: center;
                z-index: 10000;
            `;

            const modal = document.createElement('div');
            modal.style.cssText = `
                width: min(640px, 92vw);
                max-height: 85vh;
                overflow: auto;
                background: #fff;
                border-radius: 12px;
                box-shadow: 0 20px 60px rgba(0,0,0,0.25);
                font-family: 'Roboto', Arial, sans-serif;
            `;

            modal.innerHTML = `
                <div style="padding:16px 20px; border-bottom:1px solid #eee; display:flex; align-items:center; justify-content:space-between;">
                    <div style="font-weight:700; font-size:16px;">Select a Printer</div>
                    <button id="elintpos-close-overlay" style="border:none; background:#f3f4f6; padding:6px 10px; border-radius:8px; cursor:pointer;">Close</button>
                </div>
                <div style="padding:16px 20px;">
                    <div id="elintpos-printer-status" style="margin-bottom:12px; color:#374151; font-size:13px;">Searching for connected printers...</div>
                    <div id="elintpos-printer-list" style="display:grid; gap:10px;"></div>
                </div>
                <div style="padding:14px 20px; border-top:1px solid #eee; display:flex; gap:10px; justify-content:flex-end;">
                    <button id="elintpos-print-cancel" style="border:1px solid #e5e7eb; background:white; padding:10px 14px; border-radius:10px; cursor:pointer;">Cancel</button>
                    <button id="elintpos-print-continue" style="border:none; background:#4f46e5; color:white; padding:10px 14px; border-radius:10px; cursor:pointer;">Print</button>
                </div>
            `;

            overlay.appendChild(modal);
            document.body.appendChild(overlay);

            const close = () => overlay.remove();
            modal.querySelector('#elintpos-close-overlay').addEventListener('click', close);
            modal.querySelector('#elintpos-print-cancel').addEventListener('click', close);

            // Load printers from native
            let printers = [];
            try {
                if (typeof ElintPOSNative !== 'undefined' && typeof ElintPOSNative.getConnectedPrinterNames === 'function') {
                    const namesJson = ElintPOSNative.getConnectedPrinterNames();
                    const parsed = JSON.parse(namesJson || '{}');
                    if (parsed && Array.isArray(parsed.printers)) {
                        printers = parsed.printers;
                    }
                }
            } catch (_) {}

            const list = modal.querySelector('#elintpos-printer-list');
            const status = modal.querySelector('#elintpos-printer-status');

            if (!printers.length) {
                status.textContent = 'No printers connected. Connect a USB/Bluetooth/LAN printer and try again.';
            } else {
                status.textContent = 'Choose a connected printer:';
                printers.forEach((p, idx) => {
                    const item = document.createElement('div');
                    item.style.cssText = `
                        border:1px solid #e5e7eb; border-radius:10px; padding:10px 12px; cursor:pointer; display:flex; align-items:center; justify-content:space-between;
                    `;
                    item.innerHTML = `
                        <div>
                            <div style="font-weight:600;">${p.name || 'Printer ' + (idx+1)}</div>
                            <div style="font-size:12px; color:#6b7280;">${p.type || ''} ${p.address ? '• ' + p.address : p.ip ? '• ' + p.ip : ''}</div>
                        </div>
                        <div style="width:10px; height:10px; border-radius:50%; ${p.connected ? 'background:#10b981;' : 'background:#ef4444;'}"></div>
                    `;
                    item.addEventListener('click', () => {
                        // mark selection
                        [...list.children].forEach(ch => ch.style.borderColor = '#e5e7eb');
                        item.style.borderColor = '#4f46e5';
                        item.dataset.selected = 'true';
                        list.dataset.selectedIndex = String(idx);
                    });
                    list.appendChild(item);
                });
            }

            modal.querySelector('#elintpos-print-continue').addEventListener('click', () => {
                const sel = list.dataset.selectedIndex ? printers[Number(list.dataset.selectedIndex)] : null;
                if (!sel) {
                    status.textContent = 'Please select a printer first.';
                    status.style.color = '#ef4444';
                    return;
                }
                try {
                    const prefer = (sel.type || 'auto').toLowerCase().includes('blue') ? 'bt' : (sel.type||'').toLowerCase().includes('usb') ? 'usb' : (sel.type||'').toLowerCase().includes('net') ? 'lan' : 'auto';
                    if (typeof ElintPOSNative !== 'undefined' && typeof ElintPOSNative.printFromWeb === 'function') {
                        ElintPOSNative.printFromWeb(content || document.body.innerText || '', prefer);
                    }
                } catch (_) {}
                close();
            });
        } catch (e) {
            console.error('Failed to render inline printer selector', e);
            alert('Unable to open printer selector: ' + (e && e.message ? e.message : e));
        }
    }

    /**
     * Show a toast message
     */
    showToast(message) {
        try {
            if (ElintPOSNative.showToast) {
                ElintPOSNative.showToast(message);
            } else {
                alert(message);
            }
        } catch (e) {
            console.error('Error showing toast:', e);
            alert(message);
        }
    }

    /**
     * Get available printers
     */
    getAvailablePrinters() {
        if (!this.isAvailable) {
            return Promise.reject('Printer functionality not available');
        }

        try {
            const statusJson = ElintPOSNative.getPrinterConnectionStatus();
            const status = JSON.parse(statusJson);
            return Promise.resolve(status);
        } catch (e) {
            return Promise.reject('Error getting printer status: ' + e.message);
        }
    }

    /**
     * Check if any printer is connected
     */
    isPrinterConnected() {
        if (!this.isAvailable) {
            return false;
        }

        try {
            const statusJson = ElintPOSNative.getPrinterStatus();
            const status = JSON.parse(statusJson);
            return status.any === true;
        } catch (e) {
            return false;
        }
    }

    /**
     * Update print settings
     */
    updatePrintSettings(settings) {
        this.printSettings = { ...this.printSettings, ...settings };
    }

    /**
     * Get current print settings
     */
    getPrintSettings() {
        return { ...this.printSettings };
    }

    /**
     * Monitor for popups, modals, and alerts to add print buttons
     */
    monitorForDialogs() {
        // Use MutationObserver to watch for new DOM elements
        const observer = new MutationObserver((mutations) => {
            mutations.forEach((mutation) => {
                mutation.addedNodes.forEach((node) => {
                    if (node.nodeType === Node.ELEMENT_NODE) {
                        this.checkAndAddPrintButton(node);
                    }
                });
            });
        });

        // Start observing
        observer.observe(document.body, {
            childList: true,
            subtree: true
        });

        // Also monitor for common dialog libraries
        this.monitorCommonDialogLibraries();
    }

    /**
     * Monitor for common dialog libraries (Bootstrap, jQuery UI, etc.)
     */
    monitorCommonDialogLibraries() {
        // Monitor for Bootstrap modals
        if (typeof $ !== 'undefined' && $.fn.modal) {
            $(document).on('shown.bs.modal', '.modal', (e) => {
                this.addPrintButtonToElement(e.target);
            });
        }

        // Monitor for jQuery UI dialogs
        if (typeof $ !== 'undefined' && $.fn.dialog) {
            $(document).on('dialogopen', (e) => {
                this.addPrintButtonToElement(e.target);
            });
        }

        // Monitor for SweetAlert2
        if (typeof Swal !== 'undefined') {
            const originalSwal = Swal.fire;
            Swal.fire = (...args) => {
                const result = originalSwal.apply(Swal, args);
                result.then(() => {
                    setTimeout(() => {
                        const swalContainer = document.querySelector('.swal2-container');
                        if (swalContainer) {
                            this.addPrintButtonToElement(swalContainer);
                        }
                    }, 100);
                });
                return result;
            };
        }

        // Monitor for custom alert/confirm functions
        this.overrideAlertFunctions();
    }

    /**
     * Override alert, confirm, and prompt functions to add print buttons
     */
    overrideAlertFunctions() {
        const originalAlert = window.alert;
        const originalConfirm = window.confirm;
        const originalPrompt = window.prompt;

        window.alert = (message) => {
            const result = originalAlert(message);
            setTimeout(() => {
                this.addPrintButtonToAlert(message);
            }, 100);
            return result;
        };

        window.confirm = (message) => {
            const result = originalConfirm(message);
            setTimeout(() => {
                this.addPrintButtonToAlert(message);
            }, 100);
            return result;
        };

        window.prompt = (message, defaultText) => {
            const result = originalPrompt(message, defaultText);
            setTimeout(() => {
                this.addPrintButtonToAlert(message);
            }, 100);
            return result;
        };
    }

    /**
     * Check if an element is a dialog and add print button
     */
    checkAndAddPrintButton(element) {
        // Check if element is a dialog/modal/popup
        if (this.isDialogElement(element)) {
            this.addPrintButtonToElement(element);
        }

        // Check child elements
        const dialogs = element.querySelectorAll('.modal, .popup, .dialog, .alert, .invoice, .receipt, [role="dialog"], [role="alertdialog"]');
        dialogs.forEach(dialog => {
            this.addPrintButtonToElement(dialog);
        });
    }

    /**
     * Check if an element is a dialog/modal/popup
     */
    isDialogElement(element) {
        const dialogClasses = ['modal', 'popup', 'dialog', 'alert', 'invoice', 'receipt'];
        const dialogRoles = ['dialog', 'alertdialog'];
        
        // Check classes
        for (const className of dialogClasses) {
            if (element.classList.contains(className)) {
                return true;
            }
        }

        // Check role attribute
        if (dialogRoles.includes(element.getAttribute('role'))) {
            return true;
        }

        // Check for common dialog patterns
        if (element.style.position === 'fixed' || element.style.position === 'absolute') {
            if (element.style.zIndex > 1000) {
                return true;
            }
        }

        return false;
    }

    /**
     * Add print button to existing dialogs on page load
     */
    addPrintButtonsToExistingDialogs() {
        const dialogs = document.querySelectorAll('.modal, .popup, .dialog, .alert, .invoice, .receipt, [role="dialog"], [role="alertdialog"]');
        dialogs.forEach(dialog => {
            this.addPrintButtonToElement(dialog);
        });
    }

    /**
     * Add print button to a specific element
     */
    addPrintButtonToElement(element) {
        // Check if print button already exists in this element
        if (element.querySelector('.elintpos-print-btn')) {
            return;
        }

        // Only add to visible elements
        if (element.offsetParent === null) {
            return;
        }

        const printBtn = this.createPrintButton();
        
        // Try to find a good place to insert the button
        const buttonContainer = this.findButtonContainer(element);
        if (buttonContainer) {
            buttonContainer.appendChild(printBtn);
        } else {
            // Fallback: add to the element itself
            element.appendChild(printBtn);
        }

        // Add some styling to make it fit well
        this.stylePrintButtonForElement(printBtn, element);
        
        // Update button with printer information
        this.updatePrintButtonWithPrinterInfo(printBtn);
        
        // Add printer status indicator to the dialog
        this.addPrinterStatusIndicator(element);
    }

    /**
     * Create a print button element
     */
    createPrintButton() {
        const printBtn = document.createElement('button');
        printBtn.className = 'elintpos-print-btn';
        printBtn.innerHTML = '🖨️ Print Invoice';
        printBtn.type = 'button';
        
        printBtn.style.cssText = `
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            border: none;
            padding: 8px 16px;
            border-radius: 6px;
            cursor: pointer;
            font-size: 12px;
            font-weight: 600;
            margin: 5px;
            transition: all 0.3s ease;
            box-shadow: 0 2px 8px rgba(102, 126, 234, 0.3);
        `;

        // Add hover effect
        printBtn.addEventListener('mouseenter', function() {
            this.style.transform = 'translateY(-1px)';
            this.style.boxShadow = '0 4px 12px rgba(102, 126, 234, 0.4)';
        });

        printBtn.addEventListener('mouseleave', function() {
            this.style.transform = 'translateY(0)';
            this.style.boxShadow = '0 2px 8px rgba(102, 126, 234, 0.3)';
        });

        // Add click event
        printBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            this.printElementContent(element);
        });

        return printBtn;
    }

    /**
     * Find the best container for the print button
     */
    findButtonContainer(element) {
        // Look for common button containers
        const buttonSelectors = [
            '.modal-footer',
            '.dialog-footer',
            '.popup-footer',
            '.alert-footer',
            '.button-group',
            '.btn-group',
            '.actions',
            '.controls'
        ];

        for (const selector of buttonSelectors) {
            const container = element.querySelector(selector);
            if (container) {
                return container;
            }
        }

        // Look for existing buttons to place near them
        const existingButtons = element.querySelectorAll('button, .btn, input[type="button"], input[type="submit"]');
        if (existingButtons.length > 0) {
            return existingButtons[0].parentNode;
        }

        return null;
    }

    /**
     * Style the print button for the specific element
     */
    stylePrintButtonForElement(printBtn, element) {
        // Adjust size based on element type
        if (element.classList.contains('modal') || element.classList.contains('popup')) {
            printBtn.style.fontSize = '14px';
            printBtn.style.padding = '10px 20px';
        } else if (element.classList.contains('alert')) {
            printBtn.style.fontSize = '11px';
            printBtn.style.padding = '6px 12px';
        }

        // Position the button
        const elementRect = element.getBoundingClientRect();
        if (elementRect.width < 300) {
            printBtn.style.width = '100%';
            printBtn.style.margin = '5px 0';
        }
    }

    /**
     * Print the content of a specific element
     */
    printElementContent(element) {
        const content = this.extractElementContent(element);
        this.printContent(content);
    }

    /**
     * Extract content from an element for printing
     */
    extractElementContent(element) {
        // Clone the element to avoid modifying the original
        const clone = element.cloneNode(true);
        
        // Remove the print button from the clone
        const printBtn = clone.querySelector('.elintpos-print-btn');
        if (printBtn) {
            printBtn.remove();
        }

        // Extract text content
        let content = clone.innerText || clone.textContent;
        
        // Clean up the content
        content = content.replace(/\s+/g, ' ').trim();
        
        // Add header
        const title = this.extractElementTitle(element);
        content = `\n${title}\n${'='.repeat(title.length)}\n\n${content}\n\nPrinted on: ${new Date().toLocaleString()}\n`;

        return content;
    }

    /**
     * Extract title from an element
     */
    extractElementTitle(element) {
        // Try to find a title
        const titleSelectors = [
            '.modal-title',
            '.dialog-title',
            '.popup-title',
            '.alert-title',
            'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
            '.title',
            '[data-title]'
        ];

        for (const selector of titleSelectors) {
            const titleEl = element.querySelector(selector);
            if (titleEl) {
                return titleEl.innerText || titleEl.textContent;
            }
        }

        // Fallback to element class or id
        if (element.className) {
            return element.className.split(' ')[0];
        }
        if (element.id) {
            return element.id;
        }

        return 'Document';
    }

    /**
     * Add print button to alert messages
     */
    addPrintButtonToAlert(message) {
        // This is a fallback for native alerts
        // Most modern web apps use custom alert libraries
        console.log('Alert message that could be printed:', message);
    }

    /**
     * Update print button with connected printer information
     */
    updatePrintButtonWithPrinterInfo(printBtn) {
        if (!this.isAvailable) {
            printBtn.innerHTML = '🖨️ Print (No Printer)';
            printBtn.disabled = true;
            printBtn.style.opacity = '0.5';
            return;
        }

        // Get printer status
        this.getAvailablePrinters()
            .then(status => {
                const connectedPrinters = [];
                
                if (status.usb) {
                    connectedPrinters.push('USB Printer');
                }
                if (status.bluetooth) {
                    connectedPrinters.push('Bluetooth Printer');
                }
                if (status.lan) {
                    connectedPrinters.push('LAN Printer');
                }

                if (connectedPrinters.length > 0) {
                    const printerText = connectedPrinters.length === 1 
                        ? connectedPrinters[0] 
                        : `${connectedPrinters.length} Printers`;
                    printBtn.innerHTML = `🖨️ Print (${printerText})`;
                    printBtn.disabled = false;
                    printBtn.style.opacity = '1';
                } else {
                    printBtn.innerHTML = '🖨️ Print (No Printer)';
                    printBtn.disabled = true;
                    printBtn.style.opacity = '0.5';
                }
            })
            .catch(error => {
                printBtn.innerHTML = '🖨️ Print (Error)';
                printBtn.disabled = true;
                printBtn.style.opacity = '0.5';
                console.error('Error getting printer status:', error);
            });
    }

    /**
     * Update all existing print buttons with current printer information
     */
    updateAllPrintButtons() {
        const printButtons = document.querySelectorAll('.elintpos-print-btn');
        printButtons.forEach(btn => {
            this.updatePrintButtonWithPrinterInfo(btn);
        });
    }

    /**
     * Add printer status indicator to dialogs
     */
    addPrinterStatusIndicator(element) {
        // Check if status indicator already exists
        if (element.querySelector('.elintpos-printer-status')) {
            return;
        }

        const statusIndicator = document.createElement('div');
        statusIndicator.className = 'elintpos-printer-status';
        statusIndicator.style.cssText = `
            font-size: 11px;
            color: #666;
            margin: 5px 0;
            padding: 5px 10px;
            background: #f8f9fa;
            border-radius: 4px;
            border-left: 3px solid #6c757d;
        `;

        // Try to find a good place to insert the status indicator
        const header = element.querySelector('.modal-header, .popup-header, .alert-header, .invoice-header, .receipt-header');
        if (header) {
            header.appendChild(statusIndicator);
        } else {
            // Fallback: add to the element itself
            element.insertBefore(statusIndicator, element.firstChild);
        }

        // Update status
        this.updatePrinterStatusIndicator(statusIndicator);
    }

    /**
     * Update printer status indicator
     */
    updatePrinterStatusIndicator(statusIndicator) {
        if (!this.isAvailable) {
            statusIndicator.innerHTML = '⚠️ No printer available';
            statusIndicator.style.borderLeftColor = '#dc3545';
            return;
        }

        this.getAvailablePrinters()
            .then(status => {
                const connectedPrinters = [];
                
                if (status.usb) {
                    connectedPrinters.push('USB');
                }
                if (status.bluetooth) {
                    connectedPrinters.push('Bluetooth');
                }
                if (status.lan) {
                    connectedPrinters.push('LAN');
                }

                if (connectedPrinters.length > 0) {
                    statusIndicator.innerHTML = `🖨️ Connected: ${connectedPrinters.join(', ')}`;
                    statusIndicator.style.borderLeftColor = '#28a745';
                } else {
                    statusIndicator.innerHTML = '⚠️ No printer connected';
                    statusIndicator.style.borderLeftColor = '#ffc107';
                }
            })
            .catch(error => {
                statusIndicator.innerHTML = '❌ Printer status unknown';
                statusIndicator.style.borderLeftColor = '#dc3545';
                console.error('Error getting printer status:', error);
            });
    }

    /**
     * Set up periodic printer status updates
     */
    setupPrinterStatusUpdates() {
        // Update printer status every 5 seconds
        setInterval(() => {
            this.updateAllPrintButtons();
            this.updateAllPrinterStatusIndicators();
        }, 5000);

        // Also update when the page becomes visible (user switches back to app)
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) {
                this.updateAllPrintButtons();
                this.updateAllPrinterStatusIndicators();
            }
        });
    }

    /**
     * Update all printer status indicators
     */
    updateAllPrinterStatusIndicators() {
        const statusIndicators = document.querySelectorAll('.elintpos-printer-status');
        statusIndicators.forEach(indicator => {
            this.updatePrinterStatusIndicator(indicator);
        });
    }

    /**
     * Force refresh of all printer information
     */
    refreshPrinterStatus() {
        this.updateAllPrintButtons();
        this.updateAllPrinterStatusIndicators();
    }
}

// Auto-initialize when the page loads
document.addEventListener('DOMContentLoaded', function() {
    // Initialize ElintPOS Printer integration
    window.elintposPrinter = new ElintPOSPrinter();
    
    // Add some styling for better integration
    const style = document.createElement('style');
    style.textContent = `
        @media print {
            #elintpos-print-btn {
                display: none !important;
            }
        }
        
        .elintpos-printable {
            border: 2px dashed #667eea;
            padding: 10px;
            margin: 10px 0;
            border-radius: 8px;
            background: #f8f9ff;
        }
        
        .elintpos-printable:hover {
            background: #f0f2ff;
            border-color: #5a6fd8;
        }
    `;
    document.head.appendChild(style);
});

// Export for use in other scripts
if (typeof module !== 'undefined' && module.exports) {
    module.exports = ElintPOSPrinter;
}
