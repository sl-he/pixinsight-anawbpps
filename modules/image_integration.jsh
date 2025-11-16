/*
 * ANAWBPPS - ImageIntegration Module
 * Integrates calibrated and registered images into final stacks
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
// Helpers
// ============================================================================

function II_sanitizeKey(key){
    return String(key||"").replace(/[|:\\/\s]+/g, "_");
}

function II_buildSimpleSSKey(group){
    // Build simplified SS key: object|filter|exposure (NO setup, NO binning)
    var object = group.object || group.target || "UNKNOWN";
    var filter = group.filter || "L";
    var expTime = group.exposureSec || group.exposure || 0;

    return object + "|" + filter + "|" + expTime + "s";
}

function II_buildFullSSKey(group){
    // Build full SS key for TOP-5 folder lookup: setup|object|filter|binBINNING|EXPOSUREs
    var setup = group.setup || group.camera || "UNKNOWN";
    var object = group.object || group.target || "UNKNOWN";
    var filter = group.filter || "L";
    var binning = group.binning || "1x1";
    var expTime = group.exposureSec || group.exposure || 0;

    return setup + "|" + object + "|" + filter + "|" + "bin" + binning + "|" + expTime + "s";
}

// ============================================================================
// Build Output Path for Integrated Results
// ============================================================================

function II_buildOutputPath(ssKey, fileCount, workFolders){
    // Parse ssKey: "object|filter|exposure"
    var parts = ssKey.split("|");
    var object = parts[0] || "UNKNOWN";
    var filter = parts[1] || "L";
    var exposure = parts[2] || "0s";

    // Sanitize object name (remove special characters)
    var sanitizedObject = object.replace(/[\s|:\\/\.]+/g, "_");

    // Build filename: object_filter_COUNTxEXPOSURE.xisf
    var filename = sanitizedObject + "_" + filter + "_" + fileCount + "x" + exposure + ".xisf";

    // Output folder: WORK2/!Integrated/
    var base = workFolders.base2 || workFolders.base1;
    var integratedFolder = base + "/!Integrated";

    // Create folder if not exists
    if (!File.directoryExists(integratedFolder)){
        File.createDirectory(integratedFolder, true);
        Console.writeln("[ii]   Created folder: !Integrated");
    }

    var fullPath = CU_norm(integratedFolder + "/" + filename);

    return fullPath;
}

// ============================================================================
// Save Integration Results as XISF Container
// ============================================================================

function II_saveResultsAsContainer(intView, lowView, highView, outputPath){
    Console.writeln("[ii]   Saving results as 3 separate 32-bit files...");

    // Validate views
    if (!intView || !intView.image){
        throw new Error("Integration view is invalid");
    }
    if (!lowView || !lowView.image){
        throw new Error("Rejection_low view is invalid");
    }
    if (!highView || !highView.image){
        throw new Error("Rejection_high view is invalid");
    }

    // Build paths for 3 separate files
    var basePath = outputPath.replace(/\.xisf$/i, "");
    var baseName = CU_basename(basePath);

    var intPath = basePath + ".xisf";
    var lowPath = basePath + "_rejection_low.xisf";
    var highPath = basePath + "_rejection_high.xisf";

    try{
        var intWindow = intView.window;
        var lowWindow = lowView.window;
        var highWindow = highView.window;

        // Rename views to match output filenames (without .xisf extension)
        Console.writeln("[ii]     Renaming views...");

        var oldIntId = intView.id;
        var oldLowId = lowView.id;
        var oldHighId = highView.id;

        var viewId = CU_sanitizeViewId(baseName, "II");
        intView.id = viewId;
        lowView.id = viewId + "_rejection_low";
        highView.id = viewId + "_rejection_high";

        Console.writeln("[ii]       " + oldIntId + " → " + intView.id);
        Console.writeln("[ii]       " + oldLowId + " → " + lowView.id);
        Console.writeln("[ii]       " + oldHighId + " → " + highView.id);

        // Save integration (main result)
        Console.writeln("[ii]     Writing integration: " + CU_basename(intPath));
        if (!intWindow.saveAs(intPath, false, false, true, false)){
            throw new Error("Failed to save integration");
        }

        // Save rejection_low
        Console.writeln("[ii]     Writing rejection_low: " + CU_basename(lowPath));
        if (!lowWindow.saveAs(lowPath, false, false, true, false)){
            throw new Error("Failed to save rejection_low");
        }

        // Save rejection_high
        Console.writeln("[ii]     Writing rejection_high: " + CU_basename(highPath));
        if (!highWindow.saveAs(highPath, false, false, true, false)){
            throw new Error("Failed to save rejection_high");
        }

        Console.writeln("[ii]   ✓ Saved 3 files (32-bit float)");
        Console.writeln("[ii]     Main:  " + CU_basename(intPath));
        Console.writeln("[ii]     Low:   " + CU_basename(lowPath));
        Console.writeln("[ii]     High:  " + CU_basename(highPath));

    }catch(e){
        throw e;
    }
}

// ============================================================================
// Close Integration Views
// ============================================================================

function II_closeViews(intView, lowView, highView){
    Console.writeln("[ii]   Closing views...");

    var closed = 0;

    try{
        if (intView && intView.window){
            intView.window.forceClose();
            closed++;
        }
    }catch(e){
        Console.warningln("[ii]     Failed to close integration view: " + e);
    }

    try{
        if (lowView && lowView.window){
            lowView.window.forceClose();
            closed++;
        }
    }catch(e){
        Console.warningln("[ii]     Failed to close rejection_low view: " + e);
    }

    try{
        if (highView && highView.window){
            highView.window.forceClose();
            closed++;
        }
    }catch(e){
        Console.warningln("[ii]     Failed to close rejection_high view: " + e);
    }

    Console.writeln("[ii]   ✓ Closed " + closed + " view(s)");
}

// ============================================================================
// Find Reference File from TOP-5
// ============================================================================

function II_findReferenceForGroup(ssKey, icGroup, workFolders){
    Console.writeln("[ii]   Finding reference file for group: " + ssKey);
    
    // Step 1: Build full SS key for TOP-5 folder lookup
    var fullSSKey = II_buildFullSSKey(icGroup);
    var sanitizedKey = II_sanitizeKey(fullSSKey);
    var top5Folder = workFolders.approved + "/!Approved_Best5/" + sanitizedKey;
    
    Console.writeln("[ii]   TOP-5 folder: " + top5Folder);
    
    // Step 2: Check TOP-5 folder exists
    if (!File.directoryExists(top5Folder)){
        throw new Error("TOP-5 folder not found: " + top5Folder + "\n" +
                       "Please ensure SubframeSelector completed successfully");
    }
    
    // Step 3: Read files in TOP-5 folder
    var xisfFiles = [];
    var fileFind = new FileFind;
    if (fileFind.begin(top5Folder + "/*.xisf")){
        do {
            if (!fileFind.isDirectory){
                xisfFiles.push(fileFind.name);
            }
        } while (fileFind.next());
    }
    
    // Step 4: Validate exactly 1 file
    if (xisfFiles.length === 0){
        throw new Error("TOP-5 folder is empty: " + top5Folder + "\n" +
                       "Please ensure SubframeSelector completed successfully");
    }
    
    if (xisfFiles.length > 1){
        var fileList = "\n";
        for (var j=0; j<xisfFiles.length; ++j){
            fileList += "  - " + xisfFiles[j] + "\n";
        }
        throw new Error("Multiple files in TOP-5 folder: " + top5Folder + fileList +
                       "Expected 1 file, found " + xisfFiles.length + "\n" +
                       "Please manually select best reference file");
    }
    
    // Step 5: Build reference path
    var topFile = xisfFiles[0];
    var stem = CU_noext(topFile);  // e.g. "!1_..._c_cc_a"
    var rFile = workFolders.approvedSet + "/" + stem + "_r.xisf";
    
    Console.writeln("[ii]   TOP-5 file: " + topFile);
    Console.writeln("[ii]   Reference XISF: " + CU_basename(rFile));
    
    // Step 6: Validate reference XISF exists
    if (!File.exists(rFile)){
        throw new Error("Reference file not found after StarAlignment: " + rFile + "\n" +
                       "TOP-5 source: " + top5Folder + "/" + topFile + "\n" +
                       "Please ensure StarAlignment completed successfully");
    }
    
    Console.writeln("[ii]   ✓ Reference validated");
    
    return rFile;
}

// ============================================================================
// Collect and Group Files
// ============================================================================

function II_collectAndGroupFiles(PLAN, workFolders){
    Console.writeln("[ii] Collecting and grouping files...");
    
    if (!PLAN || !PLAN.groups){
        throw new Error("PLAN is empty (no groups)");
    }
    
    var iiGroups = {}; // simpleSSKey -> { key, files, referenceFile, icGroups, csvPath }
    
    // Iterate through all IC groups
    for (var icKey in PLAN.groups){
        if (!PLAN.groups.hasOwnProperty(icKey)) continue;
        
        var G = PLAN.groups[icKey];
        
        // Build simplified SS key
        var simpleSSKey = II_buildSimpleSSKey(G);
        
        // Create II group if not exists
        if (!iiGroups[simpleSSKey]){
            iiGroups[simpleSSKey] = {
                key: simpleSSKey,
                files: [],
                referenceFile: null,
                icGroups: [],
                csvPath: null
            };
        }
        
        // Store IC group reference
        iiGroups[simpleSSKey].icGroups.push(G);
        
        // Collect files from this IC group
        var bases = G.lights || [];

        // TODO-32: Check if group is CFA
        var isCFA = !!(G.bayerPattern);

        for (var i=0; i<bases.length; ++i){
            var basePath = bases[i];
            var stem = CU_noext(CU_basename(basePath));

            // TODO-32: CFA files have _d suffix after debayer
            var rFile;
            if (isCFA){
                rFile = workFolders.approvedSet + "/" + stem + "_c_cc_d_a_r.xisf";
            } else {
                rFile = workFolders.approvedSet + "/" + stem + "_c_cc_a_r.xisf";
            }

            if (File.exists(rFile)){
                iiGroups[simpleSSKey].files.push(rFile);
            } else {
                var suffix = isCFA ? "_c_cc_d_a_r.xisf" : "_c_cc_a_r.xisf";
                Console.warningln("[ii]     File not found (SA rejected): " + stem + suffix);
            }
        }
    }
    
    // Find reference file and CSV path for each II group
    for (var ssKey in iiGroups){
        if (!iiGroups.hasOwnProperty(ssKey)) continue;
        
        var iiGroup = iiGroups[ssKey];
        
        if (iiGroup.icGroups.length === 0){
            Console.warningln("[ii] No IC groups for II group: " + ssKey);
            continue;
        }
        
        // Use first IC group to find TOP-5 reference
        var firstICGroup = iiGroup.icGroups[0];
        
        try{
            var refFile = II_findReferenceForGroup(ssKey, firstICGroup, workFolders);
            iiGroup.referenceFile = refFile;
            
            // Add reference to files list if not already present
            var refExists = false;
            for (var i=0; i<iiGroup.files.length; ++i){
                if (CU_norm(iiGroup.files[i]) === CU_norm(refFile)){
                    refExists = true;
                    break;
                }
            }
            
            if (!refExists){
                iiGroup.files.push(refFile);
                Console.writeln("[ii]   Added reference to files list");
            }
            
            // Build CSV path for this group
            var setup = firstICGroup.setup || firstICGroup.camera || "UNKNOWN";
            var object = firstICGroup.object || firstICGroup.target || "UNKNOWN";
            var filter = firstICGroup.filter || "L";
            var binning = firstICGroup.binning || "1x1";
            var expTime = firstICGroup.exposureSec || firstICGroup.exposure || 0;
            
            var csvName = "subframe_weights_" + setup + "_" + object + "_" + filter + "_bin" + binning + "_" + expTime + "s.csv";
            csvName = csvName.replace(/[\s|:\/\\]+/g, "_");  // Sanitize
            iiGroup.csvPath = workFolders.approved + "/" + csvName;
            
            Console.writeln("[ii]   CSV weights: " + CU_basename(iiGroup.csvPath));
            
        }catch(e){
            Console.criticalln("[ii] Failed to prepare group '" + ssKey + "': " + e);
            throw e;
        }
    }
    
    Console.writeln("[ii] Grouped into " + Object.keys(iiGroups).length + " II group(s)");
    
    return iiGroups;
}

// ============================================================================
// Build Images Array for ImageIntegration
// ============================================================================

function II_buildImagesArray(files, referenceFile, useLN){
    Console.writeln("[ii]   Building images array...");
    
    var images = [];
    var skipped = [];
    
    // 1. Add REFERENCE FIRST
    Console.writeln("[ii]   Reference (TOP-5): " + CU_basename(referenceFile));
    
    var refXdrz = referenceFile.replace(/\.xisf$/, ".xdrz");
    var refXnml = useLN ? referenceFile.replace(/\.xisf$/, ".xnml") : "";
    
    // Validate reference
    if (!File.exists(referenceFile)){
        throw new Error("Reference file not found: " + referenceFile);
    }
    if (!File.exists(refXdrz)){
        throw new Error("Reference drizzle data not found: " + refXdrz + "\n" +
                       "Please ensure StarAlignment was configured with 'Generate drizzle data' option enabled");
    }
    if (useLN && !File.exists(refXnml)){
        throw new Error("Reference normalization data not found: " + refXnml + "\n" +
                       "Please run LocalNormalization for all files first");
    }
    
    images.push([true, CU_norm(referenceFile), CU_norm(refXdrz), CU_norm(refXnml)]);
    
    // 2. Add other files AFTER reference
    for (var i=0; i<files.length; ++i){
        var file = files[i];
        
        // Skip reference if it's in the list
        if (CU_norm(file) === CU_norm(referenceFile)){
            continue;
        }
        
        var xdrzPath = file.replace(/\.xisf$/, ".xdrz");
        var xnmlPath = useLN ? file.replace(/\.xisf$/, ".xnml") : "";
        
        // Validate file
        if (!File.exists(file)){
            Console.warningln("[ii]     File not found (skipping): " + CU_basename(file));
            skipped.push(file);
            continue;
        }
        
        if (!File.exists(xdrzPath)){
            throw new Error("Drizzle data not found: " + xdrzPath + "\n" +
                           "Please ensure StarAlignment was configured with 'Generate drizzle data' option enabled");
        }
        
        if (useLN && !File.exists(xnmlPath)){
            throw new Error("Normalization data not found: " + xnmlPath + "\n" +
                           "Please run LocalNormalization for all files first");
        }
        
        images.push([true, CU_norm(file), CU_norm(xdrzPath), CU_norm(xnmlPath)]);
    }
    
    if (skipped.length > 0){
        Console.writeln("[ii]   Skipped " + skipped.length + " file(s) not found");
    }
    
    Console.writeln("[ii]   Total files: " + images.length + " (including reference)");
    
    return images;
}

// ============================================================================
// Configure ImageIntegration Instance
// ============================================================================

function II_configureInstance(P, imagesArray, csvPath, useLN){
    // Set images
    P.images = imagesArray;
    
    // Weights from CSV
    P.weightMode = ImageIntegration.prototype.CSVWeightsFile;
    P.weightKeyword = "SSWEIGHT";
    P.csvWeightsFilePath = CU_norm(csvPath);
    P.weightScale = ImageIntegration.prototype.WeightScale_BWMV;
    P.minWeight = 0.005000;
    P.csvWeights = "";
    P.adaptiveGridSize = 16;
    P.adaptiveNoScale = false;
    P.ignoreNoiseKeywords = false;

// Normalization
    if (useLN){
        // Use LocalNormalization with .xnml data
        P.normalization = ImageIntegration.prototype.LocalNormalization;
        P.rejectionNormalization = ImageIntegration.prototype.LocalRejectionNormalization;
    } else {
        // Use standard additive normalization
        P.normalization = ImageIntegration.prototype.AdditiveWithScaling;
        P.rejectionNormalization = ImageIntegration.prototype.Scale;
    }

// Rejection - ESD
    P.rejection = ImageIntegration.prototype.Rejection_ESD;
    P.minMaxLow = 1;
    P.minMaxHigh = 1;
    P.pcClipLow = 0.200;
    P.pcClipHigh = 0.100;
    P.sigmaLow = 4.000;
    P.sigmaHigh = 3.000;
    P.winsorizationCutoff = 5.000;
    P.linearFitLow = 5.000;
    P.linearFitHigh = 2.500;
    P.esdOutliersFraction = 0.30;
    P.esdAlpha = 0.05;
    P.esdLowRelaxation = 1.50;
    P.rcrLimit = 0.10;
    P.ccdGain = 1.00;
    P.ccdReadNoise = 10.00;
    P.ccdScaleNoise = 0.00;
    P.clipLow = true;
    P.clipHigh = true;
    P.rangeClipLow = false;
    P.rangeLow = 0.000000;
    P.rangeClipHigh = false;
    P.rangeHigh = 0.980000;
    P.mapRangeRejection = true;
    P.reportRangeRejection = false;
    P.largeScaleClipLow = false;
    P.largeScaleClipLowProtectedLayers = 2;
    P.largeScaleClipLowGrowth = 2;
    P.largeScaleClipHigh = false;
    P.largeScaleClipHighProtectedLayers = 2;
    P.largeScaleClipHighGrowth = 2;
    
    // Output options
    P.generate64BitResult = false;
    P.generateRejectionMaps = true;
    P.generateSlopeMaps = false;
    P.generateIntegratedImage = true;
    P.generateDrizzleData = true;
    P.closePreviousImages = false;
    P.bufferSizeMB = 16;
    P.stackSizeMB = 1024;
    P.autoMemorySize = true;
    P.autoMemoryLimit = 0.75;
    P.useROI = false;
    P.roiX0 = 0;
    P.roiY0 = 0;
    P.roiX1 = 0;
    P.roiY1 = 0;
    P.useCache = false;
    
    // SNR evaluation
    P.evaluateSNR = true;
    P.noiseEvaluationAlgorithm = ImageIntegration.prototype.NoiseEvaluation_MRS;
    P.mrsMinDataFraction = 0.010;
    P.psfStructureLayers = 5;
    P.psfType = ImageIntegration.prototype.PSFType_Auto;
    P.generateFITSKeywords = true;
    P.subtractPedestals = true;
    P.truncateOnOutOfRange = false;
    
    // Performance
    P.noGUIMessages = true;
    P.showImages = true;
    P.useFileThreads = true;
    P.fileThreadOverload = 1.00;
    P.useBufferThreads = true;
    P.maxBufferThreads = 8;
    
    // Combination
    P.combination = ImageIntegration.prototype.Average;
    P.inputHints = "";
    P.overrideImageType = false;
    P.imageType = 0;
}

// ============================================================================
// Process One Group
// ============================================================================

function II_processGroup(ssKey, iiGroup, workFolders, useLN, node){
    Console.writeln("[ii] ========================================");
    Console.writeln("[ii] Processing group: " + ssKey);
    Console.writeln("[ii] ========================================");
    
    if (!iiGroup.referenceFile){
        throw new Error("No reference file for group: " + ssKey);
    }
    
    if (!iiGroup.files || iiGroup.files.length === 0){
        throw new Error("No files to integrate for group: " + ssKey);
    }
    
    if (!iiGroup.csvPath){
        throw new Error("No CSV weights path for group: " + ssKey);
    }
    
    Console.writeln("[ii]   Reference: " + CU_basename(iiGroup.referenceFile));
    Console.writeln("[ii]   Files: " + iiGroup.files.length);
    Console.writeln("[ii]   CSV: " + CU_basename(iiGroup.csvPath));
    
    // Update UI - Running
    if (node){
        try{
            node.setText(3, "▶ Running");
            node.setText(4, "0/" + iiGroup.files.length + " integrating");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }
    
    // Build images array
    var imagesArray = II_buildImagesArray(iiGroup.files, iiGroup.referenceFile, useLN);

    // Create ImageIntegration instance
    var P = new ImageIntegration;
    II_configureInstance(P, imagesArray, iiGroup.csvPath, useLN);
    
    Console.writeln("[ii]   Running ImageIntegration...");
    
    // Execute ImageIntegration
    var t0 = Date.now();
    var ok = true;
    try{
        P.executeGlobal();
    }catch(e){
        ok = false;
        Console.criticalln("[ii]   ImageIntegration failed: " + e);
        throw e;
    }
    var elapsed = (Date.now() - t0) / 1000;

    Console.writeln("[ii]   ✓ Completed in " + CU_fmtHMS(elapsed));

    // NEW: Save results as XISF container
    try{
        Console.writeln("[ii]   Saving results to XISF container...");

        // Find views by standard IDs
        var intView = View.viewById("integration");
        var lowView = View.viewById("rejection_low");
        var highView = View.viewById("rejection_high");

        if (!intView){
            throw new Error("Integration view 'integration' not found");
        }
        if (!lowView){
            throw new Error("Rejection view 'rejection_low' not found");
        }
        if (!highView){
            throw new Error("Rejection view 'rejection_high' not found");
        }

        // Build output path
        var outputPath = II_buildOutputPath(ssKey, imagesArray.length, workFolders);

        // Save to XISF container (3 images in one file)
        II_saveResultsAsContainer(intView, lowView, highView, outputPath);

        // Close views to free memory
        II_closeViews(intView, lowView, highView);

        Console.writeln("[ii]   ✓ Results saved and views closed");

    }catch(e){
        Console.criticalln("[ii]   Failed to save results: " + e);
        throw e;
    }

    // Update UI - Success
    if (node){
        try{
            node.setText(2, CU_fmtHMS(elapsed));
            node.setText(3, "✔ Success");
            node.setText(4, imagesArray.length + "/" + imagesArray.length + " integrated");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }
}

// ============================================================================
// Main Entry Point
// ============================================================================

function II_runForAllGroups(params){
    // params: { PLAN, workFolders, useLN, dlg }
    var PLAN = params.PLAN;
    var workFolders = params.workFolders;
    var useLN = params.useLN || false;
    var dlg = params.dlg || null;

    Console.writeln("[ii] ========================================");
    Console.writeln("[ii] ImageIntegration: Integrating all groups");
    Console.writeln("[ii] ========================================");
    Console.writeln("[ii] LocalNormalization: " + (useLN ? "ENABLED" : "DISABLED"));

    // Step 1: Collect and group files
    var iiGroups = II_collectAndGroupFiles(PLAN, workFolders);

    if (!iiGroups || Object.keys(iiGroups).length === 0){
        Console.warningln("[ii] No groups found");
        return {totalProcessed: 0, totalSkipped: 0, groupNames: []};
    }

    // Step 2: Get group keys
    var groupKeys = [];
    for (var k in iiGroups){
        if (iiGroups.hasOwnProperty(k)) groupKeys.push(k);
    }

    Console.writeln("[ii] Found " + groupKeys.length + " group(s)");

    // Step 3: PRE-CREATE all UI rows BEFORE processing
    var groupNodes = {};
    if (dlg){
        for (var i=0; i<groupKeys.length; ++i){
            var ssKey = groupKeys[i];
            var iiGroup = iiGroups[ssKey];
            
            // Parse key for label: "object | filter | exposure"
            var parts = ssKey.split("|");
            var object = parts[0] || "UNKNOWN";
            var filter = parts[1] || "L";
            var exposure = parts[2] || "0s";
            
            var label = object + " | " + filter + " | " + exposure + " (" + iiGroup.files.length + " subs)";
            var node = dlg.addRow("ImageIntegration", label);
            
            try{
                node.setText(3, "⏳ Queued");
                node.setText(4, iiGroup.files.length + "/" + iiGroup.files.length + " queued");
            }catch(_){}
            
            groupNodes[ssKey] = node;
        }
        try{ if (typeof processEvents === "function") processEvents(); }catch(_){}
    }

    Console.writeln("[ii] ========================================");

    // Step 4: Process each group
    for (var i=0; i<groupKeys.length; ++i){
        var ssKey = groupKeys[i];
        var iiGroup = iiGroups[ssKey];
        
        Console.writeln("[ii] Group " + (i+1) + "/" + groupKeys.length + ": " + ssKey);
        
        try{
            var node = groupNodes[ssKey] || null;
            II_processGroup(ssKey, iiGroup, workFolders, useLN, node);
        }catch(e){
            Console.criticalln("[ii] ERROR processing group '" + ssKey + "': " + e);
            
            // Update UI - Error
            var node = groupNodes[ssKey];
            if (node){
                try{
                    node.setText(3, "✖ Error");
                    node.setText(4, "Failed: " + e);
                }catch(_){}
            }
            
            throw e;  // Stop entire script - operator must fix
        }
    }

    Console.writeln("[ii] ========================================");
    Console.writeln("[ii] ImageIntegration complete");
    Console.writeln("[ii] ========================================");

    // Return statistics for notifications
    var totalProcessed = 0;
    var totalSkipped = 0;
    var groupNames = [];

    for (var i=0; i<groupKeys.length; ++i){
        var ssKey = groupKeys[i];
        var iiGroup = iiGroups[ssKey];
        totalProcessed += iiGroup.files.length;
        groupNames.push(ssKey);
    }

    return {
        totalProcessed: totalProcessed,
        totalSkipped: totalSkipped,
        groupNames: groupNames
    };
}