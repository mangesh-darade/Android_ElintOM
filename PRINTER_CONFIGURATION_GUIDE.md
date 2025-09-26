# Printer Configuration System Guide

This guide explains how to use the comprehensive printer configuration system that has been added to the ElintPOS Android app.

## Overview

The printer configuration system provides:
- **Printer Profile Management**: Create, edit, and manage multiple printer configurations
- **Multiple Printer Types**: Support for Bluetooth, USB, LAN, Epson SDK, XPrinter SDK, and Vendor SDK
- **Advanced Configuration**: Paper width, margins, scaling, character sets, and more
- **Testing & Validation**: Comprehensive testing and diagnostic tools
- **Persistence**: All configurations are saved and restored between app sessions

## Components

### 1. PrinterConfigManager
The core class that manages all printer profiles and configurations.

**Key Features:**
- Create, read, update, delete printer profiles
- Profile validation and type-specific settings
- Import/export functionality
- Default profile management
- Statistics and reporting

### 2. PrinterTester
Comprehensive testing and validation utility for printer configurations.

**Key Features:**
- Test individual printer profiles
- Run system diagnostics
- Validate profile configurations
- Generate test reports
- Connection testing with timeouts

### 3. Enhanced Printer Management UI
An improved HTML interface for managing printer configurations.

**Key Features:**
- Visual profile management
- Real-time configuration editing
- Connection-specific settings
- Test printing capabilities
- Import/export functionality

## Printer Types Supported

### 1. Bluetooth Printers
- **Connection**: MAC address
- **Features**: Auto-pairing, connection testing
- **Use Case**: Mobile POS, wireless printing

### 2. USB Printers
- **Connection**: Device name, Vendor ID, Product ID
- **Features**: USB device detection, permission handling
- **Use Case**: Desktop POS, direct connection

### 3. LAN/WiFi Printers
- **Connection**: IP address and port
- **Features**: Network discovery, timeout configuration
- **Use Case**: Networked POS systems

### 4. Epson SDK
- **Connection**: SDK-specific configuration
- **Features**: Native Epson printer support
- **Use Case**: Epson-specific features

### 5. XPrinter SDK
- **Connection**: SDK-specific configuration
- **Features**: Native XPrinter support
- **Use Case**: XPrinter-specific features

### 6. Vendor SDK
- **Connection**: Generic vendor configuration
- **Features**: Third-party SDK integration
- **Use Case**: Custom printer integrations

## Configuration Options

### Basic Settings
- **Profile Name**: Human-readable name for the profile
- **Printer Type**: Type of printer connection
- **Enabled**: Whether the profile is active
- **Auto-connect**: Connect automatically on startup

### Paper Configuration
- **Paper Width**: 58mm (384 dots), 80mm (576 dots), 112mm (832 dots)
- **Left Margin**: Left margin in dots (0-200)
- **Right Margin**: Right margin in dots (0-200)
- **Line Spacing**: Spacing between lines (0-100)

### Print Scaling
- **Width Multiplier**: Horizontal scaling (0-7)
- **Height Multiplier**: Vertical scaling (0-7)
- **Character Set**: UTF-8, ASCII, ISO-8859-1, Windows-1252

### Connection Settings
- **Timeout**: Connection timeout in milliseconds (1000-30000)
- **Connection Parameters**: Type-specific connection details

## JavaScript API

### Profile Management
```javascript
// Get all printer profiles
const profiles = JSON.parse(ElintPOSNative.getAllPrinterProfiles());

// Get a specific profile
const profile = JSON.parse(ElintPOSNative.getPrinterProfile(profileId));

// Save a profile
const result = JSON.parse(ElintPOSNative.savePrinterProfile(profileJson));

// Delete a profile
const result = JSON.parse(ElintPOSNative.deletePrinterProfile(profileId));

// Duplicate a profile
const result = JSON.parse(ElintPOSNative.duplicatePrinterProfile(profileId, newName));

// Set as default
const result = JSON.parse(ElintPOSNative.setPrinterProfileAsDefault(profileId));
```

### Testing & Validation
```javascript
// Test a printer profile
const result = JSON.parse(ElintPOSNative.testPrinterProfile(profileId, testText));

// Run comprehensive test
const result = JSON.parse(ElintPOSNative.testPrinterProfileComprehensive(profileId, testText));

// Validate profile configuration
const validation = JSON.parse(ElintPOSNative.validatePrinterProfile(profileId));

// Run system diagnostics
const diagnostics = JSON.parse(ElintPOSNative.runPrinterDiagnostics());

// Test all profiles
const results = JSON.parse(ElintPOSNative.testAllPrinterProfiles());

// Get test report
const report = JSON.parse(ElintPOSNative.getPrinterTestReport(profileId));
```

### Profile Operations
```javascript
// Get profiles by type
const profiles = JSON.parse(ElintPOSNative.getPrinterProfilesByType('bluetooth'));

// Get default profile for type
const profile = JSON.parse(ElintPOSNative.getDefaultPrinterProfile('usb'));

// Get last used profile
const profile = JSON.parse(ElintPOSNative.getLastUsedPrinterProfile());

// Set last used profile
const result = JSON.parse(ElintPOSNative.setLastUsedPrinterProfile(profileId));

// Get configuration statistics
const stats = JSON.parse(ElintPOSNative.getPrinterConfigStatistics());
```

### Import/Export
```javascript
// Export all profiles
const profiles = JSON.parse(ElintPOSNative.exportPrinterProfiles());

// Import profiles
const result = JSON.parse(ElintPOSNative.importPrinterProfiles(profilesJson));

// Clear all profiles
const result = JSON.parse(ElintPOSNative.clearAllPrinterProfiles());
```

## Usage Examples

### Creating a Bluetooth Printer Profile
```javascript
const profile = {
    name: "Bluetooth Receipt Printer",
    type: "bluetooth",
    enabled: true,
    paperWidth: 576, // 80mm
    leftMargin: 0,
    rightMargin: 0,
    lineSpacing: 30,
    widthMultiplier: 0,
    heightMultiplier: 0,
    charset: "UTF-8",
    autoConnect: true,
    timeout: 5000,
    connectionParams: {
        mac: "00:11:22:33:44:55"
    },
    isDefault: false
};

const result = JSON.parse(ElintPOSNative.savePrinterProfile(JSON.stringify(profile)));
```

### Creating a LAN Printer Profile
```javascript
const profile = {
    name: "Network Receipt Printer",
    type: "lan",
    enabled: true,
    paperWidth: 576,
    leftMargin: 0,
    rightMargin: 0,
    lineSpacing: 30,
    widthMultiplier: 0,
    heightMultiplier: 0,
    charset: "UTF-8",
    autoConnect: true,
    timeout: 5000,
    connectionParams: {
        ip: "192.168.1.100",
        port: "9100"
    },
    isDefault: false
};

const result = JSON.parse(ElintPOSNative.savePrinterProfile(JSON.stringify(profile)));
```

### Testing a Printer Profile
```javascript
// Test with default text
const result = JSON.parse(ElintPOSNative.testPrinterProfile(profileId));

// Test with custom text
const result = JSON.parse(ElintPOSNative.testPrinterProfile(profileId, "Custom Test Text"));

// Run comprehensive test
const result = JSON.parse(ElintPOSNative.testPrinterProfileComprehensive(profileId, "Comprehensive Test"));
```

## Accessing the Printer Management UI

To access the enhanced printer management interface:

```javascript
// Open printer management page
ElintPOSNative.openPrinterManagement();
```

This will load the comprehensive printer management HTML interface where you can:
- View and manage all printer profiles
- Create new profiles with visual forms
- Test printer connections
- Configure advanced settings
- Import/export profiles
- Run diagnostics

## Best Practices

### 1. Profile Naming
- Use descriptive names that indicate the printer type and location
- Examples: "Kitchen Bluetooth Printer", "Main Receipt Printer", "Backup USB Printer"

### 2. Default Profiles
- Set one default profile per printer type
- Use default profiles for automatic printer selection

### 3. Testing
- Always test profiles after creation or modification
- Use the validation API to check configuration before saving
- Run diagnostics regularly to ensure system health

### 4. Backup
- Export profiles regularly for backup
- Store exported profiles in a safe location
- Test import functionality periodically

### 5. Error Handling
- Always check the `ok` field in API responses
- Handle errors gracefully with appropriate user feedback
- Log errors for debugging purposes

## Troubleshooting

### Common Issues

1. **Profile Not Found**
   - Check if the profile ID is correct
   - Ensure the profile hasn't been deleted

2. **Connection Failed**
   - Verify connection parameters (MAC, IP, etc.)
   - Check if the printer is powered on and accessible
   - Ensure proper permissions are granted

3. **Print Test Failed**
   - Check printer paper and ink/ribbon
   - Verify printer is not in error state
   - Test with different print content

4. **SDK Not Available**
   - Ensure the required SDK AAR files are in the libs directory
   - Check if the SDK classes are properly loaded

### Diagnostic Tools

Use the diagnostic tools to identify issues:

```javascript
// Run full system diagnostics
const diagnostics = JSON.parse(ElintPOSNative.runPrinterDiagnostics());
console.log("System Diagnostics:", diagnostics);

// Validate specific profile
const validation = JSON.parse(ElintPOSNative.validatePrinterProfile(profileId));
if (!validation.validation.valid) {
    console.log("Validation Errors:", validation.validation.errors);
}
```

## Integration with Existing Code

The printer configuration system integrates seamlessly with existing printing code:

```javascript
// Use profile-based printing
const result = JSON.parse(ElintPOSNative.testPrinterProfile(profileId, textToPrint));

// Apply profile settings to current print configuration
const profile = JSON.parse(ElintPOSNative.getPrinterProfile(profileId));
if (profile.ok) {
    // Apply settings and print
    ElintPOSNative.setDefaultPrintConfig(
        profile.profile.leftMargin,
        profile.profile.rightMargin,
        profile.profile.lineSpacing,
        profile.profile.widthMultiplier,
        profile.profile.heightMultiplier,
        profile.profile.pageWidthDots,
        0
    );
    ElintPOSNative.printFromWeb(textToPrint, 'auto');
}
```

This comprehensive printer configuration system provides everything needed to manage multiple printers, test connections, and ensure reliable printing in your POS application.
