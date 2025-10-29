/*
 * ANAWBPPS - SubframeSelector with manual weight computation
 * Measures subframes, computes weights, and extracts TOP-5 best frames
 * Groups by acquisition conditions (setup, object, filter, binning, exposure)
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
/* helper: pre-add Measure/Output rows for all groups, mark as Queued */
function SS_preAddRows(dlg, PLAN, groups){
    var map = {};
    if (!dlg || !groups || !groups.length) return map;

    // Check if rows already exist in dlg.ssRowsMap
    if (dlg.ssRowsMap && Object.keys(dlg.ssRowsMap).length > 0) {
        Console.writeln("[ss] Rows already pre-added, skipping duplicate creation");
        return dlg.ssRowsMap;
    }

    for (var i=0;i<groups.length;++i){
        var g = groups[i];
        var label = SS_makeGroupLabel(PLAN, g.key, g.files.length);
        var opKey = "SS|" + g.key;
        var n = null;
        try{
            if (typeof PP_addRowUnique === "function")
                n = PP_addRowUnique(dlg, "SubframeSelector - Output", label, opKey);
            else
            n = dlg.addRow("SubframeSelector - Output", label);
            n.setText(3, "⏳ Queued");
            n.setText(4, g.files.length + "/" + g.files.length + " queued");
            }catch(_){}
        map[g.key] = { node: n, label: label };
    }
    try{
        if (!dlg.ssRowsMap) dlg.ssRowsMap = {};
        for (var kk in map) if (map.hasOwnProperty(kk)) dlg.ssRowsMap[kk] = map[kk];
    }catch(_){}
    try{ if (typeof processEvents==="function") processEvents(); }catch(_){}
    return map;
}

/* helper: pre-add SS rows for all PLAN groups (queued), without requiring CC outputs yet */
function SS_preAddRowsFromPlan(dlg, PLAN){
    var map = {};
    if (!dlg || !PLAN || !PLAN.groups) return map;

    for (var k in PLAN.groups) if (PLAN.groups.hasOwnProperty(k)){
        var G = PLAN.groups[k];
        var total = 0;
        try{
            if (G.lights && G.lights.length) total = G.lights.length;
            else if (G.items && G.items.length) total = G.items.length;
            else if (G.frames && G.frames.length) total = G.frames.length;
        }catch(_){}

        var label = SS_makeGroupLabel(PLAN, k, total);
        var opKey = "SS|" + k;

        var n = null;
        try{
            if (typeof PP_addRowUnique === "function")
                n = PP_addRowUnique(dlg, "SubframeSelector - Output", label, opKey);
            else
                n = dlg.addRow("SubframeSelector - Output", label);
                n.setText(3, "⏳ Queued");
                n.setText(4, total + "/" + total + " queued");
        }catch(_){}

        map[k] = { node: n, label: label };
    }

    try{
        if (!dlg.ssRowsMap) dlg.ssRowsMap = {};
        for (var kk in map) if (map.hasOwnProperty(kk)) dlg.ssRowsMap[kk] = map[kk];
    }catch(_){}
    try{ if (typeof processEvents==="function") processEvents(); }catch(_){}
    return map;
}
function SS_norm(p){
    var s = String(p||"");
    while (s.indexOf("\\") >= 0){
        var i = s.indexOf("\\");
        s = s.substring(0,i) + "/" + s.substring(i+1);
    }
    return s;
}
function SS_basename(p){
    var s = SS_norm(p);
    var i = s.lastIndexOf("/");
    return (i>=0) ? s.substring(i+1) : s;
}
function SS_noext(p){
    var b = SS_basename(p);
    var i = b.lastIndexOf(".");
    return (i>0) ? b.substring(0,i) : b;
}
function SS_dirname(p){
    var s = SS_norm(p);
    var i = s.lastIndexOf("/");
    return (i>=0) ? s.substring(0,i) : "";
}

function SS_readKeywordMap(path){
    try{
// Determine format from extension
        var ext = File.extractExtension(path).toLowerCase();
        if (ext.charAt(0) === '.') ext = ext.substring(1);

        var ff = new FileFormat(ext, true/*read*/, false/*write*/);
        if (ff.isNull) {
            Console.warningln("[ss] Cannot open format for: " + path);
            return null;
        }

        var fi = new FileFormatInstance(ff);
        if (fi.isNull) return null;

        var imgDesc = fi.open(path, "verbosity 0");
        if (!imgDesc || imgDesc.length === 0) {
            fi.close();
            return null;
        }

        var keys = ff.canStoreKeywords ? fi.keywords : [];
        fi.close();

        var map = {};
        for (var i=0;i<keys.length;++i){
            var name = (keys[i].name||"").toUpperCase();
            var val = keys[i].strippedValue || keys[i].value;
            map[name] = val;
        }
        return map;
    } catch(e){
        return null;
    }
}

function SS_toFloat(s){
    if (s===null || typeof s==="undefined") return null;
    var n = Number(s);
    return isFinite(n) ? n : null;
}

function SS_guessPixelSizeUm(hdr){
    if (!hdr) return null;
    var cands = ["XPIXSZ", "PIXSIZE", "PIXSZ", "XPIXSIZE", "PIXSCALE"]; // PS: PI XSCALE is arcsec/px sometimes
    for (var i=0;i<cands.length;++i){
        var k = cands[i];
        if (typeof hdr[k] !== "undefined"){
            var v = SS_toFloat(hdr[k]);
            if (v!==null){
                // If header has PI XSCALE (arcsec/px), skip: we'll compute from pixel size only
                if (k==="PIXSCALE" && v>0 && v<100) continue;
                return v;
            }
        }
    }
    return null;
}

function SS_guessFocalLenMm(hdr){
    if (!hdr) return null;
    var cands = ["FOCALLEN", "FOCAL", "FOCLEN", "FOCALLENGTH"];
    for (var i=0;i<cands.length;++i){
        var k = cands[i];
        if (typeof hdr[k] !== "undefined"){
            var v = SS_toFloat(hdr[k]);
            if (v!==null) return v;
        }
    }
    return null;
}

function SS_parseBinning(str){
    var s = String(str||"");
    var x = s.indexOf("x");
    if (x<0) x = s.indexOf("X");
    if (x>0){
        var a = parseInt(s.substring(0,x),10);
        if (a>0) return a;
    }
    return 1;
}

function SS_arcsecPerPixel(pixel_um, focal_mm, binFactor){
    if (!(pixel_um>0) || !(focal_mm>0)) return null;
    var scale1 = 206.265 * pixel_um / focal_mm;
    var bf = (binFactor>0) ? binFactor : 1;
    return scale1 * bf;
}

function SS_computeScaleFromFile(samplePath, binningStr){
    var hdr = SS_readKeywordMap(samplePath);
    var px = SS_guessPixelSizeUm(hdr);
    var fl = SS_guessFocalLenMm(hdr);
    var bf = SS_parseBinning(binningStr);
    var sc = SS_arcsecPerPixel(px, fl, bf);
    return {scale: sc, pixelUm: px, focalMm: fl};
}

function SS_applyTemplateDefaults(P){
    // Instances supplied by user
    P.fileCache = true;
    P.cameraResolution = SubframeSelector.prototype.Bits16;
    P.siteLocalMidnight = 3;
    P.scaleUnit = SubframeSelector.prototype.ArcSeconds;
    P.dataUnit = SubframeSelector.prototype.Electron;
    P.trimmingFactor = 0.10;
    P.structureLayers = 4;
    P.noiseLayers = 2;
    P.hotPixelFilterRadius = 1;
    P.noiseReductionFilterRadius = 0;
    P.minStructureSize = 0;
    P.sensitivity = 0.50;
    P.peakResponse = 0.50;
    P.brightThreshold = 3.00;
    P.maxDistortion = 0.60;
    P.allowClusteredSources = false;
    P.upperLimit = 1.0000;
    P.psfFit = SubframeSelector.prototype.Moffat10;
    P.psfFitCircular = false;
    P.maxPSFFits = 8000;
    P.roiX0 = P.roiY0 = P.roiX1 = P.roiY1 = 0;
    P.noNoiseAndSignalWarnings = false;
    // CRITICAL: Enable weight computation
    P.sortingProperty = SubframeSelector.prototype.Weight;
    P.sortProperty = SubframeSelector.prototype.Weight;
    P.pedestalMode = SubframeSelector.prototype.Pedestal_Keyword;
    P.pedestal = 0;
    P.pedestalKeyword = "";
    P.inputHints = "";
    P.outputHints = "";
    P.outputExtension = ".xisf";
    P.outputPrefix = "";
    P.outputPostfix = "_a";  // NOTE: files will be saved as *_cc_a.xisf in approved dir
    P.outputKeyword = "SSWEIGHT";  // CRITICAL: must be set
    P.generateHistoryProperties = true;
    P.overwriteExistingFiles = true;
    P.onError = SubframeSelector.prototype.Continue;
//    P.sortProperty = SubframeSelector.prototype.Weight;
    P.graphProperty = SubframeSelector.prototype.FWHM;
    P.auxGraphProperty = SubframeSelector.prototype.Weight;
    P.useFileThreads = true;
    P.fileThreadOverload = 1.00;
    P.maxFileReadThreads = 0;
    P.maxFileWriteThreads = 0;
}

function SS_makeGroupLabel(PLAN, gkey, total){
    // reuse IC label formatting if available
    try{
        if (typeof CP__fmtGroupForUI === "function"){
            return CP__fmtGroupForUI(gkey) + " (" + total + " subs)";
        }
    }catch(_){}
    return String(gkey||"") + " (" + total + " subs)";
}
// Format elapsed time consistently with other operations
function SS_fmtHMS(sec){
    var t = Math.max(0, Math.floor(sec));
    var hh = Math.floor(t/3600), mm = Math.floor((t%3600)/60), ss = t%60;
    var hs = Math.floor((sec - t) * 100); // hundredths
    var pad=function(n){ return (n<10?"0":"")+n; };
    return pad(hh)+":"+pad(mm)+":"+pad(ss)+"."+pad(hs);
}
/* ========================================
   NEW IMPLEMENTATION: Manual weight computation
   ======================================== */
/**
 * SS_measureAllFiles - Run ONE Measure for all files from all groups
 * Returns P.measurements array
 */
function SS_measureAllFiles(allFiles, scale, cameraGain){
    if (!allFiles || !allFiles.length){
        Console.warningln("[ss] No files to measure");
        return [];
    }

    Console.writeln("[ss] Measuring " + allFiles.length + " files...");
    Console.writeln("[ss]   Scale: " + scale.toFixed(4) + " arcsec/px");
    Console.writeln("[ss]   Camera gain: " + cameraGain);

    // Build subframes array
    var subs = [];
    for (var i=0; i<allFiles.length; ++i){
        subs.push([true, SS_norm(allFiles[i]), "", ""]);
    }

    // Create SS process
    var P = new SubframeSelector;
    SS_applyTemplateDefaults(P);
    P.subframes = subs;
    P.subframeScale = scale;
    P.cameraGain = cameraGain;
    P.nonInteractive = true;

    // Run Measure
    P.routine = SubframeSelector.prototype.MeasureSubframes;

    var t0 = Date.now();
    var ok = P.executeGlobal();
    var elapsed = (Date.now() - t0) / 1000;

    Console.writeln("[ss] Measure completed in " + SS_fmtHMS(elapsed));

    if (!ok){
        Console.criticalln("[ss] Measure failed");
        return [];
    }

    // Check measurements
    if (!P.measurements || !(P.measurements instanceof Array)){
        Console.criticalln("[ss] P.measurements is not an array!");
        return [];
    }

    Console.writeln("[ss] Measured " + P.measurements.length + " files");
    return P.measurements;
}

/**
 * SS_getMinMaxForGroup - Compute Min/Max for measurements of one group
 * Returns { FWHMMin, FWHMMax, EccentricityMin, EccentricityMax, PSFSignalWeightMin, PSFSignalWeightMax }
 */
function SS_getMinMaxForGroup(measurements){
    if (!measurements || !measurements.length){
        return {
            FWHMMin: 0, FWHMMax: 0,
            EccentricityMin: 0, EccentricityMax: 0,
            PSFSignalWeightMin: 0, PSFSignalWeightMax: 0
        };
    }

    var fwhms = [], eccs = [], psfs = [];
    for (var i=0; i<measurements.length; ++i){
        var m = measurements[i];
        if (m && m.length >= 8){
            // [0]=index, [1]=enabled, [2]=locked, [3]=path, [4]=weight(ignore),
            // [5]=FWHM, [6]=Eccentricity, [7]=PSFSignalWeight
            fwhms.push(m[5]);
            eccs.push(m[6]);
            psfs.push(m[7]);
        }
    }

    function _min(arr){ var m=arr[0]; for(var i=1;i<arr.length;++i) if(arr[i]<m) m=arr[i]; return m; }
    function _max(arr){ var m=arr[0]; for(var i=1;i<arr.length;++i) if(arr[i]>m) m=arr[i]; return m; }

    return {
        FWHMMin: _min(fwhms),
        FWHMMax: _max(fwhms),
        EccentricityMin: _min(eccs),
        EccentricityMax: _max(eccs),
        PSFSignalWeightMin: _min(psfs),
        PSFSignalWeightMax: _max(psfs)
    };
}

/**
 * SS_computeWeight - Compute weight for one measurement
 * Returns { weight: Number, approved: Boolean, reason: String }
 */
function SS_computeWeight(measurement, minMax, scale){
    // measurement: [index, enabled, locked, path, weight(ignore), FWHM, Eccentricity, PSFSignalWeight, ...]
    if (!measurement || measurement.length < 8){
        return { weight: 0, approved: false, reason: "Invalid measurement" };
    }
    var FWHM = measurement[5];
    var Eccentricity = measurement[6];
    var PSFSignalWeight = measurement[7];

    // Approval thresholds
//    var low = 0.5 * scale;
//    var high = 6.0 * scale;
    var low = 0.5;
    var high = 6.0;

    // Check approval
    var approved = true;
    var reason = "";

    if (FWHM < low){
        approved = false;
        reason = "FWHM=" + FWHM.toFixed(2) + " < " + low.toFixed(2);
    } else if (FWHM > high){
        approved = false;
        reason = "FWHM=" + FWHM.toFixed(2) + " > " + high.toFixed(2);
    } else if (Eccentricity > 0.70){
        approved = false;
        reason = "Eccentricity=" + Eccentricity.toFixed(2) + " > 0.70";
    } else if (PSFSignalWeight * 4 <= minMax.PSFSignalWeightMax){
        // NEW: Reject files with PSFSignalWeight < 25% of maximum
        // This catches clouds, closed roof, heavy light pollution, etc.
        approved = false;
        reason = "PSFSignal=" + PSFSignalWeight.toFixed(6) + " < 25% of max (" + minMax.PSFSignalWeightMax.toFixed(6) + ")";
    }

    if (!approved){
        return { weight: 0, approved: false, reason: reason };
    }

    // Compute weight (like WBPP)
    var a = 0, b = 0, ps = 0;

    // FWHM component (check division by zero)
    if (minMax.FWHMMax - minMax.FWHMMin > 0){
        a = 1 - (FWHM - minMax.FWHMMin) / (minMax.FWHMMax - minMax.FWHMMin);
    }

    // Eccentricity component
    if (minMax.EccentricityMax - minMax.EccentricityMin > 0){
        b = 1 - (Eccentricity - minMax.EccentricityMin) / (minMax.EccentricityMax - minMax.EccentricityMin);
    }

    // PSFSignalWeight component
    if (minMax.PSFSignalWeightMax - minMax.PSFSignalWeightMin > 0){
        ps = (PSFSignalWeight - minMax.PSFSignalWeightMin) / (minMax.PSFSignalWeightMax - minMax.PSFSignalWeightMin);
    }

    // Formula: 15*a + 15*b + 20*ps + 50
    var weight = 15*a + 15*b + 20*ps + 50;

    return { weight: weight, approved: true, reason: "" };
}

/**
 * SS_copyRejectedFile - Simple copy to trash
 */
function SS_copyRejectedFile(srcPath, dstPath){
    try{
        // Just copy file as-is
        if (!File.exists(srcPath)){
            Console.warningln("[ss] Source not found: " + srcPath);
            return false;
        }

        // Ensure destination directory exists
        var dstDir = SS_dirname(dstPath);
        if (dstDir && !File.directoryExists(dstDir)){
            File.createDirectory(dstDir, true);
        }

            // Remove existing file if present
            if (File.exists(dstPath)){
            File.remove(dstPath);
            }
        // Copy file
        File.copyFile(dstPath, srcPath);
        return true;

    } catch(e){
        Console.warningln("[ss] Error copying to trash: " + e);
        return false;
    }
}
/**
 * SS_saveWeightsCSV - Save weights to CSV file
 * csvData: [{filename: "file_a.xisf", weight: 85.32}, ...]
 */
function SS_saveWeightsCSV(csvData, csvPath){
    try{
        var csv = "";
        for (var i=0; i<csvData.length; ++i){
            csv += csvData[i].filename + "," + csvData[i].weight.toFixed(6) + "\n";
        }

        var f = new File;
        f.createForWriting(csvPath);
        f.outText(csv);
        f.close();

        return true;
    } catch(e){
        Console.warningln("[ss] Error saving CSV: " + e);
        return false;
    }
}
/**
 * SS_processGroup - Process one group: compute weights, use SS Output
 */
/**
 * SS_copyTop5 - Copy TOP-N best files to separate folder with rank prefix
 * @param gkey - group key (will be folder name)
 * @param approvedMeasurements - array of approved measurements (sorted by weight)
 * @param approvedDir - !Approved directory path
 * @param best5BaseDir - !Approved_Best5 directory path (base, without group subfolder)
 * @param autoReference - if true, copy only TOP-1; if false, copy TOP-5
 */
function SS_copyTop5(gkey, approvedMeasurements, approvedDir, best5BaseDir, autoReference){
    if (!best5BaseDir || !approvedMeasurements || !approvedMeasurements.length) return;

    // Sort by weight DESC
    var sorted = approvedMeasurements.slice(0);
    sorted.sort(function(a, b){ return b[4] - a[4]; }); // [4] = weight

    // Take TOP-1 or TOP-5 depending on autoReference flag
    var topN = autoReference ? sorted.slice(0, 1) : sorted.slice(0, Math.min(5, sorted.length));
    var topCount = topN.length;

    // Create group subfolder (sanitize key for filesystem)
    var groupFolder = gkey.replace(/[|:\\/\s]+/g, "_");
    var groupPath = SS_norm(best5BaseDir + "/" + groupFolder);
    try{
        if (!File.directoryExists(groupPath))
            File.createDirectory(groupPath, true);
    }catch(e){
        Console.warningln("[ss] Failed to create TOP-5 folder: " + e);
        return;
    }

    Console.writeln("[ss]   Copying TOP-" + topCount + " to: " + groupPath);

    // Copy with prefix !1_ (and !2_, !3_, !4_, !5_ if autoReference=false)
    for (var i=0; i<topN.length; ++i){
        var m = topN[i];
        var srcPath = SS_norm(String(m[3]));
        var basename = SS_basename(srcPath);
        var stem = SS_noext(basename);

        // Approved file name (with _a.xisf suffix)
        var approvedName = stem + "_a.xisf";
        var approvedPath = SS_norm(approvedDir + "/" + approvedName);

        // TOP-5 file name (with !N_ prefix)
        var rank = i + 1;
        var top5Name = "!" + rank + "_" + approvedName;
        var top5Path = SS_norm(groupPath + "/" + top5Name);

        try{
            // Remove if exists
            if (File.exists(top5Path)) File.remove(top5Path);

            // Copy from approved folder (NOTE: PI has reversed argument order vs docs)
            File.copyFile(top5Path, approvedPath);

            Console.writeln("[ss]     ✔ [" + rank + "] " + approvedName + " (weight=" + m[4].toFixed(2) + ")");
        }catch(eCopy){
            Console.warningln("[ss]     ✖ Failed to copy: " + eCopy);
        }
    }
}

function SS_processGroup(gkey, groupFiles, allMeasurements, scale, cameraGain, approvedDir, trashDir, best5BaseDir, autoReference, dlg, node){
    Console.writeln("[ss] Processing group: " + gkey);
    Console.writeln("[ss]   Files: " + groupFiles.length);

    // Extract measurements for this group (as copies)
    var groupMeasurements = [];
    var measurementMap = {}; // path -> measurement

    for (var i=0; i<allMeasurements.length; ++i){
        var m = allMeasurements[i];
        if (m && m.length >= 4){
            var path = SS_norm(String(m[3]));
            measurementMap[path] = m;
        }
    }

    for (var j=0; j<groupFiles.length; ++j){
        var gf = SS_norm(groupFiles[j]);
        if (measurementMap[gf]){
            // Make a COPY to avoid modifying original
            groupMeasurements.push(measurementMap[gf].slice(0));
        }
    }

    if (groupMeasurements.length === 0){
        Console.warningln("[ss] No measurements found for group");
        return { approved: 0, rejected: 0 };
    }

    // Compute Min/Max
    var minMax = SS_getMinMaxForGroup(groupMeasurements);

    Console.writeln("[ss]   FWHM: " + minMax.FWHMMin.toFixed(2) + " - " + minMax.FWHMMax.toFixed(2));
    Console.writeln("[ss]   Eccentricity: " + minMax.EccentricityMin.toFixed(3) + " - " + minMax.EccentricityMax.toFixed(3));
    Console.writeln("[ss]   PSFSignalWeight: " + minMax.PSFSignalWeightMin.toFixed(1) + " - " + minMax.PSFSignalWeightMax.toFixed(1));

    var approved = 0, rejected = 0;
    var approvedMeasurements = [];

    for (var k=0; k<groupMeasurements.length; ++k){
        var m = groupMeasurements[k];
        var srcPath = SS_norm(String(m[3]));
        var basename = SS_basename(srcPath);

        // Compute weight
        var result = SS_computeWeight(m, minMax, scale);

        // Substitute weight in measurement
        m[4] = result.weight;

        if (result.approved){
            m[1] = true; // enabled
            approvedMeasurements.push(m);
            approved++;
            Console.writeln("[ss]   ✓ " + basename + " → weight=" + result.weight.toFixed(2));
        } else {
            m[1] = false; // disabled
            rejected++;
            Console.writeln("[ss]   ✖ " + basename + " → " + result.reason);
        }

        // Update UI periodically (computing phase)
        if (k % 10 === 0){
            try{
                if (node) node.setText(4, (approved+rejected) + "/" + groupMeasurements.length + " computing weights");
                if (typeof processEvents === "function") processEvents();
            }catch(_){}
        }
    }

    Console.writeln("[ss]   Computed: " + approved + " approved, " + rejected + " rejected");

    // Copy approved files and save weights to CSV
    if (approvedMeasurements.length > 0){
        Console.writeln("[ss]   Copying " + approvedMeasurements.length + " approved files...");
        // Update UI: copying phase started
        try{
            if (node) node.setText(4, "0/" + approvedMeasurements.length + " copying approved");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}

        // Copy files to approved directory
        for (var j=0; j<approvedMeasurements.length; ++j){
            var m = approvedMeasurements[j];
            var srcPath = SS_norm(String(m[3]));
            var basename = SS_basename(srcPath);
            var stem = SS_noext(basename);
            var dstPath = SS_norm(approvedDir + "/" + stem + "_a.xisf");

            // Copy file
            try{
                // Remove existing file if present
                if (File.exists(dstPath)){
                    File.remove(dstPath);
                }
                File.copyFile(dstPath, srcPath);
                Console.writeln("[ss]     ✓ " + basename);
                // Update UI progress during copying
                if (j % 5 === 0){
                    try{
                        if (node) node.setText(4, (j+1) + "/" + approvedMeasurements.length + " copying approved");
                        if (typeof processEvents === "function") processEvents();
                    }catch(_){}
                }
            } catch(eCopy){
                Console.warningln("[ss]     ✖ Failed to copy: " + basename + " - " + eCopy);
                continue;
            }
        }

        // ================================================================
        // Determine TOP-N (1 or 5) and build complete CSV data
        // ================================================================

        // Sort by weight DESC to get TOP-N
        var sorted = approvedMeasurements.slice(0);
        sorted.sort(function(a, b){ return b[4] - a[4]; }); // [4] = weight
        var topN = autoReference ? sorted.slice(0, 1) : sorted.slice(0, Math.min(5, sorted.length));

        // Build CSV data: ALL approved files + TOP-5 files
        var csvData = [];

        // Add all approved files
        for (var j=0; j<approvedMeasurements.length; ++j){
            var m = approvedMeasurements[j];
            var srcPath = SS_norm(String(m[3]));
            var stem = SS_noext(SS_basename(srcPath));

            csvData.push({
                filename: stem + "_a.xisf",
                weight: m[4]
            });
        }

        // Add TOP-N files with !N_ prefix
        for (var t=0; t<topN.length; ++t){
            var m = topN[t];
            var srcPath = SS_norm(String(m[3]));
            var stem = SS_noext(SS_basename(srcPath));
            var rank = t + 1;

            csvData.push({
                filename: "!" + rank + "_" + stem + "_a.xisf",
                weight: m[4]
            });
        }

        // Save CSV with weights (includes both regular and TOP-N files)
        if (csvData.length > 0){
            // Create CSV filename from group key (replace | and spaces with _)
            var csvName = "subframe_weights_" + gkey.replace(/\|/g, "_").replace(/\s+/g, "_") + ".csv";
            var csvPath = SS_norm(approvedDir + "/" + csvName);
            if (SS_saveWeightsCSV(csvData, csvPath)){
                Console.writeln("[ss]   Saved weights CSV: " + csvPath + " (" + csvData.length + " entries)");
            } else {
                Console.warningln("[ss]   Failed to save weights CSV");
            }
        }

        // Copy TOP-N best files to separate folder
        if (best5BaseDir){
            SS_copyTop5(gkey, topN, approvedDir, best5BaseDir, autoReference);
        }
    } else {
        Console.writeln("[ss]   No approved files, skipping copy");
    }

    // Copy rejected to trash
    if (rejected > 0 && trashDir){
        Console.writeln("[ss]   Copying " + rejected + " rejected files to trash...");
        for (var r=0; r<groupMeasurements.length; ++r){
            var m = groupMeasurements[r];
            if (!m[1]){ // disabled = rejected
                var srcPath = SS_norm(String(m[3]));
                var basename = SS_basename(srcPath);
                var stem = SS_noext(basename);
                var trashPath = SS_norm(trashDir + "/" + stem + "_cc.xisf");
                SS_copyRejectedFile(srcPath, trashPath);
            }
        }
    }

    Console.writeln("[ss]   Result: " + approved + "/" + groupMeasurements.length + " approved, " + rejected + " rejected");

    return { approved: approved, rejected: rejected };
}

function SS_collectGroupFiles(PLAN, wf, preferCC){
    if (!PLAN || !PLAN.groups) return [];

    var root = String(wf && wf.cosmetic || "");
    var ssGroups = {}; // Упрощённая группировка: ssKey -> {key, files, binning}

    // Проходим все IC-группы
    for (var icKey in PLAN.groups){
        if (!PLAN.groups.hasOwnProperty(icKey)) continue;

        var G = PLAN.groups[icKey];

        // Извлечь параметры из группы
        var setup = G.setup || "UNKNOWN";
        var object = G.object || "UNKNOWN";
        var filter = G.filter || "L";
        var binning = G.binning || "1x1";
        var expTime = G.exposureSec || 0;

        // Создать упрощённый ключ для SS (без master paths, tempC, readout)
        var ssKey = setup + "|" + object + "|" + filter + "|" + "bin" + binning + "|" + expTime + "s";

        // Создать SS-группу если ещё нет
        if (!ssGroups[ssKey]){
            ssGroups[ssKey] = {
                key: ssKey,
                files: [],
                binning: binning
            };
        }

        // Собрать CC файлы из этой IC-группы
        var bases = [];
        var fromCalibrated = false;
        if (G._outputs && G._outputs.calibrated && G._outputs.calibrated.length){
            bases = G._outputs.calibrated.slice(0);
            fromCalibrated = true;
        } else if (G.lights && G.lights.length){
            bases = G.lights.slice(0);
            fromCalibrated = false;
        }

        // Построить ожидаемые CC-файлы на диске
        for (var i=0; i<bases.length; ++i){
            var b = String(bases[i]||"");
            var stem = SS_noext(b);
            var p1, p2;
            if (fromCalibrated){
                p1 = root + "/" + stem + "_cc.xisf";
                p2 = root + "/" + stem + "_c_cc.xisf";
            } else {
                p1 = root + "/" + stem + "_c_cc.xisf";
                p2 = root + "/" + stem + "_cc.xisf";
            }

            try{
                if (File.exists(p1)){
                    ssGroups[ssKey].files.push(p1);
                } else if (File.exists(p2)){
                    ssGroups[ssKey].files.push(p2);
                }
            }catch(_){}
        }
    }
// Конвертировать в массив и отсортировать по фильтрам
    var res = [];
    for (var k in ssGroups){
        if (ssGroups.hasOwnProperty(k) && ssGroups[k].files.length > 0){
            res.push(ssGroups[k]);
        }
    }

    // Сортировка по фильтрам: L → R → G → B → Ha → OIII → SII
    var filterOrder = {"L":0, "R":1, "G":2, "B":3, "HA":4, "OIII":5, "SII":6};
    res.sort(function(a, b){
        var fa = a.key.split("|")[2] || "";
        var fb = b.key.split("|")[2] || "";
        var oa = filterOrder[fa.toUpperCase()];
        var ob = filterOrder[fb.toUpperCase()];
        if (oa === undefined) oa = 99;
        if (ob === undefined) ob = 99;
        if (oa !== ob) return oa - ob;
        return a.key.localeCompare(b.key); // Алфавитный порядок если фильтр одинаковый
    });

    // Логирование результата
    Console.writeln("[ss] Regrouped into " + res.length + " SS group(s):");
    for (var i=0; i<res.length; ++i){
        Console.writeln("[ss]   " + res[i].key + " (" + res[i].files.length + " files)");
    }

    return res;
}

/* Public API */
function SS_runForAllGroups(params){
    // params: { PLAN, workFolders, preferCC, autoReference, cameraGain, subframeScale, dlg }
    var PLAN = params.PLAN, wf = params.workFolders, LI = params.LI;
    var preferCC = !!params.preferCC;
    var autoReference = (params.autoReference !== false); // default true
    var cameraGain = params.cameraGain || 0.333;
    var scale = params.subframeScale || 0.7210;
    var dlg = params.dlg || null;
// Log parameters
    Console.writeln("[ss] SubframeSelector: Manual weight computation");
    Console.writeln("[ss]   Scale: " + scale.toFixed(4) + " arcsec/px");
    Console.writeln("[ss]   Camera gain: " + cameraGain);
    Console.writeln("[ss]   Auto reference: " + (autoReference ? "ON (TOP-1)" : "OFF (TOP-5)"));

    // Step 1: Collect and regroup files
    var groups = SS_collectGroupFiles(PLAN, wf, preferCC);

    if (!groups || groups.length === 0){
        Console.warningln("[ss] No groups with files found");
        return;
    }

    Console.writeln("[ss] Found " + groups.length + " groups");

    // Pre-add rows for Measure + all groups BEFORE starting work
    if (dlg){
        try{
            // Add 2 rows per group: Measure + Output
            if (!dlg.ssRowsMap) dlg.ssRowsMap = {};
            for (var gi=0; gi<groups.length; ++gi){
                var g = groups[gi];
                var label = g.key + " (" + g.files.length + " subs)";

                // 1) Measure row
                    var measureNode = null;
                try{
                    if (typeof PP_addRowUnique === "function")
                        measureNode = PP_addRowUnique(dlg, "SubframeSelector - Measure", label, "SSM|" + g.key);
                    else
                        measureNode = dlg.addRow("SubframeSelector - Measure", label);
                        measureNode.setText(3, "⏳ Queued");
                        measureNode.setText(4, g.files.length + "/" + g.files.length + " queued");
                }catch(_){}

                // 2) Output row
                var outputNode = null;
                try{
                    if (typeof PP_addRowUnique === "function")
                        outputNode = PP_addRowUnique(dlg, "SubframeSelector - Output", label, "SSO|" + g.key);
                    else
                        outputNode = dlg.addRow("SubframeSelector - Output", label);
                    outputNode.setText(3, "⏳ Queued");
                    outputNode.setText(4, g.files.length + "/" + g.files.length + " queued");
                }catch(_){}
                // Store both nodes
                dlg.ssRowsMap[g.key] = {
                    measureNode: measureNode,
                    outputNode: outputNode,
                    label: label
                };
            }

            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }

// Step 2: Process each group (Measure + Output)
    Console.writeln("[ss] ========================================");
    Console.writeln("[ss] Processing groups");
    Console.writeln("[ss] ========================================");

    var trashDir = wf.trash || "";
    if (!trashDir){
        Console.warningln("[ss] Trash directory not set - rejected files will not be saved");
    }
// Create !Approved_Best5 base folder (once, before processing groups)
    var best5BaseDir = null;
    if (wf.approved){
        best5BaseDir = SS_norm(wf.approved + "/!Approved_Best5");
        try{ if (!File.directoryExists(best5BaseDir)) File.createDirectory(best5BaseDir, true); }catch(e){
            Console.warningln("[ss] Failed to create !Approved_Best5: " + e);
            best5BaseDir = null;
        }
    }

    for (var i=0;i<groups.length;++i){
        if (dlg && dlg.cancelled) break;

        var g = groups[i];
        var rec = (dlg && dlg.ssRowsMap && dlg.ssRowsMap[g.key]) || {};

        var measureNode = rec.measureNode || null;
        var outputNode = rec.outputNode || null;

        Console.writeln("[ss] Group " + (i+1) + "/" + groups.length + ": " + g.key);

            // STEP 1: Measure this group
        try{
            if (measureNode) measureNode.setText(3, "▶ Running");
            if (measureNode) measureNode.setText(4, "0/" + g.files.length + " measuring");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}

        var t0M = Date.now();
        var measurements = SS_measureAllFiles(g.files, scale, cameraGain);
        var elapsedM = (Date.now() - t0M) / 1000;

        try{
            if (measureNode) measureNode.setText(2, SS_fmtHMS(elapsedM));
            if (measureNode) measureNode.setText(3, "✔ Success");
            if (measureNode) measureNode.setText(4, measurements.length + "/" + g.files.length + " measured");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}

        if (!measurements || measurements.length === 0){
            Console.warningln("[ss]   Measure failed - skipping group");
            try{
                if (outputNode) outputNode.setText(3, "✖ Error");
                if (outputNode) outputNode.setText(4, "Measure failed");
            }catch(_){}
            continue;
        }

        // STEP 2: Output (compute weights, copy files)
        try{
            if (outputNode) outputNode.setText(3, "▶ Running");
            if (outputNode) outputNode.setText(4, "0/" + g.files.length + " processing");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}

        var t0O = Date.now();
        var result = SS_processGroup(
            g.key,
            g.files,
            measurements,
            scale,
            cameraGain,
            wf.approved,
            trashDir,
            best5BaseDir,
            autoReference,
            dlg,
            outputNode
        );
        var elapsedO = (Date.now() - t0O) / 1000;

        try{
            if (outputNode) outputNode.setText(2, SS_fmtHMS(elapsedO));
            if (outputNode) outputNode.setText(3, "✔ Success");
            if (outputNode) outputNode.setText(4, result.approved + "/" + g.files.length + " approved, " + result.rejected + " rejected");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }

    Console.writeln("[ss] ========================================");
    Console.writeln("[ss] SubframeSelector complete");
    Console.writeln("[ss] ========================================");
}
