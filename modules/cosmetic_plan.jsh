/*
  modules/cosmetic_plan.jsh
  Build a CosmeticCorrection plan from the existing calibration PLAN + Calibrated folder.

  - Groups by DARK only (masterDarkPath).
  - For each light in calibration plan, predicts calibrated name in workFolders.calibrated:
      <basename(light)> + POSTFIX + EXTENSION
    defaults: POSTFIX="_c", EXTENSION=".xisf" (override via opts).

  Exposed API:
    CC_makeCosmeticPlan(calibPlan, workFolders, opts) -> planCC
    CC_printPlanSummary(planCC)
    CC_savePlan(planCC, jsonPath) -> bool
*/

// ---------- tiny helpers (no regex, no '//' in strings) ----------
function CPATH_norm(p){
    var s = String(p||"");
    // backslashes -> slashes
    while (true){
        var i = -1, k;
        for (k=0;k<s.length;k++){ if (s.charAt(k) === "\\"){ i = k; break; } }
        if (i < 0) break;
        s = s.substring(0,i) + "/" + s.substring(i+1);
    }
    // collapse duplicate slashes
    while (true){
        var j = -1, t;
        for (t=0; t<s.length-1; t++){ if (s.charAt(t)==="/" && s.charAt(t+1)==="/"){ j=t; break; } }
        if (j < 0) break;
        s = s.substring(0,j) + "/" + s.substring(j+2);
    }
    // upper-case drive letter
    if (s.length>2 && s.charAt(1)===":" && s.charAt(2)==="/"){
        var c=s.charAt(0); if (c>="a" && c<="z") s=c.toUpperCase()+s.substring(1);
    }
    return s;
}
function CPATH_basename(p){
    var s = CPATH_norm(p), k=-1, i; for (i=0;i<s.length;i++){ if (s.charAt(i)==="/") k=i; }
    return (k>=0) ? s.substring(k+1) : s;
}
function CPATH_stripExt(name){
    var k=-1,i; for(i=0;i<name.length;i++){ if(name.charAt(i)===".") k=i; }
    return (k>=0)? name.substring(0,k): name;
}
// ---------- разбор полного "паспорта" дарка ----------
// Собираем: Rig | Camera | Mode | G.. | OS.. | U.. | bin.. | ..C | ..s | YYYY_MM_DD
function CC__darkPassport(darkPath){
    var base = CPATH_basename(darkPath||"");
    var rig="", camera="", mode="", gain="", offset="", usb="", bin="", temp="", exp="", date="";
    var i,j,ch;

    // rig: всё до "MasterDark" (если встречается)
    var mdIdx = base.indexOf("MasterDark");
    if (mdIdx > 0){
        var cut = base.substring(0, mdIdx);
        if (cut.length>0 && cut.charAt(cut.length-1)==="_") cut = cut.substring(0,cut.length-1);
        rig = cut;
    }

    // дата: YYYY_MM_DD (три блока цифр через '_')
    for (i=0; i<base.length-9; i++){
        var ok=true, y="", m="", d="";
        for (j=0; j<4; j++){ ch=base.charAt(i+j); if (ch>="0"&&ch<="9"){ y+=ch; } else { ok=false; break; } }
        if (!ok) continue;
        if (base.charAt(i+4)!=="_"){ ok=false; }
        if (ok){ for (j=0; j<2; j++){ ch=base.charAt(i+5+j); if (ch>="0"&&ch<="9"){ m+=ch; } else { ok=false; break; } } }
        if (!ok) continue;
        if (base.charAt(i+7)!=="_"){ ok=false; }
        if (ok){ for (j=0; j<2; j++){ ch=base.charAt(i+8+j); if (ch>="0"&&ch<="9"){ d+=ch; } else { ok=false; break; } } }
        if (!ok) continue;
        date = y+"_"+m+"_"+d;
        break;
    }

    // camera: "QHY" + буквы/цифры (QHY600M и т.п.)
    for (i=0;i<base.length-2;i++){
        if (base.charAt(i)==="Q" && base.charAt(i+1)==="H" && base.charAt(i+2)==="Y"){
            j=i+3; var s="QHY";
            while(j<base.length){
                ch = base.charAt(j);
                var isA = (ch>="A"&&ch<="Z")||(ch>="a"&&ch<="z")||(ch>="0"&&ch<="9");
                if (isA){ s+=ch; j++; } else break;
            }
            camera = s; break;
        }
    }

    // mode: частые подписи
    var modeCandidates = [
        "High Gain Mode 16BIT","Low Gain Mode 16BIT","Extended Full Well 16BIT",
        "High Gain Mode 12BIT","Low Gain Mode 12BIT"
    ];
    for (i=0;i<modeCandidates.length;i++){
        var mstr = modeCandidates[i];
        var idx = base.indexOf(mstr);
        if (idx >= 0){ mode = mstr; break; }
    }

    // G..
    for (i=0;i<base.length-1;i++){
        if (base.charAt(i)==="G"){
            j=i+1; var digs=""; while(j<base.length){ ch=base.charAt(j); if (ch>="0"&&ch<="9"){ digs+=ch; j++; } else break; }
            if (digs.length){ gain="G"+digs; break; }
        }
    }
    // OS..
    for (i=0;i<base.length-2;i++){
        if (base.charAt(i)==="O" && base.charAt(i+1)==="S"){
            j=i+2; var d2=""; while(j<base.length){ ch=base.charAt(j); if (ch>="0"&&ch<="9"){ d2+=ch; j++; } else break; }
            if (d2.length){ offset="OS"+d2; break; }
        }
    }
    // U..
    for (i=0;i<base.length-1;i++){
        if (base.charAt(i)==="U"){
            j=i+1; var d3=""; while(j<base.length){ ch=base.charAt(j); if (ch>="0"&&ch<="9"){ d3+=ch; j++; } else break; }
            if (d3.length){ usb="U"+d3; break; }
        }
    }
    // bin.. (bin1x1, Bin2x2)
    for (i=0;i<base.length-3;i++){
        var c0=base.charAt(i), c1=base.charAt(i+1), c2=base.charAt(i+2);
        if ((c0==="B"||c0==="b") && c1==="i" && c2==="n"){
            j=i+3; var dx=""; while(j<base.length){ ch=base.charAt(j); if (ch>="0"&&ch<="9"){ dx+=ch; j++; } else break; }
            if (j<base.length && base.charAt(j)==="x"){
                j++; var dy=""; while(j<base.length){ ch=base.charAt(j); if (ch>="0"&&ch<="9"){ dy+=ch; j++; } else break; }
                if (dx.length && dy.length) { bin="bin"+dx+"x"+dy; break; }
            }
        }
    }
    // exp ..s
    for (i=0;i<base.length;i++){
        if (base.charAt(i)==="s"){
            j=i-1; var dd=""; while(j>=0){ ch=base.charAt(j); if (ch>="0"&&ch<="9"){ dd=ch+dd; j--; } else break; }
            if (dd.length){ exp=dd+"s"; break; }
        }
    }
    // temp ..C / -..C (берём ближайшую к концу)
    for (i=base.length-1;i>=0;i--){
        if (base.charAt(i)==="C"){
            j=i-1; var tnum=""; var neg=false;
            while(j>=0){ ch=base.charAt(j); if (ch>="0"&&ch<="9"){ tnum=ch+tnum; j--; } else break; }
            if (j>=0 && base.charAt(j)==="-") neg=true;
            if (tnum.length){ temp=(neg?"-":"")+tnum+"C"; break; }
        }
    }

    // собрать паспорт
    var parts=[];
    if (rig)    parts.push(rig);
    if (camera) parts.push(camera);
    if (mode)   parts.push(mode);
    if (gain)   parts.push(gain);
    if (offset) parts.push(offset);
    if (usb)    parts.push(usb);
    if (bin)    parts.push(bin);
    if (temp)   parts.push(temp);
    if (exp)    parts.push(exp);
    if (date)   parts.push(date);
    return parts.join("|");
}

function CC__splitPipes(s){
    var a=[], cur=""; var i; for(i=0;i<s.length;i++){ var ch=s.charAt(i); if (ch==="|"){ a.push(cur); cur=""; } else cur+=ch; }
    a.push(cur); return a;
}
function CC__jsonEscape(str){
    var s = String(str||""), out="", i, ch;
    for (i=0;i<s.length;i++){
        ch = s.charAt(i);
        if (ch === '"') out += '\\"';
        else if (ch === '\\') out += '\\\\';
        else if (ch === '\n') out += '\\n';
        else if (ch === '\r') out += '\\r';
        else if (ch === '\t') out += '\\t';
        else out += ch;
    }
    return out;
}

// ---------- calibrated naming defaults (match your ImageCalibration) ----------
var CCAL_OUTPUT_POSTFIX   = "_c";
var CCAL_OUTPUT_EXTENSION = ".xisf";

// ---------- group key by dark ----------
function CC__darkKey(darkPath){
    var passport = CC__darkPassport(darkPath);
    var base = CPATH_norm(darkPath||"");
    return passport && passport.length ? ("DARK::" + passport + "::" + base) : ("DARK::" + base);
}

// ---------- main builder ----------
function CC_makeCosmeticPlan(calibPlan, workFolders, opts){
    opts = opts || {};
    var outDir = (workFolders && workFolders.calibrated) ? CPATH_norm(workFolders.calibrated) : "";

    var postfix   = (typeof opts.outputPostfix   === "string" && opts.outputPostfix.length)   ? opts.outputPostfix   : CCAL_OUTPUT_POSTFIX;
    var extension = (typeof opts.outputExtension === "string" && opts.outputExtension.length) ? opts.outputExtension : CCAL_OUTPUT_EXTENSION;

    if (!calibPlan || !calibPlan.groups){
        Console.warningln("[cc-plan] No calibration plan groups. Nothing to do.");
        return { type:"cosmetic", created: new Date, calibratedDir: outDir, outputPostfix: postfix, outputExtension: extension, groups: {}, totals:{groups:0,files:0,missing:0} };
    }

    var gkeys=[], k;
    for (k in calibPlan.groups) if (calibPlan.groups.hasOwnProperty(k)) gkeys.push(k);

    var groups = {}; // darkKey -> { darkPath, items:[{path,exists}], total }
    var totalFiles = 0, missing = 0;

    for (var gi=0; gi<gkeys.length; gi++){
        var gkey = gkeys[gi];
        var g    = calibPlan.groups[gkey];

        // resolve dark
        var darkP = "";
        try{ if (g.masterDark) darkP = CPATH_norm(g.masterDark); }catch(_){}
        if (!darkP){
            // from key: ...|<bias>|<dark>|<flat>
            var parts = CC__splitPipes(String(gkey||""));
            if (parts.length >= 2) darkP = CPATH_norm(parts[parts.length-2]);
        }
        if (!darkP){
            Console.warningln("[cc-plan] Skipping group without dark: " + gkey);
            continue;
        }
        var dk = CC__darkKey(darkP);
        if (!groups[dk]) groups[dk] = { darkPath: darkP, items: [], total: 0 };

        // collect lights of this calibration group
        var lights = [];
        if (g.lights && g.lights.length) lights = g.lights.slice(0);
        else if (g.items && g.items.length){
            for (var ii=0; ii<g.items.length; ii++){
                var it = g.items[ii];
                if (typeof it === "string") lights.push(it);
                else if (it && it.path) lights.push(it.path);
            }
        } else if (g.frames && g.frames.length) lights = g.frames.slice(0);

        // map lights -> calibrated paths
        for (var li=0; li<lights.length; li++){
            var L = CPATH_norm(lights[li]); if (!L) continue;
            var base    = CPATH_stripExt(CPATH_basename(L));
            var outName = base + postfix + extension;
            var outPath = outDir ? (outDir + "/" + outName) : outName;

            var ex=false; try{ ex = File.exists(outPath); }catch(_){ ex=false; }
            if (!ex) missing++;

            groups[dk].items.push({ path: outPath, exists: ex });
            groups[dk].total++;
            totalFiles++;
        }
    }

    var gc=0; for (k in groups) if (groups.hasOwnProperty(k)) gc++;
    return {
        type: "cosmetic",
        created: new Date,
        calibratedDir: outDir,
        outputPostfix: postfix,
        outputExtension: extension,
        by: "cosmetic_plan.jsh",
        totals: { groups: gc, files: totalFiles, missing: missing },
        groups: groups
    };
}

// ---------- summary & save ----------
function CC_printPlanSummary(plan){
    Console.noteln("Cosmetic plan summary:");
    if (!plan || !plan.groups){ Console.warningln("  empty plan"); return; }
    Console.writeln("  Groups: " + plan.totals.groups + ", Files: " + plan.totals.files + ", Missing in Calibrated: " + plan.totals.missing);
    var shown = 0;
    for (var key in plan.groups){
        if (!plan.groups.hasOwnProperty(key)) continue;
        var g = plan.groups[key];
        var sample = (g.items && g.items.length) ? g.items[0].path : "<none>";
        Console.writeln("    • " + key + " — " + g.total + " files; sample: " + sample);
        shown++; if (shown >= 5) break;
    }
}

function CC_savePlan(plan, jsonPath){
    jsonPath = CPATH_norm(jsonPath||"");
    if (!jsonPath.length){ Console.warningln("[cc-plan] No path to save plan"); return false; }

    var s = "";
    s += "{\n";
    s += '  "type": "cosmetic",\n';
    s += '  "created": "' + CC__jsonEscape(String(plan.created)) + '",\n';
    s += '  "calibratedDir": "' + CC__jsonEscape(plan.calibratedDir||"") + '",\n';
    s += '  "outputPostfix": "' + CC__jsonEscape(plan.outputPostfix||"") + '",\n';
    s += '  "outputExtension": "' + CC__jsonEscape(plan.outputExtension||"") + '",\n';
    s += '  "totals": {"groups": ' + (plan.totals?plan.totals.groups:0) + ', "files": ' + (plan.totals?plan.totals.files:0) + ', "missing": ' + (plan.totals?plan.totals.missing:0) + '},\n';
    s += '  "groups": {\n';

    var firstGroup = true;
    for (var key in plan.groups){
        if (!plan.groups.hasOwnProperty(key)) continue;
        var g = plan.groups[key];

        if (!firstGroup) s += ",\n"; firstGroup = false;
        s += '    "' + CC__jsonEscape(key) + '": {\n';
        s += '      "darkPath": "' + CC__jsonEscape(g.darkPath||"") + '",\n';
        s += '      "total": ' + (g.total||0) + ',\n';
        s += '      "items": [\n';

        var i;
        for (i=0; i<g.items.length; i++){
            var it = g.items[i];
            s += '        {"path": "' + CC__jsonEscape(it.path||"") + '", "exists": ' + (it.exists? "true":"false") + '}';
            if (i < g.items.length-1) s += ",\n"; else s += "\n";
        }
        s += '      ]\n';
        s += '    }';
    }
    s += '\n  }\n';
    s += "}\n";

    var f = new File;
    try{
        f.createForWriting(jsonPath);
        f.outTextLn(s);
        f.close();
        Console.noteln("[cc-plan] Saved: " + jsonPath);
        return true;
    }catch(e){
        try{ f.close(); }catch(_){}
        Console.criticalln("[cc-plan] Save failed: " + e);
        return false;
    }
}
