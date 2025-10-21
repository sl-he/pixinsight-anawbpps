/*
 * ANAWBPPS - DrizzleIntegration Module
 * Performs drizzle integration on registered images
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

function DI_norm(p){
    var s = String(p||"");
    while (s.indexOf("\\") >= 0){
        var i = s.indexOf("\\");
        s = s.substring(0,i) + "/" + s.substring(i+1);
    }
    return s;
}

function DI_basename(p){
    var s = DI_norm(p);
    var i = s.lastIndexOf("/");
    return (i>=0) ? s.substring(i+1) : s;
}

function DI_noext(p){
    var b = DI_basename(p);
    var i = b.lastIndexOf(".");
    return (i>0) ? b.substring(0,i) : b;
}

function DI_sanitizeKey(key){
    return String(key||"").replace(/[|:\\/\s]+/g, "_");
}

function DI_fmtHMS(sec){
    var t = Math.max(0, Math.floor(sec));
    var hh = Math.floor(t/3600), mm = Math.floor((t%3600)/60), ss = t%60;
    var hs = Math.floor((sec - t) * 100);
    var pad = function(n){ return (n<10?"0":"")+n; };
    return pad(hh)+":"+pad(mm)+":"+pad(ss)+"."+pad(hs);
}

// ============================================================================
// Build Drizzle Suffix
// ============================================================================

function DI_buildDrizzleSuffix(scale){
    // scale = 1.0 → "_drz1x"
    // scale = 2.0 → "_drz2x"
    // scale = 3.0 → "_drz3x"
    var scaleInt = Math.round(scale);
    return "_drz" + scaleInt + "x";
}

// ============================================================================
// Build Simple SS Key (same as II)
// ============================================================================

function DI_buildSimpleSSKey(group){
    // Build simplified SS key: object|filter|exposure (NO setup, NO binning)
    var object = group.object || group.target || "UNKNOWN";
    var filter = group.filter || "L";
    var expTime = group.exposureSec || group.exposure || 0;
    
    return object + "|" + filter + "|" + expTime + "s";
}

// ============================================================================
// Collect and Group Files
// ============================================================================

function DI_collectAndGroupFiles(PLAN, workFolders){
    Console.writeln("[di] Collecting and grouping files...");
    
    if (!PLAN || !PLAN.groups){
        throw new Error("PLAN is empty (no groups)");
    }
    
    var diGroups = {}; // simpleSSKey -> { key, files, icGroups }
    
    // Iterate through all IC groups
    for (var icKey in PLAN.groups){
        if (!PLAN.groups.hasOwnProperty(icKey)) continue;
        
        var G = PLAN.groups[icKey];
        
        // Build simplified SS key
        var simpleSSKey = DI_buildSimpleSSKey(G);
        
        // Create DI group if not exists
        if (!diGroups[simpleSSKey]){
            diGroups[simpleSSKey] = {
                key: simpleSSKey,
                files: [],
                icGroups: []
            };
        }
        
        // Store IC group reference
        diGroups[simpleSSKey].icGroups.push(G);
        
        // Collect files from this IC group
        var bases = G.lights || [];
        
        for (var i=0; i<bases.length; ++i){
            var basePath = bases[i];
            var stem = DI_noext(DI_basename(basePath));
            var xdrzFile = workFolders.approvedSet + "/" + stem + "_c_cc_a_r.xdrz";
            
            if (File.exists(xdrzFile)){
                diGroups[simpleSSKey].files.push(xdrzFile);
            } else {
                Console.warningln("[di]     File not found: " + stem + "_c_cc_a_r.xdrz");
            }
        }
    }
    
    Console.writeln("[di] Grouped into " + Object.keys(diGroups).length + " DI group(s)");
    
    return diGroups;
}

// ============================================================================
// Build InputData Array
// ============================================================================

function DI_buildInputDataArray(files, useLN, workFolders){
    Console.writeln("[di]   Building inputData array...");
    
    var inputData = [];
    var skipped = [];
    
    for (var i=0; i<files.length; ++i){
        var xdrzPath = files[i];
        var xnmlPath = "";
        
        // If LN enabled, build .xnml path
        if (useLN){
            xnmlPath = xdrzPath.replace(/\.xdrz$/i, ".xnml");
            
            // Validate .xnml exists
            if (!File.exists(xnmlPath)){
                Console.warningln("[di]     LN data not found (skipping): " + DI_basename(xnmlPath));
                skipped.push(xdrzPath);
                continue;
            }
        }
        
        // Validate .xdrz exists
        if (!File.exists(xdrzPath)){
            Console.warningln("[di]     Drizzle data not found (skipping): " + DI_basename(xdrzPath));
            skipped.push(xdrzPath);
            continue;
        }
        
        // Add to inputData: [enabled, xdrzPath, xnmlPath]
        inputData.push([true, DI_norm(xdrzPath), DI_norm(xnmlPath)]);
    }
    
    if (skipped.length > 0){
        Console.writeln("[di]   Skipped " + skipped.length + " file(s) due to missing data");
    }
    
    Console.writeln("[di]   Total files: " + inputData.length);
    
    return inputData;
}

// ============================================================================
// Configure DrizzleIntegration Instance
// ============================================================================

function DI_configureInstance(P, inputData, useLN, scale){
    // Set input data
    P.inputData = inputData;
    P.inputHints = "";
    P.inputDirectory = "";
    
    // Scale and kernel
    P.scale = scale;                                            // 1.0, 2.0, 3.0
    P.dropShrink = 0.90;                                        // Fixed
    P.kernelFunction = DrizzleIntegration.prototype.Kernel_Square;
    P.kernelGridSize = 16;
    P.originX = 0.50;
    P.originY = 0.50;
    
    // CFA
    P.enableCFA = false;
    P.cfaPattern = "";
    
    // Main features
    P.enableRejection = true;
    P.enableImageWeighting = true;
    P.enableSurfaceSplines = true;
    P.enableLocalDistortion = true;
    
    // Normalization
    P.enableLocalNormalization = useLN;                         // true if LN enabled
    P.enableAdaptiveNormalization = false;
    
    // ROI
    P.useROI = false;
    P.roiX0 = 0;
    P.roiY0 = 0;
    P.roiX1 = 0;
    P.roiY1 = 0;
    
    // Output options
    P.closePreviousImages = false;
    P.useLUT = false;
    P.truncateOnOutOfRange = false;
    P.noGUIMessages = true;
    P.showImages = true;
    P.onError = DrizzleIntegration.prototype.Continue;
}

// ============================================================================
// Build Output Path
// ============================================================================

function DI_buildOutputPath(ssKey, fileCount, workFolders, scale, isWeights){
    // Parse ssKey: "object|filter|exposure"
    var parts = ssKey.split("|");
    var object = parts[0] || "UNKNOWN";
    var filter = parts[1] || "L";
    var exposure = parts[2] || "0s";
    
    // Sanitize object name
    var sanitizedObject = object.replace(/[\s|:\\/\.]+/g, "_");
    
    // Build drizzle suffix with scale: _drz1x, _drz2x, _drz3x
    var scaleInt = Math.round(scale);
    var drzSuffix = "_drz" + scaleInt + "x";
    
    // Add weights suffix if needed
    var suffix = isWeights ? (drzSuffix + "_weights") : drzSuffix;
    
    // Build filename: object_filter_COUNTxEXPOSURE_drz2x.xisf
    var filename = sanitizedObject + "_" + filter + "_" + fileCount + "x" + exposure + suffix + ".xisf";
    
    // Output folder: WORK2/!Integrated/
    var base = workFolders.base2 || workFolders.base1;
    var integratedFolder = base + "/!Integrated";
    
    // Create folder if not exists
    if (!File.directoryExists(integratedFolder)){
        File.createDirectory(integratedFolder, true);
        Console.writeln("[di]   Created folder: !Integrated");
    }
    
    var fullPath = DI_norm(integratedFolder + "/" + filename);
    
    return fullPath;
}

// ============================================================================
// Save Drizzle Results (2 files: drizzle + weights)
// ============================================================================

function DI_saveResults(drzView, weightsView, basePath, weightsPath, scale){
    Console.writeln("[di]   Saving results as 2 separate 32-bit files...");
    
    // Validate views
    if (!drzView || !drzView.image){
        throw new Error("Drizzle integration view is invalid");
    }
    if (!weightsView || !weightsView.image){
        throw new Error("Drizzle weights view is invalid");
    }
    
    try{
        var drzWindow = drzView.window;
        var weightsWindow = weightsView.window;
        
        // Save drizzle integration
        Console.writeln("[di]     Writing drizzle integration: " + DI_basename(basePath));
        if (!drzWindow.saveAs(basePath, false, false, true, false)){
            throw new Error("Failed to save drizzle integration");
        }
        
        // Save weights
        Console.writeln("[di]     Writing drizzle weights: " + DI_basename(weightsPath));
        if (!weightsWindow.saveAs(weightsPath, false, false, true, false)){
            throw new Error("Failed to save drizzle weights");
        }
        
        Console.writeln("[di]   ✓ Saved 2 files (32-bit float)");
        Console.writeln("[di]     Main:    " + DI_basename(basePath));
        Console.writeln("[di]     Weights: " + DI_basename(weightsPath));
        
    }catch(e){
        throw e;
    }
}

// ============================================================================
// Close Drizzle Views
// ============================================================================

function DI_closeViews(drzView, weightsView){
    Console.writeln("[di]   Closing views...");
    
    var closed = 0;
    
    try{
        if (drzView && drzView.window){
            drzView.window.forceClose();
            closed++;
        }
    }catch(e){
        Console.warningln("[di]     Failed to close drizzle view: " + e);
    }
    
    try{
        if (weightsView && weightsView.window){
            weightsView.window.forceClose();
            closed++;
        }
    }catch(e){
        Console.warningln("[di]     Failed to close weights view: " + e);
    }
    
    Console.writeln("[di]   ✓ Closed " + closed + " view(s)");
}

// ============================================================================
// Process One Group
// ============================================================================

function DI_processGroup(ssKey, diGroup, workFolders, useLN, scale, node){
    Console.writeln("[di] ========================================");
    Console.writeln("[di] Processing group: " + ssKey);
    Console.writeln("[di] ========================================");
    
    if (!diGroup.files || diGroup.files.length === 0){
        throw new Error("No files to drizzle for group: " + ssKey);
    }
    
    Console.writeln("[di]   Files: " + diGroup.files.length);
    Console.writeln("[di]   Scale: " + scale + "x");
    Console.writeln("[di]   LocalNormalization: " + (useLN ? "ENABLED" : "DISABLED"));
    
    // Update UI - Running
    if (node){
        try{
            node.setText(3, "▶ Running");
            node.setText(4, "0/" + diGroup.files.length + " drizzling");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }
    
    // Build inputData array
    var inputData = DI_buildInputDataArray(diGroup.files, useLN, workFolders);
    
    if (inputData.length === 0){
        throw new Error("No valid files for drizzle integration: " + ssKey);
    }
    
    // Create DrizzleIntegration instance
    var P = new DrizzleIntegration;
    DI_configureInstance(P, inputData, useLN, scale);
    
    Console.writeln("[di]   Running DrizzleIntegration...");
    
    // Execute DrizzleIntegration
    var t0 = Date.now();
    var ok = true;
    try{
        P.executeGlobal();
    }catch(e){
        ok = false;
        Console.criticalln("[di]   DrizzleIntegration failed: " + e);
        throw e;
    }
    var elapsed = (Date.now() - t0) / 1000;
    
    Console.writeln("[di]   ✓ Completed in " + DI_fmtHMS(elapsed));
    
    // Save results
    try{
        Console.writeln("[di]   Processing results...");
        
        // Find views by standard IDs
        var drzView = View.viewById("drizzle_integration");
        var weightsView = View.viewById("drizzle_weights");
        
        if (!drzView){
            throw new Error("Drizzle view 'drizzle_integration' not found");
        }
        if (!weightsView){
            throw new Error("Weights view 'drizzle_weights' not found");
        }
        
        // Build output paths
        var basePath = DI_buildOutputPath(ssKey, inputData.length, workFolders, scale, false);
        var weightsPath = DI_buildOutputPath(ssKey, inputData.length, workFolders, scale, true);
        
        // Build view IDs with scale
        var baseName = DI_basename(basePath).replace(/\.xisf$/i, "");
        var weightsName = DI_basename(weightsPath).replace(/\.xisf$/i, "");
        
        // Rename views
        Console.writeln("[di]     Renaming views...");
        var oldDrzId = drzView.id;
        var oldWeightsId = weightsView.id;
        
        drzView.id = baseName;           // "Sivan_2_B_21x30s_drz2x"
        weightsView.id = weightsName;     // "Sivan_2_B_21x30s_drz2x_weights"
        
        Console.writeln("[di]       " + oldDrzId + " → " + drzView.id);
        Console.writeln("[di]       " + oldWeightsId + " → " + weightsView.id);
        
        // Save to files (32-bit Float)
        DI_saveResults(drzView, weightsView, basePath, weightsPath, scale);
        
        // Close views
        DI_closeViews(drzView, weightsView);
        
        Console.writeln("[di]   ✓ Results saved and views closed");
        
    }catch(e){
        Console.criticalln("[di]   Failed to save results: " + e);
        throw e;
    }
    
    // Update UI - Success
    if (node){
        try{
            node.setText(2, DI_fmtHMS(elapsed));
            node.setText(3, "✓ Success");
            node.setText(4, inputData.length + " subs drizzled");
            if (typeof processEvents === "function") processEvents();
        }catch(_){}
    }
}

// ============================================================================
// Main Entry Point
// ============================================================================

function DI_runForAllGroups(params){
    // params: { PLAN, workFolders, useLN, scale, dlg }
    var PLAN = params.PLAN;
    var workFolders = params.workFolders;
    var useLN = params.useLN || false;
    var scale = params.scale || 1.0;
    var dlg = params.dlg || null;

    Console.writeln("[di] ========================================");
    Console.writeln("[di] DrizzleIntegration: Processing all groups");
    Console.writeln("[di] ========================================");
    Console.writeln("[di] Scale: " + scale + "x");
    Console.writeln("[di] LocalNormalization: " + (useLN ? "ENABLED" : "DISABLED"));

    // Step 1: Collect and group files
    var diGroups = DI_collectAndGroupFiles(PLAN, workFolders);

    if (!diGroups || Object.keys(diGroups).length === 0){
        Console.warningln("[di] No groups found");
        return;
    }

    // Step 2: Get group keys
    var groupKeys = [];
    for (var k in diGroups){
        if (diGroups.hasOwnProperty(k)) groupKeys.push(k);
    }

    Console.writeln("[di] Found " + groupKeys.length + " group(s)");

    // Step 3: PRE-CREATE all UI rows BEFORE processing
    var groupNodes = {};
    if (dlg){
        for (var i=0; i<groupKeys.length; ++i){
            var ssKey = groupKeys[i];
            var diGroup = diGroups[ssKey];
            
            // Parse key for label: "object | filter | exposure"
            var parts = ssKey.split("|");
            var object = parts[0] || "UNKNOWN";
            var filter = parts[1] || "L";
            var exposure = parts[2] || "0s";
            
            var scaleInt = Math.round(scale);
            var label = object + " | " + filter + " | " + exposure + " (" + diGroup.files.length + " subs, " + scaleInt + "x)";
            var node = dlg.addRow("DrizzleIntegration", label);
            
            try{
                node.setText(3, "⏳ Queued");
                node.setText(4, diGroup.files.length + "/" + diGroup.files.length + " queued");
            }catch(_){}
            
            groupNodes[ssKey] = node;
        }
        try{ if (typeof processEvents === "function") processEvents(); }catch(_){}
    }

    Console.writeln("[di] ========================================");

    // Step 4: Process each group
    for (var i=0; i<groupKeys.length; ++i){
        var ssKey = groupKeys[i];
        var diGroup = diGroups[ssKey];
        
        Console.writeln("[di] Group " + (i+1) + "/" + groupKeys.length + ": " + ssKey);
        
        try{
            var node = groupNodes[ssKey] || null;
            DI_processGroup(ssKey, diGroup, workFolders, useLN, scale, node);
        }catch(e){
            Console.criticalln("[di] ERROR processing group '" + ssKey + "': " + e);
            
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

    Console.writeln("[di] ========================================");
    Console.writeln("[di] DrizzleIntegration complete");
    Console.writeln("[di] ========================================");
}
