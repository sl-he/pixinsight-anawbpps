/*
 * ANAWBPPS - Image Calibration Module (unified)
 * Smart matching of lights to master calibration frames + ImageCalibration runner
 * Merged from calibration_match.jsh and calibration_run.jsh
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

// ========================================================================
// PART 1: CALIBRATION PLAN BUILDER (from calibration_match.jsh)
// ========================================================================

// --- in-memory storage for the last built plan
var IC_LAST_PLAN = null;
function IC_GET_LAST_PLAN(){ return IC_LAST_PLAN; }

function IC_saveJSON(path, obj){
    try{
        var f = new File;
        f.createForWriting(path);
        f.outText(JSON.stringify(obj, null, 2));
        f.close();
        Console.writeln("[plan] Calibration plan saved: " + path);
    } catch(e){
        Console.criticalln("[plan] Failed to save JSON: " + e);
    }
}

function _pickFlatForLight(L, flats, verbose){
    var Ld = CU_parseISODate(L.date);
    if (!Ld) return null;

    var cands = [];
    for (var i=0;i<flats.length;++i){
        var F = flats[i];
        if (F.type !== "FLAT") continue;
        if (F.setup !== L.setup) continue;
        if (F.filter !== L.filter) continue;
        if (F.binning !== L.binning) continue;
        var Fd = CU_parseISODate(F.date);
        if (!Fd) continue;
        var dd = CU_daysDiff(Ld, Fd); // dd>=0 — flat в тот же день/раньше лайта
        cands.push({F:F, Fd:Fd, dd:dd, abs:Math.abs(dd)});
    }
    if (!cands.length) return null;

    // 1) в пределах ±3 дней — минимальный |dd|, при равенстве предпочесть dd>=0
    var within=[];
    for (var j=0;j<cands.length;++j) if (cands[j].abs<=3) within.push(cands[j]);
    if (within.length){
        within.sort(function(a,b){
            if (a.abs!==b.abs) return a.abs-b.abs;
            var pa=(a.dd>=0)?0:1, pb=(b.dd>=0)?0:1; if (pa!==pb) return pa-pb;
            return b.Fd.getTime()-a.Fd.getTime(); // более поздний лучше
        });
        if (verbose) Console.writeln("    [flat pick] within ±3d: " + within[0].F.path + " (Δ="+within[0].dd+"d)");
        return within[0].F;
    }

    // 2) иначе — самый поздний до лайта (dd>=0)
    var prior=[];
    for (var k=0;k<cands.length;++k) if (cands[k].dd>=0) prior.push(cands[k]);
    if (prior.length){
        prior.sort(function(a,b){ return b.Fd.getTime()-a.Fd.getTime(); });
        if (verbose) Console.writeln("    [flat pick] latest prior: " + prior[0].F.path + " (Δ="+prior[0].dd+"d)");
        return prior[0].F;
    }

    // 3) иначе — ближайший будущий
    cands.sort(function(a,b){
        if (a.abs!==b.abs) return a.abs-b.abs;
        return a.Fd.getTime()-b.Fd.getTime();
    });
    if (verbose) Console.writeln("    [flat pick] nearest future: " + cands[0].F.path + " (Δ="+cands[0].dd+"d)");
    return cands[0].F;
}

function _pickBiasForLight(L, biases, verbose){
    var pool=[];
    for (var i=0;i<biases.length;++i){
        var B=biases[i];
        if (B.type!=="BIAS") continue;
        // строгие поля:
        if (B.setup   !== L.setup)   continue;
        if (B.readout !== L.readout) continue;
        if (B.gain    !== L.gain)    continue;
        if (B.offset  !== L.offset)  continue;
        if (B.usb     !== L.usb)     continue;
        if (B.binning !== L.binning) continue;
        if (B.tempC   !== L.tempC)   continue;
        // расстояние по дате — «как можно ближе НАЗАД»
        var Bd=CU_parseISODate(B.date), Ld=CU_parseISODate(L.date);
        if (!Bd||!Ld) continue;
        var dd=CU_daysDiff(Ld,Bd); // >=0 — раньше/в тот же день
        pool.push({B:B, dd:dd});
    }
    if (!pool.length) return null;

    var prior=[], future=[];
    for (var k=0;k<pool.length;++k) (pool[k].dd>=0?prior:future).push(pool[k]);

    if (prior.length){
        prior.sort(function(a,b){
            if (a.dd!==b.dd) return a.dd-b.dd; // ближе
            // при одинаковой дистанции — более поздний (бОльшая дата)
            var ad=CU_parseISODate(a.B.date), bd=CU_parseISODate(b.B.date);
            return bd.getTime()-ad.getTime();
        });
        if (verbose) Console.writeln("    [bias pick] prior: " + prior[0].B.path + " (Δ="+prior[0].dd+"d)");
        return prior[0].B;
    }
    future.sort(function(a,b){ return Math.abs(a.dd)-Math.abs(b.dd); });
    if (verbose) Console.writeln("    [bias pick] future: " + future[0].B.path + " (Δ="+future[0].dd+"d)");
    return future[0].B;
}

function _pickDarkForLight(L, darks, verbose){
    var pool=[];
    for (var i=0;i<darks.length;++i){
        var D=darks[i];
        if (D.type!=="DARK") continue;
        // строгие поля:
        if (D.setup   !== L.setup)   continue;
        if (D.readout !== L.readout) continue;
        if (D.gain    !== L.gain)    continue;
        if (D.offset  !== L.offset)  continue;
        if (D.usb     !== L.usb)     continue;
        if (D.binning !== L.binning) continue;
        if (D.tempC   !== L.tempC)   continue;
        if (D.exposureSec !== L.exposureSec) continue;
        var Dd=CU_parseISODate(D.date), Ld=CU_parseISODate(L.date);
        if (!Dd||!Ld) continue;
        var dd=CU_daysDiff(Ld,Dd);
        pool.push({D:D, dd:dd});
    }
    if (!pool.length) return null;

    var prior=[], future=[];
    for (var k=0;k<pool.length;++k) (pool[k].dd>=0?prior:future).push(pool[k]);

    if (prior.length){
        prior.sort(function(a,b){
            if (a.dd!==b.dd) return a.dd-b.dd;
            var ad=CU_parseISODate(a.D.date), bd=CU_parseISODate(b.D.date);
            return bd.getTime()-ad.getTime();
        });
        if (verbose) Console.writeln("    [dark pick] prior: " + prior[0].D.path + " (Δ="+prior[0].dd+"d)");
        return prior[0].D;
    }
    future.sort(function(a,b){ return Math.abs(a.dd)-Math.abs(b.dd); });
    if (verbose) Console.writeln("    [dark pick] future: " + future[0].D.path + " (Δ="+future[0].dd+"d)");
    return future[0].D;
}

// ---------- strict validators ----------
function _isValidLight(L){
    // TODO-32 Phase 0: CFA files don't have filter, but have bayerPattern
    if (L.bayerPattern) {
        // CFA file: filter optional, bayerPattern required
        return CU_hasAllKeys(L, ["setup","readout","gain","offset","usb","binning","tempC","date","exposureSec"]);
    }
    // Mono file: filter required, bayerPattern absent
    return CU_hasAllKeys(L, ["setup","readout","gain","offset","usb","binning","tempC","date","exposureSec","filter"]);
}

function _isValidBias(B){
    return CU_hasAllKeys(B, ["setup","readout","gain","offset","usb","binning","tempC","date","type"]) && B.type==="BIAS";
}

function _isValidDark(D){
    return CU_hasAllKeys(D, ["setup","readout","gain","offset","usb","binning","tempC","date","exposureSec","type"]) && D.type==="DARK";
}

function _isValidFlat(F){
    return CU_hasAllKeys(F, ["setup","filter","binning","date","type"]) && F.type==="FLAT";
}

// ---------- match filters (unused but kept for compatibility) ----------
function _sameSetup(a,b){ return a===b; }

function _pickClosestPastByDate(targetDate, arr){
    var best = null;
    var bestDays = null;
    for (var i=0;i<arr.length;++i){
        var d = arr[i].date;
        var dd = CU_daysDiff(d, targetDate); // positive means arr[i] <= targetDate
        if (dd===null) continue;
        if (dd < 0) continue; // future, skip
        if (best===null || dd < bestDays){
            best = arr[i];
            bestDays = dd;
        }
    }
    return { pick: best, days: bestDays };
}

function _filterBiasesForLight(L, BIAS){
    var out = [];
    for (var i=0;i<BIAS.length;++i){
        var B = BIAS[i];
        if (!_isValidBias(B)) continue;
        if (!_sameSetup(L.setup, B.setup)) continue;
        if (!CU_sameStr(L.readout, B.readout)) continue;
        if (!CU_sameInt(L.gain,   B.gain))    continue;
        if (!CU_sameInt(L.offset, B.offset))  continue;
        if (!CU_sameInt(L.usb,    B.usb))     continue;
        if (!CU_sameStr(L.binning,B.binning)) continue;
        if (!CU_sameInt(L.tempC,  B.tempC))   continue;
        out.push(B);
    }
    return out;
}

function _filterDarksForLight(L, DARKS){
    var out = [];
    for (var i=0;i<DARKS.length;++i){
        var D = DARKS[i];
        if (!_isValidDark(D)) continue;
        if (!_sameSetup(L.setup, D.setup)) continue;
        if (!CU_sameStr(L.readout, D.readout)) continue;
        if (!CU_sameInt(L.gain,   D.gain))    continue;
        if (!CU_sameInt(L.offset, D.offset))  continue;
        if (!CU_sameInt(L.usb,    D.usb))     continue;
        if (!CU_sameStr(L.binning,D.binning)) continue;
        if (!CU_sameInt(L.tempC,  D.tempC))   continue;
        if (!CU_sameInt(L.exposureSec, D.exposureSec)) continue;
        out.push(D);
    }
    return out;
}

function _filterFlatsForLight(L, FLATS){
    var out = [];
    Console.writeln("[plan] Filtering " + FLATS.length + " flats for light: setup=" + L.setup + ", filter=" + L.filter + ", bayerPattern=" + L.bayerPattern + ", binning=" + L.binning);
    for (var i=0;i<FLATS.length;++i){
        var F = FLATS[i];
        if (!_isValidFlat(F)) {
            Console.writeln("[plan]   SKIP flat #" + i + ": invalid (filter=" + F.filter + ", type=" + F.type + ")");
            continue;
        }
        if (!_sameSetup(L.setup, F.setup)) {
            Console.writeln("[plan]   SKIP flat #" + i + ": setup mismatch (L=" + L.setup + ", F=" + F.setup + ")");
            continue;
        }

        // TODO-32 Phase 0: For CFA, match by bayerPattern instead of filter
        if (L.bayerPattern && F.bayerPattern) {
            // Both CFA: match by Bayer pattern
            if (!CU_sameStr(L.bayerPattern, F.bayerPattern)) {
                Console.writeln("[plan]   SKIP flat #" + i + ": bayerPattern mismatch (L=" + L.bayerPattern + ", F=" + F.bayerPattern + ")");
                continue;
            }
        } else if (!L.bayerPattern && !F.bayerPattern) {
            // Both mono: match by filter
            if (!CU_sameStr(L.filter, F.filter)) {
                Console.writeln("[plan]   SKIP flat #" + i + ": filter mismatch (L=" + L.filter + ", F=" + F.filter + ")");
                continue;
            }
        } else {
            // Mismatch: CFA vs mono
            Console.writeln("[plan]   SKIP flat #" + i + ": CFA/mono mismatch (L.bayerPattern=" + L.bayerPattern + ", F.bayerPattern=" + F.bayerPattern + ")");
            continue;
        }

        if (!CU_sameStr(L.binning,F.binning)) {
            Console.writeln("[plan]   SKIP flat #" + i + ": binning mismatch (L=" + L.binning + ", F=" + F.binning + ")");
            continue;
        }
        Console.writeln("[plan]   MATCH flat #" + i + ": " + F.filename);
        out.push(F);
    }
    Console.writeln("[plan] Found " + out.length + " matching flats");
    return out;
}

// ========================================================================
// PUBLIC API: Build Calibration Plan
// ========================================================================
function IC_buildCalibrationPlan(lightsIndex, mastersIndex, savePath, useBias){
    Console.writeln("[plan] Build calibration plan" + (useBias ? " (with Bias)" : " (without Bias)") + "…");

    // Пулы
    var lights = (lightsIndex && lightsIndex.items) ? lightsIndex.items : [];
    var masters= (mastersIndex && mastersIndex.items)? mastersIndex.items: [];
    var biases=[], darks=[], flats=[];
    for (var i=0;i<masters.length;++i){
        var m = masters[i];
        if (m.type==="BIAS") biases.push(m);
        else if (m.type==="DARK") darks.push(m);
        else if (m.type==="FLAT") flats.push(m);
    }
    Console.writeln("[plan] Pools: lights="+lights.length+", biases="+biases.length+", darks="+darks.length+", flats="+flats.length);

    var groups = {}; // key -> {lights:[], bias, dark, flat}
    var skipped = [];

    for (var li=0; li<lights.length; ++li){
        var L = lights[li];

        if (!_isValidLight(L)){
            skipped.push({path:L.path, reason:"missing required light fields"});
            if (L && L.path) Console.writeln("[plan] Skipped light (missing required fields): " + L.path);
            continue;
        }

        Console.writeln(
            "Light: " + (L.filename||L.path) +
            " → setup="+L.setup+", object="+L.object+", filter="+L.filter+
            ", bin="+L.binning+", gain="+L.gain+", offset="+L.offset+
            ", usb="+L.usb+", exp="+L.exposureSec+", tempC="+L.tempC+", date="+L.date
        );

        // Pick masters: bias only if useBias=true
        var bPick = useBias ? _pickBiasForLight(L, biases, /*verbose*/true) : null;
        var dPick = _pickDarkForLight(L, darks,  /*verbose*/true);
        var fPick = _pickFlatForLight(L, flats,  /*verbose*/true);

        if (useBias && !bPick) Console.writeln("  [no bias match]");
        if (!dPick) Console.writeln("  [no dark match]");
        if (!fPick) Console.writeln("  [no flat match]");

        // Check required masters: bias only if useBias=true
        if ((useBias && !bPick) || !dPick || !fPick){
            if (useBias && !bPick) Console.writeln("  → NO BIAS for this light");
            if (!dPick) Console.writeln("  → NO DARK for this light");
            if (!fPick) Console.writeln("  → NO FLAT for this light");
            skipped.push({path:L.path, reason:"missing master match"});
            continue;
        }

        // ключ группы — по выбранным мастерфайлам + базовые параметры лайта:
        var biasPath = bPick ? bPick.path : "";
        var darkPath = dPick.path;
        var flatPath = fPick.path;
        var gkey =
            L.setup + "|" + L.object + "|" + L.filter + "|" +
            L.readout + "|" + L.gain + "|" + L.offset + "|" + L.usb + "|" +
            L.binning + "|" + L.tempC + "|" + L.exposureSec + "|" +
            biasPath + "|" + darkPath + "|" + flatPath;

        if (!groups[gkey]){
            groups[gkey] = {
                setup: L.setup,
                object: L.object,
                filter: L.filter,
                bayerPattern: L.bayerPattern || null,  // TODO-32 Phase 0: Store Bayer pattern for CFA
                readout: L.readout,
                gain: L.gain, offset: L.offset, usb: L.usb,
                binning: L.binning, tempC: L.tempC, exposureSec: L.exposureSec,
                bias: biasPath,
                dark: darkPath,
                flat: flatPath,
                lights: []
            };
        }
        groups[gkey].lights.push(L.path);
    }

    // Сохранение
    var out = { generatedUTC: (new Date()).toISOString(), groups: groups, skipped: skipped };
    try{
        var f = new File; f.createForWriting(savePath); f.outText(JSON.stringify(out, null, 2)); f.close();
        Console.writeln("[plan] Calibration plan saved: " + savePath);
    } catch(e){
        Console.criticalln("[plan] Failed to save plan: " + e);
    }

    // Короткая сводка
    var gcount=0; for (var k in groups) if (groups.hasOwnProperty(k)) ++gcount;
    Console.writeln("\nCalibration plan summary:\nGroups: " + gcount);

    // <-- keep in memory and return
    IC_LAST_PLAN = out;
    return out;
}

// ========================================================================
// PART 2: IMAGE CALIBRATION RUNNER (from calibration_run.jsh)
// ========================================================================

// ----------------- tiny helpers (no regex) -----------------
function IC__safeSetStringEnum(IC, key, strVal){
    try{
        var cur = IC[key];            // прочитаем текущее значение
        if (typeof cur === "string")  // ставим ТОЛЬКО если у процесса тут строка
            IC[key] = String(strVal);
        // иначе пропускаем без ошибок
    }catch(_){}
}

function IC__safeSetNumberEnum(IC, key, numVal){
    try{
        var cur = IC[key];
        if (typeof cur === "number")  // ставим ТОЛЬКО если тут число
            IC[key] = Number(numVal);
    }catch(_){}
}

function IC__groupLights(g){
    if (!g) return [];
    if (g.lights && g.lights.length) return g.lights.slice(0);
    if (g.items && g.items.length){
        var a=[], i; for (i=0;i<g.items.length;i++){
            var it = g.items[i];
            if (typeof it === "string") a.push(it);
            else if (it && it.path) a.push(it.path);
        }
        return a;
    }
    if (g.frames && g.frames.length) return g.frames.slice(0);
    return [];
}

// Fallback: extract master paths from the group key if fields are missing.
// Assumes last 3 '|'-separated segments are bias, dark, flat.
function IC__extractMastersFromKey(gkey){
    var res = { bias:"", dark:"", flat:"" };
    if (!gkey) return res;
    // split by '|' without using "//" literal
    var parts = [];
    var cur = "";
    for (var i=0;i<gkey.length;i++){
        var ch = gkey.charAt(i);
        if (ch === "|"){ parts.push(cur); cur=""; }
        else cur += ch;
    }
    parts.push(cur);
    if (parts.length >= 3){
        res.bias = parts[parts.length-3];
        res.dark = parts[parts.length-2];
        res.flat = parts[parts.length-1];
    }
    return res;
}

// ----------------- parameters (EDIT HERE) -----------------
// Вставь/правь профиль ImageCalibration ниже (строки вида IC.param = value;)
function IC_applyUserParams(IC, useBias, group){
    // === BEGIN: your parameters ===
    // TODO-32: Dynamic CFA based on group
    if (group && group.bayerPattern){
        IC.enableCFA = true;
        Console.writeln("[cal] CFA enabled for group (pattern: " + group.bayerPattern + ")");
    } else {
        IC.enableCFA = false;
    }
    IC.inputHints  = "";
    IC.outputHints = "";
    IC.pedestal       = 0;
//    IC.pedestalMode   = "Keyword";
//    IC.pedestalKeyword= "";
    IC.overscanEnabled = false;
    IC.overscanImageX0 = 0;
    IC.overscanImageY0 = 0;
    IC.overscanImageX1 = 0;
    IC.overscanImageY1 = 0;

    // Master Bias: enabled only if useBias=true
    IC.masterBiasEnabled = useBias ? true : false;
    IC.masterBiasPath    = ""; // будет заменён из группы, если есть

    IC.masterDarkEnabled = true;
    IC.masterDarkPath    = ""; // будет заменён из группы, если есть
    IC.masterFlatEnabled = true;
    IC.masterFlatPath    = ""; // будет заменён из группы, если есть
    IC.calibrateBias = false;
    IC.calibrateDark = false;
    IC.calibrateFlat = false;
    IC.optimizeDarks               = false;
    IC.darkOptimizationThreshold   = 0.00000;
    IC.darkOptimizationLow         = 0.0000;
    IC.darkOptimizationWindow      = 1024;
//    IC.darkCFADetectionMode        = "DetectCFA";
    IC.separateCFAFlatScalingFactors = true;
    IC.flatScaleClippingFactor     = 0.05;
    IC.cosmeticCorrectionLow   = false;
    IC.cosmeticLowSigma        = 5;
    IC.cosmeticCorrectionHigh  = false;
    IC.cosmeticHighSigma       = 10;
    IC.cosmeticKernelRadius    = 1;
    IC.cosmeticShowMap         = false;
    IC.cosmeticShowMapAndStop  = false;
    IC.evaluateNoise           = true;
//    IC.noiseEvaluationAlgorithm= "NoiseEvaluation_MRS";
    IC__safeSetStringEnum(IC, "noiseEvaluationAlgorithm", "NoiseEvaluation_MRS");
    IC.evaluateSignal          = true;
    IC.structureLayers         = 5;
    IC.saturationThreshold     = 1.00;
    IC.saturationRelative      = false;
    IC.noiseLayers             = 1;
    IC.hotPixelFilterRadius    = 1;
    IC.noiseReductionFilterRadius = 0;
    IC.minStructureSize        = 0;
//    IC.psfType                 = "PSFType_Auto";
    IC__safeSetStringEnum(IC, "psfType",                 "PSFType_MoffatA");
    IC.psfGrowth               = 1.00;
    IC.maxStars                = 24576;
    IC.outputDirectory   = "";       // при выполнении подставим workFolders.calibrated, если задан
    IC.outputExtension   = ".xisf";
    IC.outputPrefix      = "";
    IC.outputPostfix     = "_c";
//    IC.outputSampleFormat= "f32";
    IC__safeSetStringEnum(IC, "outputSampleFormat",      "f32");
    IC.outputPedestal    = 0;
//    IC.outputPedestalMode= "OutputPedestal_Literal";
    IC.autoPedestalLimit = 0.00010;
    IC.generateHistoryProperties = true;
    IC.generateFITSKeywords      = true;
    IC.overwriteExistingFiles    = true;
//    IC.onError                   = "Continue";
    IC__safeSetStringEnum(IC, "onError",                 "Continue");
    IC.noGUIMessages             = true;
    IC.useFileThreads            = true;
    IC.fileThreadOverload        = 1.00;
    IC.maxFileReadThreads        = 0;
    IC.maxFileWriteThreads       = 0;
//    IC.cosmeticCorrectionMapId = "";
    // === END: your parameters ===
}

// ========================================================================
// PUBLIC API: Run Calibration
// ========================================================================
function IC_runCalibration(plan, workFolders, useBias){
    try{
        if (!plan || !plan.groups){
            Console.warningln("[cal] No plan.groups — skipping ImageCalibration.");
            return {totalProcessed: 0, totalSkipped: 0, groupNames: []};
        }

        // Resolve output base dir (optional)
        var outDirBase = "";
        if (workFolders && workFolders.calibrated) outDirBase = CU_norm(workFolders.calibrated);
        try{ if (outDirBase && !File.directoryExists(outDirBase)) File.createDirectory(outDirBase, true); }catch(_){}

        // Collect groups
        var gkeys = []; for (var k in plan.groups) if (plan.groups.hasOwnProperty(k)) gkeys.push(k);
        Console.noteln("[cal] Groups to process: " + gkeys.length + (useBias ? " (with Bias)" : " (without Bias)"));

        if (typeof ImageCalibration !== "function"){
            Console.warningln("[cal] ImageCalibration class not available. DRY-RUN.");
            for (var gi=0; gi<gkeys.length; gi++){
                var g = plan.groups[gkeys[gi]], lights = IC__groupLights(g);
                Console.writeln("  [dry] " + gkeys[gi] + " : " + lights.length + " files");
            }
            return {totalProcessed: 0, totalSkipped: 0, groupNames: []};
        }

        // Iterate groups
        for (var gi=0; gi<gkeys.length; gi++){
            var gkey = gkeys[gi];
            var g    = plan.groups[gkey];
            var lights = IC__groupLights(g);

            // resolve per-group masters ONLY from group
            var biasP = ""; try{ if (g.bias) biasP = CU_norm(g.bias); }catch(_){}
            var darkP = ""; try{ if (g.dark) darkP = CU_norm(g.dark); }catch(_){}
            var flatP = ""; try{ if (g.flat) flatP = CU_norm(g.flat); }catch(_){}

            // Fallback: parse from group key if fields absent
            if (!biasP || !darkP || !flatP){
                var m = IC__extractMastersFromKey(gkey);
                if (!biasP && m.bias) biasP = CU_norm(m.bias);
                if (!darkP && m.dark) darkP = CU_norm(m.dark);
                if (!flatP && m.flat) flatP = CU_norm(m.flat);
            }

            Console.noteln("\n[cal] Group " + (gi+1) + "/" + gkeys.length + ":");
            Console.writeln("      key   : " + gkey);
            Console.writeln("      lights: " + lights.length);
            Console.writeln("      outDir: " + (outDirBase ? outDirBase : "<auto>"));
            Console.writeln("      masters: " +
                (useBias ? (biasP ? "B" : "-") : "X") + (darkP ? "D" : "-") + (flatP ? "F" : "-"));

            if (!darkP || !flatP || (useBias && !biasP)){
                Console.warningln("      [cal] missing masters: " +
                    (useBias && !biasP ? "Bias " : "") + (!darkP ? "Dark " : "") + (!flatP ? "Flat " : "") +
                    "— group will still run if IC allows it.");
            }

            // --- пакетная калибровка всей группы за один запуск ---
            var IC = new ImageCalibration;
            IC_applyUserParams(IC, useBias, g);

            // per-group masters: set bias only if useBias=true
            try{
                if (useBias && biasP){
                    IC.masterBiasPath = biasP;
                    IC.masterBiasEnabled = true;
                } else {
                    IC.masterBiasPath = "";
                    IC.masterBiasEnabled = false;
                }
            }catch(_){}

            try{ if (darkP){ IC.masterDarkPath = darkP; IC.masterDarkEnabled = true; } }catch(_){}
            try{ if (flatP){ IC.masterFlatPath = flatP; IC.masterFlatEnabled = true; } }catch(_){}

            // output dir
            try{ if (outDirBase) IC.outputDirectory = outDirBase; }catch(_){}
            try{ if (outDirBase && !File.directoryExists(outDirBase)) File.createDirectory(outDirBase, true); }catch(_){}

            // собрать все кадры группы в таблицу [enabled, filePath]
            var rows = [];
            for (var i=0; i<lights.length; i++){
                var L = CU_norm(lights[i]);
                if (L && L.length) rows.push([true, L]);
            }
            try { IC.targetFrames = rows; }
            catch(eTF){ Console.criticalln("[cal] targetFrames failed: " + eTF); continue; }

            Console.writeln("      • Calibrating frames: " + rows.length);
            try{
                if (typeof IC.executeGlobal === "function"){
                    if (!IC.executeGlobal()) throw new Error("executeGlobal() returned false");
                } else if (typeof IC.executeOn === "function"){
                    // executeOn обрабатывает текущий view; для пакетной работы нужен executeGlobal
                    if (!IC.executeOn(null)) throw new Error("executeOn() returned false");
                } else {
                    throw new Error("No execute* method available");
                }
            } catch(runErr){
                Console.criticalln("[cal] FAILED group '" + gkey + "' :: " + runErr);
            }
        }

        Console.noteln("\n[cal] Done.");

        // Return empty statistics (processed count unknown in batch mode)
        return {totalProcessed: 0, totalSkipped: 0, groupNames: gkeys};
    } catch(e){
        Console.criticalln(String(e));
        throw e;
    }
}

// ============================================================================
// PART 3: UI-INTEGRATED RUNNER (like other modules)
// ============================================================================

function IC_runForAllGroups(params){
    // params: { plan, workFolders, useBias, dlg }
    var plan = params.plan;
    var workFolders = params.workFolders;
    var useBias = (params.useBias !== undefined) ? params.useBias : true;
    var dlg = params.dlg || null;

    if (!plan || !plan.groups){
        // Fallback: single row if no groups
        if (dlg){
            var r = dlg.addRow("ImageCalibration", "Apply masters to lights");
            try{
                if (typeof PP_setStatus === "function") PP_setStatus(dlg, r, PP_iconQueued());
                if (typeof processEvents === "function") processEvents();
            }catch(_){}
            try{
                if (typeof PP_setStatus === "function") PP_setStatus(dlg, r, PP_iconRunning());
                if (typeof processEvents === "function") processEvents();
            }catch(_){}
            var ok=true, err="", t0=Date.now();
            try{ IC_runCalibration(plan, workFolders, useBias); }catch(e){ ok=false; err=e.toString(); }
            var dt = Date.now()-t0;
            try{
                if (typeof formatElapsedMS === "function"){
                    dlg.updateRow(r, { elapsed: formatElapsedMS(dt), note: ok? "" : ("Failed: " + err) });
                }
                if (typeof PP_setStatus === "function") PP_setStatus(dlg, r, ok ? PP_iconSuccess() : PP_iconError());
                if (typeof processEvents === "function") processEvents();
            }catch(_){}
            if (!ok) throw new Error(err);
        } else {
            IC_runCalibration(plan, workFolders, useBias);
        }
        return {totalProcessed: 0, totalSkipped: 0, groupNames: []};
    }

    // Extract group keys
    var keys = [];
    try{
        if (plan.order && plan.order.length) keys = plan.order.slice(0);
        else for (var k in plan.groups) if (plan.groups.hasOwnProperty(k)) keys.push(k);
    }catch(_){}

    if (keys.length === 0){
        Console.warningln("[cal] No groups found in plan");
        return {totalProcessed: 0, totalSkipped: 0, groupNames: []};
    }

    Console.noteln("[cal] ImageCalibration for " + keys.length + " group(s)" + (useBias ? " (with Bias)" : " (without Bias)"));

    // Pre-add UI rows for all groups
    if (dlg){
        if (!dlg.icRowsMap) dlg.icRowsMap = {};
        for (var i=0; i<keys.length; i++){
            var gkey   = keys[i];
            var g      = plan.groups[gkey] || {};
            var frames = (g && g.lights && g.lights.length) ? g.lights.length : 0;
            var label  = gkey;

            // Format group key for UI (using CP__fmtGroupForUI if available)
            try{
                if (typeof CP__fmtGroupForUI === "function"){
                    label = CP__fmtGroupForUI(gkey);
                }
            }catch(_){}

            label += (frames ? (" ("+frames+" subs)") : "");

            var node = dlg.addRow("ImageCalibration", label);
            try{
                if (typeof PP_setStatus === "function") PP_setStatus(dlg, node, PP_iconQueued());
                if (typeof PP_setNote === "function") PP_setNote(dlg, node, frames ? (frames+"/"+frames+" queued") : "");
            }catch(_){}

            dlg.icRowsMap[gkey] = { node: node, frames: frames };
        }
        try{ if (typeof processEvents === "function") processEvents(); }catch(_){}
    }

    // Helper: build mini-plan for single group
    function __miniPlanFor(key){
        var mp = {};
        for (var prop in plan)
            if (plan.hasOwnProperty(prop) && prop !== "groups" && prop !== "order")
                mp[prop] = plan[prop];
        mp.groups = {}; mp.groups[key] = plan.groups[key];
        mp.order = [key];
        return mp;
    }

    // Process each group
    for (var j=0; j<keys.length; j++){
        var gkey = keys[j];
        var rec  = dlg ? dlg.icRowsMap[gkey] : null;
        var node = rec ? rec.node : null;

        // Update UI - Running
        if (node){
            try{
                if (typeof PP_setStatus === "function") PP_setStatus(dlg, node, PP_iconRunning());
                if (typeof PP_setNote === "function") PP_setNote(dlg, node, rec.frames ? ("0/"+rec.frames+" calibrating") : "calibrating");
                if (typeof processEvents === "function") processEvents();
            }catch(_){}
        }

        // Run calibration for this group
        var mp = __miniPlanFor(gkey);
        var okG = true, errG = "", t0 = Date.now();
        try{ IC_runCalibration(mp, workFolders, useBias); }catch(e){ okG=false; errG = e.toString(); }
        var dt = Date.now()-t0;

        // Update elapsed time
        if (node){
            try{
                if (typeof formatElapsedMS === "function"){
                    dlg.updateRow(node, { elapsed: formatElapsedMS(dt) });
                }
            }catch(_){}
        }

        // Try to get stats from PP_getLastImageCalibrationStats if available
        var processed = null, failed = null, gErr = null;
        if (typeof PP_getLastImageCalibrationStats === "function"){
            try{
                var S   = PP_getLastImageCalibrationStats();
                var per = S && (S.groups || S.byGroup || S.perGroup || S.results || S.map);
                var st  = per ? (per[gkey] || per[String(gkey)] || null) : null;
                if (st){
                    gErr      = st.error || st.err || st.message || null;
                    processed = st.processed || st.ok || st.calibrated || st.success || st.done || null;
                    failed    = st.failed || st.errors || st.bad || null;
                    if (processed==null && failed!=null) processed = Math.max(0, (rec.frames||0) - failed);
                    if (processed==null && !gErr)        processed = (rec.frames||0);
                    if (gErr) okG = false;
                }
            }catch(_){}
        }

        if (processed==null) processed = okG ? (rec ? rec.frames : 0) : 0;

        // Update UI - Complete
        if (node){
            try{
                if (typeof PP_setStatus === "function") PP_setStatus(dlg, node, okG ? PP_iconSuccess() : PP_iconError());
                if (typeof PP_setNote === "function") PP_setNote(dlg, node, okG ? (processed+"/"+(rec.frames||0)+" calibrated")
                    : ("Failed: " + (gErr || errG || "Unknown error")));
                if (typeof processEvents === "function") processEvents();
            }catch(_){}
        }

        if (!okG) throw new Error(errG || gErr || "ImageCalibration failed");
    }

    Console.noteln("[cal] All groups completed successfully");

    // Return statistics for notifications
    var totalFiles = 0;
    for (var ki=0; ki<keys.length; ki++){
        var gk = keys[ki];
        var gg = plan.groups[gk];
        if (gg && gg.lights) totalFiles += gg.lights.length;
    }

    return {
        totalProcessed: totalFiles,
        totalSkipped: 0,
        groupNames: keys
    };
}
