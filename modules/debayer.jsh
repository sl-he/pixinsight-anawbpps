
/*
 * ANAWBPPS - Debayer Module
 * Applies debayering to CFA/OSC images
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

// Configure Debayer instance with user params + group data
function DEBAYER_applyUserParams(P, group){
    Console.writeln("[debayer] Configuring Debayer instance");

    // Pattern mapping: FITS string → PixInsight enum
    var patternMap = {
        "RGGB": Debayer.prototype.RGGB,
        "BGGR": Debayer.prototype.BGGR,
        "GBRG": Debayer.prototype.GBRG,
        "GRBG": Debayer.prototype.GRBG,
        "GBGR": Debayer.prototype.GBGR,
        "RGBG": Debayer.prototype.RGBG,
        "BGRG": Debayer.prototype.BGRG
    };

    // Validate bayerPattern
    if (!group.bayerPattern){
        Console.criticalln("[debayer] ERROR: Group has no bayerPattern field");
        return false;
    }

    var pattern = group.bayerPattern.toUpperCase();
    if (!patternMap[pattern]){
        Console.criticalln("[debayer] ERROR: Unknown Bayer pattern: " + pattern);
        Console.criticalln("[debayer]        Supported patterns: RGGB, BGGR, GBRG, GRBG, GBGR, RGBG, BGRG");
        return false;
    }

    // Set CFA pattern from group
    P.cfaPattern = patternMap[pattern];
    Console.writeln("[debayer] CFA Pattern: " + pattern + " (" + P.cfaPattern + ")");

    // Debayer method
    P.debayerMethod = Debayer.prototype.VNG;  // Good balance of quality/speed

    // Noise reduction
    P.fbddNoiseReduction = 0;

    // Image display
    P.showImages = true;

    // CFA source (not used in batch mode)
    P.cfaSourceFilePath = "";

    // Target items - will be set per group
    P.targetItems = [];

    // GUI messages
    P.noGUIMessages = true;

    // Noise evaluation
    P.evaluateNoise = true;
    P.noiseEvaluationAlgorithm = Debayer.prototype.NoiseEvaluation_MRS;
    P.evaluateSignal = true;
    P.structureLayers = 5;
    P.saturationThreshold = 1.00;
    P.saturationRelative = false;
    P.noiseLayers = 1;
    P.hotPixelFilterRadius = 1;
    P.noiseReductionFilterRadius = 0;
    P.minStructureSize = 0;

    // PSF parameters
    P.psfType = Debayer.prototype.PSFType_Moffat4;
    P.psfGrowth = 1.00;
    P.maxStars = 24576;

    // Input/Output hints
    P.inputHints = "raw cfa";  // Important for CFA
    P.outputHints = "";

    // Output settings
    P.outputRGBImages = true;
    P.outputSeparateChannels = false;
    P.outputDirectory = "";  // Will be set per group
    P.outputExtension = ".xisf";
    P.outputPrefix = "";
    P.outputPostfix = "_d";

    // FITS keywords
    P.generateHistoryProperties = true;
    P.generateFITSKeywords = true;

    // File handling
    P.overwriteExistingFiles = true;
    P.onError = Debayer.prototype.OnError_Continue;

    // Threading
    P.useFileThreads = true;
    P.fileThreadOverload = 1.00;
    P.maxFileReadThreads = 0;
    P.maxFileWriteThreads = 0;

    // Memory management
    P.memoryLoadControl = true;
    P.memoryLoadLimit = 0.85;

    Console.writeln("[debayer] Configuration complete");
    return true;
}

// Process Debayer for one calibration group
function DEBAYER_runForGroup(groupKey, group, workFolders, dlg){
    Console.noteln("[debayer] Processing group: " + groupKey);

    // Check if group is CFA
    if (!group.bayerPattern){
        Console.warningln("[debayer]   Group is not CFA (no bayerPattern), skipping");
        return { ok: true, processed: 0, skipped: 0 };
    }

    // Get input files (cosmetic corrected)
    var inputDir = workFolders.cosmetic;
    var outputDir = workFolders.debayered;

    // Build list of cosmetic corrected files for this group
    var lights = [];
    if (group.lights && group.lights.length){
        lights = group.lights.slice(0);
    } else if (group.items && group.items.length){
        for (var i=0; i<group.items.length; i++){
            var it = group.items[i];
            if (typeof it === "string") lights.push(it);
            else if (it && it.path) lights.push(it.path);
        }
    } else if (group.frames && group.frames.length){
        lights = group.frames.slice(0);
    }

    if (!lights.length){
        Console.warningln("[debayer]   No lights in group, skipping");
        return { ok: true, processed: 0, skipped: 0 };
    }

    // Map to cosmetic corrected files
    var targetItems = [];
    var missing = 0;

    for (var i=0; i<lights.length; i++){
        var lightPath = CU_norm(lights[i]);
        var stem = CU_noext(CU_basename(lightPath));

        // Look for cosmetic corrected file: *_c_cc.xisf or *_cc.xisf
        var ccFile1 = inputDir + "/" + stem + "_c_cc.xisf";
        var ccFile2 = inputDir + "/" + stem + "_cc.xisf";

        var ccFile = null;
        if (File.exists(ccFile1)){
            ccFile = ccFile1;
        } else if (File.exists(ccFile2)){
            ccFile = ccFile2;
        }

        if (!ccFile){
            Console.warningln("[debayer]   Missing cosmetic file: " + ccFile1 + " or " + ccFile2);
            missing++;
            continue;
        }

        targetItems.push([true, ccFile]);
    }

    if (!targetItems.length){
        Console.warningln("[debayer]   No cosmetic corrected files found, skipping");
        return { ok: false, processed: 0, skipped: lights.length };
    }

    if (missing > 0){
        Console.warningln("[debayer]   WARNING: " + missing + " cosmetic files not found");
    }

    Console.writeln("[debayer]   Found " + targetItems.length + " cosmetic files to debayer");

    // Create Debayer instance
    var P = new Debayer;

    // Apply user params + inject group data
    if (!DEBAYER_applyUserParams(P, group)){
        Console.criticalln("[debayer]   Failed to configure Debayer instance");
        return { ok: false, processed: 0, skipped: targetItems.length };
    }

    // Set target items
    P.targetItems = targetItems;

    // Set output directory
    P.outputDirectory = outputDir;

    Console.writeln("[debayer]   Output directory: " + outputDir);
    Console.writeln("[debayer]   Bayer pattern: " + group.bayerPattern);
    Console.writeln("[debayer]   Starting debayer process...");

    // Execute
    var startT = Date.now();
    var ok = P.executeGlobal();
    var elapsed = Date.now() - startT;

    if (!ok){
        Console.criticalln("[debayer]   ERROR: Debayer process failed");
        return { ok: false, processed: 0, skipped: targetItems.length };
    }

    Console.noteln("[debayer]   SUCCESS: Debayered " + targetItems.length + " files in " + CU_fmtElapsedMS(elapsed));

    return { ok: true, processed: targetItems.length, skipped: missing };
}

// Process all groups with UI progress
function DEBAYER_runForAllGroups(params){
    Console.noteln("=== Debayer: Starting ===");

    var calibPlan = params.calibPlan;
    var workFolders = params.workFolders;
    var dlg = params.dlg || null;

    if (!calibPlan || !calibPlan.groups){
        Console.criticalln("[debayer] ERROR: No calibration plan provided");
        return { ok: false, processedGroups: 0 };
    }

    var groupKeys = [];
    for (var k in calibPlan.groups){
        groupKeys.push(k);
    }

    if (!groupKeys.length){
        Console.warningln("[debayer] No groups in calibration plan");
        return { ok: true, processedGroups: 0 };
    }

    Console.writeln("[debayer] Found " + groupKeys.length + " groups");

    // Pre-add progress rows if dialog available (only for CFA groups)
    if (dlg){
        if (!dlg.debayerRowsMap) dlg.debayerRowsMap = {};

        for (var i=0; i<groupKeys.length; i++){
            var gkey = groupKeys[i];
            var g = calibPlan.groups[gkey];

            // Only create rows for CFA groups
            if (!g.bayerPattern) continue;

            var count = 0;
            if (g.lights) count = g.lights.length;
            else if (g.items) count = g.items.length;
            else if (g.frames) count = g.frames.length;

            // Format label
            var label = gkey;
            try {
                if (typeof CP__fmtGroupForUI === "function"){
                    label = CP__fmtGroupForUI(gkey);
                }
            } catch(_){}
            label += " (" + count + " subs)";

            var node = dlg.addRow("Debayer", label);
            try {
                node.setText(3, "⏳ Queued");
                node.setText(4, count + "/" + count + " queued");
            } catch(_){}

            dlg.debayerRowsMap[gkey] = { node: node, count: count };
        }

        try { processEvents(); } catch(_){}
    }

    var processedGroups = 0;
    var skippedGroups = 0;
    var totalProcessed = 0;
    var totalSkipped = 0;

    for (var i=0; i<groupKeys.length; i++){
        var groupKey = groupKeys[i];
        var group = calibPlan.groups[groupKey];

        // Update progress UI
        var node = null;
        var count = 0;
        if (dlg && dlg.debayerRowsMap && dlg.debayerRowsMap[groupKey]){
            node = dlg.debayerRowsMap[groupKey].node;
            count = dlg.debayerRowsMap[groupKey].count || 0;
        }

        if (node){
            try {
                node.setText(3, "▶ Running");
                node.setText(4, "0/" + count + " processing");
                node.setText(2, "00:00:00.00");
            } catch(_){}
            try { processEvents(); } catch(_){}
        }

        // Process group
        var t0 = Date.now();
        var result = DEBAYER_runForGroup(groupKey, group, workFolders, dlg);
        var elapsed = Date.now() - t0;

        if (result.ok){
            if (result.processed > 0){
                processedGroups++;
                totalProcessed += result.processed;
            } else {
                skippedGroups++;
            }
            totalSkipped += result.skipped;
        } else {
            Console.criticalln("[debayer] Failed to process group: " + groupKey);
        }

        // Update progress UI
        if (node){
            try {
                node.setText(2, CU_fmtElapsedMS(elapsed));
                node.setText(3, result.ok ? (result.processed > 0 ? "✔ Success" : "⊘ Skipped") : "✖ Error");
                node.setText(4, result.processed + "/" + (result.processed + result.skipped) + " processed");
            } catch(_){}
            try { processEvents(); } catch(_){}
        }
    }

    Console.noteln("=== Debayer: Complete ===");
    Console.writeln("[debayer] Processed groups: " + processedGroups + "/" + groupKeys.length);
    Console.writeln("[debayer] Skipped groups: " + skippedGroups);
    Console.writeln("[debayer] Total files processed: " + totalProcessed);
    Console.writeln("[debayer] Total files skipped: " + totalSkipped);

    // Collect processed group names (only CFA groups that were actually processed)
    var processedGroupNames = [];
    for (var i=0; i<groupKeys.length; i++){
        var g = calibPlan.groups[groupKeys[i]];
        if (g.bayerPattern){
            processedGroupNames.push(groupKeys[i]);
        }
    }

    return {
        ok: true,
        processedGroups: processedGroups,
        totalProcessed: totalProcessed,
        totalSkipped: totalSkipped,
        groupNames: processedGroupNames
    };
}
