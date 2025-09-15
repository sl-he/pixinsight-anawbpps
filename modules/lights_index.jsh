/*=============================================================================
  modules/lights_index.jsh
  Purpose:
    Reindex light frames (FITS/XISF) recursively using header-only parsing
    (LI_parseLight). No filename parsing is used here.
  Notes:
    - Uses the same directory walking strategy as v0.2.0 (masters):
      * Try global _listNames (if masters module provided it).
      * Else use LI_listNames:
          - System.readDirectory
          - Windows fallback via 'cmd /c dir /b' + temporary file.
    - Writes JSON into <lightsRoot>/lights_index.json
    - All helpers are prefixed with LI_ to avoid redeclarations.
=============================================================================*/

/* ---------------- Small helpers (unique names) ---------------- */

function LI_norm(p){ return String(p||"").replace(/\\/g,'/'); }

function LI_ext(path){
    var s = String(path);
    var i = s.lastIndexOf('.');
    return (i >= 0) ? s.substring(i+1).toLowerCase() : "";
}

function LI_isFitsLike(path){
    var e = LI_ext(path);
    return e === "fit" || e === "fits" || e === "xisf";
}

function LI_isDir(path){
    try{ return File.directoryExists(path); } catch(_){ return false; }
}

function LI_isFile(path){
    try{
        if (!File.exists(path)) return false;
        return !File.directoryExists(path);
    } catch(_){ return false; }
}

function LI_basename(p){
    var s = String(p||"").replace(/\\/g,'/');
    var i = s.lastIndexOf('/');
    return (i>=0) ? s.substring(i+1) : s;
}

function LI_saveJSON(path, obj){
    if (!path) return false;
    try{
        var f = new File;
        f.createForWriting(path);
        f.outText(JSON.stringify(obj, null, 2));
        f.close();
        // Console.writeln("[lights] Index JSON saved: " + path); // <-- УДАЛИТЬ
        return true;
    } catch(e){
        Console.criticalln("[lights] Failed to save JSON: " + e);
        return false;
    }
}

/* ---------------- Directory listing (v0.2.0-compatible) ---------------- */

/* Private windows check (no regex) */
function LI_looksWindows(dir){
    if (!dir || dir.length < 2) return false;
    var c0 = dir.charAt(0), c1 = dir.charAt(1);
    return ((c0 >= 'A' && c0 <= 'Z') || (c0 >= 'a' && c0 <= 'z')) && c1 === ':';
}

/* Local listNames implementation (only used if global _listNames is missing) */
function LI_listNames(dir){
    dir = LI_norm(dir);

    // 1) System.readDirectory
    try{
        if (typeof System !== "undefined" && typeof System.readDirectory === "function"){
            var arr = System.readDirectory(dir);
            if (arr && arr.length){
                var out = [];
                for (var i=0; i<arr.length; ++i){
                    var n = String(arr[i]);
                    if (n !== "." && n !== "..") out.push(n);
                }
                return out;
            }
            return [];
        }
    } catch(_){}

    // 2) Windows fallback via 'cmd /c dir /b' into temp file, then read it
    try{
        if (LI_looksWindows(dir) && typeof System !== "undefined" && typeof System.execute === "function"){
            var tmp = dir + "/__anaw_lights_tmp_list.txt";
            System.execute('cmd /c dir /b "' + dir + '" > "' + tmp + '"');

            var txt = "";
            try{
                var tf = new TextFile;
                tf.openForReading(tmp);
                while (!tf.eof) txt += tf.readLine() + "\n";
                tf.close();
            } catch(e2){
                txt = "";
            }
            try{ File.remove(tmp); } catch(_){}

            if (txt){
                var lines = txt.replace(/\r/g,"").split("\n");
                var out2 = [];
                for (var j=0; j<lines.length; ++j){
                    var ln = lines[j].trim();
                    if (ln && ln !== "." && ln !== "..") out2.push(ln);
                }
                return out2;
            }
        }
    } catch(_){}

    // 3) Nothing worked
    return [];
}

/* Wrapper that prefers masters' global walker if present */
function LI_listNamesCompat(dir){
    if (typeof _listNames === "function"){
        try{ return _listNames(dir); } catch(_){}
    }
    return LI_listNames(dir);
}

function LI_walkDir(dir, cb){
    var names = LI_listNamesCompat(dir);
    for (var i=0;i<names.length;++i){
        var name = names[i];
        var full = LI_norm(dir + "/" + name);
        if (LI_isDir(full)){
            LI_walkDir(full, cb);
        } else if (LI_isFile(full)){
            cb(full);
        }
    }
}

/* ---------------- Public API ---------------- */
/**
 * LI_reindexLights
 * @param {string} rootPath  Lights root (e.g. D:/.../!!!WORK_LIGHTS)
 * @param {string} savePath  Optional JSON path to store index (or null/empty).
 *                           If empty, defaults to <rootPath>/lights_index.json
 *
 * Requires: LI_parseLight(fullPath) provided by modules/lights_parse.jsh
 */
function LI_reindexLights(rootPath, savePath){
    // ensure local declaration exists to avoid Warning [156]
    var LI_LAST_INDEX = (typeof LI_LAST_INDEX !== 'undefined') ? LI_LAST_INDEX : null;
    var lightsRoot = LI_norm(rootPath);
    Console.writeln("[lights] Reindex started: " + lightsRoot);

    var setups = {};  // setup -> {lights: count}
    var items  = [];  // detailed list

    var totalVisitedFiles = 0;
    var fitsCandidates    = 0;
    var parsedOK          = 0;
    var parseErrors       = 0;

    // Check walker availability
    var probe = LI_listNamesCompat(lightsRoot);
    if (!probe || probe.length === 0){
        // We still proceed; recursive walk will just visit none if listing is truly unavailable.
        if (!(typeof System !== "undefined" && typeof System.readDirectory === "function")){
            Console.warningln("[lights] System.readDirectory not available; will rely on fallback/global walker.");
        }
    }

    LI_walkDir(lightsRoot, function(fullPath){
        ++totalVisitedFiles;

        if (!LI_isFitsLike(fullPath))
            return;

        ++fitsCandidates;

        var base = LI_basename(fullPath);
        var info;
        try{
            if (typeof LI_parseLight !== "function")
                throw new Error("LI_parseLight(...) not found (include modules/lights_parse.jsh)");
            info = LI_parseLight(fullPath);  // header-only parser
        } catch(e){
            ++parseErrors;
            Console.criticalln("[lights] parse failed: " + base + " -> " + e);
            return;
        }

        ++parsedOK;

        // Log a compact line
        Console.writeln(
            "  " + base +
            " → setup=" + (info.setup||"null") +
            ", object=" + (info.object||"null") +
            ", filter=" + (info.filter||"null") +
            ", bin=" + (info.binning||"null") +
            ", gain=" + (info.gain===null?"null":info.gain) +
            ", offset=" + (info.offset===null?"null":info.offset) +
            ", usb=" + (info.usb===null?"null":info.usb) +
            ", exp=" + (info.exposureSec===null?"null":info.exposureSec) +
            ", tempSet=" + (info.tempSetC===null?"null":info.tempSetC) +
            ", temp=" + (info.tempC===null?"null":info.tempC) +
            ", date=" + (info.date||"null") +
            ", fLen=" + (info.focalLen===null?"null":info.focalLen)
        );

        // Count per setup (only if setup is defined)
        var setupKey = info.setup || "UNKNOWN_SETUP";
        if (!setups[setupKey]) setups[setupKey] = { lights:0 };
        ++setups[setupKey].lights;

        // Push item
        items.push({
            path: fullPath,
            filename: base,
            type: "LIGHT",
            setup: info.setup,
            telescope: info.telescope,
            camera: info.camera,
            object: info.object,
            filter: info.filter,
            binning: info.binning,
            gain: info.gain,
            offset: info.offset,
            usb: info.usb,
            readout: info.readout,
            exposureSec: info.exposureSec,
            tempSetC: info.tempSetC,
            tempC: info.tempC,
            date: info.date,
            focalLen: info.focalLen,
            width: info.width,
            height: info.height
        });
    });

    // Summary
    var setupNames = [];
    for (var k in setups) if (setups.hasOwnProperty(k)) setupNames.push(k);

    Console.writeln("");
    Console.writeln("Lights root: " + lightsRoot);
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
        Console.writeln("    lights: " + st.lights);
    }

    // Save
    var outPath = savePath && String(savePath).trim().length ? savePath : (lightsRoot + "/lights_index.json");
    LI_saveJSON(LI_norm(outPath), {
        lightsRoot: lightsRoot,
        generatedUTC: (new Date()).toISOString(),
        visitedFiles: totalVisitedFiles,
        fitsCandidates: fitsCandidates,
        parsedOK: parsedOK,
        parseErrors: parseErrors,
        setups: setups,
        items: items
    });
    try { this.LI_LAST_INDEX = {
        lightsRoot: lightsRoot,
        items: items
    }; } catch(_){}
    return {
        lightsRoot: lightsRoot,
        items: items,
        setups: setups
    };
}
