/*
  modules/cosmetic_run.jsh
  Runner for CosmeticCorrection by dark-group, using a prebuilt cosmetic plan.

  Public API:
    CC_applyUserParams(CC)                           // sets ALL params in XPSM order
    CC_runCosmetic_UI(planCC, workFolders, dlgOpt)   // run groups, optionally update progress dialog

  Notes:
    - One executeGlobal() per group (batch).
    - Uses workFolders.cosmetic as output directory when available.
    - No regex literals; comments use block style only.
*/

/* ---------- tiny helpers ---------- */
function CR_norm(p){
    var s = String(p||"");
    /* backslashes -> slashes */
    while (true){
        var i=-1,k; for(k=0;k<s.length;k++){ if (s.charAt(k) === "\\"){ i=k; break; } }
        if (i<0) break; s = s.substring(0,i) + "/" + s.substring(i+1);
    }
    /* collapse duplicated slashes */
    while (true){
        var j=-1,t; for(t=0; t<s.length-1; t++){ if (s.charAt(t)==="/" && s.charAt(t+1)==="/"){ j=t; break; } }
        if (j<0) break; s = s.substring(0,j) + "/" + s.substring(j+2);
    }
    /* uppercase drive letter */
    if (s.length>2 && s.charAt(1)===":" && s.charAt(2)==="/"){
        var c=s.charAt(0); if (c>="a" && c<="z") s=c.toUpperCase()+s.substring(1);
    }
    return s;
}
function CR_fmtHMS(sec){
    var t = Math.max(0, Math.floor(sec));
    var hh = Math.floor(t/3600), mm = Math.floor((t%3600)/60), ss = t%60;
    var pad=function(n){ return (n<10?"0":"")+n; };
    return pad(hh)+":"+pad(mm)+":"+pad(ss);
}
// Legacy guard: if some old code still calls _CR_processOneCCGroup(...), avoid ReferenceError
if (typeof _CR_processOneCCGroup !== "function"){
    function _CR_processOneCCGroup(){ /* no-op for legacy calls */ }
}

/* ---------- ALL parameters in XPSM order ---------- */
/*
      <parameter id="masterDarkPath"></parameter>
      <parameter id="outputDir"></parameter>
      <parameter id="outputExtension">.xisf</parameter>
      <parameter id="prefix"></parameter>
      <parameter id="postfix">_cc</parameter>
      <parameter id="generateHistoryProperties" value="false"/>
      <parameter id="overwrite" value="true"/>
      <parameter id="amount" value="1.00"/>
      <parameter id="cfa" value="false"/>
      <parameter id="useMasterDark" value="true"/>
      <parameter id="hotDarkCheck" value="true"/>
      <parameter id="hotDarkLevel" value="0.0012818"/>
      <parameter id="coldDarkCheck" value="true"/>
      <parameter id="coldDarkLevel" value="0.0000000"/>
      <parameter id="useAutoDetect" value="false"/>
      <parameter id="hotAutoCheck" value="true"/>
      <parameter id="hotAutoValue" value="3.0"/>
      <parameter id="coldAutoCheck" value="false"/>
      <parameter id="coldAutoValue" value="10.0"/>
      <parameter id="useDefectList" value="false"/>
*/
function CC_applyUserParams(CC){
    /* 1 */  CC.masterDarkPath = "";          /* will be set per group */
    /* 2 */  CC.outputDir = "";               /* will be set from workFolders.cosmetic */
    /* add alias for robustness across PI versions */
    CC.outputDirectory = "";         /* optional alias, ignored if not present */
    /* 3 */  CC.outputExtension = ".xisf";
    /* 4 */  CC.prefix = "";
    /* 5 */  CC.postfix = "_cc";
    /* 6 */  CC.generateHistoryProperties = true;
    /* 7 */  CC.overwrite = true;            /* and keep legacy alias if exists */
    CC.overwriteExistingFiles = true;
    /* 8 */  CC.amount = 1.00;
    /* 9 */  CC.cfa = false;
    /* 10 */ CC.useMasterDark = true;
    /* 11 */ CC.hotDarkCheck = true;
    /* 12 */ CC.hotDarkLevel = 0.0012818;
    /* 13 */ CC.coldDarkCheck = false;
    /* 14 */ CC.coldDarkLevel = 0.0000000;
    /* 15 */ CC.useAutoDetect = false;
    /* 16 */ CC.hotAutoCheck = true;
    /* 17 */ CC.hotAutoValue = 3.0;
    /* 18 */ CC.coldAutoCheck = false;
    /* 19 */ CC.coldAutoValue = 10.0;
    /* 20 */ CC.useDefectList = false;

    /* no extra fields here on purpose: stick 1:1 to XPSM */
}

/* ---------- small UI helpers (safe to call only if dlg provided) ---------- */
function CR_progressAddRowIfNeeded(dlg, label, total, opName){
    if (!dlg) return null;
    var n = null;
    try{
        if (typeof PP_addRowUnique === "function"){
            var opKey = String(opName||"CC") + "|" + String(label||"");
            n = PP_addRowUnique(dlg, "CosmeticCorrection", label, opKey);
        } else if (typeof dlg.addRow === "function"){
            n = dlg.addRow("CosmeticCorrection", label);
        }
        if (n){
            try { n.setText(2, "00:00:00"); }catch(_){}
            /* queued rows: keep note empty */
            try { if (dlg.setRowNote) dlg.setRowNote(n, ""); }catch(_){}
        }
    }catch(_){}
    return n;
}
function CR_progressFinishRow(dlg, node, okCount, total, skip, err, elapsedSec, statusText){
    if (!dlg || !node) return;
    try { node.setText(2, CR_fmtHMS(elapsedSec)); }catch(_){}
    try {
        if (dlg.setRowNote) dlg.setRowNote(node,
            okCount + "/" + total + " processed" +
            (skip>0 ? "; skip="+skip : "") +
            (err>0 ? "; err="+err : "")
        );
    }catch(_){}
    try { if (dlg.setRowStatus) dlg.setRowStatus(node, statusText||"✔ Success"); }catch(_){}

    /* process exactly ONE CC group; return on early-exit instead of 'continue' */
    function _CR_processOneCCGroup(dlg, planCC, gkey, gi, gkeys, __ccMap, __ccRows, outDirBase){
        var g    = planCC.groups[gkey];
        var total = 0;
        if (g && g.files && g.files.length) total = g.files.length;
        else if (g && g.items && g.items.length) total = g.items.length;
        else if (g && g.frames && g.frames.length) total = g.frames.length;

        var labelCore = String(gkey||"");
        try{
            if (typeof CC_fmtCosmeticGroupLabel === "function")
                labelCore = CC_fmtCosmeticGroupLabel(gkey);
        }catch(_){}
        var label = labelCore + " (" + total + " subs)";

        /* progress row (pre-added or created) */
        var node = null, rowInfo = null;
        if (dlg){
            try{ rowInfo = (__ccMap && __ccMap[gkey]) || null; }catch(_){ rowInfo=null; }
            if (rowInfo){ node = rowInfo.node; total = rowInfo.total; }
            if (!node){
                node = CR_progressAddRowIfNeeded(dlg, label, total, "CC");
            }
            if (node){
                try{ if (dlg.setRowStatus) dlg.setRowStatus(node, "▶ Running"); }catch(_){}
                try{ if (dlg.markRowStarted) dlg.markRowStarted(node); }catch(_){}
                try { if (dlg.setRowNote) dlg.setRowNote(n, ""); }catch(_){}
                try{ node.setText(2, "00:00:00"); }catch(_){}
                try{ processEvents(); }catch(_){}
            }
        }

        Console.noteln("");
        Console.noteln("[cc-run] Group " + (gi+1) + "/" + gkeys.length + ":");
        Console.writeln("         key   : " + gkey);

        var items = (g && g.items) ? g.items : [];
        /* re-resolve 'total' if necessary */
        if (!total) total = items.length;

        var tf = [];
        var skip = 0, missing = 0;
        for (var i=0;i<items.length;i++){
            var p = items[i] && items[i].path ? CR_norm(items[i].path) : "";
            if (!p){ skip++; continue; }
            var ex=false; try{ ex = File.exists(p); }catch(_){ ex=false; }
            if (ex) tf.push([true, p]); else missing++;
        }
        if (tf.length === 0){
            Console.warningln("[cc-run]   skip group: no existing files (missing="+missing+")");
            if (node) CR_progressFinishRow(dlg, node, 0, total, skip, missing, 0, "⨯ Cancelled");
            return;
        }
        if (missing>0) Console.warningln("[cc-run]   note: " + missing + " file(s) missing; proceeding with " + tf.length);

        /* process availability */
        if (typeof CosmeticCorrection !== "function"){
            Console.warningln("[cc-run]   CosmeticCorrection process not available — dry-run");
            if (node) CR_progressFinishRow(dlg, node, tf.length, total, skip, missing, 0, "✔ Success (dry)");
            return;
        }

        /* create and set params */
        var CC = new CosmeticCorrection;
        CC_applyUserParams(CC);
        /* overwrite compatibility */
        try { CC.overwriteExistingFiles = true; } catch(_){}
        try { CC.overwriteFiles        = true; } catch(_){}
        try { CC.overwrite             = true; } catch(_){}
        try { CC.replaceExistingFiles  = true; } catch(_){}

        /* output dir compatibility */
        try{
            if (outDirBase){
                CC.outputDir = outDirBase;
                CC.outputDirectory = outDirBase;
            }
        }catch(_){}

        var setOK = true;
        try{ CC.targetFrames = tf; }catch(_){ setOK = false; }
        if (!setOK){
            Console.criticalln("[cc-run]   targetFrames failed.");
            if (node) CR_progressFinishRow(dlg, node, 0, total, skip, tf ? tf.length : 0, 0, "✖ Error");
            return;
        }

        /* run */
        var gStart = Date.now();
        var okCount = 0, err = 0;
        var ok = CR_executeProcess(CC, function(onOk){ okCount=onOk; }, function(onErr){ err=onErr; }, node, total);
        var gElapsed = (Date.now() - gStart)/1000;
        var status = ok ? "✔ Success" : "✖ Error";
        if (node) CR_progressFinishRow(dlg, node, okCount, total, skip, err, gElapsed, status);
        try{ processEvents(); }catch(_){}
    }
    try { if (dlg.markRowFinished) dlg.markRowFinished(node, elapsedSec); }catch(_){}
    try { processEvents(); }catch(_){}
}

/* ---------- main runner ---------- */
function CC_runCosmetic_UI(planCC, workFolders, dlg /* optional */){
    if (!planCC || !planCC.groups){
        Console.warningln("[cc-run] No plan groups — nothing to do.");
        return;
    }

    /* resolve output directory for CC */
    var outDirBase = (workFolders && workFolders.cosmetic) ? CR_norm(workFolders.cosmetic) : "";
    try{ if (outDirBase && !File.directoryExists(outDirBase)) File.createDirectory(outDirBase, true); }catch(_){}

    /* collect group keys */
    var gkeys = []; var k;
    for (k in planCC.groups) if (planCC.groups.hasOwnProperty(k)) gkeys.push(k);

    Console.noteln("[cc-run] Groups to process: " + gkeys.length);

    /* pre-add all progress rows as queued (or reuse if pre-added in outer UI) */
    var __ccRows = [];
    var __ccMap  = {}; // map by group key -> {node,total}
    if (dlg && dlg.ccRowsMap){
        /* rows were pre-added in script.js — reuse them */
        for (var __i=0; __i<gkeys.length; __i++){
            var __k = gkeys[__i];
            var info = dlg.ccRowsMap[__k] || null;
            if (info && info.node) { __ccMap[__k] = info; __ccRows.push({ key:__k, node:info.node, total:info.total||0 }); }
        }
        try{ processEvents(); }catch(_){}
    } else if (dlg){
        /* fallback: add here if not pre-added */
        for (var __i=0; __i<gkeys.length; __i++){
            var __k = gkeys[__i];
            var __g = planCC.groups[__k];
            var __total = (__g && __g.files && __g.files.length) ? __g.files.length :
                (__g && __g.items && __g.items.length) ? __g.items.length :
                    (__g && __g.frames && __g.frames.length) ? __g.frames.length : 0;
            var __labelCore = String(__k||"");
            if (__labelCore.indexOf("DARK::") === 0){
                var __s = __labelCore, __first=-1, __second=-1, __j;
                for (__j=0; __j<__s.length-1; __j++){ if (__s.charAt(__j)===":" && __s.charAt(__j+1)===":"){ __first=__j; break; } }
                if (__first>=0){ for (__j=__first+2; __j<__s.length-1; __j++){ if (__s.charAt(__j)===":" && __s.charAt(__j+1)===":"){ __second=__j; break; } } }
                if (__second>=0) __labelCore = __s.substring(__first+2, __second);
            }
            var __label = __labelCore + " (" + __total + " subs)";
            var __node = CR_progressAddRowIfNeeded(dlg, __label, __total, "CC");
            /* ensure queued rows have empty note */
            try{ if (dlg.setRowNote) dlg.setRowNote(__node, ""); }catch(_){}
            if (!dlg.ccRowsMap) dlg.ccRowsMap = {};
            __ccMap[__k] = { node: __node, total: __total };
            __ccRows.push({ key:__k, node:__node, total:__total });
        }
        try{ processEvents(); }catch(_){}
    }
    var totalStart = Date.now();

    __CC_GROUPS__: for (var gi=0; gi<gkeys.length; gi++){
        var gkey = gkeys[gi];
        var g    = planCC.groups[gkey];

        var items = (g && g.items) ? g.items : [];
        var total = (g && g.total) ? g.total : items.length;
        var darkP = (g && g.darkPath) ? CR_norm(g.darkPath) : "";

        /* label for progress (passport if key looks like DARK::passport::path) */
        var labelCore = String(gkey||"");
        if (labelCore.indexOf("DARK::") === 0){
            var s = labelCore, first=-1, second=-1, i;
            for (i=0;i<s.length-1;i++){ if (s.charAt(i)===":" && s.charAt(i+1)===":"){ first=i; break; } }
            if (first>=0){ for (i=first+2;i<s.length-1;i++){ if (s.charAt(i)===":" && s.charAt(i+1)===":"){ second=i; break; } } }
            if (second>=0) labelCore = s.substring(first+2, second);
        }
        var label = labelCore + " (" + total + " subs)";

                /* progress row (pre-added or reused) */
                    var node = null, rowInfo = null;
                if (dlg){ try{ rowInfo = __ccMap[gkey] || (__ccRows[gi]||null); }catch(_){ rowInfo=null; } }
                if (rowInfo){ node = rowInfo.node; total = rowInfo.total; }
        if (dlg && node && typeof dlg.markRowStarted === "function")
            dlg.markRowStarted(node);
        /* switch UI from Queued -> Running explicitly */
        if (dlg && node){
            try{ if (dlg.setRowStatus) dlg.setRowStatus(node, "▶ Running"); }catch(_){}
            try{ if (dlg.markRowStarted) dlg.markRowStarted(node); }catch(_){}
            try{ if (dlg.setRowNote) dlg.setRowNote(node, "0/"+total+" processing"); }catch(_){}
            try{ processEvents(); }catch(_){}
        }

//                if (dlg && node && dlg.setRowStatus) try{ dlg.setRowStatus(node, "▶ Running"); }catch(_){}
                Console.noteln("");
        Console.noteln("[cc-run] Group " + (gi+1) + "/" + gkeys.length + ":");
        Console.writeln("         key   : " + gkey);
        Console.writeln("         dark  : " + darkP);
        Console.writeln("         files : " + total);
        Console.writeln("         outDir: " + (outDirBase||"<current>"));

        /* build targetFrames: only existing files */
        var tf = [];
        var missing=0, skip=0;
        for (var i=0;i<items.length;i++){
            var p = items[i] && items[i].path ? CR_norm(items[i].path) : "";
            if (!p){ skip++; continue; }
            var ex=false; try{ ex = File.exists(p); }catch(_){ ex=false; }
            if (ex) tf.push([true, p]); else missing++;
        }
        if (tf.length === 0){
                        Console.warningln("[cc-run]   skip group: no existing files (missing="+missing+")");
                        if (node) CR_progressFinishRow(dlg, node, 0, total, skip, missing, 0, "⨯ Cancelled");
            continue;
        }
        if (missing>0) Console.warningln("[cc-run]   note: " + missing + " file(s) missing; proceeding with " + tf.length);

        /* process availability */
        if (typeof CosmeticCorrection !== "function"){
                        Console.warningln("[cc-run]   CosmeticCorrection process not available — dry-run");
                        if (node) CR_progressFinishRow(dlg, node, tf.length, total, skip, missing, 0, "✔ Success (dry)");
            continue;
        }

        /* configure process */
        var CC = new CosmeticCorrection;
        CC_applyUserParams(CC);                 /* set all fields per XPSM */
            // overwrite compatibility
             try { CC.overwriteExistingFiles = true; } catch(_){}
            try { CC.overwriteFiles        = true; } catch(_){}
            try { CC.overwrite             = true; } catch(_){}
            try { CC.replaceExistingFiles  = true; } catch(_){}
        if (darkP){ CC.masterDarkPath = darkP; CC.useMasterDark = true; }

        if (outDirBase){
            CC.outputDir = outDirBase;
            /* alias for old builds, safe to set too */
            CC.outputDirectory = outDirBase;
        }

        var setOK = true;
        try{ CC.targetFrames = tf; }catch(_){ setOK = false; }
        if (!setOK){
                        Console.criticalln("[cc-run]   targetFrames failed.");
                        if (node) CR_progressFinishRow(dlg, node, 0, total, skip, tf.length, 0, "✖ Error");
            continue;
        }

        /* run */
        var gStart = Date.now();
        var ok = true;
        try{
            if (typeof CC.executeGlobal === "function") ok = !!CC.executeGlobal();
            else if (typeof CC.executeOn === "function") ok = !!CC.executeOn(null);
            else throw new Error("No execute* method available");
        }catch(runErr){
            ok = false;
        }

        var gElapsed = (Date.now() - gStart)/1000;
        if (ok){
            Console.noteln("[cc-run]   ✔ Success in " + CR_fmtHMS(gElapsed));
            if (node) {
                CR_progressFinishRow(dlg, node, tf.length, total, skip, missing, gElapsed, "✔ Success");
                if (dlg && typeof dlg.markRowFinished === "function") dlg.markRowFinished(node, gElapsed);
            }
        } else {
            Console.criticalln("[cc-run]   ✖ Error in " + CR_fmtHMS(gElapsed));
            if (node) {
                CR_progressFinishRow(dlg, node, 0, total, skip, tf.length, gElapsed, "✖ Error");
                if (dlg && typeof dlg.markRowFinished === "function") dlg.markRowFinished(node, gElapsed);
            }
        }

        /* cancel between groups */
        if (dlg && dlg.cancelled){
            var rest;
            for (rest=gi+1; rest<gkeys.length; rest++){
                try{
                    var restKey = gkeys[rest];
                    var restG   = planCC.groups[restKey];
                    var restTotal = (restG && restG.total) ? restG.total : (restG && restG.items ? restG.items.length : 0);
                    var restLabel = String(restKey||"");
                    if (restLabel.indexOf("DARK::") === 0){
                        var s2=restLabel, f=-1, se=-1, ii;
                        for (ii=0;ii<s2.length-1;ii++){ if (s2.charAt(ii)===":" && s2.charAt(ii+1)===":"){ f=ii; break; } }
                        if (f>=0){ for (ii=f+2;ii<s2.length-1;ii++){ if (s2.charAt(ii)===":" && s2.charAt(ii+1)===":"){ se=ii; break; } } }
                        if (se>=0) restLabel = s2.substring(f+2, se);
                    }
                    restLabel = restLabel + " ("+restTotal+" subs)";
                    var restNode = CR_progressAddRowIfNeeded(dlg, restLabel, restTotal, "CC");
                    if (restNode){
                        try { if (dlg.setRowStatus) dlg.setRowStatus(restNode, "⨯ Cancelled"); }catch(_){}
                        try { if (dlg.setRowNote) dlg.setRowNote(restNode, ""); }catch(_){}
                        try { restNode.setText(2, "00:00:00"); }catch(_){}
                        try { if (dlg.markRowFinished) dlg.markRowFinished(restNode, 0); }catch(_){}
                    }
                }catch(_){}
            }
            break;
        }
        /* обработать текущую группу здесь, пока мы внутри цикла */
            _CR_processOneCCGroup(dlg, planCC, gkey, gi, gkeys, __ccMap, __ccRows, outDirBase);
        }


    var totalSec = (Date.now() - totalStart)/1000;
    Console.noteln("");
    Console.noteln("[cc-run] Done. Total: " + CR_fmtHMS(totalSec));
    // Переводим кнопку в DONE, если не было отмены
    try {
        if (dlg && !dlg.cancelled && typeof dlg.setDone === "function")
            dlg.setDone();
    } catch(_){}
}
