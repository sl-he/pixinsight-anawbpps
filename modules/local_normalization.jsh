/*
 * ANAWBPPS - LocalNormalization with reference from TOP-5
 * Normalizes all files in a group using best reference from TOP-5 folder
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

function LN_sanitizeKey(key){
    return String(key||"").replace(/[|:\\/\s]+/g, "_");
}

function LN_buildSimpleSSKey(group){
    // Build simplified SS key: object|filter|exposure (NO setup, NO binning)
    var object = group.object || group.target || "UNKNOWN";
    var filter = group.filter || "L";
    var expTime = group.exposureSec || group.exposure || 0;

    return object + "|" + filter + "|" + expTime + "s";
}

function LN_buildFullSSKey(group){
    // Build full SS key for TOP-5 folder lookup: setup|object|filter|binBINNING|EXPOSUREs
    var setup = group.setup || group.camera || "UNKNOWN";
    var object = group.object || group.target || "UNKNOWN";
    var filter = group.filter || "L";
    var binning = group.binning || "1x1";
    var expTime = group.exposureSec || group.exposure || 0;

    return setup + "|" + object + "|" + filter + "|" + "bin" + binning + "|" + expTime + "s";
}

// ============================================================================
// Find Reference File from TOP-5
// ============================================================================

function LN_findReferenceForGroup(ssKey, icGroup, workFolders){
    Console.writeln("[ln]   Finding reference file for group: " + ssKey);
    
    // Step 1: Build full SS key for TOP-5 folder lookup
    var fullSSKey = LN_buildFullSSKey(icGroup);
    var sanitizedKey = LN_sanitizeKey(fullSSKey);
    var top5Folder = workFolders.approved + "/!Approved_Best5/" + sanitizedKey;
    
    Console.writeln("[ln]   TOP-5 folder: " + top5Folder);
    
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
    
    // Step 5: Build reference paths
    var topFile = xisfFiles[0];
    var stem = CU_noext(topFile);  // e.g. "!1_..._c_cc_a"
    var rFile = workFolders.approvedSet + "/" + stem + "_r.xisf";
    var xdrzFile = workFolders.approvedSet + "/" + stem + "_r.xdrz";
    
    Console.writeln("[ln]   TOP-5 file: " + topFile);
    Console.writeln("[ln]   Reference XISF: " + CU_basename(rFile));
    
    // Step 6: Validate reference XISF exists
    if (!File.exists(rFile)){
        throw new Error("Reference file not found after StarAlignment: " + rFile + "\n" +
                       "TOP-5 source: " + top5Folder + "/" + topFile + "\n" +
                       "Please ensure StarAlignment completed successfully");
    }
    
    // Step 7: Validate drizzle data exists for reference of this group
    if (!File.exists(xdrzFile)){
        throw new Error("Drizzle data file not found for reference of group '" + ssKey + "': " + xdrzFile + "\n" +
                       "Reference XISF: " + rFile + "\n" +
                       "TOP-5 source: " + top5Folder + "/" + topFile + "\n" +
                       "Please ensure StarAlignment was configured with 'Generate drizzle data' option enabled");
    }
    
    Console.writeln("[ln]   ✓ Reference validated");
    Console.writeln("[ln]   ✓ Drizzle data found for reference");
    
    return rFile;
}

// ============================================================================
// Collect and Group Files
// ============================================================================

function LN_collectAndGroupFiles(PLAN, workFolders){
    Console.writeln("[ln] Collecting and grouping files...");
    
    if (!PLAN || !PLAN.groups){
        throw new Error("PLAN is empty (no groups)");
    }
    
    var lnGroups = {}; // simpleSSKey -> { key, files, referenceFile, icGroups }
    
    // Iterate through all IC groups
    for (var icKey in PLAN.groups){
        if (!PLAN.groups.hasOwnProperty(icKey)) continue;
        
        var G = PLAN.groups[icKey];
        
        // Build simplified SS key
        var simpleSSKey = LN_buildSimpleSSKey(G);
        
        // Create LN group if not exists
        if (!lnGroups[simpleSSKey]){
            lnGroups[simpleSSKey] = {
                key: simpleSSKey,
                files: [],
                referenceFile: null,
                icGroups: []
            };
        }
        
        // Store IC group reference
        lnGroups[simpleSSKey].icGroups.push(G);
        
        // Collect files from this IC group
        var bases = G.lights || [];
        
        for (var i=0; i<bases.length; ++i){
            var basePath = bases[i];
            var stem = CU_noext(CU_basename(basePath));
            var rFile = workFolders.approvedSet + "/" + stem + "_c_cc_a_r.xisf";
            
            if (File.exists(rFile)){
                lnGroups[simpleSSKey].files.push(rFile);
            } else {
                Console.warningln("[ln]     File not found (SA rejected): " + stem + "_c_cc_a_r.xisf");
            }
        }
    }
    
    // Find reference file for each LN group
    for (var ssKey in lnGroups){
        if (!lnGroups.hasOwnProperty(ssKey)) continue;
        
        var lnGroup = lnGroups[ssKey];
        
        if (lnGroup.icGroups.length === 0){
            Console.warningln("[ln] No IC groups for LN group: " + ssKey);
            continue;
        }
        
        // Use first IC group to find TOP-5 reference
        var firstICGroup = lnGroup.icGroups[0];
        
        try{
            var refFile = LN_findReferenceForGroup(ssKey, firstICGroup, workFolders);
            lnGroup.referenceFile = refFile;
            
            // Add reference to files list if not already present
            var refExists = false;
            for (var i=0; i<lnGroup.files.length; ++i){
                if (CU_norm(lnGroup.files[i]) === CU_norm(refFile)){
                    refExists = true;
                    break;
                }
            }
            
            if (!refExists){
                lnGroup.files.push(refFile);
                Console.writeln("[ln]   Added reference to files list");
            }
            
        }catch(e){
            Console.criticalln("[ln] Failed to find reference for group '" + ssKey + "': " + e);
            throw e;
        }
    }
    
    Console.writeln("[ln] Grouped into " + Object.keys(lnGroups).length + " LN group(s)");
    
    return lnGroups;
}

// ============================================================================
// Instance Configuration (from LN.txt)
// ============================================================================

function LN_applyInstanceSettings(P){
    P.scale = 1024;
    P.noScale = false;
    P.globalLocationNormalization = false;
    P.truncate = true;
    P.backgroundSamplingDelta = 32;
    P.rejection = true;
    P.referenceRejection = false;
    P.lowClippingLevel = 0.000045;
    P.highClippingLevel = 0.85;
    P.referenceRejectionThreshold = 3.00;
    P.targetRejectionThreshold = 3.20;
    P.hotPixelFilterRadius = 2;
    P.noiseReductionFilterRadius = 0;
    P.modelScalingFactor = 8.00;
    P.scaleEvaluationMethod = LocalNormalization.prototype.ScaleEvaluationMethod_PSFSignal;
    P.localScaleCorrections = false;
    P.psfStructureLayers = 5;
    P.saturationThreshold = 0.75;
    P.saturationRelative = true;
    P.rejectionLimit = 0.30;
    P.psfNoiseLayers = 1;
    P.psfHotPixelFilterRadius = 1;
    P.psfNoiseReductionFilterRadius = 0;
    P.psfMinStructureSize = 0;
    P.psfMinSNR = 40.00;
    P.psfAllowClusteredSources = true;
    P.psfType = LocalNormalization.prototype.PSFType_Auto;
    P.psfGrowth = 1.00;
    P.psfMaxStars = 24576;
    P.referenceIsView = false;
    P.inputHints = "";
    P.outputHints = "";
    P.generateNormalizedImages = LocalNormalization.prototype.GenerateNormalizedImages_ViewExecutionOnly;
    P.generateNormalizationData = true;
    P.generateInvalidData = false;
    P.generateHistoryProperties = true;
    P.showBackgroundModels = false;
    P.showLocalScaleModels = false;
    P.showRejectionMaps = false;
    P.showStructureMaps = false;
    P.plotNormalizationFunctions = LocalNormalization.prototype.PlotNormalizationFunctions_Palette3D;
    P.noGUIMessages = false;
    P.autoMemoryLimit = 0.85;
    P.outputExtension = ".xisf";
    P.outputPrefix = "";
    P.outputPostfix = "_n";
    P.overwriteExistingFiles = true;
    P.onError = LocalNormalization.prototype.OnError_Continue;
    P.useFileThreads = true;
    P.fileThreadOverload = 1.20;
    P.maxFileReadThreads = 1;
    P.maxFileWriteThreads = 1;
    P.graphSize = 800;
    P.graphTextSize = 12;
    P.graphTitleSize = 18;
    P.graphTransparent = false;
    P.graphOutputDirectory = "";
}

// ============================================================================
// Process One Group
// ============================================================================

function LN_processGroup(ssKey, lnGroup, workFolders, node){
    Console.writeln("[ln] ========================================");
    Console.writeln("[ln] Processing group: " + ssKey);
    Console.writeln("[ln] ========================================");
    
    if (!lnGroup.referenceFile){
        throw new Error("No reference file for group: " + ssKey);
    }
    
    if (!lnGroup.files || lnGroup.files.length === 0){
        throw new Error("No files to normalize for group: " + ssKey);
    }
    
    Console.writeln("[ln]   Reference: " + CU_basename(lnGroup.referenceFile));
    Console.writeln("[ln]   Files: " + lnGroup.files.length);
    
    // Update UI - Running
    if (node){
        try{
            node.setText(3, "▶ Running");
            node.setText(4, "0/" + lnGroup.files.length + " normalizing");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }
    
    // Create LocalNormalization instance
    var P = new LocalNormalization;
    LN_applyInstanceSettings(P);
    
    // Set reference
    P.referencePathOrViewId = CU_norm(lnGroup.referenceFile);
    
    // Set output directory
    P.outputDirectory = CU_norm(workFolders.approvedSet);
    
    // Build targets array
    var targetsArray = [];
    for (var i=0; i<lnGroup.files.length; ++i){
        targetsArray.push([true, CU_norm(lnGroup.files[i])]);
    }
    
    P.targetItems = targetsArray;
    
    Console.writeln("[ln]   Running LocalNormalization...");
    
    // Execute LocalNormalization
    var t0 = Date.now();
    var ok = true;
    try{
        P.executeGlobal();
    }catch(e){
        ok = false;
        Console.criticalln("[ln]   LocalNormalization failed: " + e);
        throw e;
    }
    var elapsed = (Date.now() - t0) / 1000;
    
    Console.writeln("[ln]   ✓ Completed in " + CU_fmtHMS(elapsed));
    
    // Update UI - Success
    if (node){
        try{
            node.setText(2, CU_fmtHMS(elapsed));
            node.setText(3, "✔ Success");
            node.setText(4, lnGroup.files.length + "/" + lnGroup.files.length + " normalized");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }
}

// ============================================================================
// Main Entry Point
// ============================================================================

function LN_runForAllGroups(params){
    // params: { PLAN, workFolders, dlg }
    var PLAN = params.PLAN;
    var workFolders = params.workFolders;
    var dlg = params.dlg || null;

    Console.writeln("[ln] ========================================");
    Console.writeln("[ln] LocalNormalization: Reference from TOP-5");
    Console.writeln("[ln] ========================================");

    // Step 1: Collect and group files
    var lnGroups = LN_collectAndGroupFiles(PLAN, workFolders);

    if (!lnGroups || Object.keys(lnGroups).length === 0){
        Console.warningln("[ln] No groups found");
        return;
    }

    // Step 2: Get group keys
    var groupKeys = [];
    for (var k in lnGroups){
        if (lnGroups.hasOwnProperty(k)) groupKeys.push(k);
    }

    Console.writeln("[ln] Found " + groupKeys.length + " group(s)");

    // Step 3: PRE-CREATE all UI rows BEFORE processing
    var groupNodes = {};
    if (dlg){
        for (var i=0; i<groupKeys.length; ++i){
            var ssKey = groupKeys[i];
            var lnGroup = lnGroups[ssKey];
            
            // Parse key for label: "object | filter | exposure"
            var parts = ssKey.split("|");
            var object = parts[0] || "UNKNOWN";
            var filter = parts[1] || "L";
            var exposure = parts[2] || "0s";
            
            var label = object + " | " + filter + " | " + exposure + " (" + lnGroup.files.length + " subs)";
            var node = dlg.addRow("LocalNormalization", label);
            
            try{
                node.setText(3, "⏳ Queued");
                node.setText(4, lnGroup.files.length + "/" + lnGroup.files.length + " queued");
            }catch(_){}
            
            groupNodes[ssKey] = node;
        }
        try{ if (typeof processEvents === "function") processEvents(); }catch(_){}
    }

    Console.writeln("[ln] ========================================");

    // Step 4: Process each group
    for (var i=0; i<groupKeys.length; ++i){
        var ssKey = groupKeys[i];
        var lnGroup = lnGroups[ssKey];
        
        Console.writeln("[ln] Group " + (i+1) + "/" + groupKeys.length + ": " + ssKey);
        
        try{
            var node = groupNodes[ssKey] || null;
            LN_processGroup(ssKey, lnGroup, workFolders, node);
        }catch(e){
            Console.criticalln("[ln] ERROR processing group '" + ssKey + "': " + e);
            
            // Update UI - Error
            if (node){
                try{
                    node.setText(3, "✖ Error");
                    node.setText(4, "Failed: " + e);
                }catch(_){}
            }
            
            throw e;  // Stop entire script - operator must fix
        }
    }

    Console.writeln("[ln] ========================================");
    Console.writeln("[ln] LocalNormalization complete");
    Console.writeln("[ln] ========================================");
}