/*
 * ANAWBPPS - Light frames parser (header-only)
 * Extracts metadata from FITS/XISF light frames without loading image data
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

/* -------- Small helpers (no regex to avoid PI parser quirks) -------- */

function _basename(p){
    var s = String(p||"").replace(/\\/g,'/');
    var i = s.lastIndexOf('/');
    return (i>=0) ? s.substring(i+1) : s;
}

function _toInt(v){
    if (v === undefined || v === null) return null;
    var s = String(v).trim();
    if (s.length === 0) return null;
    var n = Number(s);
    if (!isFinite(n)) return null;
    return Math.round(n);
}

function _toFloat(v){
    if (v === undefined || v === null) return null;
    var s = String(v).trim();
    if (s.length === 0) return null;
    var n = Number(s);
    if (!isFinite(n)) return null;
    return n;
}

function _mkBinning(xb, yb){
    var xi = _toInt(xb);
    var yi = _toInt(yb);
    if (xi!==null && yi!==null) return xi + "x" + yi;
    return null;
}

function _upperOrNull(s){
    if (!s && s!==0) return null;
    var t = String(s).trim();
    return t.length ? t.toUpperCase() : null;
}

function _cleanReadout(s){
    if (!s && s!==0) return null;
    var t = String(s).trim();
    // Compact multiple spaces
    var out = "";
    var prevSpace = false;
    for (var i=0;i<t.length;++i){
        var c = t.charAt(i);
        var isSpace = (c===" " || c==="\t");
        if (isSpace){
            if (!prevSpace){ out += " "; prevSpace = true; }
        } else {
            out += c; prevSpace = false;
        }
    }
    return out.length ? out : null;
}

/* Extract YYYY-MM-DD from various DATE-OBS variants (no regex). */
function _extractDateOnly(s){
    if (!s) return null;
    var str = String(s).trim();
    if (!str.length) return null;

    // Many formats: "YYYY-MM-DDTHH:MM:SS", "YYYY-MM-DD HH:MM:SS",
    // "YYYY_MM_DD_HH-MM-SS", "YYYY/MM/DD", etc.
    // Strategy: scan for 4 digits (year), then a sep, then 2 digits, sep, 2 digits.
    function isDigit(ch){ return ch>='0' && ch<='9'; }
    function readNDigits(src, pos, n){
        if (pos+n > src.length) return null;
        var val = 0;
        for (var k=0;k<n;++k){
            var ch = src.charAt(pos+k);
            if (!isDigit(ch)) return null;
            val = val*10 + (ch.charCodeAt(0)-48);
        }
        return val;
    }
    var L = str.length;
    for (var i=0;i<=L-10;++i){
        var y = readNDigits(str, i, 4); if (y===null) continue;
        var s1Pos = i+4; if (s1Pos >= L) continue;
        var s1 = str.charAt(s1Pos);
        // Accept '-', '_', '/', or nothing (rare)
        if (!(s1==='-' || s1==='_' || s1==='/')) continue;

        var m = readNDigits(str, i+5, 2); if (m===null) continue;
        var s2Pos = i+7; if (s2Pos >= L) continue;
        var s2 = str.charAt(s2Pos);
        if (!(s2==='-' || s2==='_' || s2==='/')) continue;

        var d = readNDigits(str, i+8, 2); if (d===null) continue;

        // Basic range sanity
        if (m<1 || m>12 || d<1 || d>31) continue;

        // Build YYYY-MM-DD
        var mm = (m<10) ? ("0"+m) : String(m);
        var dd = (d<10) ? ("0"+d) : String(d);
        return String(y) + "-" + mm + "-" + dd;
    }
    return null;
}

/* Pick first non-null from a list */
function _first(){
    for (var i=0;i<arguments.length;++i)
        if (arguments[i] !== null && arguments[i] !== undefined)
            return arguments[i];
    return null;
}

/* -------- Keyword access -------- */

function _keywordsToMap(karray){
    // Build a simple { NAME: value } map, NAME in upper-case
    var map = {};
    for (var i=0;i<karray.length;++i){
        var k = karray[i];
        // Some builds have .trim() producing .strippedValue
        var name = String(k.name||"").trim();
        var val  = (typeof k.strippedValue !== "undefined") ? k.strippedValue : k.value;
        if (typeof k.trim === "function") {
            try { k.trim(); val = (typeof k.strippedValue !== "undefined") ? k.strippedValue : k.value; } catch(_){}
        }
        if (!name.length) continue;
        map[name.toUpperCase()] = val;
    }
    return map;
}

/* -------- Main: header-only light parser -------- */

function LI_parseLight(fullPath){
    var filePath = String(fullPath||"");
    var fileName = _basename(filePath);

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
    var width=null, height=null;
    try{
        var id = info[0];
        if (id){
            if (typeof id.width  !== "undefined")  width  = id.width;
            if (typeof id.height !== "undefined")  height = id.height;
        }
    } catch(_){}

    inst.close();

    var K = _keywordsToMap(kws);

    // Core identity
    var telescope = _upperOrNull(_first(K["TELESCOP"], K["TELESCOPE"]));
    var camera    = _upperOrNull(_first(K["INSTRUME"], K["INSTRUMENT"]));
    var object    = (function(v){
        var t = (v==null) ? "" : String(v).trim();
        return t.length ? t : null;
    })(K["OBJECT"]);
    var imagetyp  = _upperOrNull(_first(K["IMAGETYP"], K["IMAGETYPE"], K["FRAME"]));

    // Enforce LIGHT type (if present and not LIGHT, still return "LIGHT" — we index only lights)
    var type = "LIGHT";

    // Photometric / acquisition params
    var filter   = _upperOrNull(K["FILTER"]);
    var readout  = _cleanReadout(_first(K["READOUTM"], K["QREADOUT"], K["READMODE"], K["CAMMODE"]));
    var gain     = _toInt(_first(K["GAIN"], K["EGAIN"]));       // prefer integer
    var offset   = _toInt(_first(K["OFFSET"], K["QOFFSET"]));
    var usb      = _toInt(_first(K["USBLIMIT"], K["QUSBLIM"]));

    var xbin     = _first(K["XBINNING"], K["BINNINGX"], K["XBIN"], K["XBINNING_BIN"]);
    var ybin     = _first(K["YBINNING"], K["BINNINGY"], K["YBIN"], K["YBINNING_BIN"]);
    var binning  = _mkBinning(xbin, ybin);

    var exp      = _toFloat(_first(K["EXPOSURE"], K["EXPTIME"], K["DURATION"])); // seconds

    var tSet     = _toFloat(_first(K["SET-TEMP"], K["SET_TEMP"], K["SETPOINT"], K["CCD-TSET"]));
    var tCcd     = _toFloat(_first(K["CCD-TEMP"], K["CCD_TEMP"], K["SENSOR_TEMP"], K["CCD-TEMP1"]));
    // Normalize to integer °C (round), keep null if absent
    var tempSetC = (tSet==null) ? null : Math.round(tSet);
    var tempC    = (tCcd==null) ? null : Math.round(tCcd);

    var focalLen = _toFloat(_first(K["FOCALLEN"], K["FOCAL"], K["FOCLEN"]));

    var dateObs  = (function(){
        var v = _first(K["DATE-OBS"], K["DATE_OBS"], K["DATEOBS"], K["DATE"]);
        var only = _extractDateOnly(v);
        return only;
    })();

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
        filter: filter,          // L/R/G/B/HA/OIII/SII etc.
        binning: binning,        // "1x1", "2x2"
        gain: gain,              // int or null
        offset: offset,          // int or null
        usb: usb,                // int or null
        readout: readout,        // string or null
        exposureSec: exp,        // float seconds or null
        tempSetC: tempSetC,      // int or null
        tempC: tempC,            // int or null
        date: dateObs,           // "YYYY-MM-DD" or null
        focalLen: focalLen,      // mm (float) or null

        // geometry (no pixel read)
        width: width,
        height: height
    };
}
