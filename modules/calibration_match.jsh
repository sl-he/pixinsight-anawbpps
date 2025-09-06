// modules/calibration_match.jsh
// Strict in-memory matcher for ANAWBPPS.
// No regexes. Verbose tracing. Bias/Dark require usb; Flat uses setup+filter+binning.

// --- in-memory storage for the last built plan
var CM_LAST_PLAN = null;
function CM_GET_LAST_PLAN(){ return CM_LAST_PLAN; }

function _norm(p){ return String(p||"").replace(/\\/g,'/'); }
function _saveJSON(path, obj){
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
function _dateToKey(d){ // "YYYY-MM-DD" -> "YYYY-MM-DD"
    if (!d) return "";
    var s = String(d).trim();
    // accept "YYYY-MM-DD" or "YYYY-MM-DDTHH:MM:SS"
    var i = s.indexOf("T");
    return (i > 0) ? s.substring(0,i) : s;
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

// ---------- match filters ----------
function _sameSetup(a,b){ return a===b; }
function _sameStr(a,b){ return String(a||"")===String(b||""); }
function _sameInt(a,b){ return parseInt(a,10)===parseInt(b,10); }

// pick the closest (past) by date
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

function _isValidLight(L){
    // минимально необходимый набор (под вас): setup, object, filter, readout, gain, offset, usb, binning, tempC, exposureSec, date
    return !!(L && L.setup && L.object && L.filter && L.readout &&
        (L.gain!=null) && (L.offset!=null) && (L.usb!=null) &&
        L.binning && (L.tempC!=null) && (L.exposureSec!=null) && L.date);
}

function CM_buildPlanInMemory(lightsIndex, mastersIndex, savePath){
    Console.writeln("[plan] Build calibration plan…");

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

        var bPick = _pickBiasForLight(L, biases, /*verbose*/true);
        var dPick = _pickDarkForLight(L, darks,  /*verbose*/true);
        var fPick = _pickFlatForLight(L, flats,  /*verbose*/true);

        if (!bPick) Console.writeln("  [no bias match]");
        if (!dPick) Console.writeln("  [no dark match]");
        if (!fPick) Console.writeln("  [no flat match]");

        if (!bPick || !dPick || !fPick){
            if (!bPick) Console.writeln("  → NO BIAS for this light");
            if (!dPick) Console.writeln("  → NO DARK for this light");
            if (!fPick) Console.writeln("  → NO FLAT for this light");
            skipped.push({path:L.path, reason:"missing master match"});
            continue;
        }

        // ключ группы — по выбранным мастерфайлам + базовые параметры лайта:
        var biasPath = bPick.path, darkPath = dPick.path, flatPath = fPick.path;
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
                bias: biasPath, dark: darkPath, flat: flatPath,
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

    // <-- NEW: keep in memory and return
    CM_LAST_PLAN = out;
    return out;
}
