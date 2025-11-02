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

/* ============================================================================
 * LEVEL 1: Low-level Helpers (private, prefix _fi_)
 * ============================================================================ */

/* -------- Path utilities -------- */

/**
 * Normalize path to forward slashes, no trailing slash
 */
function _fi_norm(path){
    if (!path) return "";
    var s = String(path).replace(/\\/g, '/');
    // Remove trailing slashes
    while (s.length > 1 && s.charAt(s.length-1) == '/'){
        s = s.substring(0, s.length-1);
    }
    return s;
}

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

/**
 * Extract basename from path
 */
function _fi_basename(path){
    var s = String(path || "").replace(/\\/g, '/');
    var i = s.lastIndexOf('/');
    return (i >= 0) ? s.substring(i+1) : s;
}

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

/**
 * Safe integer conversion, returns null if invalid
 */
function _fi_toInt(v){
    if (v == undefined || v == null) return null;
    var s = String(v).trim();
    if (s.length == 0) return null;
    var n = Number(s);
    if (!isFinite(n)) return null;
    return Math.round(n);
}

/**
 * Safe float conversion, returns null if invalid
 */
function _fi_toFloat(v){
    if (v == undefined || v == null) return null;
    var s = String(v).trim();
    if (s.length == 0) return null;
    var n = Number(s);
    if (!isFinite(n)) return null;
    return n;
}

/**
 * Number or null (for compatibility with masters_parse)
 */
function _fi_numOrNull(v){
    return _fi_toFloat(v);
}

/* -------- String utilities -------- */

/**
 * Uppercase or null
 */
function _fi_upperOrNull(s){
    if (!s && s != 0) return null;
    var t = String(s).trim();
    return t.length ? t.toUpperCase() : null;
}

/**
 * Clean readout mode string (compact multiple spaces)
 */
function _fi_cleanReadout(s){
    if (!s && s != 0) return null;
    var t = String(s).trim();
    var out = "";
    var prevSpace = false;
    for (var i = 0; i < t.length; i++){
        var c = t.charAt(i);
        var isSpace = (c == " " || c == "\t");
        if (isSpace){
            if (!prevSpace){ out += " "; prevSpace = true; }
        } else {
            out += c; prevSpace = false;
        }
    }
    return out.length ? out : null;
}

/**
 * Create binning string "XxY"
 */
function _fi_mkBinning(xb, yb){
    var xi = _fi_toInt(xb);
    var yi = _fi_toInt(yb);
    if (xi != null && yi != null) return xi + "x" + yi;
    return null;
}

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

/* -------- Date/time parsing -------- */

/**
 * Extract YYYY-MM-DD from various date formats (no regex)
 */
function _fi_extractDateOnly(s){
    if (!s) return null;
    var str = String(s).trim();
    if (!str.length) return null;

    function isDigit(ch){ return ch >= '0' && ch <= '9'; }
    function readNDigits(src, pos, n){
        if (pos + n > src.length) return null;
        var val = 0;
        for (var k = 0; k < n; k++){
            var ch = src.charAt(pos + k);
            if (!isDigit(ch)) return null;
            val = val * 10 + (ch.charCodeAt(0) - 48);
        }
        return val;
    }

    var L = str.length;
    for (var i = 0; i <= L - 10; i++){
        var y = readNDigits(str, i, 4);
        if (y == null) continue;

        var s1Pos = i + 4;
        if (s1Pos >= L) continue;
        var s1 = str.charAt(s1Pos);
        if (!(s1 == '-' || s1 == '_' || s1 == '/')) continue;

        var m = readNDigits(str, i + 5, 2);
        if (m == null) continue;

        var s2Pos = i + 7;
        if (s2Pos >= L) continue;
        var s2 = str.charAt(s2Pos);
        if (!(s2 == '-' || s2 == '_' || s2 == '/')) continue;

        var d = readNDigits(str, i + 8, 2);
        if (d == null) continue;

        // Basic range sanity
        if (m < 1 || m > 12 || d < 1 || d > 31) continue;

        // Build YYYY-MM-DD
        var mm = (m < 10) ? ("0" + m) : String(m);
        var dd = (d < 10) ? ("0" + d) : String(d);
        return String(y) + "-" + mm + "-" + dd;
    }
    return null;
}

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

/**
 * Pick first non-null argument
 */
function _fi_first(){
    for (var i = 0; i < arguments.length; i++){
        var v = arguments[i];
        if (v != undefined && v != null) return v;
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
    dir = _fi_norm(dir);
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
        var full = _fi_norm(dir + "/" + name);
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
    var r = _fi_norm(root), f = _fi_norm(full);
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
    var s = _fi_norm(full);
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

    return _fi_keywordsToMap(kws);
}

/* -------- Type-specific parsers -------- */

/**
 * Parse light frame from FITS/XISF file
 * Reads headers only (no pixel data loaded)
 * Returns unified data structure with dateTime (UTC) and dateTimeLoc (local)
 */
function _fi_parseLight(fullPath){
    var filePath = String(fullPath || "");
    var fileName = _fi_basename(filePath);

    // Open via FileFormat (read-only, no pixels loaded)
    var ext = File.extractExtension(filePath).toLowerCase();
    var F = new FileFormat(ext, true, false);
    if (F.isNull)
        throw new Error("Unsupported format or FileFormat not available: " + fileName);

    var inst = new FileFormatInstance(F);
    if (inst.isNull)
        throw new Error("Cannot create FileFormatInstance: " + fileName);

    var info = inst.open(filePath, "verbosity 0");
    if (!info || (info && info.length <= 0)){
        inst.close();
        throw new Error("Failed to open file: " + fileName);
    }

    var kws = [];
    if (F.canStoreKeywords)
        kws = inst.keywords;

    // Geometry (available from open() without loading pixels)
    var width = null, height = null;
    try{
        var id = info[0];
        if (id){
            if (typeof id.width != "undefined")  width = id.width;
            if (typeof id.height != "undefined") height = id.height;
        }
    } catch(_){}

    inst.close();

    var K = _fi_keywordsToMap(kws);

    // Core identity
    var telescope = _fi_upperOrNull(_fi_first(K["TELESCOP"], K["TELESCOPE"]));
    var camera = _fi_upperOrNull(_fi_first(K["INSTRUME"], K["INSTRUMENT"]));
    var object = (function(v){
        var t = (v == null) ? "" : String(v).trim();
        return t.length ? t : null;
    })(K["OBJECT"]);
    var imagetyp = _fi_upperOrNull(_fi_first(K["IMAGETYP"], K["IMAGETYPE"], K["FRAME"]));

    // Enforce LIGHT type (we index only lights)
    var type = "LIGHT";

    // Photometric / acquisition params
    var filter = _fi_upperOrNull(K["FILTER"]);
    var readout = _fi_cleanReadout(_fi_first(K["READOUTM"], K["QREADOUT"], K["READMODE"], K["CAMMODE"]));
    var gain = _fi_toInt(_fi_first(K["GAIN"], K["EGAIN"]));
    var offset = _fi_toInt(_fi_first(K["OFFSET"], K["QOFFSET"]));
    var usb = _fi_toInt(_fi_first(K["USBLIMIT"], K["QUSBLIM"]));

    var xbin = _fi_first(K["XBINNING"], K["BINNINGX"], K["XBIN"], K["XBINNING_BIN"]);
    var ybin = _fi_first(K["YBINNING"], K["BINNINGY"], K["YBIN"], K["YBINNING_BIN"]);
    var binning = _fi_mkBinning(xbin, ybin);

    var exp = _fi_toFloat(_fi_first(K["EXPOSURE"], K["EXPTIME"], K["DURATION"]));

    var tSet = _fi_toFloat(_fi_first(K["SET-TEMP"], K["SET_TEMP"], K["SETPOINT"], K["CCD-TSET"]));
    var tCcd = _fi_toFloat(_fi_first(K["CCD-TEMP"], K["CCD_TEMP"], K["SENSOR_TEMP"], K["CCD-TEMP1"]));
    var tempSetC = (tSet == null) ? null : Math.round(tSet);
    var tempC = (tCcd == null) ? null : Math.round(tCcd);

    var focalLen = _fi_toFloat(_fi_first(K["FOCALLEN"], K["FOCAL"], K["FOCLEN"]));

    // Pixel size in microns
    var xPixSz = _fi_toFloat(_fi_first(K["XPIXSZ"], K["PIXSIZE"], K["PIXELSZ"]));
    var yPixSz = _fi_toFloat(_fi_first(K["YPIXSZ"], K["PIXSIZE"], K["PIXELSZ"]));

    // Calculate scale (arcsec/pixel) if we have both focalLen and pixelSize
    var scale = null;
    if (focalLen != null && xPixSz != null){
        // scale = (pixelSize_µm / focalLength_mm) × 206.265
        scale = (xPixSz / focalLen) * 206.265;
    }

    // Date/Time: extract both UTC (DATE-OBS) and Local (DATE-LOC)
    var dateObsRaw = _fi_first(K["DATE-OBS"], K["DATE_OBS"], K["DATEOBS"], K["DATE"]);
    var dateLocRaw = _fi_first(K["DATE-LOC"], K["DATE_LOC"], K["DATELOC"]);

    var date = _fi_extractDateOnly(dateObsRaw);
    var dateTime = _fi_extractDateTime(dateObsRaw);
    var dateTimeLoc = _fi_extractDateTime(dateLocRaw);

    var setup = (telescope && camera) ? (telescope + "_" + camera) : null;

    return {
        // identity
        path: filePath,
        filename: fileName,
        type: type,
        parseMethod: "headers",  // parsed from FITS headers

        // setup
        setup: setup,
        telescope: telescope,
        camera: camera,
        object: object,

        // acquisition
        filter: filter,
        binning: binning,
        gain: gain,
        offset: offset,
        usb: usb,
        readout: readout,
        exposureSec: exp,
        tempSetC: tempSetC,
        tempC: tempC,

        // date/time
        date: date,              // "YYYY-MM-DD" (from DATE-OBS)
        dateTime: dateTime,      // "YYYY-MM-DDTHH:MM:SS" UTC (from DATE-OBS)
        dateTimeLoc: dateTimeLoc, // "YYYY-MM-DDTHH:MM:SS" local (from DATE-LOC)

        // lights-specific
        focalLen: focalLen,      // mm (float)
        xPixelSize: xPixSz,      // microns (float)
        yPixelSize: yPixSz,      // microns (float)
        scale: scale,            // arcsec/pixel (calculated)
        width: width,
        height: height
    };
}

/**
 * Parse calibration frame (Dark/Flat/Bias/DarkFlat) from FITS/XISF file
 * Similar to _fi_parseLight() but for calibration files
 * Returns unified data structure
 */
function _fi_parseCalibration(fullPath){
    var filePath = String(fullPath || "");
    var fileName = _fi_basename(filePath);

    // Read FITS keywords
    var K = _fi_readFitsKeywords(filePath);
    if (!K){
        throw new Error("Failed to read FITS keywords: " + fileName);
    }

    // Core identity
    var telescope = _fi_upperOrNull(_fi_first(K["TELESCOP"], K["TELESCOPE"]));
    var camera = _fi_upperOrNull(_fi_first(K["INSTRUME"], K["INSTRUMENT"]));
    var imagetyp = _fi_upperOrNull(_fi_first(K["IMAGETYP"], K["IMAGETYPE"], K["FRAME"]));

    // Type is required for calibration files
    if (!imagetyp){
        throw new Error("IMAGETYP keyword missing: " + fileName);
    }

    var type = imagetyp; // DARK, FLAT, BIAS, DARKFLAT

    // Acquisition params
    var filter = _fi_upperOrNull(K["FILTER"]);
    var readout = _fi_cleanReadout(_fi_first(K["READOUTM"], K["QREADOUT"], K["READMODE"], K["CAMMODE"]));
    var gain = _fi_toInt(_fi_first(K["GAIN"], K["EGAIN"]));
    var offset = _fi_toInt(_fi_first(K["OFFSET"], K["QOFFSET"]));
    var usb = _fi_toInt(_fi_first(K["USBLIMIT"], K["QUSBLIM"]));

    var xbin = _fi_first(K["XBINNING"], K["BINNINGX"], K["XBIN"], K["XBINNING_BIN"]);
    var ybin = _fi_first(K["YBINNING"], K["BINNINGY"], K["YBIN"], K["YBINNING_BIN"]);
    var binning = _fi_mkBinning(xbin, ybin);

    var exp = _fi_toFloat(_fi_first(K["EXPOSURE"], K["EXPTIME"], K["DURATION"]));

    var tSet = _fi_toFloat(_fi_first(K["SET-TEMP"], K["SET_TEMP"], K["SETPOINT"], K["CCD-TSET"]));
    var tCcd = _fi_toFloat(_fi_first(K["CCD-TEMP"], K["CCD_TEMP"], K["SENSOR_TEMP"], K["CCD-TEMP1"]));
    var tempSetC = (tSet == null) ? null : Math.round(tSet);
    var tempC = (tCcd == null) ? null : Math.round(tCcd);

    // Date/Time: extract both UTC (DATE-OBS) and Local (DATE-LOC)
    var dateObsRaw = _fi_first(K["DATE-OBS"], K["DATE_OBS"], K["DATEOBS"], K["DATE"]);
    var dateLocRaw = _fi_first(K["DATE-LOC"], K["DATE_LOC"], K["DATELOC"]);

    var date = _fi_extractDateOnly(dateObsRaw);
    var dateTime = _fi_extractDateTime(dateObsRaw);
    var dateTimeLoc = _fi_extractDateTime(dateLocRaw);

    var setup = (telescope && camera) ? (telescope + "_" + camera) : null;

    return {
        // identity
        path: filePath,
        filename: fileName,
        type: type, // DARK/FLAT/BIAS/DARKFLAT
        parseMethod: "headers",  // parsed from FITS headers

        // setup
        setup: setup,
        telescope: telescope,
        camera: camera,

        // acquisition
        filter: filter,      // important for FLATs
        binning: binning,
        gain: gain,
        offset: offset,
        usb: usb,
        readout: readout,
        exposureSec: exp,    // important for DARKs
        tempSetC: tempSetC,
        tempC: tempC,

        // date/time
        date: date,
        dateTime: dateTime,
        dateTimeLoc: dateTimeLoc
    };
}

// --- Filename fallback parser (for old masters) ---
function _fi_parseByFilename(fullPath){
    var fileName = _fi_basename(fullPath);
    var name = fileName.replace(/\.\w+$/, ''); // remove extension
    var clean = name.replace(/!+/g, "");
    var up = clean.toUpperCase();

    // Type: BIAS/DARK/FLAT
    var type = up.indexOf("MASTERBIAS") >= 0 ? "BIAS" :
               up.indexOf("MASTERDARK") >= 0 ? "DARK" :
               (up.indexOf("MASTERF") >= 0 || up.indexOf("MASTERFLAT") >= 0) ? "FLAT" : null;

    // Setup: everything before "_Master"
    var setup = null;
    var setupIdx = clean.indexOf("_Master");
    if (setupIdx > 0){
        setup = clean.substring(0, setupIdx);
    }

    // Split into tokens
    var parts = clean.split("_");

    // Telescope: from setup
    var telescope = setup;

    // Camera: look for QHY*, ASI*, etc.
    var camera = null;
    for (var i = 0; i < parts.length; ++i){
        if (/^(QHY|ASI|ZWO|FLI|SBIG|ATIK)/i.test(parts[i])){
            camera = parts[i];
            break;
        }
    }

    // Readout: "High Gain Mode 16BIT"
    var readout = null;
    var mRead = clean.match(/(High Gain Mode\s*\d*BIT|Low Gain Mode\s*\d*BIT)/i);
    if (mRead) readout = mRead[1];

    // Binning: 1x1 or Bin1x1
    var binning = null;
    var mBin = clean.match(/(?:BIN)?(\d+)x(\d+)/i);
    if (mBin) binning = (mBin[1] + "x" + mBin[2]);

    // Gain / Offset / USB
    var gain = null;
    var mG = clean.match(/_G(\d+)/i);
    if (mG) gain = _fi_toInt(mG[1]);

    var offset = null;
    var mO = clean.match(/_OS(\d+)/i);
    if (mO) offset = _fi_toInt(mO[1]);

    var usb = null;
    var mU = clean.match(/_U(\d+)/i);
    if (mU) usb = _fi_toInt(mU[1]);

    // Temperature: _0C or _-20C
    var tempC = null;
    var mT = clean.match(/_(-?\d+)C/i);
    if (mT) tempC = _fi_toInt(mT[1]);

    // Exposure: _015s (3 digits seconds) - for DARKs only
    var exposureSec = null;
    var mExp = clean.match(/_([\d.]+)s/i);
    if (mExp){
        var v = parseFloat(mExp[1]);
        if (!isNaN(v)) exposureSec = v;
    }

    // Filter (only for flats): _L_, _R_, _G_, _B_, _HA_, _OIII_, _SII_
    var filter = null;
    var mF = clean.match(/_(L|R|G|B|HA|H\-ALPHA|OIII|O3|SII|S2)_/i);
    if (mF) filter = _fi_normFilter(mF[1]);

    // Date: YYYY_MM_DD or YYYY-MM-DD
    var date = null;
    var mD = clean.match(/(\d{4}[-_]\d{2}[-_]\d{2})/);
    if (mD) date = mD[1].replace(/_/g, "-"); // normalize to YYYY-MM-DD

    return {
        path: _fi_norm(fullPath),
        filename: fileName,
        type: type,            // BIAS/DARK/FLAT or null
        parseMethod: "filename",  // parsed from filename (fallback)
        setup: setup,          // may be null (caller may fill from path)
        telescope: telescope,  // from setup
        camera: camera,        // from camera token
        readout: readout || null,
        binning: binning || null,
        gain: gain,
        offset: offset,
        usb: usb,
        tempSetC: tempC,       // from filename, assume set temp
        tempC: null,           // not available from filename
        date: date,
        dateTime: null,        // not available from filename
        dateTimeLoc: null,     // not available from filename
        exposureSec: exposureSec, // for darks
        filter: filter         // for flats
    };
}

// --- Master frame parser (headers first, filename fallback) ---
function _fi_parseMaster(fullPath, rootPath){
    var fileName = _fi_basename(fullPath);

    // Skip DarkFlats completely
    if (/\b(DARKFLAT|FLATDARK)\b/i.test(fileName)){
        throw new Error("DarkFlat/FlatDark skipped");
    }

    // Try reading FITS headers first
    var parsed = null;
    try {
        var K = _fi_readFitsKeywords(fullPath);

        // Check if we have sufficient headers
        var telescope = _fi_upperOrNull(_fi_first(K["TELESCOP"], K["TELESCOPE"]));
        var camera = _fi_upperOrNull(_fi_first(K["INSTRUME"], K["INSTRUMENT"]));
        var imagetyp = _fi_upperOrNull(_fi_first(K["IMAGETYP"], K["IMAGETYPE"], K["FRAME"]));

        if (telescope && camera && imagetyp){
            // Parse from headers (similar to _fi_parseCalibration)
            var setup = telescope + "_" + camera;

            // Type: normalize BIAS/DARK/FLAT/DARKFLAT
            var type = "UNKNOWN";
            if (imagetyp == "BIAS" || imagetyp == "MASTERBIAS") type = "BIAS";
            else if (imagetyp == "DARK" || imagetyp == "MASTERDARK") type = "DARK";
            else if (imagetyp == "FLAT" || imagetyp == "MASTERFLAT") type = "FLAT";
            else if (imagetyp == "DARKFLAT" || imagetyp == "MASTERDARKFLAT") type = "DARKFLAT";

            // Acquisition params
            var xb = _fi_toInt(_fi_first(K["XBINNING"], K["BINNINGX"], K["XBIN"]));
            var yb = _fi_toInt(_fi_first(K["YBINNING"], K["BINNINGY"], K["YBIN"]));
            var binning = _fi_mkBinning(xb, yb);
            var gain = _fi_toInt(_fi_first(K["GAIN"], K["EGAIN"]));
            var offset = _fi_toInt(_fi_first(K["OFFSET"], K["QOFFSET"]));
            var usb = _fi_toInt(_fi_first(K["USBLIMIT"], K["QUSBLIM"]));
            var readout = _fi_cleanReadout(_fi_first(K["READOUTM"], K["QREADOUT"], K["READMODE"], K["CAMMODE"]));

            // Temperature
            var tempSetC = _fi_toInt(_fi_first(K["SET-TEMP"], K["SET_TEMP"], K["SETPOINT"], K["CCD-TSET"]));
            var tempC = _fi_toInt(_fi_first(K["CCD-TEMP"], K["CCD_TEMP"], K["SENSOR_TEMP"], K["CCD-TEMP1"]));

            // Date/Time
            var dateObsRaw = _fi_first(K["DATE-OBS"], K["DATE_OBS"], K["DATEOBS"], K["DATE"]);
            var dateLocRaw = _fi_first(K["DATE-LOC"], K["DATE_LOC"], K["DATELOC"]);
            var date = _fi_extractDateOnly(dateObsRaw);
            var dateTime = _fi_extractDateTime(dateObsRaw);
            var dateTimeLoc = _fi_extractDateTime(dateLocRaw);

            // Type-specific: exposure (for darks), filter (for flats)
            var exp = _fi_toFloat(_fi_first(K["EXPOSURE"], K["EXPTIME"], K["DURATION"]));
            var filter = _fi_normFilter(_fi_first(K["FILTER"]));

            parsed = {
                path: _fi_norm(fullPath),
                filename: fileName,
                type: type,
                parseMethod: "headers",  // parsed from FITS headers

                setup: setup,
                telescope: telescope,
                camera: camera,

                filter: filter,
                binning: binning,
                gain: gain,
                offset: offset,
                usb: usb,
                readout: readout,
                exposureSec: exp,
                tempSetC: tempSetC,
                tempC: tempC,

                date: date,
                dateTime: dateTime,
                dateTimeLoc: dateTimeLoc
            };
        }
    } catch(e){
        // Headers not available or insufficient, will fallback to filename
    }

    // Fallback to filename parsing if headers insufficient
    if (!parsed){
        parsed = _fi_parseByFilename(fullPath);
    }

    // If still no setup, try to derive from path
    if (!parsed.setup && rootPath){
        parsed.setup = _fi_setupFromPath(rootPath, fullPath);
        // Update parseMethod if setup was derived from path
        if (parsed.setup && parsed.parseMethod){
            if (parsed.parseMethod == "headers"){
                parsed.parseMethod = "mixed";  // headers + path
            } else if (parsed.parseMethod == "filename"){
                parsed.parseMethod = "mixed";  // filename + path
            }
        }
    }

    // Validate: must have type and setup
    if (!parsed.type){
        throw new Error("Cannot determine master type: " + fileName);
    }
    if (!parsed.setup){
        parsed.setup = "UNKNOWN_SETUP";
    }

    return parsed;
}

// --- Universal file parser (auto-detect type) ---
function _fi_parseFile(fullPath, rootPath){
    var fileName = _fi_basename(fullPath);
    var fileNameUp = fileName.toUpperCase();

    // Skip DarkFlats
    if (/\b(DARKFLAT|FLATDARK)\b/i.test(fileName)){
        throw new Error("DarkFlat/FlatDark skipped");
    }

    var detectedType = null; // "LIGHT", "CALIBRATION", "MASTER"
    var imagetyp = null;

    // Step 1: Try to detect type from FITS headers (priority!)
    try {
        var K = _fi_readFitsKeywords(fullPath);
        imagetyp = _fi_upperOrNull(_fi_first(K["IMAGETYP"], K["IMAGETYPE"], K["FRAME"]));

        if (imagetyp){
            // Normalize and detect type
            if (imagetyp == "LIGHT" || imagetyp == "LIGHTFRAME"){
                detectedType = "LIGHT";
            }
            else if (imagetyp == "MASTERBIAS" || imagetyp == "MASTERDARK" || imagetyp == "MASTERFLAT" || imagetyp == "MASTERDARKFLAT"){
                detectedType = "MASTER";
            }
            else if (imagetyp == "BIAS" || imagetyp == "DARK" || imagetyp == "FLAT"){
                // Could be raw calibration OR master (our masters have IMAGETYP='Dark'/'Flat'/'Bias')
                // Check filename to disambiguate
                if (fileNameUp.indexOf("MASTERDARKFLAT") >= 0 || fileNameUp.indexOf("MASTERFLATDARK") >= 0 ||
                    fileNameUp.indexOf("MASTERBIAS") >= 0 || fileNameUp.indexOf("MASTERDARK") >= 0 ||
                    fileNameUp.indexOf("MASTERFLAT") >= 0 || fileNameUp.indexOf("MASTERF") >= 0){
                    detectedType = "MASTER";  // Filename indicates master
                } else {
                    detectedType = "CALIBRATION";  // Raw calibration file
                }
            }
        }
    } catch(e){
        // Headers not available, will try filename detection
    }

    // Step 2: If type not detected from headers, try filename patterns
    if (!detectedType){
        if (fileNameUp.indexOf("_LIGHT_") >= 0 || fileNameUp.indexOf("_LIGHT.") >= 0){
            detectedType = "LIGHT";
        }
        else if (fileNameUp.indexOf("MASTERDARKFLAT") >= 0 || fileNameUp.indexOf("MASTERFLATDARK") >= 0 ||
                 fileNameUp.indexOf("MASTERBIAS") >= 0 || fileNameUp.indexOf("MASTERDARK") >= 0 ||
                 fileNameUp.indexOf("MASTERFLAT") >= 0 || fileNameUp.indexOf("MASTERF") >= 0){
            detectedType = "MASTER";
        }
        else if (fileNameUp.indexOf("_BIAS_") >= 0 || fileNameUp.indexOf("_DARK_") >= 0 ||
                 fileNameUp.indexOf("_FLAT_") >= 0){
            detectedType = "CALIBRATION";
        }
    }

    // Step 3: Route to appropriate parser
    var parsed = null;

    if (detectedType == "LIGHT"){
        parsed = _fi_parseLight(fullPath, rootPath);
    }
    else if (detectedType == "CALIBRATION"){
        parsed = _fi_parseCalibration(fullPath, rootPath);
    }
    else if (detectedType == "MASTER"){
        parsed = _fi_parseMaster(fullPath, rootPath);
    }
    else {
        throw new Error("Cannot determine file type: " + fileName);
    }

    // Add detected type info
    parsed.detectedType = detectedType;

    return parsed;
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
            var parsed = _fi_parseFile(fullPath, rootPath);

            // Only include LIGHT frames
            if (parsed.detectedType == "LIGHT"){
                results.push(parsed);
            }
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
            var parsed = _fi_parseFile(fullPath, rootPath);

            // Only include raw CALIBRATION frames (BIAS/DARK/FLAT)
            if (parsed.detectedType == "CALIBRATION"){
                results.push(parsed);
            }
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

        try {
            var parsed = _fi_parseFile(fullPath, rootPath);

            // Only include MASTER frames
            if (parsed.detectedType == "MASTER"){
                results.push(parsed);
            }
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

/* ============================================================================
 * Compatibility Wrappers
 * ============================================================================ */

/**
 * Compatibility wrapper for lights_index.jsh::LI_reindexLights()
 * @param rootPath - directory to scan
 * @param savePath - output JSON file path
 * @returns {count, errors, time} statistics
 */
function LI_reindexLights(rootPath, savePath){
    return FI_indexLights(rootPath, savePath);
}

/**
 * Compatibility wrapper for masters_index.jsh::MI_reindexMasters()
 * @param rootPath - directory to scan
 * @param savePath - output JSON file path
 * @returns {count, errors, time} statistics
 */
function MI_reindexMasters(rootPath, savePath){
    return FI_indexMasters(rootPath, savePath);
}

#endif // __ANAWBPPS_FITS_INDEXING_JSH
