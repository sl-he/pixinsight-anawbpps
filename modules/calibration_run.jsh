/*
  modules/calibration_run.jsh
  ImageCalibration runner using inline parameters (no file I/O, no globals).
  - Parameters live in CAL_applyUserParams(IC) — edit there.
  - Lights and masters are taken ONLY from plan.groups[...] (triads).
  - Output directory: workFolders.calibrated if provided, else PI default near source.
*/

// ----------------- tiny helpers (no regex) -----------------
function CAL__safeSetStringEnum(IC, key, strVal){
    try{
        var cur = IC[key];            // прочитаем текущее значение
        if (typeof cur === "string")  // ставим ТОЛЬКО если у процесса тут строка
            IC[key] = String(strVal);
        // иначе пропускаем без ошибок
    }catch(_){}
}
function CAL__safeSetNumberEnum(IC, key, numVal){
    try{
        var cur = IC[key];
        if (typeof cur === "number")  // ставим ТОЛЬКО если тут число
            IC[key] = Number(numVal);
    }catch(_){}
}

function CAL__norm(p){
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

function CAL__groupLights(g){
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
function CAL__extractMastersFromKey(gkey){
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
function CAL_applyUserParams(IC){
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
    IC.masterBiasEnabled = true;
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
    CAL__safeSetStringEnum(IC, "noiseEvaluationAlgorithm", "NoiseEvaluation_MRS");
    IC.evaluateSignal          = true;
    IC.structureLayers         = 5;
    IC.saturationThreshold     = 1.00;
    IC.saturationRelative      = false;
    IC.noiseLayers             = 1;
    IC.hotPixelFilterRadius    = 1;
    IC.noiseReductionFilterRadius = 0;
    IC.minStructureSize        = 0;
//    IC.psfType                 = "PSFType_Auto";
    CAL__safeSetStringEnum(IC, "psfType",                 "PSFType_MoffatA");
    IC.psfGrowth               = 1.00;
    IC.maxStars                = 24576;
    IC.outputDirectory   = "";       // при выполнении подставим workFolders.calibrated, если задан
    IC.outputExtension   = ".xisf";
    IC.outputPrefix      = "";
    IC.outputPostfix     = "_c";
//    IC.outputSampleFormat= "f32";
    CAL__safeSetStringEnum(IC, "outputSampleFormat",      "f32");
    IC.outputPedestal    = 0;
//    IC.outputPedestalMode= "OutputPedestal_Literal";
    IC.autoPedestalLimit = 0.00010;
    IC.generateHistoryProperties = true;
    IC.generateFITSKeywords      = true;
    IC.overwriteExistingFiles    = true;
//    IC.onError                   = "Continue";
    CAL__safeSetStringEnum(IC, "onError",                 "Continue");
    IC.noGUIMessages             = true;
    IC.useFileThreads            = true;
    IC.fileThreadOverload        = 1.00;
    IC.maxFileReadThreads        = 0;
    IC.maxFileWriteThreads       = 0;
//    IC.cosmeticCorrectionMapId = "";
    // === END: your parameters ===
}

// ----------------- main runner -----------------
function CAL_runCalibration(plan, workFolders /* third arg ignored */){
    try{
        if (!plan || !plan.groups){
            Console.warningln("[cal] No plan.groups — skipping ImageCalibration.");
            return;
        }

        // Resolve output base dir (optional)
        var outDirBase = "";
        if (workFolders && workFolders.calibrated) outDirBase = CAL__norm(workFolders.calibrated);
        try{ if (outDirBase && !File.directoryExists(outDirBase)) File.createDirectory(outDirBase, true); }catch(_){}

        // Collect groups
        var gkeys = []; for (var k in plan.groups) if (plan.groups.hasOwnProperty(k)) gkeys.push(k);
        Console.noteln("[cal] Groups to process: " + gkeys.length);

        if (typeof ImageCalibration !== "function"){
            Console.warningln("[cal] ImageCalibration class not available. DRY-RUN.");
            for (var gi=0; gi<gkeys.length; gi++){
                var g = plan.groups[gkeys[gi]], lights = CAL__groupLights(g);
                Console.writeln("  [dry] " + gkeys[gi] + " : " + lights.length + " files");
            }
            return;
        }

        // Iterate groups
        for (var gi=0; gi<gkeys.length; gi++){
            var gkey = gkeys[gi];
            var g    = plan.groups[gkey];
            var lights = CAL__groupLights(g);

// resolve per-group masters ONLY from group
            var biasP = ""; try{ if (g.masterBias) biasP = CAL__norm(g.masterBias); }catch(_){}
            var darkP = ""; try{ if (g.masterDark) darkP = CAL__norm(g.masterDark); }catch(_){}
            var flatP = ""; try{ if (g.masterFlat) flatP = CAL__norm(g.masterFlat); }catch(_){}

// Fallback: parse from group key if fields absent
            if (!biasP || !darkP || !flatP){
                var m = CAL__extractMastersFromKey(gkey);
                if (!biasP && m.bias) biasP = CAL__norm(m.bias);
                if (!darkP && m.dark) darkP = CAL__norm(m.dark);
                if (!flatP && m.flat) flatP = CAL__norm(m.flat);
            }
            Console.noteln("\n[cal] Group " + (gi+1) + "/" + gkeys.length + ":");
            Console.writeln("      key   : " + gkey);
            Console.writeln("      lights: " + lights.length);
            Console.writeln("      outDir: " + (outDirBase ? outDirBase : "<auto>"));
            Console.writeln("      masters: " +
                (biasP ? "B" : "-") + (darkP ? "D" : "-") + (flatP ? "F" : "-"));

            if (!darkP || !flatP){
                Console.warningln("      [cal] missing masters: " +
                    (biasP? "" : "Bias ") + (darkP? "" : "Dark ") + (flatP? "" : "Flat ") + "— group will still run if IC allows it.");
            }

            // --- пакетная калибровка всей группы за один запуск ---
            var IC = new ImageCalibration;
            CAL_applyUserParams(IC);

            // per-group masters
            try{ if (biasP){ IC.masterBiasPath = biasP; IC.masterBiasEnabled = true; } }catch(_){}
            try{ if (darkP){ IC.masterDarkPath = darkP; IC.masterDarkEnabled = true; } }catch(_){}
            try{ if (flatP){ IC.masterFlatPath = flatP; IC.masterFlatEnabled = true; } }catch(_){}

            // output dir
            try{ if (outDirBase) IC.outputDirectory = outDirBase; }catch(_){}
            try{ if (outDirBase && !File.directoryExists(outDirBase)) File.createDirectory(outDirBase, true); }catch(_){}

            // собрать все кадры группы в таблицу [enabled, filePath]
            var rows = [];
            for (var i=0; i<lights.length; i++){
                var L = CAL__norm(lights[i]);
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
