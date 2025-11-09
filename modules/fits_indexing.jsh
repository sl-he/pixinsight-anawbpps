/*
 * ANAWBPPS - Unified FITS indexing module
 * Indexes light frames, calibration frames, and master frames
 *
 * Replaces: lights_index.jsh, lights_parse.jsh, masters_index.jsh, masters_parse.jsh
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

#ifndef __ANAWBPPS_FITS_INDEXING_JSH
#define __ANAWBPPS_FITS_INDEXING_JSH

#include "common_utils.jsh"

/* ============================================================================
 * Performance Monitoring (optional, for testing)
 * ============================================================================ */
var _FI_HEADER_READ_COUNT = 0;
var _FI_BYTES_READ = 0;

function FI_resetHeaderReadCount(){
    _FI_HEADER_READ_COUNT = 0;
    _FI_BYTES_READ = 0;
}

function FI_getHeaderReadCount(){
    return _FI_HEADER_READ_COUNT;
}

function FI_getBytesRead(){
    return _FI_BYTES_READ;
}

/* ============================================================================
 * LEVEL 1: Low-level Helpers (private, prefix _fi_)
 * ============================================================================ */

/* -------- Path utilities -------- */

// _fi_norm removed - now using CU_norm from common_utils.jsh

/**
 * Get file extension (lowercase, without dot)
 */
function _fi_ext(path){
    var s = String(path || "");
    var i = s.lastIndexOf('.');
    if (i < 0) return "";
    return s.substring(i+1).toLowerCase();
}

/**
 * Check if file is FITS-like (fits, fit, xisf)
 */
function _fi_isFitsLike(path){
    var e = _fi_ext(path);
    return (e == "fits" || e == "fit" || e == "xisf");
}

// _fi_basename removed - now using CU_basename from common_utils.jsh

/**
 * Check if path is a directory
 */
function _fi_isDir(path){
    return File.directoryExists(path);
}

/**
 * Check if path is a file
 */
function _fi_isFile(path){
    return File.exists(path);
}

/* -------- Type conversion -------- */

// _fi_toInt and _fi_toFloat removed - now using CU_toInt/CU_toFloat from common_utils.jsh

/**
 * Number or null (for compatibility with masters_parse)
 */
function _fi_numOrNull(v){
    return CU_toFloat(v);
}

/* -------- String utilities -------- */

// _fi_upperOrNull, _fi_cleanReadout, _fi_mkBinning removed - now using CU_* from common_utils.jsh

/**
 * Normalize filter name (HA → Ha, OIII → OIII, SII → SII)
 */
function _fi_normFilter(f){
    if (!f) return null;
    var s = String(f).trim();
    if (!s.length) return null;
    var u = s.toUpperCase();

    // Common narrowband filters
    if (u == "HA" || u == "H-ALPHA" || u == "HALPHA") return "Ha";
    if (u == "OIII" || u == "O-III" || u == "O3") return "OIII";
    if (u == "SII" || u == "S-II" || u == "S2") return "SII";
    if (u == "NII" || u == "N-II" || u == "N2") return "NII";

    // Broadband filters
    if (u == "L" || u == "LUM" || u == "LUMEN" || u == "LUMINANCE") return "L";
    if (u == "R" || u == "RED") return "R";
    if (u == "G" || u == "GREEN") return "G";
    if (u == "B" || u == "BLUE") return "B";

    // Return as-is if not recognized
    return s;
}

/**
 * Normalize filter name for unified parser
 * L R G B Ha OIII SII - standard filters
 * Custom filters - preserve original case
 */
function _fi_normFilterUnified(f){
    if (!f) return null;
    var s = String(f).trim();
    if (!s.length) return null;
    var u = s.toUpperCase();

    // Broadband filters (uppercase short form)
    if (u == "L" || u == "LUM" || u == "LUMEN" || u == "LUMINANCE") return "L";
    if (u == "R" || u == "RED") return "R";
    if (u == "G" || u == "GREEN") return "G";
    if (u == "B" || u == "BLUE") return "B";

    // Narrowband filters (Ha with lowercase 'a', others uppercase)
    if (u == "HA" || u == "H-ALPHA" || u == "HALPHA") return "Ha";
    if (u == "OIII" || u == "O-III" || u == "O3") return "OIII";
    if (u == "SII" || u == "S-II" || u == "S2") return "SII";

    // Custom filter - return original (preserve user's case)
    return s;
}

/* -------- Date/time parsing -------- */

// _fi_extractDateOnly removed - now using CU_extractDateOnly from common_utils.jsh

/**
 * Extract full date+time in ISO 8601 format: YYYY-MM-DDTHH:MM:SS
 * Returns as-is if already in this format, null otherwise
 */
function _fi_extractDateTime(s){
    if (!s) return null;
    var str = String(s).trim();
    // Check if it looks like ISO format (YYYY-MM-DDTHH:MM:SS)
    // Simple check: starts with 4 digits, then -, then has T separator
    if (str.length >= 19 && str.charAt(4) == '-' && str.charAt(10) == 'T'){
        // Extract first 19 chars: YYYY-MM-DDTHH:MM:SS
        return str.substring(0, 19);
    }
    return null;
}

/**
 * Calculate time difference in hours between two ISO 8601 datetime strings
 * Format: "YYYY-MM-DDTHH:MM:SS"
 * Returns null if either string is invalid
 */
function _fi_dateTimeDiff(dt1, dt2){
    if (!dt1 || !dt2) return null;

    function parseISO(s){
        // Parse "YYYY-MM-DDTHH:MM:SS" to Date object
        if (s.length < 19) return null;
        var y = parseInt(s.substring(0, 4), 10);
        var m = parseInt(s.substring(5, 7), 10);
        var d = parseInt(s.substring(8, 10), 10);
        var h = parseInt(s.substring(11, 13), 10);
        var min = parseInt(s.substring(14, 16), 10);
        var sec = parseInt(s.substring(17, 19), 10);

        // Basic validation
        if (isNaN(y) || isNaN(m) || isNaN(d) || isNaN(h) || isNaN(min) || isNaN(sec)) return null;
        if (m < 1 || m > 12 || d < 1 || d > 31) return null;
        if (h < 0 || h > 23 || min < 0 || min > 59 || sec < 0 || sec > 59) return null;

        // Create Date object (month is 0-indexed in JS)
        return new Date(y, m - 1, d, h, min, sec);
    }

    var d1 = parseISO(dt1);
    var d2 = parseISO(dt2);
    if (!d1 || !d2) return null;

    var diffMs = Math.abs(d1.getTime() - d2.getTime());
    var diffHours = diffMs / (1000 * 60 * 60);
    return diffHours;
}

/* -------- Utility -------- */

// _fi_first removed - now using CU_first from common_utils.jsh

/**
 * Safe keyword lookup - checks if key exists before accessing
 * Returns value or null, avoids warnings about undefined properties
 */
function _fi_getKey(map, key){
    if (!map) return null;
    // Check if key exists in map to avoid ES3 warnings
    for (var k in map){
        if (k == key) return map[k];
    }
    return null;
}

/**
 * Safe CU_first() for keyword maps - avoids ES3 warnings
 * Usage: _fi_firstKey(K, "KEY1", "KEY2", "KEY3")
 */
function _fi_firstKey(map){
    if (!map) return null;
    for (var i = 1; i < arguments.length; i++){
        var val = _fi_getKey(map, arguments[i]);
        if (val != undefined && val != null) return val;
    }
    return null;
}

/* ============================================================================
 * LEVEL 2: Core Functions (private)
 * ============================================================================ */

/* -------- Directory operations -------- */

/**
 * Check if path looks like Windows drive (C:, D:, etc.)
 */
function _fi_looksWindows(dir){
    if (!dir || dir.length < 2) return false;
    var c0 = dir.charAt(0), c1 = dir.charAt(1);
    return ((c0 >= 'A' && c0 <= 'Z') || (c0 >= 'a' && c0 <= 'z')) && c1 == ':';
}

/**
 * List directory contents (names only, not full paths)
 * Cross-platform: tries FileFind → System.readDirectory → Windows cmd fallback
 * Returns array of names (excluding "." and "..")
 */
function _fi_listNames(dir){
    dir = CU_norm(dir);
    var out = [];

    // Method 1: FileFind iterator (most reliable, works in PI 1.9.x)
    try{
        var ff = new FileFind;
        if (ff.begin(dir + "/*")){
            do{
                var name = ff.name;
                if (name && name != "." && name != ".."){
                    out.push(name);
                }
            } while (ff.next());
            ff.end();
        }
        if (out.length > 0) return out;
    } catch(e){
        // FileFind failed, try fallback
    }

    // Method 2: System.readDirectory
    try{
        if (typeof System != "undefined" && typeof System.readDirectory == "function"){
            var arr = System.readDirectory(dir);
            if (arr && arr.length){
                var out2 = [];
                for (var i = 0; i < arr.length; i++){
                    var n = String(arr[i]);
                    if (n != "." && n != ".."){
                        out2.push(n);
                    }
                }
                if (out2.length > 0) return out2;
            }
        }
    } catch(e){
        // System.readDirectory failed, try fallback
    }

    // Method 3: Windows cmd fallback (only on Windows)
    try{
        if (_fi_looksWindows(dir) && typeof System != "undefined" && typeof System.execute == "function"){
            var tmp = dir + "/__anaw_tmp_list.txt";
            System.execute('cmd /c dir /b "' + dir + '" > "' + tmp + '"');

            var txt = "";
            try{
                var tf = new TextFile;
                tf.openForReading(tmp);
                while (!tf.eof) txt += tf.readLine() + "\n";
                tf.close();
            } catch(e2){
                txt = "";
            }
            try{ File.remove(tmp); } catch(_){}

            if (txt){
                var lines = txt.replace(/\r/g, "").split("\n");
                var out3 = [];
                for (var j = 0; j < lines.length; j++){
                    var ln = lines[j].trim();
                    if (ln && ln != "." && ln != ".."){
                        out3.push(ln);
                    }
                }
                return out3;
            }
        }
    } catch(e){
        // All methods failed
    }

    return [];
}

/**
 * Recursively walk directory and call callback for each file
 * @param dir - directory path
 * @param cb - callback function(fullPath)
 */
function _fi_walkDir(dir, cb){
    var names = _fi_listNames(dir);
    for (var i = 0; i < names.length; i++){
        var name = names[i];
        var full = CU_norm(dir + "/" + name);
        if (_fi_isDir(full)){
            _fi_walkDir(full, cb);
        } else if (_fi_isFile(full)){
            cb(full);
        }
    }
}

/* -------- Path-based setup detection (for masters) -------- */

/**
 * Derive setup from path structure (for old masters without headers)
 * Extracts first path segment after root directory
 * Example: root=D:/MASTERS, full=D:/MASTERS/CYPRUS_FSQ/.../file.fit → "CYPRUS_FSQ"
 */
function _fi_setupFromPath(root, full){
    var r = CU_norm(root), f = CU_norm(full);
    if (f.substring(0, r.length).toUpperCase() == r.toUpperCase()){
        var rel = f.substring(r.length);
        if (rel.charAt(0) == '/') rel = rel.substring(1);
        var i = rel.indexOf('/');
        if (i > 0) return rel.substring(0, i);
    }
    return null;
}

/**
 * Get parent directory name (fallback for setup detection)
 */
function _fi_parentDirName(full){
    var s = CU_norm(full);
    var i = s.lastIndexOf('/');
    if (i <= 0) return null;
    var parent = s.substring(0, i);
    var j = parent.lastIndexOf('/');
    return (j >= 0) ? parent.substring(j + 1) : parent;
}

/* -------- FITS I/O -------- */

/**
 * Convert keywords array to map {NAME: value}
 * NAME is uppercase, value is strippedValue
 */
function _fi_keywordsToMap(karray){
    var map = {};
    for (var i = 0; i < karray.length; i++){
        var k = karray[i];
        var name = String(k.name || "").trim();
        var val = (typeof k.strippedValue != "undefined") ? k.strippedValue : k.value;

        // Try to strip value if trim() method exists
        if (typeof k.trim == "function"){
            try {
                k.trim();
                val = (typeof k.strippedValue != "undefined") ? k.strippedValue : k.value;
            } catch(_){}
        }

        if (!name.length) continue;
        map[name.toUpperCase()] = val;
    }
    return map;
}

/**
 * Read FITS keywords from file
 * Returns {KEYWORD: value} map or null on error
 */
function _fi_readFitsKeywords(filePath){
    // Increment performance counter (optional, for testing)
    _FI_HEADER_READ_COUNT++;

    var ext = File.extractExtension(filePath).toLowerCase();
    var F = new FileFormat(ext, true, false);
    if (F.isNull){
        Console.warningln("[fi] Unsupported file format: " + filePath);
        return null;
    }

    var f = new FileFormatInstance(F);
    if (f.isNull){
        Console.warningln("[fi] Cannot create FileFormatInstance: " + filePath);
        return null;
    }

    var info = f.open(filePath, "verbosity 0");
    if (!info || (info && info.length <= 0)){
        f.close();
        return null;
    }

    var kws = [];
    if (F.canStoreKeywords){
        kws = f.keywords;
    }

    f.close();

    // Count bytes: sum of keyword name + value string lengths
    for (var i = 0; i < kws.length; i++){
        _FI_BYTES_READ += kws[i].name.length;
        _FI_BYTES_READ += String(kws[i].value).length;
    }

    return _fi_keywordsToMap(kws);
}

/**
 * Universal unified parser for all file types (LIGHT/CALIBRATION/MASTER)
 * Combines logic from _fi_parseLight, _fi_parseCalibration, _fi_parseMaster
 *
 * @param fullPath - full path to file
 * @param rootPath - root directory (optional, for setup extraction from path)
 * @param expectedType - "LIGHT"|"CALIBRATION"|"MASTER"|null (auto-detect if null)
 * @returns parsed object with all fields
 */
function _fi_parseUnified(fullPath, rootPath, expectedType){
    var fileName = CU_basename(fullPath);
    var fileNameUp = fileName.toUpperCase();

    // Skip DarkFlats
    if (/\b(DARKFLAT|FLATDARK)\b/i.test(fileName)){
        throw new Error("DarkFlat/FlatDark skipped");
    }

    // Read FITS keywords once
    var K = _fi_readFitsKeywords(fullPath);
    var headersAvailable = (K != null);

    // Extract common identity keywords (if headers available)
    var telescope = null, camera = null, imagetyp = null;
    if (headersAvailable){
        telescope = CU_upperOrNull(CU_first(_fi_getKey(K, "TELESCOP"), _fi_getKey(K, "TELESCOPE")));
        camera = CU_upperOrNull(CU_first(_fi_getKey(K, "INSTRUME"), _fi_getKey(K, "INSTRUMENT")));
        imagetyp = CU_upperOrNull(CU_first(_fi_getKey(K, "IMAGETYP"), _fi_getKey(K, "IMAGETYPE"), _fi_getKey(K, "FRAME")));
    }

    // Auto-detect type if not specified
    var detectedType = expectedType;
    if (!detectedType && imagetyp){
        if (imagetyp == "LIGHT" || imagetyp == "LIGHTFRAME"){
            detectedType = "LIGHT";
        }
        else if (imagetyp == "MASTERBIAS" || imagetyp == "MASTERDARK" || imagetyp == "MASTERFLAT" || imagetyp == "MASTERDARKFLAT"){
            detectedType = "MASTER";
        }
        else if (imagetyp == "BIAS" || imagetyp == "DARK" || imagetyp == "FLAT"){
            // Check filename to disambiguate calibration vs master
            if (fileNameUp.indexOf("MASTER") >= 0){
                detectedType = "MASTER";
            } else {
                detectedType = "CALIBRATION";
            }
        }
    }

    // Fallback to filename patterns if headers insufficient
    if (!detectedType){
        if (/\bBIAS\b/i.test(fileName)) detectedType = "CALIBRATION";
        else if (/\bDARK\b/i.test(fileName)) detectedType = "CALIBRATION";
        else if (/\bFLAT\b/i.test(fileName)) detectedType = "CALIBRATION";
        else if (/\bMASTER/i.test(fileName)) detectedType = "MASTER";
        else detectedType = "LIGHT";
    }

    // Check if we need filename fallback (only for MASTER type)
    var useFilenameFallback = false;
    if (detectedType == "MASTER" && (!headersAvailable || !telescope || !camera || !imagetyp)){
        useFilenameFallback = true;
    }

    // For MASTER: check critical parameters from headers before deciding fallback
    if (detectedType == "MASTER" && !useFilenameFallback && headersAvailable && telescope && camera && imagetyp){
        // Pre-check critical parameters (gain, offset, usb, readout, binning, tempSetC)
        var gain_pre = CU_toInt(_fi_firstKey(K, "GAIN", "EGAIN"));
        var offset_pre = CU_toInt(_fi_firstKey(K, "OFFSET", "QOFFSET"));
        var usb_pre = CU_toInt(_fi_firstKey(K, "USBLIMIT", "QUSBLIM"));
        var readout_pre = CU_cleanReadout(_fi_firstKey(K, "READOUTM", "QREADOUT", "READMODE", "CAMMODE"));
        var xbin_pre = _fi_firstKey(K, "XBINNING", "BINNINGX", "XBIN", "XBINNING_BIN");
        var ybin_pre = _fi_firstKey(K, "YBINNING", "BINNINGY", "YBIN", "YBINNING_BIN");
        var binning_pre = CU_mkBinning(xbin_pre, ybin_pre);
        var tempSetC_pre = CU_toFloat(_fi_firstKey(K, "SET-TEMP", "SETTEMP", "SET_TEMP"));

        // If any critical parameters missing, use filename fallback
        if ((gain_pre === null || gain_pre === undefined) ||
            (offset_pre === null || offset_pre === undefined) ||
            (usb_pre === null || usb_pre === undefined) ||
            !readout_pre || !binning_pre ||
            (tempSetC_pre === null || tempSetC_pre === undefined)){
            useFilenameFallback = true;
        }
    }

    // For non-MASTER types, require telescope/camera from headers
    if (!useFilenameFallback && (!telescope || !camera)){
        throw new Error("TELESCOP or INSTRUME missing: " + fileName);
    }

    var setup = null, filter = null, readout = null, gain = null, offset = null, usb = null;
    var binning = null, exp = null, tempSetC = null, tempC = null;
    var date = null, dateTime = null, dateTimeLoc = null;
    var parseMethod = "headers";

    if (useFilenameFallback){
        // === FILENAME FALLBACK for old masters without headers ===
        parseMethod = "filename";

        var name = fileName.replace(/\.\w+$/, ''); // remove extension
        var clean = name.replace(/!+/g, "");
        var up = clean.toUpperCase();

        // Telescope: everything before "_Master"
        var setupIdx = clean.indexOf("_Master");
        if (setupIdx > 0){
            telescope = clean.substring(0, setupIdx);
        }

        // Split into tokens
        var parts = clean.split("_");

        // Camera: look for QHY*, ASI*, etc.
        for (var i = 0; i < parts.length; ++i){
            if (/^(QHY|ASI|ZWO|FLI|SBIG|ATIK)/i.test(parts[i])){
                camera = parts[i];
                break;
            }
        }

        // Setup: telescope + "_" + camera
        if (telescope && camera){
            setup = telescope + "_" + camera;
        } else if (telescope){
            setup = telescope;  // fallback if no camera found
        }

        // Readout: "High Gain Mode 16BIT"
        var mRead = clean.match(/(High Gain Mode\s*\d*BIT|Low Gain Mode\s*\d*BIT)/i);
        if (mRead) readout = mRead[1];

        // Binning: 1x1 or Bin1x1
        var mBin = clean.match(/(?:BIN)?(\d+)x(\d+)/i);
        if (mBin) binning = (mBin[1] + "x" + mBin[2]);

        // Gain / Offset / USB
        var mG = clean.match(/_G(\d+)/i);
        if (mG) gain = CU_toInt(mG[1]);

        var mO = clean.match(/_OS(\d+)/i);
        if (mO) offset = CU_toInt(mO[1]);

        var mU = clean.match(/_U(\d+)/i);
        if (mU) usb = CU_toInt(mU[1]);

        // Temperature: _0C or _-20C
        var mT = clean.match(/_(-?\d+)C/i);
        if (mT) tempSetC = CU_toInt(mT[1]);

        // tempC = tempSetC (matching uses tempC)
        tempC = tempSetC;

        // Exposure: _015s (3 digits seconds) - for DARKs only
        var mExp = clean.match(/_([\d.]+)s/i);
        if (mExp){
            var v = parseFloat(mExp[1]);
            if (!isNaN(v)) exp = v;
        }

        // Filter (only for flats): _L_, _R_, _G_, _B_, _HA_, _OIII_, _SII_
        var mF = clean.match(/_(L|R|G|B|HA|H\-ALPHA|OIII|O3|SII|S2)_/i);
        if (mF) filter = _fi_normFilterUnified(mF[1]);

        // Date: YYYY_MM_DD or YYYY-MM-DD
        var mD = clean.match(/(\d{4}[-_]\d{2}[-_]\d{2})/);
        if (mD) date = mD[1].replace(/_/g, "-"); // normalize to YYYY-MM-DD

        // If setup not extracted, try from path
        if (!setup && rootPath){
            setup = _fi_setupFromPath(rootPath, fullPath);
        }

        // If setup still not available, try parent dir name
        if (!setup){
            setup = _fi_parentDirName(fullPath);
        }

    } else {
        // === PARSE FROM HEADERS ===
        setup = telescope + "_" + camera;

        var filterRaw = _fi_getKey(K, "FILTER");
        filter = _fi_normFilterUnified(filterRaw);
        readout = CU_cleanReadout(_fi_firstKey(K, "READOUTM", "QREADOUT", "READMODE", "CAMMODE"));
        gain = CU_toInt(_fi_firstKey(K, "GAIN", "EGAIN"));
        offset = CU_toInt(_fi_firstKey(K, "OFFSET", "QOFFSET"));
        usb = CU_toInt(_fi_firstKey(K, "USBLIMIT", "QUSBLIM"));

        var xbin = _fi_firstKey(K, "XBINNING", "BINNINGX", "XBIN", "XBINNING_BIN");
        var ybin = _fi_firstKey(K, "YBINNING", "BINNINGY", "YBIN", "YBINNING_BIN");
        binning = CU_mkBinning(xbin, ybin);

        exp = CU_toFloat(_fi_firstKey(K, "EXPOSURE", "EXPTIME", "DURATION"));

        tempSetC = CU_toFloat(_fi_firstKey(K, "SET-TEMP", "SETTEMP", "SET_TEMP"));
        if (tempSetC != null) tempSetC = Math.round(tempSetC);

        // tempC = tempSetC (matching uses tempC)
        tempC = tempSetC;

        var dateObsRaw = _fi_firstKey(K, "DATE-OBS", "DATE_OBS", "DATEOBS", "DATE");
        var dateLocRaw = _fi_firstKey(K, "DATE-LOC", "DATE_LOC", "DATELOC");
        date = CU_extractDateOnly(dateObsRaw);
        dateTime = _fi_extractDateTime(dateObsRaw);
        dateTimeLoc = _fi_extractDateTime(dateLocRaw);
    }

    // Type-specific fields
    var type = "UNKNOWN";
    var object = null;
    var focalLen = null, xPixSz = null, yPixSz = null, scale = null;

    if (detectedType == "LIGHT"){
        type = "LIGHT";

        // Object name (only from headers)
        if (headersAvailable){
            object = (function(v){
                var t = (v == null) ? "" : String(v).trim();
                return t.length ? t : null;
            })(_fi_getKey(K, "OBJECT"));

            // Optical params (for scale calculation)
            focalLen = CU_toFloat(_fi_firstKey(K, "FOCALLEN", "FOCAL", "FOCLEN"));
            xPixSz = CU_toFloat(_fi_firstKey(K, "XPIXSZ", "PIXSIZE", "PIXELSZ"));
            yPixSz = CU_toFloat(_fi_firstKey(K, "YPIXSZ", "PIXSIZE", "PIXELSZ"));

            // Calculate scale
            if (focalLen != null && xPixSz != null){
                scale = (xPixSz / focalLen) * 206.265;
            }
        }

    } else if (detectedType == "CALIBRATION" || detectedType == "MASTER"){
        // Determine specific type
        if (imagetyp){
            if (imagetyp == "BIAS" || imagetyp == "MASTERBIAS") type = "BIAS";
            else if (imagetyp == "DARK" || imagetyp == "MASTERDARK") type = "DARK";
            else if (imagetyp == "FLAT" || imagetyp == "MASTERFLAT") type = "FLAT";
            else if (imagetyp == "DARKFLAT" || imagetyp == "MASTERDARKFLAT") type = "DARKFLAT";
        }

        // Fallback to filename if type still unknown
        if (type == "UNKNOWN"){
            var up = fileName.toUpperCase();
            if (up.indexOf("MASTERBIAS") >= 0) type = "BIAS";
            else if (up.indexOf("MASTERDARK") >= 0) type = "DARK";
            else if (up.indexOf("MASTERF") >= 0 || up.indexOf("MASTERFLAT") >= 0) type = "FLAT";
            else if (/\bBIAS\b/i.test(fileName)) type = "BIAS";
            else if (/\bDARK\b/i.test(fileName)) type = "DARK";
            else if (/\bFLAT\b/i.test(fileName)) type = "FLAT";
        }

        // Check OBJECT for FlatWizard (special case for flats created via wizard)
        if (headersAvailable && type == "FLAT"){
            var objRaw = _fi_getKey(K, "OBJECT");
            if (objRaw && String(objRaw).trim() == "FlatWizard"){
                // Valid master flat, but clear object (not a real sky target)
                object = null;
            }
        }
    }

    // Build result
    var result = {
        path: CU_norm(fullPath),
        filename: fileName,
        type: type,
        parseMethod: parseMethod,  // "headers" or "filename"
        detectedType: detectedType,

        setup: setup,
        telescope: telescope,
        camera: camera
    };

    // Add optional fields only if non-null (matching old parsers)
    if (object != null) result.object = object;
    if (filter != null) result.filter = filter;
    if (binning != null) result.binning = binning;
    if (gain != null) result.gain = gain;
    if (offset != null) result.offset = offset;
    if (usb != null) result.usb = usb;
    if (readout != null) result.readout = readout;
    if (exp != null) result.exposureSec = exp;
    if (tempSetC != null) result.tempSetC = tempSetC;
    if (tempC != null) result.tempC = tempC;
    if (date != null) result.date = date;
    if (dateTime != null) result.dateTime = dateTime;
    if (dateTimeLoc != null) result.dateTimeLoc = dateTimeLoc;

    // Lights-specific optical params
    if (focalLen != null) result.focalLen = focalLen;
    if (xPixSz != null) result.xPixelSize = xPixSz;
    if (yPixSz != null) result.yPixelSize = yPixSz;
    if (scale != null) result.scale = scale;

    return result;
}

/* ============================================================================
 * LEVEL 3: Public API (prefix FI_)
 * ============================================================================ */

/**
 * Save object to JSON file
 * @param path - output file path
 * @param obj - object to serialize (array or object)
 * @returns true on success, false on error
 */
function FI_saveJSON(path, obj){
    try {
        // Serialize to JSON with indentation
        var json = JSON.stringify(obj, null, 2);

        // Write to file
        var f = new File;
        f.createForWriting(path);
        f.outTextLn(json);
        f.close();

        return true;
    } catch(e){
        Console.warningln("Failed to save JSON to " + path + ": " + e);
        return false;
    }
}

/**
 * Index light frames in directory and save to JSON
 * @param rootPath - directory to scan
 * @param savePath - output JSON file path
 * @returns {count, errors, time} statistics
 */
function FI_indexLights(rootPath, savePath){
    var t0 = Date.now();
    var results = [];
    var errors = [];

    // Walk directory and parse all FITS files
    _fi_walkDir(rootPath, function(fullPath){
        if (!_fi_isFitsLike(fullPath)) return;

        try {
            var parsed = _fi_parseUnified(fullPath, rootPath, "LIGHT");
            results.push(parsed);
        } catch(e){
            errors.push({
                path: fullPath,
                error: String(e)
            });
        }
    });

    // Save to JSON
    var saveSuccess = false;
    if (savePath){
        saveSuccess = FI_saveJSON(savePath, results);
    }

    var elapsed = (Date.now() - t0) / 1000;

    // Create return object compatible with old LI_reindexLights format
    var returnObj = {
        lightsRoot: rootPath,
        items: results,
        count: results.length,
        errors: errors.length,
        errorList: errors,
        time: elapsed,
        saved: saveSuccess
    };

    // Store globally for PP_getLastLightsIndex compatibility
    try {
        this.LI_LAST_INDEX = returnObj;
    } catch(_){}

    return returnObj;
}

/**
 * Index raw calibration frames in directory and save to JSON
 * @param rootPath - directory to scan
 * @param savePath - output JSON file path
 * @returns {count, errors, time} statistics
 */
function FI_indexCalibration(rootPath, savePath){
    var t0 = Date.now();
    var results = [];
    var errors = [];

    // Walk directory and parse all FITS files
    _fi_walkDir(rootPath, function(fullPath){
        if (!_fi_isFitsLike(fullPath)) return;

        try {
            var parsed = _fi_parseUnified(fullPath, rootPath, "CALIBRATION");
            results.push(parsed);
        } catch(e){
            errors.push({
                path: fullPath,
                error: String(e)
            });
        }
    });

    // Save to JSON
    var saveSuccess = false;
    if (savePath){
        saveSuccess = FI_saveJSON(savePath, results);
    }

    var elapsed = (Date.now() - t0) / 1000;

    return {
        count: results.length,
        errors: errors.length,
        errorList: errors,
        time: elapsed,
        saved: saveSuccess
    };
}

/**
 * Index master calibration frames in directory and save to JSON
 * @param rootPath - directory to scan
 * @param savePath - output JSON file path
 * @returns {count, errors, time} statistics
 */
function FI_indexMasters(rootPath, savePath){
    var t0 = Date.now();
    var results = [];
    var errors = [];

    // Walk directory and parse all FITS files
    _fi_walkDir(rootPath, function(fullPath){
        if (!_fi_isFitsLike(fullPath)) return;

        // Master files MUST be in XISF format (required for calibration in PixInsight)
        var ext = File.extractExtension(fullPath).toLowerCase();
        if (ext != ".xisf"){
            var fileName = CU_basename(fullPath);
            Console.warningln("[FI_indexMasters] Skipping non-XISF master: " + fileName + " (ext: " + ext + ")");
            return;
        }

        try {
            var parsed = _fi_parseUnified(fullPath, rootPath, "MASTER");
            results.push(parsed);
        } catch(e){
            errors.push({
                path: fullPath,
                error: String(e)
            });
        }
    });

    // Save to JSON
    var saveSuccess = false;
    if (savePath){
        saveSuccess = FI_saveJSON(savePath, results);
    }

    var elapsed = (Date.now() - t0) / 1000;

    // Create return object compatible with old MI_reindexMasters format
    var returnObj = {
        mastersRoot: rootPath,
        items: results,
        count: results.length,
        errors: errors.length,
        errorList: errors,
        time: elapsed,
        saved: saveSuccess
    };

    // Store globally for PP_getLastMastersIndex compatibility
    try {
        this.MI_LAST_INDEX = returnObj;
    } catch(_){}

    return returnObj;
}

#endif // __ANAWBPPS_FITS_INDEXING_JSH
