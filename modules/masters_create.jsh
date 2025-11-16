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

// Common utility functions (_mc_basename, _mc_toInt, _mc_toFloat, _mc_first,
// _mc_upperOrNull, _mc_cleanReadout, _mc_mkBinning) removed - now using CU_* from common_utils.jsh

/* Extract full DATE-OBS with time (ISO 8601 format) */
function _mc_extractDateTimeISO(s){
    if (!s) return null;
    var str = String(s).trim();
    // Return as-is if it looks like ISO format (YYYY-MM-DDTHH:MM:SS)
    if (str.match(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/)){
        return str;
    }
    return null;
}

// _mc_extractDateOnly removed - now using CU_extractDateOnly from common_utils.jsh

/* Convert date string to YYYY_MM_DD format for filename */
function _mc_dateToFilename(dateStr){
    if (!dateStr) return null;
    // dateStr is "YYYY-MM-DD", replace - with _
    var result = "";
    for (var i=0; i<dateStr.length; ++i){
        var c = dateStr.charAt(i);
        result += (c == "-") ? "_" : c;
    }
    return result;
}

/* -------- FITS keyword reading -------- */

function _mc_readFitsKeywords(filePath){
    var ext = File.extractExtension(filePath).toLowerCase();
    var F = new FileFormat(ext, true, false);
    if (F.isNull){
        Console.warningln("[mc] Unsupported file format: " + filePath);
        return null;
    }

    var f = new FileFormatInstance(F);
    if (f.isNull){
        Console.warningln("[mc] Cannot create FileFormatInstance: " + filePath);
        return null;
    }

    var info = f.open(filePath, "verbosity 0");
    if (!info || (info && info.length <= 0)){
        f.close();
        return null;
    }

    var kws = [];
    if (F.canStoreKeywords)
        kws = f.keywords;

    f.close();

    var kv = {};
    for (var i=0; i<kws.length; ++i){
        var name = kws[i].name;
        var value = kws[i].strippedValue;
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

    function isFitsLike(path){
        var ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        return ext == "fit" || ext == "fits" || ext == "xisf" || ext == "fts";
    }

    function listDir(dir){
        // Use _fi_listNames from fits_indexing.jsh (uses FileFind - works cross-platform)
        if (typeof _fi_listNames == "function"){
            try {
                return _fi_listNames(dir);
            } catch(e){
                Console.warningln("[mc] _fi_listNames failed: " + e);
            }
        } else {
            Console.warningln("[mc] _fi_listNames function not found (fits_indexing.jsh not loaded?)");
        }
        return [];
    }

    function scanDir(dir){
        var names = listDir(dir);
        Console.writeln("[mc]   Scanning: " + dir + " (" + names.length + " entries)");

        for (var i=0; i<names.length; ++i){
            var name = names[i];
            if (name == "." || name == "..") continue;

            var fullPath = dir + "/" + name;

            // Check if directory
            if (File.directoryExists(fullPath)){
                Console.writeln("[mc]     DIR: " + name);
                scanDir(fullPath);
            }
            // Check if file
            else if (File.exists(fullPath)){
                if (isFitsLike(fullPath)){
                    Console.writeln("[mc]     FILE: " + name);
                    files.push(fullPath);
                } else {
                    Console.writeln("[mc]     SKIP (not FITS): " + name);
                }
            }
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
        var imageType = CU_upperOrNull(K["IMAGETYP"]);
        if (!imageType) continue; // Skip files without IMAGETYP

        var dateObs = CU_extractDateOnly(K["DATE-OBS"]);
        var dateTimeObs = _mc_extractDateTimeISO(K["DATE-OBS"]); // Full date-time for precise grouping

        // TODO-35: Sanitize filter to avoid invalid characters in paths/filenames
        var filterRaw = CU_upperOrNull(K["FILTER"]);
        var filter = filterRaw ? CU_sanitizeKey(filterRaw) : null;

        var bayerPattern = CU_upperOrNull(K["BAYERPAT"]); // TODO-32: Read Bayer pattern for CFA
        var gain = CU_toInt(K["GAIN"]);
        var offset = CU_toInt(K["OFFSET"]);
        var usb = CU_toInt(CU_first(K["USBLIMIT"], K["QUSBLIM"]));
        var binning = CU_mkBinning(K["XBINNING"], K["YBINNING"]);

        // TODO-35: Sanitize readout mode to avoid invalid characters
        var readoutRaw = CU_cleanReadout(K["READOUTM"]);
        var readout = readoutRaw ? CU_sanitizeKey(readoutRaw) : null;

        var setTemp = CU_toFloat(K["SET-TEMP"]);
        var expTime = CU_toFloat(K["EXPTIME"]);

        // TODO-35: Sanitize telescop and instrume
        var telescop = CU_upperOrNull(CU_first(K["TELESCOP"], K["TELESCOPE"]));
        if (telescop) telescop = CU_sanitizeKey(telescop);

        var instrume = CU_upperOrNull(CU_first(K["INSTRUME"], K["INSTRUMENT"]));
        if (instrume) instrume = CU_sanitizeKey(instrume);

        // Setup: TELESCOP + "_" + INSTRUME (already sanitized above)
        var setup = null;
        if (telescop && instrume){
            setup = telescop + "_" + instrume;
        } else if (telescop){
            setup = telescop;
        } else if (instrume){
            setup = instrume;
        }

        items.push({
            path: fpath,
            imageType: imageType,
            dateObs: dateObs,
            dateTimeObs: dateTimeObs, // Full date-time with hours:minutes:seconds
            filter: filter,
            bayerPattern: bayerPattern, // TODO-32: Bayer pattern for CFA
            gain: gain,
            offset: offset,
            usb: usb,
            binning: binning,
            readout: readout,
            setTemp: setTemp,
            expTime: expTime,
            setup: setup,
            telescop: telescop,
            instrume: instrume
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
        var hasFilter = (item.filter != null && item.filter != "");

        // Логика определения типа:
        // - IMAGETYP = "DARK" + НЕТ FILTER → Dark
        // - IMAGETYP = "DARK" + ЕСТЬ FILTER → DarkFlat
        // - IMAGETYP = "FLAT" + ЕСТЬ FILTER → Flat
        if (type == "DARK"){
            if (hasFilter){
                darkFlats.push(item);
            } else {
                darks.push(item);
            }
        } else if (type == "FLAT"){
            flats.push(item);
        }
    }

    Console.noteln("[mc]   Dark files: " + darks.length);
    Console.noteln("[mc]   DarkFlat files: " + darkFlats.length);
    Console.noteln("[mc]   Flat files: " + flats.length);

    // Group Darks (±7 days tolerance)
    var darkGroups = _mc_groupByParamsInternal(darks, 7, false);
    Console.noteln("[mc]   Dark groups: " + darkGroups.length);

    // Group DarkFlats (±3 hours tolerance)
    var darkFlatGroups = _mc_groupByParamsInternal(darkFlats, 3, true, true);
    Console.noteln("[mc]   DarkFlat groups: " + darkFlatGroups.length);

    // Group Flats (±3 hours tolerance)
    var flatGroups = _mc_groupByParamsInternal(flats, 3, true, true);
    Console.noteln("[mc]   Flat groups: " + flatGroups.length);

    return {
        darks: darkGroups,
        darkFlats: darkFlatGroups,
        flats: flatGroups
    };
}

/**
 * Internal grouping function
 * maxDateDiff - maximum date difference (in days or hours depending on useHours)
 * requireFilter - whether filter is required for grouping
 * useHours - if true, maxDateDiff is in hours and uses dateTimeObs; if false, uses days and dateObs
 */
function _mc_groupByParamsInternal(items, maxDateDiff, requireFilter, useHours){
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
        keyParts.push("G" + (item.gain != null ? item.gain : "NULL"));
        keyParts.push("OS" + (item.offset != null ? item.offset : "NULL"));
        keyParts.push("U" + (item.usb != null ? item.usb : "NULL"));
        keyParts.push(item.binning || "UNKNOWN");

        // Temperature with tolerance ±0.1°C
        var temp = item.setTemp != null ? Math.round(item.setTemp * 10) / 10 : null;
        keyParts.push("T" + (temp != null ? temp : "NULL"));

        // Exposure time
        keyParts.push("E" + (item.expTime != null ? item.expTime : "NULL"));

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

        // Sort by date or date-time depending on useHours
        if (useHours){
            groupItems.sort(function(a, b){
                var dateA = a.dateTimeObs || "9999-99-99T99:99:99";
                var dateB = b.dateTimeObs || "9999-99-99T99:99:99";
                if (dateA < dateB) return -1;
                if (dateA > dateB) return 1;
                return 0;
            });
        } else {
            groupItems.sort(function(a, b){
                var dateA = a.dateObs || "9999-99-99";
                var dateB = b.dateObs || "9999-99-99";
                if (dateA < dateB) return -1;
                if (dateA > dateB) return 1;
                return 0;
            });
        }

        // Split by date difference
        var currentSubgroup = [];
        var currentMinDate = null;
        var currentOldestDate = null; // Track oldest date in subgroup
        var currentMinDateOnly = null; // Track date-only for filename

        for (var j=0; j<groupItems.length; ++j){
            var item = groupItems[j];
            var itemDate = useHours ? item.dateTimeObs : item.dateObs;
            var itemDateOnly = item.dateObs; // Always use date-only for filename

            if (!itemDate){
                // Skip items without date
                continue;
            }

            if (currentMinDate == null){
                // Start new subgroup
                currentMinDate = itemDate;
                currentOldestDate = itemDate;
                currentMinDateOnly = itemDateOnly;
                currentSubgroup.push(item);
            } else {
                // Check date difference
                var diff = useHours ?
                    _mc_dateTimeDiffHours(currentMinDate, itemDate) :
                    _mc_dateDiffDays(currentMinDate, itemDate);

                if (diff <= maxDateDiff){
                    // Add to current subgroup
                    currentSubgroup.push(item);
                    // Update oldest date if needed
                    if (itemDate < currentOldestDate){
                        currentOldestDate = itemDate;
                    }
                } else {
                    // Finish current subgroup and start new one
                    if (currentSubgroup.length >= 30){
                        finalGroups.push({
                            key: gkey,
                            minDate: currentMinDateOnly, // Use date-only for filename
                            oldestDate: currentOldestDate,
                            items: currentSubgroup
                        });
                    } else {
                        Console.warningln("[mc]   Skipping group " + gkey + " (only " + currentSubgroup.length + " files, need ≥30)");
                    }

                    currentMinDate = itemDate;
                    currentOldestDate = itemDate;
                    currentMinDateOnly = itemDateOnly;
                    currentSubgroup = [item];
                }
            }
        }

        // Don't forget last subgroup
        if (currentSubgroup.length >= 30){
            finalGroups.push({
                key: gkey,
                minDate: currentMinDateOnly, // Use date-only for filename
                oldestDate: currentOldestDate,
                items: currentSubgroup
            });
        } else if (currentSubgroup.length > 0){
            Console.warningln("[mc]   Skipping group " + gkey + " (only " + currentSubgroup.length + " files, need ≥30)");
        }
    }

    return finalGroups;
}

/**
 * Calculate difference between two date-times in hours
 * DateTime in ISO format YYYY-MM-DDTHH:MM:SS
 */
function _mc_dateTimeDiffHours(dateTime1, dateTime2){
    if (!dateTime1 || !dateTime2) return 999999;

    try {
        // Parse ISO 8601 format: YYYY-MM-DDTHH:MM:SS
        var d1 = new Date(dateTime1);
        var d2 = new Date(dateTime2);

        if (isNaN(d1.getTime()) || isNaN(d2.getTime())) return 999999;

        var diffMs = Math.abs(d2.getTime() - d1.getTime());
        var diffHours = diffMs / (1000 * 60 * 60);

        return diffHours;
    } catch(e){
        return 999999;
    }
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
        if (parts.length != 3) return null;
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
 * Format: TELESCOP_INSTRUME_Master{Type}_DATE_[FILTER_]READOUT_G*_OS*_[U*_]Bin*_*s_*C.xisf
 */
/**
 * Generate subdirectory path for master file
 * @param {object} group - Group object with items and minDate
 * @param {string} type - Master type: "Dark", "DarkFlat", "Flat", "Bias"
 * @param {string} basePath - Base masters path
 * @returns {string} Full directory path
 *   Examples:
 *     Masters_Path/!!!DARKS_LIB/SETUP/DARKS_2024_01_15
 *     Masters_Path/!!!DARKFLATS_LIB/SETUP/DARKFLATS_2024_01_15
 *     Masters_Path/!!!FLATS_LIB/SETUP/FLATS_2024_01_15
 */
function MC_generateMasterSubdir(group, type, basePath){
    var firstItem = group.items[0];

    // Build setup name from TELESCOP and INSTRUME
    // TODO-35: Sanitize to avoid slashes and invalid characters in paths
    var setup = "";
    if (firstItem.telescop) setup += CU_sanitizeKey(firstItem.telescop);
    if (firstItem.instrume){
        if (setup) setup += "_";
        setup += CU_sanitizeKey(firstItem.instrume);
    }
    if (!setup) setup = "UNKNOWN_SETUP";

    // Library folder based on type
    var libFolder;
    var typePrefix; // for date folder name
    if (type == "Dark") {
        libFolder = "!!!DARKS_LIB";
        typePrefix = "DARKS";
    } else if (type == "DarkFlat") {
        libFolder = "!!!DARKFLATS_LIB";
        typePrefix = "DARKFLATS";
    } else if (type == "Flat") {
        libFolder = "!!!FLATS_LIB";
        typePrefix = "FLATS";
    } else if (type == "Bias") {
        libFolder = "!!!BIASES_LIB";
        typePrefix = "BIASES";
    } else {
        libFolder = "!!!MASTERS_LIB";
        typePrefix = "MASTERS";
    }

    // Date-based subfolder (e.g., DARKS_2024_01_15)
    var dateStr = _mc_dateToFilename(group.minDate); // YYYY_MM_DD
    var dateFolder = typePrefix + "_" + dateStr;

    // Combine: basePath/!!!DARKS_LIB/SETUP/DARKS_2024_01_15
    return basePath + "/" + libFolder + "/" + setup + "/" + dateFolder;
}

function MC_generateMasterFileName(group, type){
    // Extract parameters from first item in group
    var firstItem = group.items[0];

    var parts = [];

    // TELESCOP and INSTRUME
    // TODO-35: Sanitize to avoid slashes and invalid characters in filenames
    if (firstItem.telescop){
        parts.push(CU_sanitizeKey(firstItem.telescop));
    }
    if (firstItem.instrume){
        parts.push(CU_sanitizeKey(firstItem.instrume));
    }

    parts.push("Master" + type); // MasterDark, MasterDarkFlat, MasterFlat

    parts.push(_mc_dateToFilename(group.minDate)); // YYYY_MM_DD

    if (type == "DarkFlat" || type == "Flat"){
        if (firstItem.filter){
            // TODO-35: Filter already sanitized in MC_indexRawFiles
            parts.push(firstItem.filter);
        }
    }

    // TODO-35: Readout already sanitized in MC_indexRawFiles
    parts.push(firstItem.readout || "UNKNOWN");
    parts.push("G" + (firstItem.gain != null ? firstItem.gain : "NULL"));
    parts.push("OS" + (firstItem.offset != null ? firstItem.offset : "NULL"));

    if (firstItem.usb != null){
        parts.push("U" + firstItem.usb);
    }

    parts.push("Bin" + (firstItem.binning || "UNKNOWN"));

    // Exposure: зависит от типа
    // - MasterDark: округлять к целому (30.0s → 030s)
    // - MasterDarkFlat/Flat: НЕ округлять (2.5s → 2.5s)
    var exp = firstItem.expTime != null ? firstItem.expTime : 0;
    var expStr;

    if (type == "Dark"){
        // Округлить к целому, 3 цифры
        expStr = String(Math.round(exp));
        while (expStr.length < 3) expStr = "0" + expStr;
    } else {
        // DarkFlat и Flat: не округлять
        // Если целое число - показать как целое, иначе с дробной частью
        if (exp == Math.floor(exp)){
            expStr = String(Math.floor(exp));
        } else {
            expStr = String(exp);
        }
    }
    parts.push(expStr + "s");

    var temp = firstItem.setTemp != null ? Math.round(firstItem.setTemp) : 0;
    parts.push(temp + "C");

    return parts.join("_") + ".xisf";
}

/**
 * Create MasterDark files
 * @param {Array} darkGroups - Array of grouped dark frames
 * @param {string} mastersBasePath - Base masters path (will create subdirs)
 * @param {function} progressCallback - Optional callback(index, total, groupInfo)
 */
function MC_createMasterDarks(darkGroups, mastersBasePath, progressCallback){
    Console.noteln("[mc] Creating MasterDark files... (" + darkGroups.length + " groups)");

    var results = [];

    for (var i = 0; i < darkGroups.length; i++){
        var group = darkGroups[i];
        var firstItem = group.items[0];

        // Generate output subdirectory and filename
        var outputDir = MC_generateMasterSubdir(group, "Dark", mastersBasePath);
        var fileName = MC_generateMasterFileName(group, "Dark");
        var fullPath = outputDir + "/" + fileName;

        Console.noteln("[mc]   Group " + (i+1) + "/" + darkGroups.length + ": " +
                       group.items.length + " files -> " + fileName);

        // Notify progress callback - start
        var t0 = Date.now();
        if (progressCallback){
            try {
                progressCallback(i+1, darkGroups.length, {
                    fileName: fileName,
                    fileCount: group.items.length,
                    group: group,
                    phase: 'start'
                });
            } catch(e){}
        }

        // Prepare images array for ImageIntegration
        var images = [];
        for (var j = 0; j < group.items.length; j++){
            images.push([true, group.items[j].path, "", ""]);
        }

        // Configure ImageIntegration (Dark/DarkFlat settings)
        var P = new ImageIntegration;
        P.images = images;
        P.combination = ImageIntegration.prototype.Average;
        P.normalization = ImageIntegration.prototype.NoNormalization;
        P.rejection = ImageIntegration.prototype.LinearFit;
        P.rejectionNormalization = ImageIntegration.prototype.NoRejectionNormalization;
        P.weightMode = ImageIntegration.prototype.DontCare;
        P.generateDrizzleData = false;
        P.generateRejectionMaps = false;
        P.evaluateSNR = false;
        P.useCache = false;
        P.linearFitLow = 4.000;
        P.linearFitHigh = 2.000;

        // Execute ImageIntegration
        try {
            P.executeGlobal();
        } catch(e){
            Console.criticalln("[mc]   ERROR: ImageIntegration failed: " + e);
            // Try to close integration window if it exists
            try {
                var errView = View.viewById("integration");
                if (errView && !errView.isNull){
                    errView.window.forceClose();
                }
            } catch(e2){}
            continue;
        }

        // Get integration result
        var intView = View.viewById("integration");
        if (!intView || intView.isNull){
            Console.criticalln("[mc]   ERROR: Integration view not found");
            continue;
        }

        var intWindow = intView.window;

        // Rename view (sanitize for valid identifier)
        intView.id = CU_sanitizeViewId(fileName, "MD");

        // Add FITS keywords (similar to MasterFlat)
        // Get existing keywords, filter out duplicates, add required ones
        var keywords = intWindow.keywords;
        var filteredKeywords = [];
        var keysToSkip = ["IMAGETYP", "TELESCOP", "INSTRUME", "BAYERPAT", "EXPTIME", "EXPOSURE", "GAIN", "OFFSET", "USBLIMIT", "READOUTM", "SET-TEMP", "XBINNING", "YBINNING"];
        for (var k = 0; k < keywords.length; ++k){
            var keyName = keywords[k].name.toUpperCase();
            var shouldSkip = false;
            for (var s = 0; s < keysToSkip.length; ++s){
                if (keyName == keysToSkip[s]){
                    shouldSkip = true;
                    break;
                }
            }
            if (!shouldSkip){
                filteredKeywords.push(keywords[k]);
            }
        }
        keywords = filteredKeywords;

        // Add required keywords from firstItem
        keywords.push(new FITSKeyword("IMAGETYP", "'MasterDark'", "Type of integrated image"));
        keywords.push(new FITSKeyword("TELESCOP", "'" + firstItem.telescop + "'", "Telescope name"));
        keywords.push(new FITSKeyword("INSTRUME", "'" + firstItem.instrume + "'", "Camera/Instrument name"));

        // Add BAYERPAT for CFA cameras (TODO-32)
        if (firstItem.bayerPattern){
            keywords.push(new FITSKeyword("BAYERPAT", "'" + firstItem.bayerPattern + "'", "Bayer color filter array pattern"));
        }

        keywords.push(new FITSKeyword("EXPTIME", String(firstItem.expTime), "[s] Exposure duration"));
        keywords.push(new FITSKeyword("GAIN", String(firstItem.gain), "Sensor gain"));
        keywords.push(new FITSKeyword("OFFSET", String(firstItem.offset), "Sensor gain offset"));
        if (firstItem.usb != null){
            keywords.push(new FITSKeyword("USBLIMIT", String(firstItem.usb), "Camera-specific USB setting"));
        }
        keywords.push(new FITSKeyword("READOUTM", "'" + firstItem.readout + "'", "Sensor readout mode"));
        keywords.push(new FITSKeyword("SET-TEMP", String(firstItem.setTemp), "[degC] CCD temperature setpoint"));
        keywords.push(new FITSKeyword("EXPOSURE", String(firstItem.expTime), "[s] Exposure duration"));

        // Add binning
        var binParts = firstItem.binning.split("x");
        if (binParts.length == 2){
            keywords.push(new FITSKeyword("XBINNING", binParts[0], "Binning factor (X axis)"));
            keywords.push(new FITSKeyword("YBINNING", binParts[1], "Binning factor (Y axis)"));
        }

        intWindow.keywords = keywords;

        // Create output directory if it doesn't exist
        if (!File.directoryExists(outputDir)){
            Console.noteln("[mc]     Creating directory: " + outputDir);
            File.createDirectory(outputDir, true);
        }

        // Save as XISF
        if (!intWindow.saveAs(fullPath, false, false, true, false)){
            Console.criticalln("[mc]   ERROR: Failed to save " + fullPath);
            intWindow.forceClose();
            continue;
        }

        // Close window
        intWindow.forceClose();

        Console.noteln("[mc]     Saved: " + fullPath);
        try { if (typeof processEvents == "function") processEvents(); } catch(_){}

        // Notify progress callback - complete
        var elapsed = (Date.now() - t0) / 1000; // in seconds
        if (progressCallback){
            try {
                progressCallback(i+1, darkGroups.length, {
                    fileName: fileName,
                    fileCount: group.items.length,
                    group: group,
                    phase: 'complete',
                    elapsed: elapsed,
                    success: true
                });
            } catch(e){}
        }

        // Add to results
        results.push({
            path: fullPath,
            fileName: fileName,
            group: group
        });
    }

    Console.noteln("[mc]   MasterDark creation complete: " + results.length + "/" + darkGroups.length + " succeeded");

    return results;
}

/**
 * Create MasterDarkFlat files
 * @param {Array} dfGroups - Array of grouped dark flat frames
 * @param {string} mastersBasePath - Base masters path (will create subdirs)
 * @param {function} progressCallback - Optional callback(index, total, groupInfo)
 */
function MC_createMasterDarkFlats(dfGroups, mastersBasePath, progressCallback){
    Console.noteln("[mc] Creating MasterDarkFlat files... (" + dfGroups.length + " groups)");

    var results = [];

    for (var i = 0; i < dfGroups.length; i++){
        var group = dfGroups[i];
        var firstItem = group.items[0];

        // Generate output subdirectory and filename
        var outputDir = MC_generateMasterSubdir(group, "DarkFlat", mastersBasePath);
        var fileName = MC_generateMasterFileName(group, "DarkFlat");
        var fullPath = outputDir + "/" + fileName;

        Console.noteln("[mc]   Group " + (i+1) + "/" + dfGroups.length + ": " +
                       group.items.length + " files -> " + fileName);

        // Notify progress callback - start
        var t0 = Date.now();
        if (progressCallback){
            try {
                progressCallback(i+1, dfGroups.length, {
                    fileName: fileName,
                    fileCount: group.items.length,
                    group: group,
                    phase: 'start'
                });
            } catch(e){}
        }

        // Prepare images array for ImageIntegration
        var images = [];
        for (var j = 0; j < group.items.length; j++){
            images.push([true, group.items[j].path, "", ""]);
        }

        // Configure ImageIntegration (Dark/DarkFlat settings)
        var P = new ImageIntegration;
        P.images = images;
        P.combination = ImageIntegration.prototype.Average;
        P.normalization = ImageIntegration.prototype.NoNormalization;
        P.rejection = ImageIntegration.prototype.LinearFit;
        P.rejectionNormalization = ImageIntegration.prototype.NoRejectionNormalization;
        P.weightMode = ImageIntegration.prototype.DontCare;
        P.generateDrizzleData = false;
        P.generateRejectionMaps = false;
        P.evaluateSNR = false;
        P.useCache = false;
        P.linearFitLow = 4.000;
        P.linearFitHigh = 2.000;

        // Execute ImageIntegration
        try {
            P.executeGlobal();
        } catch(e){
            Console.criticalln("[mc]   ERROR: ImageIntegration failed: " + e);
            // Try to close integration window if it exists
            try {
                var errView = View.viewById("integration");
                if (errView && !errView.isNull){
                    errView.window.forceClose();
                }
            } catch(e2){}
            continue;
        }

        // Get integration result
        var intView = View.viewById("integration");
        if (!intView || intView.isNull){
            Console.criticalln("[mc]   ERROR: Integration view not found");
            continue;
        }

        var intWindow = intView.window;

        // Rename view (sanitize for valid identifier)
        intView.id = CU_sanitizeViewId(fileName, "MDF");

        // Add FITS keywords (similar to MasterDark and MasterFlat)
        // Get existing keywords, filter out duplicates, add required ones
        var keywords = intWindow.keywords;
        var filteredKeywords = [];
        var keysToSkip = ["IMAGETYP", "TELESCOP", "INSTRUME", "BAYERPAT", "EXPTIME", "EXPOSURE", "GAIN", "OFFSET", "USBLIMIT", "READOUTM", "SET-TEMP", "XBINNING", "YBINNING", "FILTER"];
        for (var k = 0; k < keywords.length; ++k){
            var keyName = keywords[k].name.toUpperCase();
            var shouldSkip = false;
            for (var s = 0; s < keysToSkip.length; ++s){
                if (keyName == keysToSkip[s]){
                    shouldSkip = true;
                    break;
                }
            }
            if (!shouldSkip){
                filteredKeywords.push(keywords[k]);
            }
        }
        keywords = filteredKeywords;

        // Add required keywords from firstItem
        keywords.push(new FITSKeyword("IMAGETYP", "'MasterDarkFlat'", "Type of integrated image"));
        keywords.push(new FITSKeyword("TELESCOP", "'" + firstItem.telescop + "'", "Telescope name"));
        keywords.push(new FITSKeyword("INSTRUME", "'" + firstItem.instrume + "'", "Camera/Instrument name"));

        // Add BAYERPAT for CFA cameras (TODO-32)
        if (firstItem.bayerPattern){
            keywords.push(new FITSKeyword("BAYERPAT", "'" + firstItem.bayerPattern + "'", "Bayer color filter array pattern"));
        }

        // Add FILTER
        if (firstItem.filter){
            keywords.push(new FITSKeyword("FILTER", "'" + firstItem.filter + "'", "Filter name"));
        }

        keywords.push(new FITSKeyword("EXPTIME", String(firstItem.expTime), "[s] Exposure duration"));
        keywords.push(new FITSKeyword("GAIN", String(firstItem.gain), "Sensor gain"));
        keywords.push(new FITSKeyword("OFFSET", String(firstItem.offset), "Sensor gain offset"));
        if (firstItem.usb != null){
            keywords.push(new FITSKeyword("USBLIMIT", String(firstItem.usb), "Camera-specific USB setting"));
        }
        keywords.push(new FITSKeyword("READOUTM", "'" + firstItem.readout + "'", "Sensor readout mode"));
        keywords.push(new FITSKeyword("SET-TEMP", String(firstItem.setTemp), "[degC] CCD temperature setpoint"));
        keywords.push(new FITSKeyword("EXPOSURE", String(firstItem.expTime), "[s] Exposure duration"));

        // Add binning
        var binParts = firstItem.binning.split("x");
        if (binParts.length == 2){
            keywords.push(new FITSKeyword("XBINNING", binParts[0], "Binning factor (X axis)"));
            keywords.push(new FITSKeyword("YBINNING", binParts[1], "Binning factor (Y axis)"));
        }

        intWindow.keywords = keywords;

        // Create output directory if it doesn't exist
        if (!File.directoryExists(outputDir)){
            Console.noteln("[mc]     Creating directory: " + outputDir);
            File.createDirectory(outputDir, true);
        }

        // Save as XISF
        if (!intWindow.saveAs(fullPath, false, false, true, false)){
            Console.criticalln("[mc]   ERROR: Failed to save " + fullPath);
            intWindow.forceClose();
            continue;
        }

        // Close window
        intWindow.forceClose();

        Console.noteln("[mc]     Saved: " + fullPath);
        try { if (typeof processEvents == "function") processEvents(); } catch(_){}

        // Notify progress callback - complete
        var elapsed = (Date.now() - t0) / 1000; // in seconds
        if (progressCallback){
            try {
                progressCallback(i+1, dfGroups.length, {
                    fileName: fileName,
                    fileCount: group.items.length,
                    group: group,
                    phase: 'complete',
                    elapsed: elapsed,
                    success: true
                });
            } catch(e){}
        }

        // Add to results (include metadata for matching)
        results.push({
            path: fullPath,
            fileName: fileName,
            group: group,
            // Metadata for matching
            setup: firstItem.setup,
            filter: firstItem.filter,
            readoutMode: firstItem.readoutMode,
            gain: firstItem.gain,
            offset: firstItem.offset,
            usb: firstItem.usb,
            binning: firstItem.binning,
            setTemp: firstItem.setTemp,
            expTime: firstItem.expTime,
            date: group.oldestDate  // Full timestamp for ±3 hours matching
        });
    }

    Console.noteln("[mc]   MasterDarkFlat creation complete: " + results.length + "/" + dfGroups.length + " succeeded");

    return results;
}

/**
 * Match MasterDarkFlat to Flat groups
 * Returns: { flatGroupIndex: matchedDfMaster }
 */
function MC_matchDarkFlatToFlat(dfMasters, flatGroups){
    Console.noteln("[mc] Matching MasterDarkFlat to Flat groups...");

    var matches = {};
    var matchedCount = 0;

    for (var i = 0; i < flatGroups.length; i++){
        var flatGroup = flatGroups[i];
        var firstFlat = flatGroup.items[0];

        // Must match parameters
        var pool = [];
        for (var j = 0; j < dfMasters.length; j++){
            var df = dfMasters[j];

            // Strict matching: setup, filter, readout, gain, offset, usb, binning, temp, expTime
            if (df.setup != firstFlat.setup) continue;
            if (df.filter != firstFlat.filter) continue;
            if (df.readoutMode != firstFlat.readoutMode) continue;
            if (df.gain != firstFlat.gain) continue;
            if (df.offset != firstFlat.offset) continue;
            if (df.usb != firstFlat.usb) continue;
            if (df.binning != firstFlat.binning) continue;

            // Temperature tolerance ±0.1°C
            var tempDiff = Math.abs(df.setTemp - firstFlat.setTemp);
            if (tempDiff > 0.1) continue;

            // EXPTIME must match
            if (df.expTime != firstFlat.expTime) continue;

            // Time window: ±3 hours
            var flatDate = new Date(flatGroup.oldestDate);
            var dfDate = new Date(df.date);
            var diffMs = dfDate.getTime() - flatDate.getTime();
            var diffHours = diffMs / (1000.0 * 60.0 * 60.0);

            if (Math.abs(diffHours) > 3.0) continue;

            // Add to pool
            pool.push({
                df: df,
                diffHours: diffHours,
                absDiffHours: Math.abs(diffHours)
            });
        }

        // No candidates found
        if (pool.length == 0){
            Console.warningln("[mc]   WARNING: No matching MasterDarkFlat found for Flat group " +
                             "(filter=" + firstFlat.filter + ", expTime=" + firstFlat.expTime + "s) within \u00b13 hours");
            continue;
        }

        // Sort pool: 1) Future first (diffHours < 0), 2) Closest by time
        pool.sort(function(a, b){
            var aFuture = (a.diffHours < 0);
            var bFuture = (b.diffHours < 0);

            // Priority: future first
            if (aFuture != bFuture){
                return aFuture ? -1 : 1;
            }

            // Among same direction: closest by time
            return a.absDiffHours - b.absDiffHours;
        });

        var best = pool[0];
        matches[i] = best.df;
        matchedCount++;

        var dirStr = (best.diffHours < 0) ? "future" : "past";
        Console.noteln("[mc]   Flat group " + (i+1) + " (filter=" + firstFlat.filter + ") -> " +
                       best.df.fileName + " (" + dirStr + ", " + best.absDiffHours.toFixed(2) + "h)");
    }

    Console.noteln("[mc]   Matching complete: " + matchedCount + "/" + flatGroups.length + " Flat groups matched");

    return matches;
}

/**
 * Find Flat group indices that have no matching DarkFlat
 * @param {Array} flatGroups - Array of all Flat groups
 * @param {Object} dfMatches - Object with matched DarkFlats { groupIndex: dfMaster }
 * @returns {Array} Array of unmatched group indices
 */
function MC_findUnmatchedGroups(flatGroups, dfMatches){
    var unmatched = [];
    for (var i = 0; i < flatGroups.length; i++){
        if (!dfMatches.hasOwnProperty(i)){
            unmatched.push(i);
        }
    }
    return unmatched;
}

/**
 * Prepare raw Flat groups (without calibration) for MasterFlat creation
 * @param {Array} flatGroups - Array of all Flat groups
 * @param {Array} unmatchedIndices - Array of group indices without DarkFlat
 * @returns {Object} { groupIndex: { group, calibratedPaths, wasCalibrated } }
 */
function MC_prepareRawFlats(flatGroups, unmatchedIndices){
    if (unmatchedIndices.length == 0) {
        return {};
    }

    Console.warningln("[mc] Preparing raw Flats without DarkFlat calibration for " +
                      unmatchedIndices.length + " group(s)");

    var result = {};

    for (var i = 0; i < unmatchedIndices.length; i++){
        var idx = unmatchedIndices[i];
        var group = flatGroups[idx];
        var firstFlat = group.items[0];

        Console.warningln("[mc]   Group " + (idx+1) + " (filter=" + firstFlat.filter +
                          ", expTime=" + firstFlat.expTime + "s): No DarkFlat found, using raw Flats");

        // Collect raw file paths
        var rawPaths = [];
        for (var j = 0; j < group.items.length; j++){
            rawPaths.push(group.items[j].path);
        }

        result[idx] = {
            group: group,
            calibratedPaths: rawPaths,  // Actually raw, but same structure
            wasCalibrated: false        // Flag for reporting
        };
    }

    return result;
}

/**
 * Merge two group objects into one
 * @param {Object} obj1 - First object (e.g., calibrated groups)
 * @param {Object} obj2 - Second object (e.g., raw groups)
 * @returns {Object} Merged object
 */
function MC_mergeGroups(obj1, obj2){
    var result = {};

    // Copy obj1
    for (var key in obj1){
        if (obj1.hasOwnProperty(key)){
            result[key] = obj1[key];
        }
    }

    // Copy obj2
    for (var key in obj2){
        if (obj2.hasOwnProperty(key)){
            result[key] = obj2[key];
        }
    }

    return result;
}

/**
 * Calibrate Flat files using MasterDarkFlat
 * Returns: { flatGroupIndex: [array of calibrated file paths] }
 * @param {Array} flatGroups - Array of grouped flat frames
 * @param {Object} dfMatches - Matching MasterDarkFlat for each flat group
 * @param {string} tempPath - Temporary directory path
 * @param {function} progressCallback - Optional callback(index, total, groupInfo)
 */
function MC_calibrateFlats(flatGroups, dfMatches, tempPath, progressCallback){
    Console.noteln("[mc] Calibrating Flat files...");

    var calibratedGroups = {};
    var totalFiles = 0;

    // Count total files to calibrate
    for (var key in dfMatches){
        if (dfMatches.hasOwnProperty(key)){
            var idx = parseInt(key, 10);
            totalFiles += flatGroups[idx].items.length;
        }
    }

    Console.noteln("[mc]   Total Flat files to calibrate: " + totalFiles);

    // Create temp directory if it doesn't exist
    if (!File.directoryExists(tempPath)){
        Console.noteln("[mc]   Creating temp directory: " + tempPath);
        File.createDirectory(tempPath, true);
    }

    // Count matched groups for progress
    var matchedGroupCount = 0;
    for (var k in dfMatches){
        if (dfMatches.hasOwnProperty(k)) matchedGroupCount++;
    }

    var processedGroupCount = 0;

    // Process each matched Flat group
    for (var key in dfMatches){
        if (!dfMatches.hasOwnProperty(key)) continue;

        var groupIdx = parseInt(key, 10);
        var flatGroup = flatGroups[groupIdx];
        var dfMaster = dfMatches[groupIdx];
        var firstFlat = flatGroup.items[0];

        processedGroupCount++;

        Console.noteln("[mc]   Calibrating Flat group " + (groupIdx+1) + " (filter=" + firstFlat.filter +
                       ", " + flatGroup.items.length + " files) with " + dfMaster.fileName);

        // Notify progress callback - start
        var t0 = Date.now();
        if (progressCallback){
            try {
                progressCallback(groupIdx + 1, matchedGroupCount, {
                    fileName: "CalibFlat_" + firstFlat.filter,
                    fileCount: flatGroup.items.length,
                    filter: firstFlat.filter,
                    group: flatGroup,
                    phase: 'start'
                });
            } catch(e){}
        }

        // Prepare target frames array
        var targetFrames = [];
        for (var i = 0; i < flatGroup.items.length; i++){
            targetFrames.push([true, flatGroup.items[i].path]);
        }

        // Configure ImageCalibration
        var P = new ImageCalibration;
        P.targetFrames = targetFrames;
        P.enableCFA = false;
        P.cfaPattern = ImageCalibration.prototype.Auto;
        P.inputHints = "";
        P.outputHints = "";
        P.pedestal = 0;
        P.pedestalMode = ImageCalibration.prototype.Keyword;
        P.pedestalKeyword = "";
        P.overscanEnabled = false;
        P.masterBiasEnabled = false;  // NO Bias
        P.masterDarkEnabled = true;   // Use MasterDarkFlat
        P.masterDarkPath = dfMaster.path;
        P.masterFlatEnabled = false;
        P.calibrateBias = false;
        P.calibrateDark = false;
        P.calibrateFlat = false;
        P.optimizeDarks = true;
        P.darkOptimizationThreshold = 0.00000;
        P.darkOptimizationLow = 3.0000;
        P.darkOptimizationWindow = 1024;
        P.darkCFADetectionMode = ImageCalibration.prototype.DetectCFA;
        P.evaluateNoise = true;
        // Safe set noiseEvaluationAlgorithm (API changed in newer versions)
        try {
            var cur = P.noiseEvaluationAlgorithm;
            if (typeof cur == "string"){
                P.noiseEvaluationAlgorithm = "NoiseEvaluation_MRS";
            } else if (typeof cur == "number"){
                P.noiseEvaluationAlgorithm = ImageCalibration.prototype.NoiseEvaluation_MRS;
            }
        } catch(e){
            // Ignore if property doesn't exist
        }
        P.outputDirectory = tempPath;
        P.outputExtension = ".xisf";
        P.outputPrefix = "";
        P.outputPostfix = "_c";
        // Safe set outputSampleFormat (API changed in newer versions)
        try {
            var cur = P.outputSampleFormat;
            if (typeof cur == "string"){
                P.outputSampleFormat = "SameAsTarget";
            } else if (typeof cur == "number"){
                P.outputSampleFormat = ImageCalibration.prototype.SameAsTarget;
            }
        } catch(e){
            // Ignore if property doesn't exist
        }
        P.outputPedestal = 0;
        P.overwriteExistingFiles = true;
        // Safe set onError (API changed in newer versions)
        try {
            var cur2 = P.onError;
            if (typeof cur2 == "string"){
                P.onError = "Continue";
            } else if (typeof cur2 == "number"){
                P.onError = ImageCalibration.prototype.Continue;
            }
        } catch(e){
            // Ignore if property doesn't exist
        }
        P.noGUIMessages = true;

        // Execute ImageCalibration
        try {
            P.executeGlobal();
        } catch(e){
            Console.criticalln("[mc]   ERROR: ImageCalibration failed: " + e);
            continue;
        }

        // Collect calibrated file paths (outputDirectory + original filename + _c.xisf)
        var calibratedPaths = [];
        for (var j = 0; j < flatGroup.items.length; j++){
            var originalPath = flatGroup.items[j].path;
            var originalName = CU_basename(originalPath);
            var baseName = originalName.replace(/\.(fit|fits|fts|xisf)$/i, "");
            var calibratedName = baseName + "_c.xisf";
            var calibratedPath = tempPath + "/" + calibratedName;
            calibratedPaths.push(calibratedPath);
        }

        calibratedGroups[groupIdx] = {
            group: flatGroup,
            calibratedPaths: calibratedPaths,
            dfMaster: dfMaster,
            wasCalibrated: true
        };

        Console.noteln("[mc]     Calibrated " + calibratedPaths.length + " files -> " + tempPath);
        try { if (typeof processEvents == "function") processEvents(); } catch(_){}

        // Notify progress callback - complete
        var elapsed = (Date.now() - t0) / 1000; // in seconds
        if (progressCallback){
            try {
                progressCallback(groupIdx + 1, matchedGroupCount, {
                    fileName: "CalibFlat_" + firstFlat.filter,
                    fileCount: flatGroup.items.length,
                    filter: firstFlat.filter,
                    group: flatGroup,
                    phase: 'complete',
                    elapsed: elapsed,
                    success: true
                });
            } catch(e){}
        }
    }

    Console.noteln("[mc]   Flat calibration complete: " + Object.keys(calibratedGroups).length + " groups");

    return calibratedGroups;
}

/**
 * Create MasterFlat files from calibrated Flat files
 * @param {Object} calibratedFlatGroups - Calibrated flat groups with paths
 * @param {string} mastersBasePath - Base masters path (will create subdirs)
 * @param {function} progressCallback - Optional callback(index, total, groupInfo)
 */
function MC_createMasterFlats(calibratedFlatGroups, mastersBasePath, progressCallback){
    var groupCount = Object.keys(calibratedFlatGroups).length;
    Console.noteln("[mc] Creating MasterFlat files... (" + groupCount + " groups)");

    var results = [];
    var processedCount = 0;

    for (var key in calibratedFlatGroups){
        if (!calibratedFlatGroups.hasOwnProperty(key)) continue;

        var groupIdx = parseInt(key, 10);
        var data = calibratedFlatGroups[groupIdx];
        var flatGroup = data.group;
        var calibratedPaths = data.calibratedPaths;
        var firstItem = flatGroup.items[0];

        processedCount++;

        // Generate output subdirectory and filename
        var outputDir = MC_generateMasterSubdir(flatGroup, "Flat", mastersBasePath);
        var fileName = MC_generateMasterFileName(flatGroup, "Flat");
        var fullPath = outputDir + "/" + fileName;

        Console.noteln("[mc]   Group " + (groupIdx+1) + ": " +
                       calibratedPaths.length + " files -> " + fileName);

        // Notify progress callback - start
        var t0 = Date.now();
        if (progressCallback){
            try {
                progressCallback(processedCount, groupCount, {
                    fileName: fileName,
                    fileCount: calibratedPaths.length,
                    group: flatGroup,
                    phase: 'start'
                });
            } catch(e){}
        }

        // Prepare images array for ImageIntegration
        var images = [];
        for (var i = 0; i < calibratedPaths.length; i++){
            images.push([true, calibratedPaths[i], "", ""]);
        }

        // Configure ImageIntegration (Flat settings - different from Dark/DarkFlat!)
        var P = new ImageIntegration;
        P.images = images;
        P.combination = ImageIntegration.prototype.Average;
        P.normalization = ImageIntegration.prototype.Multiplicative;  // Different!
        P.rejection = ImageIntegration.prototype.LinearFit;
        P.rejectionNormalization = ImageIntegration.prototype.EqualizeFluxes;  // Different!
        P.weightMode = ImageIntegration.prototype.DontCare;
        P.generateDrizzleData = false;
        P.generateRejectionMaps = false;
        P.evaluateSNR = false;
        P.useCache = true;  // Different!
        P.linearFitLow = 5.000;  // Different!
        P.linearFitHigh = 2.500;  // Different!

        // Execute ImageIntegration
        try {
            P.executeGlobal();
        } catch(e){
            Console.criticalln("[mc]   ERROR: ImageIntegration failed: " + e);
            // Try to close integration window if it exists
            try {
                var errView = View.viewById("integration");
                if (errView && !errView.isNull){
                    errView.window.forceClose();
                }
            } catch(e2){}
            continue;
        }

        // Get integration result
        var intView = View.viewById("integration");
        if (!intView || intView.isNull){
            Console.criticalln("[mc]   ERROR: Integration view not found");
            continue;
        }

        var intWindow = intView.window;

        // Rename view (sanitize for valid identifier)
        intView.id = CU_sanitizeViewId(fileName, "MF");

        // Add FITS keywords (similar to MasterDark creation)
        // Get existing keywords, filter out duplicates, add required ones
        var keywords = intWindow.keywords;
        var filteredKeywords = [];
        var keysToSkip = ["IMAGETYP", "TELESCOP", "INSTRUME", "BAYERPAT", "GAIN", "OFFSET", "USBLIMIT", "READOUTM", "SET-TEMP", "XBINNING", "YBINNING", "FILTER"];
        for (var k = 0; k < keywords.length; ++k){
            var keyName = keywords[k].name.toUpperCase();
            var shouldSkip = false;
            for (var s = 0; s < keysToSkip.length; ++s){
                if (keyName == keysToSkip[s]){
                    shouldSkip = true;
                    break;
                }
            }
            if (!shouldSkip){
                filteredKeywords.push(keywords[k]);
            }
        }
        keywords = filteredKeywords;

        // Add required keywords from firstItem
        keywords.push(new FITSKeyword("IMAGETYP", "'MasterFlat'", "Type of integrated image"));
        keywords.push(new FITSKeyword("TELESCOP", "'" + firstItem.telescop + "'", "Telescope name"));
        keywords.push(new FITSKeyword("INSTRUME", "'" + firstItem.instrume + "'", "Camera/Instrument name"));

        // Add BAYERPAT for CFA cameras (TODO-32)
        if (firstItem.bayerPattern){
            keywords.push(new FITSKeyword("BAYERPAT", "'" + firstItem.bayerPattern + "'", "Bayer color filter array pattern"));
        }

        // Add FILTER
        if (firstItem.filter){
            keywords.push(new FITSKeyword("FILTER", "'" + firstItem.filter + "'", "Filter name"));
        }

        // Add camera parameters
        keywords.push(new FITSKeyword("GAIN", String(firstItem.gain), "Sensor gain"));
        keywords.push(new FITSKeyword("OFFSET", String(firstItem.offset), "Sensor gain offset"));
        if (firstItem.usb != null){
            keywords.push(new FITSKeyword("USBLIMIT", String(firstItem.usb), "Camera-specific USB setting"));
        }
        keywords.push(new FITSKeyword("READOUTM", "'" + firstItem.readout + "'", "Sensor readout mode"));
        keywords.push(new FITSKeyword("SET-TEMP", String(firstItem.setTemp), "[degC] CCD temperature setpoint"));

        // Add binning
        var binParts = firstItem.binning.split("x");
        if (binParts.length == 2){
            keywords.push(new FITSKeyword("XBINNING", binParts[0], "Binning factor (X axis)"));
            keywords.push(new FITSKeyword("YBINNING", binParts[1], "Binning factor (Y axis)"));
        }

        intWindow.keywords = keywords;

        // Create output directory if it doesn't exist
        if (!File.directoryExists(outputDir)){
            Console.noteln("[mc]     Creating directory: " + outputDir);
            File.createDirectory(outputDir, true);
        }

        // Save as XISF
        if (!intWindow.saveAs(fullPath, false, false, true, false)){
            Console.criticalln("[mc]   ERROR: Failed to save " + fullPath);
            intWindow.forceClose();
            continue;
        }

        // Close window
        intWindow.forceClose();

        Console.noteln("[mc]     Saved: " + fullPath);
        try { if (typeof processEvents == "function") processEvents(); } catch(_){}

        // Notify progress callback - complete
        var elapsed = (Date.now() - t0) / 1000; // in seconds
        if (progressCallback){
            try {
                progressCallback(processedCount, groupCount, {
                    fileName: fileName,
                    fileCount: calibratedPaths.length,
                    group: flatGroup,
                    phase: 'complete',
                    elapsed: elapsed,
                    success: true
                });
            } catch(e){}
        }

        // Add to results
        results.push({
            path: fullPath,
            fileName: fileName,
            group: flatGroup,
            wasCalibrated: data.wasCalibrated
        });
    }

    Console.noteln("[mc]   MasterFlat creation complete: " + results.length + "/" + groupCount + " succeeded");

    return results;
}

/**
 * Main function: Create all master calibration files
 * @param {string} rawPath - Path to raw calibration files
 * @param {string} mastersPath - Path for master files output
 * @param {string} work1Path - Work directory 1
 * @param {string} work2Path - Work directory 2
 * @param {function} progressCallback - Optional callback(phase, data)
 *                                      phase: 'init' | 'progress' | 'complete'
 *                                      For 'init': data = { groups: {...} }
 *                                      For 'progress': data = { type, index, total, groupInfo }
 */
function MC_createMasters(rawPath, mastersPath, work1Path, work2Path, progressCallback){
    Console.noteln("[mc] Creating master calibration files...");
    Console.noteln("[mc]   Raw path: " + rawPath);
    Console.noteln("[mc]   Masters path: " + mastersPath);
    Console.noteln("[mc]   Work1 path: " + work1Path);
    Console.noteln("[mc]   Work2 path: " + work2Path);
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // 1. Index raw files
    var rawIndex = MC_indexRawFiles(rawPath);
    if (!rawIndex || rawIndex.length == 0){
        throw new Error("No valid calibration files found");
    }
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // 1.5. Save calibration_frames_index.json (for debugging/analysis)
    try {
        if (typeof FI_indexCalibration == "function") {
            var calibIndexPath = rawPath + "/calibration_frames_index.json";
            Console.noteln("[mc] Saving calibration frames index to: " + calibIndexPath);
            FI_indexCalibration(rawPath, calibIndexPath);
        }
    } catch(e){
        Console.warningln("[mc] Failed to save calibration_frames_index.json: " + e);
    }
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // 2. Group by parameters
    var groups = MC_groupByParams(rawIndex);
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // Notify UI about groups (for pre-creating rows)
    if (progressCallback){
        try {
            progressCallback('init', { groups: groups });
        } catch(e){
            Console.warningln("[mc] Progress callback (init) error: " + e);
        }
    }
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // 3. Determine paths
    // Remove trailing slashes to avoid double slashes
    var w2 = work2Path.replace(/\/+$/, "");
    var w1 = work1Path.replace(/\/+$/, "");
    var mp = mastersPath.replace(/\/+$/, "");

    // Temp path fallback: work2Path if available, otherwise work1Path
    var tempPath = (work2Path && work2Path.trim()) ?
                   (w2 + "/Masters_Temp") :
                   (w1 + "/Masters_Temp");

    Console.noteln("[mc]   Masters base path: " + mp);
    Console.noteln("[mc]   Temp path: " + tempPath);

    // Create progress callback wrapper for individual operations
    function makeCallback(masterType){
        return function(index, total, groupInfo){
            if (progressCallback){
                try {
                    progressCallback('progress', {
                        type: masterType,
                        index: index,
                        total: total,
                        groupInfo: groupInfo
                    });
                } catch(e){
                    Console.warningln("[mc] Progress callback error: " + e);
                }
            }
        };
    }

    // 4. Create MasterDarks (subdirs created automatically)
    var masterDarks = MC_createMasterDarks(groups.darks, mp, makeCallback('dark'));
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // 5. Create MasterDarkFlats (subdirs created automatically)
    var masterDarkFlats = MC_createMasterDarkFlats(groups.darkFlats, mp, makeCallback('darkflat'));
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // 6. Match DarkFlats to Flats
    var dfMatches = MC_matchDarkFlatToFlat(masterDarkFlats, groups.flats);
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // Notify UI about dfMatches (for creating Flat Calibration rows)
    if (progressCallback){
        try {
            progressCallback('dfmatches', { dfMatches: dfMatches, flatGroups: groups.flats });
        } catch(e){
            Console.warningln("[mc] Progress callback (dfmatches) error: " + e);
        }
    }
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // 7a. Calibrate matched Flat groups (with DarkFlat)
    var calibratedWithDF = MC_calibrateFlats(groups.flats, dfMatches, tempPath, makeCallback('calibflat'));
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // 7b. Find unmatched Flat groups (no DarkFlat)
    var unmatchedIndices = MC_findUnmatchedGroups(groups.flats, dfMatches);

    // 7c. Prepare raw Flats for unmatched groups (auto-fallback)
    var rawFlats = MC_prepareRawFlats(groups.flats, unmatchedIndices);
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    // 7d. Merge calibrated and raw groups
    var allFlats = MC_mergeGroups(calibratedWithDF, rawFlats);

    // 8. Create MasterFlats from ALL groups (calibrated + raw)
    var masterFlats = MC_createMasterFlats(allFlats, mp, makeCallback('flat'));
    try { if (typeof processEvents == "function") processEvents(); } catch(_){}

    Console.noteln("[mc] Complete!");
    Console.noteln("[mc]   MasterDark: " + masterDarks.length);
    Console.noteln("[mc]   MasterDarkFlat: " + masterDarkFlats.length);
    Console.noteln("[mc]   MasterFlat: " + masterFlats.length);

    // 9. Report MasterFlats created without DarkFlat calibration
    if (unmatchedIndices.length > 0) {
        Console.noteln("[mc]");
        Console.noteln("[mc]   MasterFlats created WITHOUT DarkFlat calibration:");
        for (var i = 0; i < masterFlats.length; i++) {
            if (masterFlats[i].wasCalibrated === false) {
                Console.noteln("[mc]     - " + masterFlats[i].fileName);
            }
        }
    }

    try { if (typeof processEvents == "function") processEvents(); } catch(_){}
}
