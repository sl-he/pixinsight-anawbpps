/*
 * ANAWBPPS - Master calibration files creator
 * Creates master Bias/Dark/DarkFlat/Flat from raw calibration frames
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

/* -------- Helper functions (reused from lights_parse) -------- */

function _mc_basename(p){
    var s = String(p||"").replace(/\\/g,'/');
    var i = s.lastIndexOf('/');
    return (i>=0) ? s.substring(i+1) : s;
}

function _mc_toInt(v){
    if (v === undefined || v === null) return null;
    var s = String(v).trim();
    if (s.length === 0) return null;
    var n = Number(s);
    if (!isFinite(n)) return null;
    return Math.round(n);
}

function _mc_toFloat(v){
    if (v === undefined || v === null) return null;
    var s = String(v).trim();
    if (s.length === 0) return null;
    var n = Number(s);
    if (!isFinite(n)) return null;
    return n;
}

function _mc_first(){
    for (var i=0; i<arguments.length; ++i){
        var v = arguments[i];
        if (v !== undefined && v !== null) return v;
    }
    return null;
}

function _mc_upperOrNull(s){
    if (!s && s!==0) return null;
    var t = String(s).trim();
    return t.length ? t.toUpperCase() : null;
}

function _mc_cleanReadout(s){
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

function _mc_mkBinning(xb, yb){
    var xi = _mc_toInt(xb);
    var yi = _mc_toInt(yb);
    if (xi!==null && yi!==null) return xi + "x" + yi;
    return null;
}

/* Extract YYYY-MM-DD from DATE-OBS */
function _mc_extractDateOnly(s){
    if (!s) return null;
    var str = String(s).trim();
    if (!str.length) return null;

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
        if (!(s1==='-' || s1==='_' || s1==='/')) continue;

        var m = readNDigits(str, i+5, 2); if (m===null) continue;
        var s2Pos = i+7; if (s2Pos >= L) continue;
        var s2 = str.charAt(s2Pos);
        if (!(s2==='-' || s2==='_' || s2==='/')) continue;

        var d = readNDigits(str, i+8, 2); if (d===null) continue;

        if (m<1 || m>12) continue;
        if (d<1 || d>31) continue;
        if (y<1900 || y>2100) continue;

        var mm = (m<10) ? ("0"+m) : String(m);
        var dd = (d<10) ? ("0"+d) : String(d);
        return String(y) + "-" + mm + "-" + dd;
    }
    return null;
}

/* Convert date string to YYYY_MM_DD format for filename */
function _mc_dateToFilename(dateStr){
    if (!dateStr) return null;
    // dateStr is "YYYY-MM-DD", replace - with _
    var result = "";
    for (var i=0; i<dateStr.length; ++i){
        var c = dateStr.charAt(i);
        result += (c === "-") ? "_" : c;
    }
    return result;
}

/* -------- FITS keyword reading -------- */

function _mc_readFitsKeywords(filePath){
    var F = new FileFormat;
    if (!F.canRead(filePath)){
        Console.warningln("[mc] Cannot read file: " + filePath);
        return null;
    }
    var f = new FileFormatInstance(F);
    var imgDesc = new ImageDescription;

    if (!f.open(filePath, "raw cfa verbosity 0")){
        Console.warningln("[mc] Failed to open: " + filePath);
        return null;
    }

    if (f.images.length === 0){
        f.close();
        return null;
    }

    f.selectImage(0);
    var K = f.keywords;
    f.close();

    var kv = {};
    for (var i=0; i<K.length; ++i){
        var name = K[i].name;
        var value = K[i].strippedValue;
        kv[name] = value;
    }
    return kv;
}

/* -------- Main functions -------- */

/**
 * Index raw calibration files (recursive)
 * Returns array of file objects with metadata
 */
function MC_indexRawFiles(rawPath){
    Console.noteln("[mc] Indexing raw calibration files from: " + rawPath);

    var files = [];

    function scanDir(dir){
        var fileList = File.findFileList(dir, "*.xisf,*.fits,*.fit");
        for (var i=0; i<fileList.length; ++i){
            files.push(dir + "/" + fileList[i]);
        }

        var subdirs = File.findDirectoryList(dir);
        for (var j=0; j<subdirs.length; ++j){
            scanDir(dir + "/" + subdirs[j]);
        }
    }

    scanDir(rawPath);

    Console.noteln("[mc]   Found " + files.length + " files");

    // Parse FITS headers
    var items = [];
    for (var i=0; i<files.length; ++i){
        var fpath = files[i];
        var K = _mc_readFitsKeywords(fpath);
        if (!K) continue;

        // Extract metadata
        var imageType = _mc_upperOrNull(K["IMAGETYP"]);
        if (!imageType) continue; // Skip files without IMAGETYP

        var dateObs = _mc_extractDateOnly(K["DATE-OBS"]);
        var filter = _mc_upperOrNull(K["FILTER"]);
        var gain = _mc_toInt(K["GAIN"]);
        var offset = _mc_toInt(K["OFFSET"]);
        var usb = _mc_toInt(_mc_first(K["USBLIMIT"], K["QUSBLIM"]));
        var binning = _mc_mkBinning(K["XBINNING"], K["YBINNING"]);
        var readout = _mc_cleanReadout(K["READOUTM"]);
        var setTemp = _mc_toFloat(K["SET-TEMP"]);
        var expTime = _mc_toFloat(K["EXPTIME"]);

        // Extract setup from filename (like in lights_parse)
        var basename = _mc_basename(fpath);
        var setup = null;
        // Simple parsing: assume format like "L71_ES150_QHY600M_*.fits"
        // Extract first 3 underscore-separated parts
        var parts = basename.split("_");
        if (parts.length >= 3){
            setup = parts[0] + "_" + parts[1] + "_" + parts[2];
        }

        items.push({
            path: fpath,
            imageType: imageType,
            dateObs: dateObs,
            filter: filter,
            gain: gain,
            offset: offset,
            usb: usb,
            binning: binning,
            readout: readout,
            setTemp: setTemp,
            expTime: expTime,
            setup: setup
        });
    }

    Console.noteln("[mc]   Parsed " + items.length + " valid calibration files");

    return items;
}

/**
 * Group indexed files by parameters
 * Returns { darks: [], darkFlats: [], flats: [] }
 */
function MC_groupByParams(items){
    Console.noteln("[mc] Grouping by parameters...");

    // Separate by type first
    var darks = [];
    var darkFlats = [];
    var flats = [];

    for (var i=0; i<items.length; ++i){
        var item = items[i];
        var type = item.imageType;

        // TODO: Replace with actual IMAGETYP values when known
        if (type === "DARK"){
            darks.push(item);
        } else if (type === "DARKFLAT" || type === "DARK FLAT"){
            darkFlats.push(item);
        } else if (type === "FLAT" || type === "FLAT FIELD"){
            flats.push(item);
        }
    }

    Console.noteln("[mc]   Dark files: " + darks.length);
    Console.noteln("[mc]   DarkFlat files: " + darkFlats.length);
    Console.noteln("[mc]   Flat files: " + flats.length);

    // Group Darks
    var darkGroups = _mc_groupByParamsInternal(darks, 7, false);
    Console.noteln("[mc]   Dark groups: " + darkGroups.length);

    // Group DarkFlats
    var darkFlatGroups = _mc_groupByParamsInternal(darkFlats, 3, true);
    Console.noteln("[mc]   DarkFlat groups: " + darkFlatGroups.length);

    // Group Flats
    var flatGroups = _mc_groupByParamsInternal(flats, 3, true);
    Console.noteln("[mc]   Flat groups: " + flatGroups.length);

    return {
        darks: darkGroups,
        darkFlats: darkFlatGroups,
        flats: flatGroups
    };
}

/**
 * Internal grouping function
 * maxDateDiff - maximum date difference in days
 * requireFilter - whether filter is required for grouping
 */
function _mc_groupByParamsInternal(items, maxDateDiff, requireFilter){
    var groups = {};

    for (var i=0; i<items.length; ++i){
        var item = items[i];

        // Build group key (without date)
        var keyParts = [];
        keyParts.push(item.setup || "UNKNOWN");

        if (requireFilter){
            keyParts.push(item.filter || "NOFILTER");
        }

        keyParts.push(item.readout || "UNKNOWN");
        keyParts.push("G" + (item.gain !== null ? item.gain : "NULL"));
        keyParts.push("OS" + (item.offset !== null ? item.offset : "NULL"));
        keyParts.push("U" + (item.usb !== null ? item.usb : "NULL"));
        keyParts.push(item.binning || "UNKNOWN");

        // Temperature with tolerance ±0.1°C
        var temp = item.setTemp !== null ? Math.round(item.setTemp * 10) / 10 : null;
        keyParts.push("T" + (temp !== null ? temp : "NULL"));

        // Exposure time
        keyParts.push("E" + (item.expTime !== null ? item.expTime : "NULL"));

        var baseKey = keyParts.join("_");

        if (!groups[baseKey]){
            groups[baseKey] = [];
        }
        groups[baseKey].push(item);
    }

    // Now split groups by date if needed
    var finalGroups = [];

    for (var gkey in groups){
        if (!groups.hasOwnProperty(gkey)) continue;

        var groupItems = groups[gkey];

        // Sort by date
        groupItems.sort(function(a, b){
            var dateA = a.dateObs || "9999-99-99";
            var dateB = b.dateObs || "9999-99-99";
            if (dateA < dateB) return -1;
            if (dateA > dateB) return 1;
            return 0;
        });

        // Split by date difference
        var currentSubgroup = [];
        var currentMinDate = null;

        for (var j=0; j<groupItems.length; ++j){
            var item = groupItems[j];
            var itemDate = item.dateObs;

            if (!itemDate){
                // Skip items without date
                continue;
            }

            if (currentMinDate === null){
                // Start new subgroup
                currentMinDate = itemDate;
                currentSubgroup.push(item);
            } else {
                // Check date difference
                var daysDiff = _mc_dateDiffDays(currentMinDate, itemDate);

                if (daysDiff <= maxDateDiff){
                    // Add to current subgroup
                    currentSubgroup.push(item);
                } else {
                    // Finish current subgroup and start new one
                    if (currentSubgroup.length >= 30){
                        finalGroups.push({
                            key: gkey,
                            minDate: currentMinDate,
                            items: currentSubgroup
                        });
                    } else {
                        Console.warningln("[mc]   Skipping group " + gkey + " (only " + currentSubgroup.length + " files, need ≥30)");
                    }

                    currentMinDate = itemDate;
                    currentSubgroup = [item];
                }
            }
        }

        // Don't forget last subgroup
        if (currentSubgroup.length >= 30){
            finalGroups.push({
                key: gkey,
                minDate: currentMinDate,
                items: currentSubgroup
            });
        } else if (currentSubgroup.length > 0){
            Console.warningln("[mc]   Skipping group " + gkey + " (only " + currentSubgroup.length + " files, need ≥30)");
        }
    }

    return finalGroups;
}

/**
 * Calculate difference between two dates in days
 * Dates in format YYYY-MM-DD
 */
function _mc_dateDiffDays(date1, date2){
    // Simple implementation: convert to timestamps
    function parseDate(dateStr){
        // dateStr is "YYYY-MM-DD"
        var parts = dateStr.split("-");
        if (parts.length !== 3) return null;
        var y = parseInt(parts[0], 10);
        var m = parseInt(parts[1], 10);
        var d = parseInt(parts[2], 10);
        // JavaScript Date months are 0-based
        return new Date(y, m-1, d);
    }

    var d1 = parseDate(date1);
    var d2 = parseDate(date2);

    if (!d1 || !d2) return 999999; // Invalid dates

    var diffMs = Math.abs(d2.getTime() - d1.getTime());
    var diffDays = diffMs / (1000 * 60 * 60 * 24);

    return diffDays;
}

/**
 * Generate master file name from group
 * type: "Dark", "DarkFlat", "Flat"
 */
function MC_generateMasterFileName(group, type){
    // Extract parameters from first item in group
    var firstItem = group.items[0];

    var parts = [];
    parts.push(firstItem.setup || "UNKNOWN");
    parts.push("Master" + type); // MasterDark, MasterDarkFlat, MasterFlat

    parts.push(_mc_dateToFilename(group.minDate)); // YYYY_MM_DD

    if (type === "DarkFlat" || type === "Flat"){
        if (firstItem.filter){
            parts.push(firstItem.filter);
        }
    }

    parts.push(firstItem.readout || "UNKNOWN");
    parts.push("G" + (firstItem.gain !== null ? firstItem.gain : "NULL"));
    parts.push("OS" + (firstItem.offset !== null ? firstItem.offset : "NULL"));

    if (firstItem.usb !== null){
        parts.push("U" + firstItem.usb);
    }

    parts.push("Bin" + (firstItem.binning || "UNKNOWN"));

    // Exposure: 3 digits (000s, 005s, 120s)
    var exp = firstItem.expTime !== null ? firstItem.expTime : 0;
    var expStr = String(Math.round(exp));
    while (expStr.length < 3) expStr = "0" + expStr;
    parts.push(expStr + "s");

    var temp = firstItem.setTemp !== null ? Math.round(firstItem.setTemp) : 0;
    parts.push(temp + "C");

    return parts.join("_") + ".xisf";
}

/**
 * Create MasterDark files
 */
function MC_createMasterDarks(darkGroups, outputPath){
    Console.noteln("[mc] Creating MasterDark files... (" + darkGroups.length + " groups)");

    // TODO: Implement ImageIntegration for each group

    Console.noteln("[mc]   TODO: MasterDark creation not implemented yet");

    return [];
}

/**
 * Create MasterDarkFlat files
 */
function MC_createMasterDarkFlats(dfGroups, outputPath){
    Console.noteln("[mc] Creating MasterDarkFlat files... (" + dfGroups.length + " groups)");

    // TODO: Implement ImageIntegration for each group

    Console.noteln("[mc]   TODO: MasterDarkFlat creation not implemented yet");

    return [];
}

/**
 * Match MasterDarkFlat to Flat groups
 */
function MC_matchDarkFlatToFlat(dfMasters, flatGroups){
    Console.noteln("[mc] Matching MasterDarkFlat to Flat groups...");

    // TODO: Implement matching logic (from calibration_match.jsh)

    Console.noteln("[mc]   TODO: Matching not implemented yet");

    return {};
}

/**
 * Calibrate Flat files using MasterDarkFlat
 */
function MC_calibrateFlats(flatGroups, dfMatches, tempPath){
    Console.noteln("[mc] Calibrating Flat files...");

    // TODO: Implement ImageCalibration

    Console.noteln("[mc]   TODO: Flat calibration not implemented yet");

    return {};
}

/**
 * Create MasterFlat files
 */
function MC_createMasterFlats(calibratedFlatGroups, outputPath){
    Console.noteln("[mc] Creating MasterFlat files... (" + calibratedFlatGroups.length + " groups)");

    // TODO: Implement ImageIntegration for calibrated flats

    Console.noteln("[mc]   TODO: MasterFlat creation not implemented yet");

    return [];
}

/**
 * Main function: Create all master calibration files
 */
function MC_createMasters(rawPath, mastersPath, work2Path){
    Console.noteln("[mc] Creating master calibration files...");
    Console.noteln("[mc]   Raw path: " + rawPath);
    Console.noteln("[mc]   Masters path: " + mastersPath);
    Console.noteln("[mc]   Work2 path: " + work2Path);

    // 1. Index raw files
    var rawIndex = MC_indexRawFiles(rawPath);
    if (!rawIndex || rawIndex.length === 0){
        throw new Error("No valid calibration files found");
    }

    // 2. Group by parameters
    var groups = MC_groupByParams(rawIndex);

    // 3. Create MasterDarks
    var outputPath = mastersPath || (work2Path + "/!Integrated");
    var masterDarks = MC_createMasterDarks(groups.darks, outputPath);

    // 4. Create MasterDarkFlats
    var masterDarkFlats = MC_createMasterDarkFlats(groups.darkFlats, outputPath);

    // 5. Match DarkFlats to Flats
    var dfMatches = MC_matchDarkFlatToFlat(masterDarkFlats, groups.flats);

    // 6. Calibrate Flats
    var tempPath = work2Path + "/Masters_Temp";
    var calibratedFlats = MC_calibrateFlats(groups.flats, dfMatches, tempPath);

    // 7. Create MasterFlats
    var masterFlats = MC_createMasterFlats(calibratedFlats, outputPath);

    Console.noteln("[mc] Complete!");
    Console.noteln("[mc]   MasterDark: " + masterDarks.length);
    Console.noteln("[mc]   MasterDarkFlat: " + masterDarkFlats.length);
    Console.noteln("[mc]   MasterFlat: " + masterFlats.length);
}
