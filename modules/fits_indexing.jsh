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

/* TODO: _fi_parseMaster() */
/* TODO: Filename fallback parser (_fi_parseByFilename) */

/* ============================================================================
 * LEVEL 3: Public API (prefix FI_)
 * ============================================================================ */

/* TODO: FI_indexLights(rootPath, savePath) */
/* TODO: FI_indexCalibration(rootPath, savePath) */
/* TODO: FI_indexMasters(rootPath, savePath) */
/* TODO: FI_parseFile(path, type) */
/* TODO: FI_saveJSON(path, obj) */

/* ============================================================================
 * Compatibility Wrappers
 * ============================================================================ */

/* TODO: LI_reindexLights(rootPath, savePath) */
/* TODO: MI_reindexMasters(rootPath, savePath) */

#endif // __ANAWBPPS_FITS_INDEXING_JSH
