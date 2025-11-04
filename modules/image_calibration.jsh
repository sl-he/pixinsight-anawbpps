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

function IC_norm(p){ return String(p||"").replace(/\\/g,'/'); }

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

function _parseISODateSafe(yyyy_mm_dd){
    var s = String(yyyy_mm_dd||"").trim();
    var y=0,m=0,d=0, i1=s.indexOf('-'); if (i1>0){ y = parseInt(s.substring(0,i1),10);
        var i2 = s.indexOf('-', i1+1); if (i2>i1){ m = parseInt(s.substring(i1+1,i2),10);
            d = parseInt(s.substring(i2+1),10);
        }
    }
    if (!(y>0 && m>0 && d>0)) return null;
    return new Date(y, m-1, d);
}

function _daysDiff(a,b){ return Math.round((a.getTime()-b.getTime())/(24*3600*1000)); }

function _pickFlatForLight(L, flats, verbose){
    var Ld = _parseISODateSafe(L.date);
    if (!Ld) return null;

    var cands = [];
    for (var i=0;i<flats.length;++i){
        var F = flats[i];
        if (F.type !== "FLAT") continue;
        if (F.setup !== L.setup) continue;
        if (F.filter !== L.filter) continue;
        if (F.binning !== L.binning) continue;
        var Fd = _parseISODateSafe(F.date);
        if (!Fd) continue;
        var dd = _daysDiff(Ld, Fd); // dd>=0 — flat в тот же день/раньше лайта
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
        var Bd=_parseISODateSafe(B.date), Ld=_parseISODateSafe(L.date);
        if (!Bd||!Ld) continue;
        var dd=_daysDiff(Ld,Bd); // >=0 — раньше/в тот же день
        pool.push({B:B, dd:dd});
    }
    if (!pool.length) return null;

    var prior=[], future=[];
    for (var k=0;k<pool.length;++k) (pool[k].dd>=0?prior:future).push(pool[k]);

    if (prior.length){
        prior.sort(function(a,b){
            if (a.dd!==b.dd) return a.dd-b.dd; // ближе
            // при одинаковой дистанции — более поздний (бОльшая дата)
            var ad=_parseISODateSafe(a.B.date), bd=_parseISODateSafe(b.B.date);
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
        var Dd=_parseISODateSafe(D.date), Ld=_parseISODateSafe(L.date);
        if (!Dd||!Ld) continue;
        var dd=_daysDiff(Ld,Dd);
        pool.push({D:D, dd:dd});
    }
    if (!pool.length) return null;

    var prior=[], future=[];
    for (var k=0;k<pool.length;++k) (pool[k].dd>=0?prior:future).push(pool[k]);

    if (prior.length){
        prior.sort(function(a,b){
            if (a.dd!==b.dd) return a.dd-b.dd;
            var ad=_parseISODateSafe(a.D.date), bd=_parseISODateSafe(b.D.date);
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
function _hasAll(obj, keys){
    for (var i=0;i<keys.length;++i){
        var k = keys[i];
        if (obj[k] === null || typeof obj[k] === "undefined" || obj[k] === "")
            return false;
    }
    return true;
}

function _isValidLight(L){
    return _hasAll(L, ["setup","readout","gain","offset","usb","binning","tempC","date","exposureSec","filter"]);
}

function _isValidBias(B){
    return _hasAll(B, ["setup","readout","gain","offset","usb","binning","tempC","date","type"]) && B.type==="BIAS";
}

function _isValidDark(D){
    return _hasAll(D, ["setup","readout","gain","offset","usb","binning","tempC","date","exposureSec","type"]) && D.type==="DARK";
}

function _isValidFlat(F){
    return _hasAll(F, ["setup","filter","binning","date","type"]) && F.type==="FLAT";
}

// ---------- match filters (unused but kept for compatibility) ----------
function _sameSetup(a,b){ return a===b; }
function _sameStr(a,b){ return String(a||"")===String(b||""); }
function _sameInt(a,b){ return parseInt(a,10)===parseInt(b,10); }

function _pickClosestPastByDate(targetDate, arr){
    var best = null;
    var bestDays = null;
    for (var i=0;i<arr.length;++i){
        var d = arr[i].date;
        var dd = _daysDiff(d, targetDate); // positive means arr[i] <= targetDate
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
        if (!_sameStr(L.readout, B.readout)) continue;
        if (!_sameInt(L.gain,   B.gain))    continue;
        if (!_sameInt(L.offset, B.offset))  continue;
        if (!_sameInt(L.usb,    B.usb))     continue;
        if (!_sameStr(L.binning,B.binning)) continue;
        if (!_sameInt(L.tempC,  B.tempC))   continue;
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
        if (!_sameStr(L.readout, D.readout)) continue;
        if (!_sameInt(L.gain,   D.gain))    continue;
        if (!_sameInt(L.offset, D.offset))  continue;
        if (!_sameInt(L.usb,    D.usb))     continue;
        if (!_sameStr(L.binning,D.binning)) continue;
        if (!_sameInt(L.tempC,  D.tempC))   continue;
        if (!_sameInt(L.exposureSec, D.exposureSec)) continue;
        out.push(D);
    }
    return out;
}

function _filterFlatsForLight(L, FLATS){
    var out = [];
    for (var i=0;i<FLATS.length;++i){
        var F = FLATS[i];
        if (!_isValidFlat(F)) continue;
        if (!_sameSetup(L.setup, F.setup)) continue;
        if (!_sameStr(L.filter, F.filter)) continue;
        if (!_sameStr(L.binning,F.binning)) continue;
        out.push(F);
    }
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

function IC__norm(p){
    var s = String(p||"");

    // backslashes -> forward slashes
    while (s.indexOf("\\") >= 0){
        var i = s.indexOf("\\");
        s = s.substring(0,i) + "/" + s.substring(i+1);
    }

    // collapse duplicate forward slashes
    var changed = true;
    while (changed){
        changed = false;
        for (var i=0; i < s.length-1; i++){
            if (s.charAt(i) === "/" && s.charAt(i+1) === "/"){
                s = s.substring(0,i) + "/" + s.substring(i+2);
                changed = true;
                break;
            }
        }
    }

    // upper-case drive letter (q:/ → Q:/)
    if (s.length > 2 && s.charAt(1) === ":" && s.charAt(2) === "/"){
        var c = s.charAt(0);
        if (c >= "a" && c <= "z")
            s = c.toUpperCase() + s.substring(1);
    }
    return s;
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
function IC_applyUserParams(IC, useBias){
    // === BEGIN: your parameters ===
    IC.enableCFA = false;
//    IC.cfaPattern = "Auto";
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
            return;
        }

        // Resolve output base dir (optional)
        var outDirBase = "";
        if (workFolders && workFolders.calibrated) outDirBase = IC__norm(workFolders.calibrated);
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
            return;
        }

        // Iterate groups
        for (var gi=0; gi<gkeys.length; gi++){
            var gkey = gkeys[gi];
            var g    = plan.groups[gkey];
            var lights = IC__groupLights(g);

            // resolve per-group masters ONLY from group
            var biasP = ""; try{ if (g.bias) biasP = IC__norm(g.bias); }catch(_){}
            var darkP = ""; try{ if (g.dark) darkP = IC__norm(g.dark); }catch(_){}
            var flatP = ""; try{ if (g.flat) flatP = IC__norm(g.flat); }catch(_){}

            // Fallback: parse from group key if fields absent
            if (!biasP || !darkP || !flatP){
                var m = IC__extractMastersFromKey(gkey);
                if (!biasP && m.bias) biasP = IC__norm(m.bias);
                if (!darkP && m.dark) darkP = IC__norm(m.dark);
                if (!flatP && m.flat) flatP = IC__norm(m.flat);
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
            IC_applyUserParams(IC, useBias);

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
                var L = IC__norm(lights[i]);
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
    } catch(e){
        Console.criticalln(String(e));
        throw e;
    }
}
