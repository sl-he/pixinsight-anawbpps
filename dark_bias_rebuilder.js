/*
 * Dark/Bias Rebuilder - Standalone Script
 * Reconstructs unbiased master-darks from bias-calibrated master-darks
 * 
 * Mathematical basis:
 *   MasterDark_raw = MasterDark_cal + MasterBias
 * 
 * Valid only when:
 *   - Original darks were calibrated with bias subtraction (no scaling/optimization)
 *   - Master-bias used in original pipeline is available
 *   - Same acquisition parameters: gain, offset, readout, binning, temp, USB
 * 
 * Copyright (C) 2024-2025 sl-he
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#feature-id    sl-he > DarkBiasRebuilder
#feature-info  Rebuild unbiased master-darks from bias-calibrated master-darks and master-biases.
#define TITLE "DarkBiasRebuilder"
#define VERSION "1.0.0"


#include <pjsr/StdDialogCode.jsh>
#include <pjsr/Sizer.jsh>
#include "modules/fits_indexing.jsh"

// ============================================================================
// Configuration
// ============================================================================

var DBR_WINDOW_DAYS = 14;  // ±14 days matching window for bias selection

// ============================================================================
// Helpers
// ============================================================================

function DBR_norm(p){
    var s = String(p||"").replace(/\\/g, '/');
    while (s.length > 1 && s.charAt(s.length-1) == '/'){
        s = s.substring(0, s.length-1);
    }
    return s;
}

function DBR_basename(p){
    var s = DBR_norm(p);
    var i = s.lastIndexOf('/');
    return (i>=0) ? s.substring(i+1) : s;
}

function DBR_dirname(p){
    var s = DBR_norm(p);
    var i = s.lastIndexOf('/');
    return (i>0) ? s.substring(0, i) : s;
}

function DBR_ensureDir(dir){
    if (!File.directoryExists(dir)){
        File.createDirectory(dir, true);
    }
}

function DBR_relativePath(fullPath, root){
    var f = DBR_norm(fullPath);
    var r = DBR_norm(root);
    if (f.substring(0, r.length).toUpperCase() == r.toUpperCase()){
        var rel = f.substring(r.length);
        if (rel.charAt(0) == '/') rel = rel.substring(1);
        return rel;
    }
    return DBR_basename(fullPath);
}

function DBR_parseISODate(dateStr){
    // Parse "YYYY-MM-DD" to Date object
    if (!dateStr) return null;
    var parts = dateStr.split('-');
    if (parts.length < 3) return null;
    var y = parseInt(parts[0], 10);
    var m = parseInt(parts[1], 10);
    var d = parseInt(parts[2], 10);
    if (isNaN(y) || isNaN(m) || isNaN(d)) return null;
    if (m < 1 || m > 12 || d < 1 || d > 31) return null;
    return new Date(y, m - 1, d);
}

function DBR_daysDiff(dateStr1, dateStr2){
    var d1 = DBR_parseISODate(dateStr1);
    var d2 = DBR_parseISODate(dateStr2);
    if (!d1 || !d2) return null;
    var diffMs = Math.abs(d1.getTime() - d2.getTime());
    return Math.round(diffMs / (24 * 3600 * 1000));
}

function DBR_sameStr(a, b){
    return String(a||"") === String(b||"");
}

function DBR_sameInt(a, b){
    if (a == null || b == null) return false;
    return parseInt(a, 10) === parseInt(b, 10);
}

function DBR_saveJSON(path, obj){
    try {
        var json = JSON.stringify(obj, null, 2);
        var f = new File;
        f.createForWriting(path);
        f.outTextLn(json);
        f.close();
        Console.writeln("[dbr] JSON saved: " + path);
        return true;
    } catch(e){
        Console.warningln("[dbr] Failed to save JSON: " + e);
        return false;
    }
}

// ============================================================================
// Master Files Indexing (supports .fit, .fits, .xisf)
// ============================================================================

/**
 * Index master files in directory (supports FIT/FITS/XISF)
 * Uses _fi_parseUnified from fits_indexing.jsh
 * Returns: { items: [...], count: N, errors: E, time: T }
 */
function DBR_indexMasters(rootPath){
    var t0 = Date.now();
    var results = [];
    var errors = 0;
    
    Console.writeln("[dbr-index] Scanning: " + rootPath);
    
    function isFitsLike(path){
        var ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        return (ext == "fit" || ext == "fits" || ext == "xisf" || ext == "fts");
    }
    
    function walkDir(dir){
        var names = [];
        
        // Use FileFind for cross-platform compatibility
        try {
            var ff = new FileFind;
            if (ff.begin(dir + "/*")){
                do {
                    var name = ff.name;
                    if (name && name != "." && name != ".."){
                        names.push(name);
                    }
                } while (ff.next());
                ff.end();
            }
        } catch(e){
            Console.warningln("[dbr-index] FileFind failed in " + dir + ": " + e);
            return;
        }
        
        for (var i = 0; i < names.length; i++){
            var name = names[i];
            var fullPath = DBR_norm(dir + "/" + name);
            
            if (File.directoryExists(fullPath)){
                // Recurse into subdirectory
                walkDir(fullPath);
            } else if (File.exists(fullPath) && isFitsLike(fullPath)){
                // Parse FITS-like file
                try {
                    // Use _fi_parseUnified from fits_indexing.jsh
                    if (typeof _fi_parseUnified !== "function"){
                        throw new Error("_fi_parseUnified not available (fits_indexing.jsh not loaded?)");
                    }
                    
                    var parsed = _fi_parseUnified(fullPath, rootPath, "MASTER");
                    results.push(parsed);
                    
                } catch(e){
                    // Skip files that can't be parsed (e.g., DarkFlats, corrupted files)
                    var errMsg = String(e);
                    if (errMsg.indexOf("DarkFlat") < 0 && errMsg.indexOf("FlatDark") < 0){
                        // Only log non-DarkFlat errors
                        Console.warningln("[dbr-index] Parse error: " + DBR_basename(fullPath) + " — " + e);
                    }
                    errors++;
                }
            }
        }
    }
    
    walkDir(rootPath);
    
    var elapsed = (Date.now() - t0) / 1000;
    
    Console.writeln("[dbr-index] Indexed " + results.length + " files, " + errors + " errors (" + elapsed.toFixed(2) + "s)");
    
    return {
        items: results,
        count: results.length,
        errors: errors,
        time: elapsed
    };
}

// ============================================================================
// Bias Matching Logic (adapted from calibration_match.jsh)
// ============================================================================

/**
 * Find matching bias for a dark
 * Strict match: setup, readout, gain, offset, usb, binning, tempC
 * Date: ±WINDOW_DAYS, pick closest (any direction)
 */
function DBR_pickBiasForDark(D, biases){
    var pool = [];
    
    for (var i = 0; i < biases.length; i++){
        var B = biases[i];
        
        // Type check
        if (B.type !== "BIAS") continue;
        
        // Strict parameter matching
        if (!DBR_sameStr(D.setup, B.setup)) continue;
        if (!DBR_sameStr(D.readout, B.readout)) continue;
        if (!DBR_sameInt(D.gain, B.gain)) continue;
        if (!DBR_sameInt(D.offset, B.offset)) continue;
        if (!DBR_sameInt(D.usb, B.usb)) continue;
        if (!DBR_sameStr(D.binning, B.binning)) continue;
        if (!DBR_sameInt(D.tempC, B.tempC)) continue;
        
        // Date proximity check
        var dd = DBR_daysDiff(D.date, B.date);
        if (dd === null) continue;
        if (dd > DBR_WINDOW_DAYS) continue;
        
        pool.push({ B: B, dd: dd });
    }
    
    if (pool.length === 0) return null;
    
    // Sort by closest date (ascending distance)
    pool.sort(function(a, b){
        return a.dd - b.dd;
    });
    
    return pool[0].B;
}

// ============================================================================
// Image Reconstruction
// ============================================================================

/**
 * Rebuild single dark: MasterDark_raw = MasterDark_cal + MasterBias
 */
function DBR_rebuildDark(darkPath, biasPath, outPath, dryRun){
    Console.writeln("[dbr]   Dark: " + DBR_basename(darkPath));
    Console.writeln("[dbr]   Bias: " + DBR_basename(biasPath));
    Console.writeln("[dbr]   Out:  " + DBR_basename(outPath));
    
    if (dryRun){
        Console.writeln("[dbr]   → DRY RUN: skipping actual processing");
        return true;
    }
    
    var wDark = null, wBias = null;
    
    try {
        // Open images
        var darkWindows = ImageWindow.open(darkPath);
        if (!darkWindows || darkWindows.length < 1){
            throw new Error("Cannot open dark: " + darkPath);
        }
        wDark = darkWindows[0];
        
        var biasWindows = ImageWindow.open(biasPath);
        if (!biasWindows || biasWindows.length < 1){
            throw new Error("Cannot open bias: " + biasPath);
        }
        wBias = biasWindows[0];
        
        var imgDark = wDark.mainView.image;
        var imgBias = wBias.mainView.image;
        
        // Geometry check
        if (imgDark.width !== imgBias.width || 
            imgDark.height !== imgBias.height || 
            imgDark.numberOfChannels !== imgBias.numberOfChannels){
            throw new Error("Geometry mismatch: dark=" + 
                imgDark.width + "x" + imgDark.height + "x" + imgDark.numberOfChannels + 
                ", bias=" + 
                imgBias.width + "x" + imgBias.height + "x" + imgBias.numberOfChannels);
        }
        
        // Ensure both images are in float format (no explicit conversion needed if already float)
        // PixelMath will work with whatever format is loaded
        
        // Apply PixelMath: wDark = wDark + wBias
        Console.writeln("[dbr]   → Applying PixelMath: $T + " + wBias.mainView.id);
        
        var P = new PixelMath;
        P.expression = "$T + " + wBias.mainView.id;
        P.expression1 = "";
        P.expression2 = "";
        P.expression3 = "";
        P.useSingleExpression = true;
        P.symbols = "";
        P.clearImageCacheAndExit = false;
        P.cacheGeneratedImages = false;
        P.generateOutput = true;
        P.singleThreaded = false;
        P.optimization = true;
        P.use64BitWorkingImage = false;
        P.rescale = false;
        P.rescaleLower = 0;
        P.rescaleUpper = 1;
        P.truncate = true;  // Standard PixelMath behavior (32-bit float preserves full range)
        P.truncateLower = 0;
        P.truncateUpper = 1;
        P.createNewImage = false;  // Modify wDark in-place
        P.showNewImage = true;
        P.newImageId = "";
        P.newImageWidth = 0;
        P.newImageHeight = 0;
        P.newImageAlpha = false;
        P.newImageColorSpace = PixelMath.prototype.SameAsTarget;
        P.newImageSampleFormat = PixelMath.prototype.SameAsTarget;
        
        // Execute on dark view (modifies wDark in-place)
        if (!P.executeOn(wDark.mainView)){
            throw new Error("PixelMath execution failed");
        }
        
        // Add HISTORY keywords
        var kws = wDark.keywords;
        kws.push(
            new FITSKeyword("HISTORY", "", 
                "Reconstructed: MasterDark_raw = MasterDark_cal + MasterBias")
        );
        kws.push(
            new FITSKeyword("HISTORY", "", 
                "Dark: " + DBR_basename(darkPath))
        );
        kws.push(
            new FITSKeyword("HISTORY", "", 
                "Bias: " + DBR_basename(biasPath))
        );
        wDark.keywords = kws;
        
        // Ensure output directory exists
        DBR_ensureDir(DBR_dirname(outPath));
        
        // Save modified dark as 32-bit float XISF
        Console.writeln("[dbr]   → Saving to: " + outPath);
        if (!wDark.saveAs(outPath, false, false, true, false)){
            throw new Error("Failed to save: " + outPath);
        }
        
        // Close windows
        wBias.forceClose();
        wDark.forceClose();
        
        Console.writeln("[dbr]   ✓ Success");
        return true;
        
    } catch(e){
        Console.criticalln("[dbr]   ✖ ERROR: " + e);
        
        // Cleanup
        try { if (wDark) wDark.forceClose(); } catch(_){}
        try { if (wBias) wBias.forceClose(); } catch(_){}
        
        return false;
    }
}

// ============================================================================
// Main Processing Pipeline
// ============================================================================

function DBR_run(mastersRoot, outputRoot, dryRun){
    Console.show();
    Console.writeln("========================================");
    Console.writeln("Dark/Bias Rebuilder");
    Console.writeln("========================================");
    Console.writeln("Masters Root: " + mastersRoot);
    Console.writeln("Output Root:  " + outputRoot);
    Console.writeln("Dry Run:      " + (dryRun ? "YES" : "NO"));
    Console.writeln("========================================\n");
    
    var t0 = Date.now();
    
    var diagnostics = {
        darksWithMissingData: [],
        biasesWithMissingData: []
    };
    
    // Index all masters using DBR_indexMasters (supports .fit files)
    Console.writeln("[dbr] Indexing master files...");
    var mastersIndex = DBR_indexMasters(mastersRoot);
    var allMasters = mastersIndex.items || [];
    
    Console.writeln("[dbr]   Found " + allMasters.length + " master files");
    if (mastersIndex.errors > 0){
        Console.warningln("[dbr]   Parsing errors: " + mastersIndex.errors);
    }
    
    // Separate darks and biases
    Console.writeln("[dbr] Separating darks and biases...");
    var darksAll = [];
    var biasesAll = [];
    
    for (var i = 0; i < allMasters.length; i++){
        var item = allMasters[i];
        if (item.type === "DARK"){
            darksAll.push(item);
        } else if (item.type === "BIAS"){
            biasesAll.push(item);
        }
    }
    
    Console.writeln("[dbr]   Darks:  " + darksAll.length);
    Console.writeln("[dbr]   Biases: " + biasesAll.length);
    Console.writeln("");
    // Check darks
    Console.writeln("[dbr] Analyzing darks...");
    var darkFiles = darksAll;
    
    // Check for missing metadata in darks
    var darksComplete = 0;
    for (var i = 0; i < darkFiles.length; i++){
        var D = darkFiles[i];
        var missing = [];
        if (!D.setup) missing.push("setup");
        if (D.gain == null || D.gain == undefined) missing.push("gain");
        if (D.offset == null || D.offset == undefined) missing.push("offset");
        if (D.usb == null || D.usb == undefined) missing.push("usb");
        if (!D.readout) missing.push("readout");
        if (!D.binning) missing.push("binning");
        if (D.tempC == null || D.tempC == undefined) missing.push("tempC");
        if (!D.date) missing.push("date");
        
        if (missing.length > 0){
            diagnostics.darksWithMissingData.push({
                path: D.path,
                parseMethod: D.parseMethod || "unknown",
                missingFields: missing
            });
        } else {
            darksComplete++;
        }
    }
    
    Console.writeln("[dbr]   Complete metadata: " + darksComplete + " / " + darkFiles.length);
    if (diagnostics.darksWithMissingData.length > 0){
        Console.warningln("[dbr]   Incomplete metadata: " + diagnostics.darksWithMissingData.length + 
                         " (see diagnostics in JSON)");
    }
    Console.writeln("");
    
    // Check biases
    Console.writeln("[dbr] Analyzing biases...");
    var biasFiles = biasesAll;
    
    // Check for missing metadata in biases
    var biasesComplete = 0;
    for (var j = 0; j < biasFiles.length; j++){
        var B = biasFiles[j];
        var missing = [];
        if (!B.setup) missing.push("setup");
        if (B.gain == null || B.gain == undefined) missing.push("gain");
        if (B.offset == null || B.offset == undefined) missing.push("offset");
        if (B.usb == null || B.usb == undefined) missing.push("usb");
        if (!B.readout) missing.push("readout");
        if (!B.binning) missing.push("binning");
        if (B.tempC == null || B.tempC == undefined) missing.push("tempC");
        if (!B.date) missing.push("date");
        
        if (missing.length > 0){
            diagnostics.biasesWithMissingData.push({
                path: B.path,
                parseMethod: B.parseMethod || "unknown",
                missingFields: missing
            });
        } else {
            biasesComplete++;
        }
    }
    
    Console.writeln("[dbr]   Complete metadata: " + biasesComplete + " / " + biasFiles.length);
    if (diagnostics.biasesWithMissingData.length > 0){
        Console.warningln("[dbr]   Incomplete metadata: " + diagnostics.biasesWithMissingData.length + 
                         " (see diagnostics in JSON)");
    }
    Console.writeln("");
    
    // Match and process
    Console.writeln("[dbr] Matching and processing...\n");
    
    var plan = [];
    var matched = 0, skipped = 0, errors = 0;
    
    for (var k = 0; k < darkFiles.length; k++){
        var D = darkFiles[k];
        
        Console.writeln("[dbr] (" + (k+1) + "/" + darkFiles.length + ") " + DBR_basename(D.path));
        Console.writeln("[dbr]   Setup: " + D.setup + ", Bin: " + D.binning + 
                       ", G:" + D.gain + ", OS:" + D.offset + ", U:" + D.usb + 
                       ", Temp:" + D.tempC + "C, Exp:" + D.exposureSec + "s, Date:" + D.date);
        
        // Find matching bias
        var B = DBR_pickBiasForDark(D, biasFiles);
        
        if (!B){
            Console.warningln("[dbr]   → NO BIAS MATCH (skipped)");
            skipped++;
            plan.push({
                dark: D.path,
                bias: null,
                output: null,
                status: "skipped",
                reason: "no_bias_match"
            });
            Console.writeln("");
            continue;
        }
        
        var biasDays = DBR_daysDiff(D.date, B.date);
        Console.writeln("[dbr]   → Matched bias: " + DBR_basename(B.path) + 
                       " (Δ=" + biasDays + "d)");
        
        // Build output path (mirror directory structure + rename)
        var relPath = DBR_relativePath(D.path, mastersRoot);
        var outPath = DBR_norm(outputRoot + "/" + relPath);
        
        // Insert "_No_Bias_" after "MasterDark"
        var outName = DBR_basename(outPath);
        var newName = outName.replace(/MasterDark/i, "MasterDark_No_Bias");
        
        // Force .xisf extension (replace .fit/.fits with .xisf)
        newName = newName.replace(/\.(fit|fits|fts)$/i, ".xisf");
        if (!/\.xisf$/i.test(newName)){
            // If no extension or unknown extension, add .xisf
            newName = newName + ".xisf";
        }
        
        outPath = DBR_dirname(outPath) + "/" + newName;
        
        // Process
        var success = DBR_rebuildDark(D.path, B.path, outPath, dryRun);
        
        if (success){
            matched++;
            plan.push({
                dark: D.path,
                bias: B.path,
                output: outPath,
                status: "success",
                biasDaysOffset: biasDays
            });
        } else {
            errors++;
            plan.push({
                dark: D.path,
                bias: B.path,
                output: outPath,
                status: "error",
                biasDaysOffset: biasDays
            });
        }
        
        Console.writeln("");
    }
    
    var elapsed = (Date.now() - t0) / 1000;
    
    // Save plan JSON
    var planPath = DBR_norm(outputRoot + "/rebuild_plan.json");
    var planData = {
        generatedUTC: (new Date()).toISOString(),
        mastersRoot: mastersRoot,
        outputRoot: outputRoot,
        dryRun: dryRun,
        windowDays: DBR_WINDOW_DAYS,
        indexingStats: {
            totalFilesFound: allMasters.length,
            parsingErrors: mastersIndex.errors,
            darks: {
                identified: darkFiles.length,
                completeMetadata: darksComplete,
                incompleteMetadata: diagnostics.darksWithMissingData.length
            },
            biases: {
                identified: biasFiles.length,
                completeMetadata: biasesComplete,
                incompleteMetadata: diagnostics.biasesWithMissingData.length
            }
        },
        matchingStats: {
            totalDarks: darkFiles.length,
            matched: matched,
            skipped: skipped,
            errors: errors,
            elapsedSeconds: elapsed
        },
        diagnostics: {
            darksWithMissingData: diagnostics.darksWithMissingData.length,
            biasesWithMissingData: diagnostics.biasesWithMissingData.length,
            details: diagnostics
        },
        plan: plan
    };
    DBR_saveJSON(planPath, planData);
    
    // Summary
    Console.writeln("========================================");
    Console.writeln("INDEXING SUMMARY");
    Console.writeln("========================================");
    Console.writeln("Total files found:    " + allMasters.length);
    if (mastersIndex.errors > 0){
        Console.writeln("Parsing errors:       " + mastersIndex.errors);
    }
    Console.writeln("");
    Console.writeln("Darks:");
    Console.writeln("  Identified:           " + darkFiles.length);
    Console.writeln("  Complete metadata:    " + darksComplete);
    Console.writeln("  Incomplete metadata:  " + diagnostics.darksWithMissingData.length);
    Console.writeln("");
    Console.writeln("Biases:");
    Console.writeln("  Identified:           " + biasFiles.length);
    Console.writeln("  Complete metadata:    " + biasesComplete);
    Console.writeln("  Incomplete metadata:  " + diagnostics.biasesWithMissingData.length);
    Console.writeln("");
    Console.writeln("========================================");
    Console.writeln("MATCHING SUMMARY");
    Console.writeln("========================================");
    Console.writeln("Total darks:   " + darkFiles.length);
    Console.writeln("Matched:       " + matched);
    Console.writeln("Skipped:       " + skipped + " (no bias match)");
    Console.writeln("Errors:        " + errors);
    Console.writeln("Elapsed:       " + elapsed.toFixed(2) + "s");
    Console.writeln("Plan saved:    " + planPath);
    Console.writeln("========================================");
    
    if (dryRun){
        Console.noteln("\n✓ DRY RUN COMPLETE — No files were written");
        Console.noteln("Review the plan JSON and re-run without Dry Run to process files.");
    } else {
        Console.noteln("\n✓ PROCESSING COMPLETE");
    }
}

// ============================================================================
// UI Dialog
// ============================================================================

function DarkBiasRebuilderDialog(){
    this.__base__ = Dialog;
    this.__base__();
    
    var self = this;
    
    this.windowTitle = "Dark/Bias Rebuilder";
    this.scaledMinWidth = 700;
    
    // Info label
    this.lblInfo = new Label(this);
    this.lblInfo.text = "Reconstruct unbiased master-darks from bias-calibrated master-darks.\n" +
                       "Formula: MasterDark_raw = MasterDark_cal + MasterBias\n" +
                       "Matching: setup, gain, offset, USB, readout, binning, temp (±14 days)";
    this.lblInfo.wordWrapping = true;
    
    // Masters root (single folder for both darks and biases)
    this.lblMasters = new Label(this);
    this.lblMasters.text = "Masters Root:";
    this.lblMasters.minWidth = 150;
    
    this.editMasters = new Edit(this);
    this.editMasters.minWidth = 400;
    this.editMasters.toolTip = "Directory with master-dark and master-bias files (will be auto-separated)";
    
    this.btnMasters = new ToolButton(this);
    this.btnMasters.icon = this.scaledResource(":/browser/select-file.png");
    this.btnMasters.setScaledFixedSize(20, 20);
    this.btnMasters.toolTip = "Select folder";
    this.btnMasters.onClick = function(){
        var d = new GetDirectoryDialog;
        d.caption = "Masters Root";
        if (d.execute()){
            self.editMasters.text = d.directory;
        }
    };
    
    this.sizerMasters = new HorizontalSizer;
    this.sizerMasters.spacing = 6;
    this.sizerMasters.add(this.lblMasters);
    this.sizerMasters.add(this.editMasters, 100);
    this.sizerMasters.add(this.btnMasters);
    
    // Output root
    this.lblOutput = new Label(this);
    this.lblOutput.text = "Output Root:";
    this.lblOutput.minWidth = 150;
    
    this.editOutput = new Edit(this);
    this.editOutput.minWidth = 400;
    this.editOutput.toolTip = "Output directory (will mirror dark directory structure)";
    
    this.btnOutput = new ToolButton(this);
    this.btnOutput.icon = this.scaledResource(":/browser/select-file.png");
    this.btnOutput.setScaledFixedSize(20, 20);
    this.btnOutput.toolTip = "Select folder";
    this.btnOutput.onClick = function(){
        var d = new GetDirectoryDialog;
        d.caption = "Output Root";
        if (d.execute()){
            self.editOutput.text = d.directory;
        }
    };
    
    this.sizerOutput = new HorizontalSizer;
    this.sizerOutput.spacing = 6;
    this.sizerOutput.add(this.lblOutput);
    this.sizerOutput.add(this.editOutput, 100);
    this.sizerOutput.add(this.btnOutput);
    
    // Dry run checkbox
    this.chkDryRun = new CheckBox(this);
    this.chkDryRun.text = "Dry Run (only match and save plan JSON, no file processing)";
    this.chkDryRun.checked = true;
    
    // Buttons
    this.btnRun = new PushButton(this);
    this.btnRun.text = "Run Rebuild";
    this.btnRun.onClick = function(){
        var mastersRoot = self.editMasters.text.trim();
        var outputRoot = self.editOutput.text.trim();
        var dryRun = self.chkDryRun.checked;
        
        if (!mastersRoot || !outputRoot){
            (new MessageBox(
                "Please specify both Masters Root and Output Root directories.",
                "Dark/Bias Rebuilder",
                StdIcon_Warning,
                StdButton_Ok
            )).execute();
            return;
        }
        
        if (!File.directoryExists(mastersRoot)){
            (new MessageBox(
                "Masters directory does not exist:\n" + mastersRoot,
                "Dark/Bias Rebuilder",
                StdIcon_Error,
                StdButton_Ok
            )).execute();
            return;
        }
        
        self.ok();
        
        try {
            DBR_run(mastersRoot, outputRoot, dryRun);
        } catch(e){
            Console.criticalln("========================================");
            Console.criticalln("FATAL ERROR: " + e);
            Console.criticalln("========================================");
            
            (new MessageBox(
                "Processing failed:\n\n" + e + "\n\nCheck Console for details.",
                "Dark/Bias Rebuilder",
                StdIcon_Error,
                StdButton_Ok
            )).execute();
        }
    };
    
    this.btnCancel = new PushButton(this);
    this.btnCancel.text = "Cancel";
    this.btnCancel.onClick = function(){
        self.cancel();
    };
    
    this.sizerButtons = new HorizontalSizer;
    this.sizerButtons.spacing = 6;
    this.sizerButtons.addStretch();
    this.sizerButtons.add(this.btnRun);
    this.sizerButtons.add(this.btnCancel);
    
    // Main layout
    this.sizer = new VerticalSizer;
    this.sizer.margin = 8;
    this.sizer.spacing = 8;
    this.sizer.add(this.lblInfo);
    this.sizer.addSpacing(12);
    this.sizer.add(this.sizerMasters);
    this.sizer.add(this.sizerOutput);
    this.sizer.addSpacing(12);
    this.sizer.add(this.chkDryRun);
    this.sizer.addSpacing(12);
    this.sizer.add(this.sizerButtons);
    
    this.adjustToContents();
}

DarkBiasRebuilderDialog.prototype = new Dialog;

// ============================================================================
// Entry Point
// ============================================================================

function main(){
    Console.show();
    
    var dlg = new DarkBiasRebuilderDialog();
    dlg.execute();
}

main();
