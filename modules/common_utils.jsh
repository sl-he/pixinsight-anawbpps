/*
 * ANAWBPPS - Common Utilities
 * Centralized utility functions used across all modules
 *
 * Copyright (C) 2024-2025 sl-he
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Repository: https://github.com/sl-he/pixinsight-anawbpps
 */

// ============================================================================
// Path Utilities
// ============================================================================

/**
 * Normalize path: replace backslashes with forward slashes, remove trailing slash
 * @param {string} p - Path to normalize
 * @returns {string} Normalized path
 */
function CU_norm(p){
    if (!p) return "";
    var s = String(p).replace(/\\/g, '/');
    // Remove trailing slash
    if (s.length > 1 && s.charAt(s.length-1) == '/'){
        s = s.substring(0, s.length-1);
    }
    return s;
}

/**
 * Extract basename (filename without directory path)
 * @param {string} p - Full file path
 * @returns {string} Filename only
 */
function CU_basename(p){
    if (!p) return "";
    var s = String(p);
    var i = s.lastIndexOf('/');
    if (i < 0) i = s.lastIndexOf('\\');
    return (i >= 0) ? s.substring(i+1) : s;
}

/**
 * Remove file extension from filename
 * @param {string} s - Filename or path
 * @returns {string} Filename without extension
 */
function CU_noext(s){
    if (!s) return "";
    var str = String(s);
    var i = str.lastIndexOf('.');
    if (i > 0 && i > str.lastIndexOf('/') && i > str.lastIndexOf('\\')){
        return str.substring(0, i);
    }
    return str;
}

// ============================================================================
// Type Conversion Utilities
// ============================================================================

/**
 * Safe integer conversion with default value
 * @param {*} val - Value to convert
 * @param {number} def - Default value if conversion fails (default: 0)
 * @returns {number} Integer value
 */
function CU_toInt(val, def){
    if (val == null || val == undefined) return (def != null) ? def : 0;
    var n = parseInt(val, 10);
    return isNaN(n) ? ((def != null) ? def : 0) : n;
}

/**
 * Safe float conversion with default value
 * @param {*} val - Value to convert
 * @param {number} def - Default value if conversion fails (default: 0.0)
 * @returns {number} Float value
 */
function CU_toFloat(val, def){
    if (val == null || val == undefined) return (def != null) ? def : 0.0;
    var n = parseFloat(val);
    return isNaN(n) ? ((def != null) ? def : 0.0) : n;
}

/**
 * Get first non-null value from array of candidates
 * @param {Array} arr - Array of candidate values
 * @returns {*} First non-null value or null
 */
function CU_first(arr){
    if (!arr || !arr.length) return null;
    for (var i=0; i<arr.length; i++){
        var v = arr[i];
        if (v != null && v != undefined && v != "") return v;
    }
    return null;
}

// ============================================================================
// String Utilities
// ============================================================================

/**
 * Convert string to uppercase, return null if empty
 * @param {string} s - String to convert
 * @returns {string|null} Uppercase string or null
 */
function CU_upperOrNull(s){
    if (!s || s == "") return null;
    return String(s).toUpperCase();
}

/**
 * Clean readout mode string (replace spaces with underscores)
 * @param {string} r - Readout mode string
 * @returns {string} Cleaned readout mode
 */
function CU_cleanReadout(r){
    if (!r) return "";
    return String(r).replace(/ /g, '_');
}

/**
 * Create binning string in format "NxM"
 * @param {number} x - X binning
 * @param {number} y - Y binning (optional, defaults to x)
 * @returns {string} Binning string like "1x1" or "2x2"
 */
function CU_mkBinning(x, y){
    var xb = CU_toInt(x, 1);
    var yb = (y != null && y != undefined) ? CU_toInt(y, 1) : xb;
    return xb + "x" + yb;
}

// ============================================================================
// Time Formatting Utilities
// ============================================================================

/**
 * Format elapsed time in HH:MM:SS.hh format
 * @param {number} sec - Time in seconds (can be fractional)
 * @returns {string} Formatted time string
 */
function CU_fmtHMS(sec){
    if (!sec || sec < 0) return "00:00:00.00";
    var h = Math.floor(sec / 3600);
    var m = Math.floor((sec % 3600) / 60);
    var s = sec % 60;
    var hh = (h < 10) ? ("0" + h) : String(h);
    var mm = (m < 10) ? ("0" + m) : String(m);
    var ss = (s < 10) ? ("0" + s.toFixed(2)) : s.toFixed(2);
    return hh + ":" + mm + ":" + ss;
}

/**
 * Format elapsed time from milliseconds to HH:MM:SS.hh
 * @param {number} ms - Time in milliseconds
 * @returns {string} Formatted time string
 */
function CU_fmtElapsedMS(ms){
    if (!ms || ms < 0) return "00:00:00.00";
    return CU_fmtHMS(ms / 1000.0);
}

// ============================================================================
// Date/Time Parsing Utilities
// ============================================================================

/**
 * Extract date part from ISO datetime string (YYYY-MM-DD from YYYY-MM-DDTHH:MM:SS)
 * @param {string} dt - ISO datetime string
 * @returns {string} Date part (YYYY-MM-DD) or empty string
 */
function CU_extractDateOnly(dt){
    if (!dt) return "";
    var s = String(dt).trim();
    var i = s.indexOf('T');
    return (i > 0) ? s.substring(0, i) : s;
}

// ============================================================================
// SubframeSelector Key Building Utilities
// ============================================================================

/**
 * Sanitize string for use in SS key (replace spaces/special chars with underscore)
 * @param {string} s - String to sanitize
 * @returns {string} Sanitized string
 */
function CU_sanitizeKey(s){
    if (!s) return "";
    return String(s).replace(/[^a-zA-Z0-9_-]/g, '_');
}

/**
 * Build simple SS key from setup, object, filter
 * @param {string} setup - Setup name
 * @param {string} object - Object name
 * @param {string} filter - Filter name
 * @returns {string} Simple key like "SETUP_OBJECT_FILTER"
 */
function CU_buildSimpleSSKey(setup, object, filter){
    var s = CU_sanitizeKey(setup || "");
    var o = CU_sanitizeKey(object || "");
    var f = CU_sanitizeKey(filter || "");
    return s + "_" + o + "_" + f;
}

/**
 * Build full SS key from frame data (includes exposure time)
 * @param {Object} frame - Frame object with setup, object, filter, exposureSec properties
 * @returns {string} Full key like "SETUP_OBJECT_FILTER_300s"
 */
function CU_buildFullSSKey(frame){
    if (!frame) return "";
    var s = CU_sanitizeKey(frame.setup || "");
    var o = CU_sanitizeKey(frame.object || "");
    var f = CU_sanitizeKey(frame.filter || "");
    var e = CU_toInt(frame.exposureSec, 0);
    return s + "_" + o + "_" + f + "_" + e + "s";
}

// ============================================================================
// FITS Keyword Reading Utility
// ============================================================================

/**
 * Read FITS keywords from file
 * @param {string} fullPath - Full path to FITS/XISF file
 * @returns {Array|null} Array of FITSKeyword objects or null on error
 */
function CU_readFitsKeywords(fullPath){
    if (!fullPath) return null;

    try {
        var ff = new FileFormat("xisf", true, false);
        if (!ff.isValid) ff = new FileFormat("fits", true, false);
        if (!ff.isValid) return null;

        var f = new FileFormatInstance(ff);
        if (!f.open(fullPath, "r")){
            f.close();
            return null;
        }

        var keywords = f.keywords;
        f.close();
        return keywords;
    } catch(e){
        Console.warningln("[CU] readFitsKeywords error: " + e);
        return null;
    }
}
