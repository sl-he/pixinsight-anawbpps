/*=============================================================================
  modules/masters_index.jsh
  Purpose:
    Reindex Masters library recursively. Version-agnostic directory listing:
      - System.readDirectory (preferred)
      - Windows 'cmd /c dir /b' fallback via temp file
    For each FITS/XISF file calls MP_parseMaster(path) from masters_parse.jsh.
    Logs per-file info and final per-setup summary. Optionally saves JSON index.
  Requirements:
    - Include modules/masters_parse.jsh BEFORE this module to provide
      MP_parseMaster(path)
=============================================================================*/

// Debug switch (kept for future use)
var MI_DEBUG = true;

/* -------------------- Small helpers -------------------- */

// Normalize path to forward slashes
function _norm(p){ return String(p||"").replace(/\\/g,'/'); }

// Get lowercase extension without dot
function _ext(path){
    var s = String(path);
    var i = s.lastIndexOf('.');
    return (i >= 0) ? s.substring(i+1).toLowerCase() : "";
}

// FITS-like?
function _isFitsLike(path){
    var e = _ext(path);
    return e === "fit" || e === "fits" || e === "xisf";
}

// Exists and is directory?
function _isDir(path){
    try{ return File.directoryExists(path); } catch(_){ return false; }
}

// Exists and is file?
function _isFile(path){
    try{
        if (!File.exists(path)) return false;
        return !File.directoryExists(path);
    } catch(_){ return false; }
}

// Save JSON (pretty) using File API (works on PI 1.9.x)
function _saveJSON(path, obj){
    if (!path) return false;
    try{
        var f = new File;
        f.createForWriting(path);
        f.outText(JSON.stringify(obj, null, 2));
        f.close();
        Console.writeln("[masters] Index JSON saved: " + path);
        return true;
    } catch(e){
        Console.criticalln("[masters] Failed to save JSON: " + e);
        return false;
    }
}

// Safe Windows drive-letter detection without regex
function _looksWindows(dir){
    if (!dir || dir.length < 2) return false;
    var c0 = dir.charAt(0), c1 = dir.charAt(1);
    return ((c0 >= 'A' && c0 <= 'Z') || (c0 >= 'a' && c0 <= 'z')) && c1 === ':';
}

/* ---------------- Directory listing (names only) ---------------- */
// Return the last path component from a full path (handles / and \)
function _basename(p){
    if (!p) return "";
    var s = String(p);
    // normalize slashes just for splitting
    var i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
    return (i >= 0) ? s.substring(i+1) : s;
}

// Return array of entry *names* (no paths). Never throws.
// Tries System.readDirectory first; if empty or gives absolute paths,
// normalizes to names. Falls back to Windows cmd and writes tmp to %TEMP%.
// Return array of entry *names* (no paths). Never throws.
// Uses PixInsight's FileFind iterator (works in PI 1.9.x).
function _listNames(dir){
    dir = _norm(dir);
    var out = [];
    try{
        var ff = new FileFind;
        // перечисляем ВСЁ, и файлы, и каталоги
        if (ff.begin(dir + "/*")){
            do{
                var name = ff.name; // только имя, без пути
                if (name && name !== "." && name !== "..")
                    out.push(name);
            } while (ff.next());
            ff.end();
        }
    } catch(e){
        if (MI_DEBUG) Console.writeln("[masters] list(FileFind) failed: " + e);
    }
    return out;
}

// Try to derive setup from the path, if not present in headers/filename.
// It extracts the path segment immediately under !!!BIASES_LIB / !!!DARKS_LIB / !!!FLATS_LIB.
function _deriveSetupFromPath(fullPath){
    var p = _norm(fullPath);
    var markers = [ "!!!BIASES_LIB", "!!!DARKS_LIB", "!!!FLATS_LIB" ];
    for (var m = 0; m < markers.length; ++m){
        var i = p.toUpperCase().indexOf("/" + markers[m] + "/");
        if (i >= 0){
            var rest = p.substring(i + markers[m].length + 2); // skip "/MARKER/"
            var j = rest.indexOf("/");
            if (j >= 0) return rest.substring(0, j);
            return rest; // if file directly inside
        }
    }
    return null;
}
// Берём первый сегмент пути относительно корня mastersRoot.
// Пример: root=D:/MASTERS, full=D:/MASTERS/CYPRUS_FSQ/.../file.fit  -> "CYPRUS_FSQ"
function _setupFromRoot(root, full){
    var r = _norm(root), f = _norm(full);
    if (f.substring(0, r.length).toUpperCase() === r.toUpperCase()){
        var rel = f.substring(r.length);
        if (rel.charAt(0) === '/') rel = rel.substring(1);
        var i = rel.indexOf('/');
        if (i > 0) return rel.substring(0, i);
    }
    return null;
}

// Если вообще ничего не нашлось — берём имя родительской папки файла.
function _parentDirName(full){
    var s = _norm(full);
    var i = s.lastIndexOf('/');
    if (i <= 0) return null;
    var parent = s.substring(0, i);
    var j = parent.lastIndexOf('/');
    return (j >= 0) ? parent.substring(j+1) : parent;
}

/* ---------------- Recursive walker ---------------- */

function _walkDir(dir, cb){
    var names = _listNames(dir);
    for (var i=0;i<names.length;++i){
        var name = names[i];
        var full = _norm(dir + "/" + name);
        if (_isDir(full)){
            _walkDir(full, cb);
        } else if (_isFile(full)){
            cb(full);
        }
    }
}

/* ---------------- Very rough filename fallback parser ---------------- */

// Used only if MP_parseMaster(...) is not available.
function _fallbackParseByName(path){
    var name = File.extractNameAndExtension(path);
    var s = name.toUpperCase();
    var type = s.indexOf("MASTERBIAS")>=0 ? "BIAS" :
        s.indexOf("MASTERDARK")>=0 ? "DARK" :
            (s.indexOf("MASTERF")>=0 || s.indexOf("MASTERFLAT")>=0 ? "FLAT" : "UNKNOWN");

    // setup: everything before first "_Master"
    var setup = "UNKNOWN_SETUP";
    var m = name.match(/^(.+?)_Master/i);
    if (m) setup = m[1];

    // binning
    var bin = "";
    var mb = name.match(/(?:Bin)?(\d+)x(\d+)/i);
    if (mb) bin = mb[1] + "x" + mb[2];

    // filter for flats
    var filter = "";
    var mf = name.match(/_(L|R|G|B|HA|H\-ALPHA|OIII|O3|SII|S2)_/i);
    if (mf) filter = mf[1].toUpperCase();

    // gain/offset/usb/temp
    var mg = name.match(/_G(\d+)/i);  var og = mg ? parseInt(mg[1],10) : null;
    var mo = name.match(/_OS(\d+)/i); var oo = mo ? parseInt(mo[1],10) : null;
    var mu = name.match(/_U(\d+)/i); var ou = mu ? parseInt(mu[1],10) : null;
    var mt = name.match(/_(-?\d+)C/i);var tt = mt ? (parseInt(mt[1],10)+"C") : "";

    return {
        path: path,
        // identity
        setup: setup,
        type: type,
        // optics/cam
        filter: filter || null,
        binning: bin || "",
        gain: (og != null) ? og : null,
        offset: (oo != null) ? oo : null,
        usb: (ou != null) ? ou : null,
        tempC: tt || null,
        exposureSec: expSec != null ? expSec : null,
        readout: readout || null,
        date: dateStr || null
    };
}

/* ---------------- Public API ---------------- */

/**
 * MI_reindexMasters
 * @param {string} rootPath  Masters root (e.g. D:/.../!!!!!MASTERS)
 * @param {string} savePath  Optional JSON path to store index (or null/empty)
 */
function MI_reindexMasters(rootPath, savePath){
    // ensure local declaration exists to avoid Warning [156]
    var MI_LAST_INDEX = (typeof MI_LAST_INDEX !== 'undefined') ? MI_LAST_INDEX : null;
    var mastersRoot = _norm(rootPath);
    Console.writeln("[masters] Reindex started: " + mastersRoot);

    var setups = {};  // setup -> {biases, darks, flats}
    var items  = [];  // detailed list

    var totalVisitedFiles = 0;
    var fitsCandidates    = 0;
    var parsedOK          = 0;
    var parseErrors       = 0;

    _walkDir(mastersRoot, function(fullPath){
        ++totalVisitedFiles;

        // Только FITS/XISF
        if (!_isFitsLike(fullPath))
            return;

        // DarkFlats — исключаем по имени (позже добавим по хедерам)
        var upName = File.extractNameAndExtension(fullPath).toUpperCase();
        if (upName.indexOf("DARKFLAT") >= 0 || upName.indexOf("MASTERDF") >= 0 || upName.indexOf("DFLAT") >= 0)
            return;

        ++fitsCandidates;

        var base = File.extractNameAndExtension(fullPath);
        var info;
        try{
            if (typeof MP_parseMaster === "function")
                info = MP_parseMaster(fullPath);   // headers->filename
            else
                info = _fallbackParseByName(fullPath);

            // На будущее: если парсер начнёт возвращать DARKFLAT — тоже выкидываем
            if (info.type && String(info.type).toUpperCase() === "DARKFLAT")
                return;

        } catch(e){
            ++parseErrors;
            Console.criticalln("[masters] parse failed: " + base + " -> " + e);
            return;
        }

        // Если setup не пришёл из хедеров/имени — берём из относительного пути от корня.
        if (!info.setup || info.setup === ""){
            var s1 = _setupFromRoot(mastersRoot, fullPath);
            if (s1) info.setup = s1;
            else {
                // совсем последний шанс — имя родительской папки
                var s2 = _parentDirName(fullPath);
                if (s2) info.setup = s2;
            }
        }

        ++parsedOK;

        Console.writeln(
            "  " + base +
            " → type=" + info.type +
            ", filter=" + info.filter +
            ", bin=" + info.binning +
            ", gain=" + info.gain +
            ", offset=" + info.offset +
            ", usb=" + info.usb +
            ", tempC=" + info.tempC +
            (info.exposureSec != null ? (", exp=" + info.exposureSec) : "") +
            (info.readout ? (", readout=" + info.readout) : "")
        );

        var setupKey = info.setup || "UNKNOWN_SETUP";
        if (!setups[setupKey]) setups[setupKey] = { biases:0, darks:0, flats:0 };

        if (info.type === "BIAS") ++setups[setupKey].biases;
        else if (info.type === "DARK") ++setups[setupKey].darks;
        else if (info.type === "FLAT") ++setups[setupKey].flats;

        // strict required fields for matching
        var needs = ["setup","readout","gain","offset","binning","tempC"];
        if (info.type === "DARK") needs.push("exposureSec");
        if (info.type === "FLAT") needs = ["setup","filter","binning"]; // flats logic
        // USB is also required for bias/dark (первая/вторая группа)
        if (info.type === "BIAS" || info.type === "DARK") needs.push("usb");

        for (var q=0; q<needs.length; ++q){
            var k = needs[q];
            if (info[k] === null || info[k] === "" || typeof info[k] === "undefined"){
                Console.warningln("[masters] Skip malformed " + info.type + ": missing " + k + " → " + File.extractNameAndExtension(fullPath));
                return; // skip this master
            }
        }
        // strict required fields for matching
        var needs;
        if (info.type === "FLAT"){
            needs = ["setup","filter","binning","date"];
        } else if (info.type === "BIAS"){
            needs = ["setup","readout","gain","offset","usb","binning","tempC","date"];
        } else if (info.type === "DARK"){
            needs = ["setup","readout","gain","offset","usb","binning","tempC","date","exposureSec"];
        } else {
            needs = []; // unknown type - можно отбросить ниже
        }

        for (var q=0; q<needs.length; ++q){
            var k = needs[q];
            if (info[k] === null || typeof info[k] === "undefined" || info[k] === ""){
                Console.warningln("[masters] Skip malformed " + info.type + ": missing " + k + " → " + File.extractNameAndExtension(fullPath));
                return; // skip this master
            }
        }

        items.push({
            type: info.type,
            setup: info.setup,
            filter: info.filter,
            binning: info.binning,
            gain: info.gain,
            offset: info.offset,
            usb: info.usb,            // ← вот эта строка
            tempC: info.tempC,
            exposureSec: info.exposureSec,
            readout: info.readout,
            date: info.date,
            path: info.path
        });

    });


    // Summary
    var setupNames = [];
    for (var k in setups) if (setups.hasOwnProperty(k)) setupNames.push(k);

    Console.writeln("");
    Console.writeln("Masters root: " + mastersRoot);
    Console.writeln("Generated: " + (new Date()).toUTCString());
    Console.writeln("Visited files: " + totalVisitedFiles);
    Console.writeln("FITS/XISF candidates: " + fitsCandidates);
    Console.writeln("Parsed OK: " + parsedOK + " | Parse errors: " + parseErrors);
    Console.writeln("Setups found: " + setupNames.length);
    Console.writeln("");

    for (var i=0; i<setupNames.length; ++i){
        var sName = setupNames[i];
        var st = setups[sName];
        Console.writeln("• " + sName);
        Console.writeln("    biases: " + st.biases);
        Console.writeln("    darks : " + st.darks);
        Console.writeln("    flats : " + st.flats);
    }

    if (savePath){
        _saveJSON(_norm(savePath), {
            mastersRoot: mastersRoot,
            generatedUTC: (new Date()).toISOString(),
            visitedFiles: totalVisitedFiles,
            fitsCandidates: fitsCandidates,
            parsedOK: parsedOK,
            parseErrors: parseErrors,
            setups: setups,
            items: items
        });
    }
    try { this.MI_LAST_INDEX = {
        mastersRoot: mastersRoot,
        items: items
    }; } catch(_){}
}
