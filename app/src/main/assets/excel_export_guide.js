/**
 * ElintPOS Excel (CSV) Export Guide
 */

function csvEscape(value) {
  if (value == null) return '';
  const s = String(value).replaceAll('"', '""');
  return /[",\n\r]/.test(s) ? `"${s}"` : s;
}

function jsonArrayToCsvString(arr) {
  if (!Array.isArray(arr) || arr.length === 0) return '';
  // Collect headers
  const headers = Array.from(arr.reduce((set, obj) => {
    Object.keys(obj || {}).forEach(k => set.add(k));
    return set;
  }, new Set()));

  const lines = [];
  lines.push(headers.map(csvEscape).join(','));
  arr.forEach(row => {
    const cells = headers.map(h => csvEscape(row?.[h] ?? ''));
    lines.push(cells.join(','));
  });
  return lines.join('\n');
}

// Save ready CSV content
function saveCsv(csvString, fileName = null) {
  try {
    return JSON.parse(ElintPOSNative.saveCsv(csvString, fileName));
  } catch (e) {
    return { ok: false, msg: e.message };
  }
}

// Convert JSON array to CSV and save
function exportJsonArrayToCsv(jsonArray, fileName = null) {
  try {
    const result = JSON.parse(ElintPOSNative.jsonArrayToCsv(JSON.stringify(jsonArray), fileName));
    return result;
  } catch (e) {
    return { ok: false, msg: e.message };
  }
}

// For containers like { items: [...] }
function exportObjectArrayFieldToCsv(obj, arrayField, fileName = null) {
  try {
    const result = JSON.parse(ElintPOSNative.jsonObjectArrayFieldToCsv(JSON.stringify(obj), arrayField, fileName));
    return result;
  } catch (e) {
    return { ok: false, msg: e.message };
  }
}

function openCsv(path) {
  try { return JSON.parse(ElintPOSNative.openCsv(path)); } catch (e) { return { ok: false, msg: e.message }; }
}

function shareCsv(path) {
  try { return JSON.parse(ElintPOSNative.shareCsv(path)); } catch (e) { return { ok: false, msg: e.message }; }
}

function listCsv(prefix = 'ElintPOS_') {
  try { return JSON.parse(ElintPOSNative.listCsv(prefix)); } catch (e) { return { ok: false, msg: e.message }; }
}

function deleteCsv(path) {
  try { return JSON.parse(ElintPOSNative.deleteCsv(path)); } catch (e) { return { ok: false, msg: e.message }; }
}

// Example: Export sales table
function exportSalesToExcel(rows) {
  const fileName = `Sales_${new Date().toISOString().slice(0,10)}.csv`;
  return exportJsonArrayToCsv(rows, fileName);
}

window.ElintPOSExcel = {
  csvEscape,
  jsonArrayToCsvString,
  saveCsv,
  exportJsonArrayToCsv,
  exportObjectArrayFieldToCsv,
  openCsv,
  shareCsv,
  listCsv,
  deleteCsv,
  exportSalesToExcel
};


