/*
 * ANAWBPPS - CosmeticCorrection Module
 * Applies hot/cold pixel correction using auto-detect mode
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

// Path normalization helper
function CC_normPath(p){
    var s = String(p||"");
    while (true){
        var i=-1,k; 
        for(k=0;k<s.length;k++){ 
            if (s.charAt(k) === "\\"){ i=k; break; } 
        }
        if (i<0) break; 
        s = s.substring(0,i) + "/" + s.substring(i+1);
    }
    while (true){
        var j=-1,t; 
        for(t=0; t<s.length-1; t++){ 
            if (s.charAt(t)==="/" && s.charAt(t+1)==="/"){ j=t; break; } 
        }
        if (j<0) break; 
        s = s.substring(0,j) + "/" + s.substring(j+2);
    }
    if (s.length>2 && s.charAt(1)===":" && s.charAt(2)==="/"){
        var c=s.charAt(0); 
        if (c>="a" && c<="z") s=c.toUpperCase()+s.substring(1);
    }
    return s;
}

// Time formatting
function CC_fmtTime(ms){
    // Use the same format as other modules
    if (ms < 0) ms = 0;
    var totalSeconds = Math.floor(ms / 1000);
    var hh = Math.floor(totalSeconds / 3600);
    var mm = Math.floor((totalSeconds % 3600) / 60);
    var ss = totalSeconds % 60;
    var hundredths = Math.floor((ms % 1000) / 10);
    function pad2(n) { return (n < 10 ? "0" : "") + n; }
    return pad2(hh) + ":" + pad2(mm) + ":" + pad2(ss) + "." + pad2(hundredths);
}

// Basename helper
function CC_basename(p){
    var s = CC_normPath(p);
    var k=-1;
    for (var i=0; i<s.length; i++){ 
        if (s.charAt(i)==="/") k=i; 
    }
    return (k>=0) ? s.substring(k+1) : s;
}

// Strip extension
function CC_stripExt(name){
    var k=-1;
    for(var i=0; i<name.length; i++){ 
        if(name.charAt(i)===".") k=i; 
    }
    return (k>=0) ? name.substring(0,k) : name;
}
// Configure CosmeticCorrection instance with auto-detect mode
function CC_configureInstance(P){
    Console.writeln("[cc] Configuring CosmeticCorrection instance");
    
    // Target frames - will be set per group
    P.targetFrames = [];
    
    // Master Dark - NOT USED!
    P.masterDarkPath = "";
    P.useMasterDark = false;
    
    // Hot/Cold Dark detection - NOT USED!
    P.hotDarkCheck = false;
    P.hotDarkLevel = 1.0;
    P.coldDarkCheck = false;
    P.coldDarkLevel = 0.0;
    
    // Auto Detection - ENABLED! (main mode)
    P.useAutoDetect = true;
    P.hotAutoCheck = true;
    P.hotAutoValue = 3.0;   // Sigma for hot pixels
    P.coldAutoCheck = true;
    P.coldAutoValue = 3.0;  // Sigma for cold pixels
    
    // Defect List - NOT USED
    P.useDefectList = false;
    P.defects = [];
    
    // Output settings
    P.outputDir = "";           // Will be set per group
    P.outputExtension = ".xisf";
    P.prefix = "";
    P.postfix = "_cc";
    P.overwrite = true;         // Overwrite existing files
    
    // Other settings
    P.amount = 1.00;
    P.cfa = false;
    P.generateHistoryProperties = false;
    
    // Compatibility aliases
    try { P.outputDirectory = ""; } catch(_){}
    try { P.overwriteExistingFiles = true; } catch(_){}
    
    Console.writeln("[cc] Auto-detect mode enabled (hot=3.0σ, cold=3.0σ)");
    Console.writeln("[cc] Configuration complete");
}

// Process CosmeticCorrection for one calibration group
function CC_runForGroup(groupKey, group, workFolders, dlg){
    Console.noteln("[cc] Processing group: " + groupKey);
    
    // Get input files (calibrated)
    var inputDir = workFolders.calibrated;
    var outputDir = workFolders.cosmetic;
    
    // Build list of calibrated files for this group
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
        Console.warningln("[cc]   No lights in group, skipping");
        return { ok: true, processed: 0, skipped: 0 };
    }
    
    // Map to calibrated files
    var targetFrames = [];
    var missing = 0;
    
    for (var i=0; i<lights.length; i++){
        var lightPath = CC_normPath(lights[i]);
        var base = CC_stripExt(CC_basename(lightPath));
        var calibratedName = base + "_c.xisf";
        var calibratedPath = inputDir + "/" + calibratedName;
        
        var exists = false;
        try { exists = File.exists(calibratedPath); } catch(_){}
        
        if (exists){
            targetFrames.push([true, calibratedPath]);
        } else {
            missing++;
        }
    }
    
    if (!targetFrames.length){
        Console.warningln("[cc]   No calibrated files found (missing=" + missing + ")");
        return { ok: false, processed: 0, skipped: lights.length };
    }
    
    if (missing > 0){
        Console.warningln("[cc]   " + missing + " file(s) missing, processing " + targetFrames.length);
    }
    
    Console.writeln("[cc]   Files to process: " + targetFrames.length);
    Console.writeln("[cc]   Output dir: " + outputDir);
    
    // Create CC instance
    var P = new CosmeticCorrection;
    CC_configureInstance(P);
    
    // Set target frames and output dir
    P.targetFrames = targetFrames;
    P.outputDir = outputDir;
    try { P.outputDirectory = outputDir; } catch(_){}
    
    // Execute
    var t0 = Date.now();
    var ok = true;
    
    try {
        if (typeof P.executeGlobal === "function"){
            ok = !!P.executeGlobal();
        } else if (typeof P.executeOn === "function"){
            ok = !!P.executeOn(null);
        } else {
            throw new Error("No execute method available");
        }
    } catch(e){
        Console.criticalln("[cc]   Error: " + e);
        ok = false;
    }

    var elapsed = Date.now() - t0;  // миллисекунды!

    if (ok){
        Console.noteln("[cc]   ✔ Success in " + CC_fmtTime(elapsed));
        return { ok: true, processed: targetFrames.length, skipped: missing };
    } else {
        Console.criticalln("[cc]   ✖ Error in " + CC_fmtTime(elapsed));
        return { ok: false, processed: 0, skipped: targetFrames.length + missing };
    }
}

// Run CosmeticCorrection for all calibration groups
function CC_runForAllGroups(params){
    var PLAN = params.PLAN;
    var workFolders = params.workFolders;
    var dlg = params.dlg || null;
    
    if (!PLAN || !PLAN.groups){
        Console.warningln("[cc] No calibration plan groups");
        return;
    }
    
    Console.noteln("[cc] Starting CosmeticCorrection for all groups");
    
    // Get group keys
    var groupKeys = [];
    try {
        if (PLAN.order && PLAN.order.length){
            groupKeys = PLAN.order.slice(0);
        } else {
            for (var k in PLAN.groups){
                if (PLAN.groups.hasOwnProperty(k)) groupKeys.push(k);
            }
        }
    } catch(_){}
    
    Console.writeln("[cc] Groups to process: " + groupKeys.length);
    
    // Pre-add progress rows if dialog available
    if (dlg){
        if (!dlg.ccRowsMap) dlg.ccRowsMap = {};
        
        for (var i=0; i<groupKeys.length; i++){
            var gkey = groupKeys[i];
            var g = PLAN.groups[gkey];
            
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
            
            var node = dlg.addRow("CosmeticCorrection", label);
            try {
                node.setText(3, "⏳ Queued");
                node.setText(4, count + "/" + count + " queued");
            } catch(_){}
            
            dlg.ccRowsMap[gkey] = { node: node, count: count };
        }
        
        try { processEvents(); } catch(_){}
    }
    
    // Process each group
    var totalStart = Date.now();
    var totalProcessed = 0;
    var totalSkipped = 0;
    
    for (var i=0; i<groupKeys.length; i++){
        var gkey = groupKeys[i];
        var group = PLAN.groups[gkey];
        
        // Update progress UI
        var node = null;
        if (dlg && dlg.ccRowsMap && dlg.ccRowsMap[gkey]){
            node = dlg.ccRowsMap[gkey].node;
        }
        
        if (node){
            try {
                node.setText(3, "▶ Running");
                node.setText(4, "processing...");
                node.setText(2, "00:00:00");
            } catch(_){}
            try { processEvents(); } catch(_){}
        }
        
        // Process group
        var t0 = Date.now();
        var result = CC_runForGroup(gkey, group, workFolders, dlg);
        var elapsed = Date.now() - t0;  // миллисекунды!
        
        totalProcessed += result.processed || 0;
        totalSkipped += result.skipped || 0;
        
        // Update progress UI
        if (node){
            try {
                node.setText(2, CC_fmtTime(elapsed));
                node.setText(3, result.ok ? "✔ Success" : "✖ Error");
                node.setText(4, result.processed + "/" + (result.processed + result.skipped) + " processed");
            } catch(_){}
            try { processEvents(); } catch(_){}
        }
    }

    var totalElapsed = Date.now() - totalStart;  // миллисекунды!

    Console.noteln("");
    Console.noteln("[cc] CosmeticCorrection complete");
    Console.writeln("[cc] Total processed: " + totalProcessed);
    Console.writeln("[cc] Total skipped: " + totalSkipped);
    Console.writeln("[cc] Total time: " + CC_fmtTime(totalElapsed));
}

