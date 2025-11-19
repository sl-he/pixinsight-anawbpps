/*
 * ANAWBPPS - StarAlignment with reference selection from TOP-5
 * Aligns all groups of a target using best reference from G or OIII channel
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

function SA_sanitizeKey(key){
    return String(key||"").replace(/[|:\\/\s]+/g, "_");
}

function SA_buildSSKey(group){
    // Build same key format as SubframeSelector uses for TOP-5 folders
    // Format: setup|object|filter|binBINNING|EXPOSUREs
    var setup = group.setup || group.camera || "UNKNOWN";
    var object = group.object || group.target || "UNKNOWN";
    var filter = group.filter || "L";
    var binning = group.binning || "1x1";
    var expTime = group.exposureSec || group.exposure || 0;

    return setup + "|" + object + "|" + filter + "|" + "bin" + binning + "|" + expTime + "s";
}

// ============================================================================
// Grouping by Target
// ============================================================================

function SA_groupByTarget(PLAN){
    if (!PLAN || !PLAN.groups) return {};
    
    var targets = {};
    
    for (var gkey in PLAN.groups){
        if (!PLAN.groups.hasOwnProperty(gkey)) continue;
        
        var G = PLAN.groups[gkey];
        var target = G.object || G.target || "UNKNOWN";
        
        if (!targets[target]) targets[target] = [];
        targets[target].push({
            key: gkey,
            group: G,
            filter: G.filter || "L",
            exposureSec: G.exposureSec || G.exposure || 0
        });
    }
    
    Console.writeln("[sa] Grouped into " + Object.keys(targets).length + " target(s)");
    return targets;
}

// ============================================================================
// Reference Group Selection (G → OIII → CFA → ERROR)
// ============================================================================

function SA_findReferenceGroup(targetGroups){
    if (!targetGroups || !targetGroups.length){
        throw new Error("No groups for target");
    }

    Console.writeln("[sa]   Finding reference group...");

    // Step 1: Try to find G channel groups
    var gGroups = [];
    for (var i=0; i<targetGroups.length; ++i){
        var tg = targetGroups[i];
        var filt = String(tg.filter||"").toUpperCase();
        if (filt === "G"){
            gGroups.push(tg);
        }
    }

    if (gGroups.length > 0){
        Console.writeln("[sa]   Found " + gGroups.length + " group(s) with G channel");

        // Find group with maximum exposure
        var maxExp = gGroups[0];
        for (var j=1; j<gGroups.length; ++j){
            if (gGroups[j].exposureSec > maxExp.exposureSec){
                maxExp = gGroups[j];
            }
        }

        Console.writeln("[sa]   Selected G channel, exposure: " + maxExp.exposureSec + "s");
        return maxExp;
    }

    // Step 2: Try to find OIII channel groups
    var oiiiGroups = [];
    for (var k=0; k<targetGroups.length; ++k){
        var tg2 = targetGroups[k];
        var filt2 = String(tg2.filter||"").toUpperCase();
        if (filt2 === "OIII"){
            oiiiGroups.push(tg2);
        }
    }

    if (oiiiGroups.length > 0){
        Console.writeln("[sa]   Found " + oiiiGroups.length + " group(s) with OIII channel");

        // Find group with maximum exposure
        var maxExp2 = oiiiGroups[0];
        for (var m=1; m<oiiiGroups.length; ++m){
            if (oiiiGroups[m].exposureSec > maxExp2.exposureSec){
                maxExp2 = oiiiGroups[m];
            }
        }

        Console.writeln("[sa]   Selected OIII channel, exposure: " + maxExp2.exposureSec + "s");
        return maxExp2;
    }

    // Step 3: Try to find CFA groups (TODO-32)
    var cfaGroups = [];
    for (var c=0; c<targetGroups.length; ++c){
        var tgCFA = targetGroups[c];
        var filtCFA = String(tgCFA.filter||"").toUpperCase();
        // CFA groups have bayerPattern or filter="CFA"
        if (tgCFA.bayerPattern || filtCFA === "CFA"){
            cfaGroups.push(tgCFA);
        }
    }

    if (cfaGroups.length > 0){
        Console.writeln("[sa]   Found " + cfaGroups.length + " CFA group(s)");

        // Find group with maximum exposure
        var maxExpCFA = cfaGroups[0];
        for (var cj=1; cj<cfaGroups.length; ++cj){
            if (cfaGroups[cj].exposureSec > maxExpCFA.exposureSec){
                maxExpCFA = cfaGroups[cj];
            }
        }

        Console.writeln("[sa]   Selected CFA group, exposure: " + maxExpCFA.exposureSec + "s");
        return maxExpCFA;
    }

    // Step 4: No G, OIII or CFA found
    throw new Error("No G, OIII or CFA channel found for reference selection");
}

// ============================================================================
// Reference File Selection from TOP-5
// ============================================================================

function SA_findReferenceFile(refGroup, workFolders){
    Console.writeln("[sa]   Finding reference file in TOP-5...");

    if (!workFolders || !workFolders.approved){
        throw new Error("Approved folder not configured");
    }

    // Build SS key (same format as SubframeSelector uses)
    var ssKey = SA_buildSSKey(refGroup.group);
    var sanitizedKey = SA_sanitizeKey(ssKey);
    Console.writeln("[sa]   SS key: " + ssKey);
    Console.writeln("[sa]   Sanitized key: " + sanitizedKey);
    
    // Build TOP-5 path
    var top5Path = CU_norm(workFolders.approved + "/!Approved_Best5/" + sanitizedKey);
    Console.writeln("[sa]   TOP-5 path: " + top5Path);
    
    // Check if directory exists
    if (!File.directoryExists(top5Path)){
        throw new Error("TOP-5 folder not found: " + top5Path + "\n" +
                       "Please ensure SubframeSelector completed successfully");
    }

// Read directory contents (only .xisf files)
    var xisfFiles = [];
    var fileFind = new FileFind;

    if (fileFind.begin(top5Path + "/*.xisf")){
        do {
            if (!fileFind.isDirectory){
                xisfFiles.push(fileFind.name);
            }
        } while (fileFind.next());
    }
    
    Console.writeln("[sa]   Found " + xisfFiles.length + " XISF file(s) in TOP-5");
    
    // Check count
    if (xisfFiles.length === 0){
        throw new Error("TOP-5 folder is empty: " + top5Path + "\n" +
                       "Please ensure SubframeSelector completed successfully");
    }
    
    if (xisfFiles.length > 1){
        var fileList = "\n";
        for (var j=0; j<xisfFiles.length; ++j){
            fileList += "  - " + xisfFiles[j] + "\n";
        }
        throw new Error("Multiple files in TOP-5 folder: " + top5Path + fileList +
                       "Expected 1 file, found " + xisfFiles.length + "\n" +
                       "Please manually select best reference file");
    }
    
    // Exactly 1 file - use it
    var referenceFile = CU_norm(top5Path + "/" + xisfFiles[0]);
    Console.writeln("[sa]   Reference file: " + xisfFiles[0]);
    
    return referenceFile;
}

// ============================================================================
// Collect Target Files from !Approved
// ============================================================================
function SA_collectTargetFiles(targetGroups, workFolders, refGroup){
    Console.writeln("[sa]   Collecting approved files...");
    if (!workFolders || !workFolders.approved){
        throw new Error("Approved folder not configured");
    }
    var approvedDir = workFolders.approved;
    var targetFiles = [];

    // ================================================================
    // Track processed TOP-5 folders to avoid duplicates
    // (multiple IC groups can point to same SS group)
    // ================================================================
    var processedTop5Folders = {}; // sanitizedKey -> true

    // Collect TOP-5 files from ALL groups (but avoid duplicates)
    Console.writeln("[sa]   Collecting TOP-5 files from all groups...");
    for (var i=0; i<targetGroups.length; ++i){
        var tg = targetGroups[i];
        var ssKey = SA_buildSSKey(tg.group);
        var sanitizedKey = SA_sanitizeKey(ssKey);

        // Skip if already processed this SS group
        if (processedTop5Folders[sanitizedKey]){
            Console.writeln("[sa]     Skipping duplicate TOP-5 folder: " + tg.filter + " (already processed)");
            continue;
        }

        var top5Path = CU_norm(approvedDir + "/!Approved_Best5/" + sanitizedKey);

        if (File.directoryExists(top5Path)){
            var fileFind2 = new FileFind;
            if (fileFind2.begin(top5Path + "/*.xisf")){
                do {
                    if (!fileFind2.isDirectory){
                        var fullPath = CU_norm(top5Path + "/" + fileFind2.name);
                        targetFiles.push(fullPath);
                        Console.writeln("[sa]     Added TOP-5 [" + tg.filter + "]: " + fileFind2.name);
                    }
                } while (fileFind2.next());
            }

            // Mark this SS group as processed
            processedTop5Folders[sanitizedKey] = true;
        } else {
            Console.writeln("[sa]     No TOP-5 folder for group: " + tg.filter + " (path: " + sanitizedKey + ")");
        }
    }

    // Continue with regular groups...
    for (var i=0; i<targetGroups.length; ++i){
        var tg = targetGroups[i];
        var G = tg.group;

        // Get base file names from group
        var bases = G.lights || [];

        Console.writeln("[sa]     Group " + (i+1) + "/" + targetGroups.length +
            ": " + tg.filter + " (" + bases.length + " files)");

        // TODO-32: Check if group is CFA (check G.bayerPattern, not tg.bayerPattern!)
        var isCFA = !!(G.bayerPattern);

        // Build approved file paths
        for (var j=0; j<bases.length; ++j){
            var basePath = bases[j];
            var stem = CU_noext(CU_basename(basePath));

            // TODO-32: CFA files have _d suffix after debayer
            var approvedPath;
            if (isCFA){
                approvedPath = CU_norm(approvedDir + "/" + stem + "_c_cc_d_a.xisf");
            } else {
                approvedPath = CU_norm(approvedDir + "/" + stem + "_c_cc_a.xisf");
            }

            if (File.exists(approvedPath)){
                targetFiles.push(approvedPath);
            } else {
                var suffix = isCFA ? "_c_cc_d_a.xisf" : "_c_cc_a.xisf";
                Console.warningln("[sa]       File not found (skipped): " + stem + suffix);
            }
        }
    }

    Console.writeln("[sa]   Collected " + targetFiles.length + " approved files");

    if (targetFiles.length === 0){
        throw new Error("No approved files found for target\n" +
            "Please ensure SubframeSelector completed successfully");
    }
    return targetFiles;
}
// ============================================================================
// Instance Configuration (from SA.txt)
// ============================================================================

function SA_applyInstanceSettings(P){
    // Structure detection
    P.structureLayers = 5;
    P.noiseLayers = 0;
    P.hotPixelFilterRadius = 1;
    P.noiseReductionFilterRadius = 0;
    P.minStructureSize = 0;
    P.sensitivity = 0.50;
    P.peakResponse = 0.50;
    P.brightThreshold = 3.00;
    P.maxStarDistortion = 0.60;
    P.allowClusteredSources = false;
    P.localMaximaDetectionLimit = 0.75;
    P.upperLimit = 1.000;
    P.invert = false;
    
    // Distortion model
    P.distortionModel = "";
    P.undistortedReference = false;
    P.rigidTransformations = false;
    P.distortionCorrection = true;
    P.distortionMaxIterations = 20;
    P.distortionMatcherExpansion = 1.00;
    P.rbfType = StarAlignment.prototype.DDMThinPlateSpline;
    P.maxSplinePoints = 4000;
    P.splineOrder = 2;
    P.splineSmoothness = 0.250;
    P.splineOutlierDetectionRadius = 160;
    P.splineOutlierDetectionMinThreshold = 4.0;
    P.splineOutlierDetectionSigma = 5.0;
    
    // Matching
    P.matcherTolerance = 0.0500;
    P.ransacTolerance = 1.9000;
    P.ransacMaxIterations = 2000;
    P.ransacMaximizeInliers = 1.00;
    P.ransacMaximizeOverlapping = 1.00;
    P.ransacMaximizeRegularity = 1.00;
    P.ransacMinimizeError = 1.00;
    P.maxStars = 0;
    P.fitPSF = StarAlignment.prototype.FitPSF_DistortionOnly;
    P.psfTolerance = 0.50;
    
    // Triangle matching (disabled)
    P.useTriangles = false;
    P.polygonSides = 5;
    P.descriptorsPerStar = 20;
    
    // Mosaic options
    P.restrictToPreviews = true;
    P.intersection = StarAlignment.prototype.MosaicOnly;
    P.useBrightnessRelations = false;
    P.useScaleDifferences = false;
    P.scaleTolerance = 0.100;
    
    // Output settings
    P.inputHints = "";
    P.outputHints = "";
    P.mode = StarAlignment.prototype.RegisterMatch;
    P.writeKeywords = true;
    P.generateMasks = false;
    P.generateDrizzleData = true;
    P.generateDistortionMaps = false;
    P.generateHistoryProperties = true;
    P.inheritAstrometricSolution = false;
    P.frameAdaptation = false;
    P.randomizeMosaic = false;
    
    // Pixel interpolation
    P.pixelInterpolation = StarAlignment.prototype.CubicBSplineFilter;
    P.clampingThreshold = 0.30;
    
    // File output
    P.outputExtension = ".xisf";
    P.outputPrefix = "";
    P.outputPostfix = "_r";
    P.maskPostfix = "_m";
    P.distortionMapPostfix = "_dm";
    P.outputSampleFormat = StarAlignment.prototype.SameAsTarget;
    P.overwriteExistingFiles = true;
    P.onError = StarAlignment.prototype.Continue;
    
    // Threading
    P.useFileThreads = true;
    P.fileThreadOverload = 1.20;
    P.maxFileReadThreads = 1;
    P.maxFileWriteThreads = 1;
    P.memoryLoadControl = true;
    P.memoryLoadLimit = 0.85;
}

// ============================================================================
// Process One Target
// ============================================================================

function SA_processTarget(target, targetGroups, workFolders, node, targetIndex, totalTargets){
    Console.writeln("[sa] ========================================");
    Console.writeln("[sa] Processing target: " + target);
    Console.writeln("[sa] ========================================");
    
    // Step 1: Find reference group
    var refGroup = SA_findReferenceGroup(targetGroups);
    Console.writeln("[sa]   Reference group: " + refGroup.key);
    
    // Step 2: Find reference file in TOP-5
    var referenceFile = SA_findReferenceFile(refGroup, workFolders);
    
    // Step 3: Collect all target files from !Approved
    var targetFiles = SA_collectTargetFiles(targetGroups, workFolders, refGroup);

    // Step 4: Update pre-created UI row with actual file count
    if (node){
        try{
            // Update label with exact count
            node.setText(1, target + " (" + targetFiles.length + " subs)");
            node.setText(4, targetFiles.length + "/" + targetFiles.length + " queued");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }
    
    // Step 5: Update UI - Running
    if (node){
        try{
            node.setText(3, "▶ Running");
            node.setText(4, "0/" + targetFiles.length + " aligning");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }
    
    // Step 6: Create and configure StarAlignment instance
    var P = new StarAlignment;
    SA_applyInstanceSettings(P);
    
    // Set reference
    P.referenceImage = referenceFile;
    P.referenceIsFile = true;
    
    // Set output directory
    P.outputDirectory = CU_norm(workFolders.approvedSet);
    
    // Create output directory if needed
    try{
        if (!File.directoryExists(P.outputDirectory)){
            File.createDirectory(P.outputDirectory, true);
            Console.writeln("[sa]   Created output directory: " + P.outputDirectory);
        }
    }catch(e){
        throw new Error("Failed to create output directory: " + e);
    }
    
    // Set targets
    var targetsArray = [];
    for (var i=0; i<targetFiles.length; ++i){
        targetsArray.push([true, true, targetFiles[i]]);
    }
    P.targets = targetsArray;
    Console.writeln("[sa]   Reference: " + CU_basename(referenceFile));
    Console.writeln("[sa]   Targets: " + P.targets.length + " files");
    Console.writeln("[sa]   Output: " + P.outputDirectory);
    Console.writeln("[sa]   Running StarAlignment...");
    
    // Step 7: Execute StarAlignment
    var t0 = Date.now();
    P.executeGlobal();  // Ignore return value - SA handles errors internally
    var elapsed = (Date.now() - t0) / 1000;
    
    Console.writeln("[sa]   ✔ Completed in " + CU_fmtHMS(elapsed));
    
    // Step 8: Update UI - Success
    if (node){
        try{
            node.setText(2, CU_fmtHMS(elapsed));
            node.setText(3, "✔ Success");
            node.setText(4, targetFiles.length + "/" + targetFiles.length + " aligned");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }
}

// ============================================================================
// Main Entry Point
// ============================================================================

function SA_runForAllTargets(params){
    // params: { PLAN, workFolders, dlg }
    var PLAN = params.PLAN;
    var workFolders = params.workFolders;
    var dlg = params.dlg || null;

    Console.writeln("[sa] ========================================");
    Console.writeln("[sa] StarAlignment: Reference from TOP-5");
    Console.writeln("[sa] ========================================");

    // Step 1: Group by target
    var targets = SA_groupByTarget(PLAN);

    if (!targets || Object.keys(targets).length === 0){
        Console.warningln("[sa] No targets found");
        return {totalProcessed: 0, totalSkipped: 0, groupNames: []};
    }

    // Step 2: Get target keys
    var targetKeys = [];
    for (var t in targets){
        if (targets.hasOwnProperty(t)) targetKeys.push(t);
    }

    Console.writeln("[sa] Found " + targetKeys.length + " target(s)");

// Step 3: PRE-CREATE all UI rows BEFORE processing
    var targetNodes = {};
    if (dlg){
        for (var i=0; i<targetKeys.length; ++i){
            var target = targetKeys[i];
            var targetGroups = targets[target];
// Count files for this target (rough estimate from lights)
            var totalFiles = 0;
            for (var j=0; j<targetGroups.length; ++j){
                var bases = targetGroups[j].group.lights || [];
                totalFiles += bases.length;
            }

            var label = target + " (" + totalFiles + " subs)";
            var node = dlg.addRow("StarAlignment", label);
            try{
                node.setText(3, "⏳ Queued");
                node.setText(4, totalFiles + "/" + totalFiles + " queued");
            }catch(_){}
            targetNodes[target] = node;
        }
        try{ if (typeof processEvents === "function") processEvents(); }catch(_){}
    }

    Console.writeln("[sa] ========================================");

    // Step 4: Process each target (pass pre-created node)
    for (var i=0; i<targetKeys.length; ++i){
        var target = targetKeys[i];
        var targetGroups = targets[target];

        Console.writeln("[sa] Target " + (i+1) + "/" + targetKeys.length + ": " + target);

        try{
            var node = targetNodes[target] || null;
            SA_processTarget(target, targetGroups, workFolders, node, i+1, targetKeys.length);
        }catch(e){
            Console.criticalln("[sa] ERROR processing target '" + target + "': " + e);
            throw e;  // Stop entire script - operator must fix
        }
    }

    Console.writeln("[sa] ========================================");
    Console.writeln("[sa] StarAlignment complete");
    Console.writeln("[sa] ========================================");

    // Return statistics for notifications
    var totalProcessed = 0;
    var totalSkipped = 0;
    var groupNames = [];

    for (var i=0; i<targetKeys.length; ++i){
        var target = targetKeys[i];
        var targetGroups = targets[target];
        groupNames.push(target);
        for (var j=0; j<targetGroups.length; ++j){
            var bases = targetGroups[j].group.lights || [];
            totalProcessed += bases.length;
        }
    }

    return {
        totalProcessed: totalProcessed,
        totalSkipped: totalSkipped,
        groupNames: groupNames
    };
}