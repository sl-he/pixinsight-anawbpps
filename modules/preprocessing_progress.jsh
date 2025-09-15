// preprocessing_progress.jsh
// Minimal, include-free progress UI + one-function stubs for each pipeline process.
// UI strings are English-only, timer shows hundredths. Total never pauses until finalize().
// All comments and UI strings are English-only.

/* ============================
   Status icons and helpers
   ============================ */
function PP_iconQueued(){  return "⏳ Queued";  }
function PP_iconRunning(){ return "▶ Running"; }
function PP_iconSuccess(){ return "✔ Success"; }
function PP_iconError(){   return "✖ Error";   }
function PP_setStatus(dlg, node, statusText){
    try{
        if (dlg && typeof dlg.updateRow === "function")
            dlg.updateRow(node, { status: statusText });
        else if (node && typeof node.setText === "function")
            node.setText(3, statusText);
    }catch(_){}
}
function PP_setNote(dlg, node, noteText){
    try{
        if (dlg && typeof dlg.updateRow === "function")
            dlg.updateRow(node, { note: noteText });
        else if (node && typeof node.setText === "function")
            node.setText(4, noteText);
    }catch(_){}
}
// -------- Helpers to summarize masters counts (B/D/F) robustly --------
function PP_guessMastersCounts(MI){
    var b=0,d=0,f=0;
    if (!MI) return {b:b,d:d,f:f};
    // 0) direct top-level arrays
    if (MI.biases && typeof MI.biases.length === "number") b = MI.biases.length;
    if (MI.darks  && typeof MI.darks.length  === "number") d = MI.darks.length;
    if (MI.flats  && typeof MI.flats.length  === "number") f = MI.flats.length;
    if (b||d||f) return {b:b,d:d,f:f};
    // 1) explicit pools
    if (MI._pools){
        b = (MI._pools.biases||[]).length;
        d = (MI._pools.darks ||[]).length;
        f = (MI._pools.flats ||[]).length;
        return {b:b,d:d,f:f};
    }
    if (MI.pools){
        b = (MI.pools.biases||[]).length;
        d = (MI.pools.darks ||[]).length;
        f = (MI.pools.flats ||[]).length;
        return {b:b,d:d,f:f};
    }
    if (MI.byType){
        b = (MI.byType.bias || MI.byType.biases || []).length;
        d = (MI.byType.dark || MI.byType.darks  || []).length;
        f = (MI.byType.flat || MI.byType.flats  || []).length;
        return {b:b,d:d,f:f};
    }
    // 1b) stats/summary objects
    if (MI.stats){
        b = MI.stats.biases || MI.stats.bias || 0;
        d = MI.stats.darks  || MI.stats.dark  || 0;
        f = MI.stats.flats  || MI.stats.flat  || 0;
        if (b||d||f) return {b:b,d:d,f:f};
    }
    if (MI.counts){
        b = MI.counts.B || MI.counts.biases || MI.counts.bias || 0;
        d = MI.counts.D || MI.counts.darks  || MI.counts.dark || 0;
        f = MI.counts.F || MI.counts.flats  || MI.counts.flat || 0;
        if (b||d||f) return {b:b,d:d,f:f};
    }
    // 2) items with type/kind/name heuristics
    var items = MI.items || MI.list || [];
    for (var i=0;i<items.length;i++){
        var it = items[i] || {};
        var t  = (it.type||it.kind||it.class||it.category||it.masterType||"").toString().toLowerCase();
        var n  = (it.name||it.file||it.path||it.fname||"").toString().toLowerCase();
        if (!t){
            if (n.indexOf("bias")>=0 || n.indexOf("masterbias")>=0) t="bias";
            else if (n.indexOf("dark")>=0 || n.indexOf("masterdark")>=0) t="dark";
            else if (n.indexOf("flat")>=0 || n.indexOf("masterf")>=0 || n.indexOf("masterflat")>=0) t="flat";
        }
        if (t.indexOf("bias")>=0) ++b;
        else if (t.indexOf("dark")>=0) ++d;
        else if (t.indexOf("flat")>=0 || t==="f") ++f;
    }
    return {b:b,d:d,f:f};
}


//============================
//0) Small helpers for group label formatting
//============================
function CP__splitByPipe(s){
    try{ return String(s).split("|"); }catch(_){ return [String(s)]; }
}
// Example input key:
//   CYPRUS_FSQ106_F3_QHY600M|Sivan 2|B|High Gain Mode 16BIT|56|40|50|1x1|0|30|<bias>|<dark>|<flat>
// Desired UI label:
//   CYPRUS_FSQ106_F3_QHY600M|Sivan 2|B|High Gain Mode 16BIT|G56|OS40|U50|bin1x1|0C|30s
function CP__fmtGroupForUI(gkey){
    var p = CP__splitByPipe(gkey);
    if (p.length < 10) return String(gkey);
    var cam = p[0], target = p[1], filt = p[2], mode = p[3];
    var g = p[4], os = p[5], u = p[6], bin = p[7], temp = p[8], exp = p[9];
    var G   = "G"+g;
    var OS  = "OS"+os;
    var U   = "U"+u;
    var BIN = (String(bin).indexOf("x")>=0 ? "bin" : "") + bin;
    var TEMP= (String(temp).indexOf("C")>=0 ? temp : (temp+"C"));
    var EXP = (String(exp).indexOf("s")>=0 ? exp  : (exp +"s"));
    return cam + "|" + target + "|" + filt + "|" + mode + "|" + G + "|" + OS + "|" + U + "|" + BIN + "|" + TEMP + "|" + EXP;
}
// Extract YYYY_MM_DD from DARK master path inside the group key
function CP__extractDarkDateFromKey(gkey){
    try{
        var p = CP__splitByPipe(gkey);
        // [0..9] — паспорт, [10]=bias, [11]=dark, [12]=flat
        if (p.length < 12) return "";
        var dark = String(p[11]||"");
        // Compact: 20240704 / 2024-07-04 / 2024_07_04
        var m = dark.match(/(20\d{2})[-_\.]?(0[1-9]|1[0-2])[-_\.]?([0-3]\d)/);
        if (m) return m[1]+"_"+m[2]+"_"+m[3];
        // Already split with separators; normalize to underscores
        m = dark.match(/(20\d{2}[-_\.](?:0[1-9]|1[0-2])[-_\.](?:[0-3]\d))/);
        if (m) return m[1].replace(/[-\.]/g, "_");
    }catch(_){}
    return "";
}

// Set elapsed time robustly (works with/without dlg.updateRow)
function PP_setElapsed(dlg, node, elapsedText){
    try{
        if (dlg && typeof dlg.updateRow === "function")
            dlg.updateRow(node, { elapsed: elapsedText });
        else if (node && typeof node.setText === "function")
            node.setText(2, elapsedText); // column 2 is Elapsed
    }catch(_){}
}

// --- Helpers to format CC label robustly from dark path ---
function __cc_getDarkPath(gkey, g){
    // 1) prefer explicit field from group
    if (g){
        var names = ["darkPath","dark","masterDark","path","file"];
        for (var i=0;i<names.length;i++){
            var v = g[names[i]];
            if (v && typeof v === "string" && v.length) return v;
        }
    }
    // 2) from gkey: last '::' segment is usually a full path
    try{
        var s = String(gkey);
        var idx = s.lastIndexOf("::");
        if (idx >= 0) return s.substring(idx+2);
        // fallback: greedy path with .xisf/.fits
        var m = s.match(/[A-Za-z]:[\\/][^|]*\.(xisf|fits|fit)/i);
        if (m) return m[0];
    }catch(_){}
    return "";
}

function __cc_labelFromDarkPath(darkPath){
    var full = String(darkPath||""); if (!full) return "";
    var p = full.replace(/\\/g,"/");
    var parts = p.split("/");
    var fnameWithExt = parts.length ? parts[parts.length-1] : "";
    var fname = fnameWithExt.replace(/\.(xisf|fits?|fts)$/i,"");
    var setup = parts.length>1 ? parts[parts.length-2] : "";

    // Tokenize by underscores (spaces остаются внутри токенов)
    var toks = fname.split("_");
    function findTok(pred){
        for (var i=0;i<toks.length;i++) if (pred(toks[i])) return toks[i];
        return "";
    }
    // MODE: первый токен, содержащий "Mode"
    var modeTok = findTok(function(t){ return /mode/i.test(t); });
    var mode = modeTok ? modeTok.replace(/[-]+/g," ").replace(/\s+/g," ").trim() : "";

    // Параметры из имени
    function pick(re){ var m=fname.match(re); return m?m[1]:""; }
    var g    = pick(/(?:^|_)G(\d+)(?:_|$)/i);
    var os   = pick(/(?:^|_)OS(\d+)(?:_|$)/i);
    var u    = pick(/(?:^|_)U(\d+)(?:_|$)/i);
    var bin  = pick(/(?:^|_)Bin(\d+x\d+)(?:_|$)/i);
    var expS = pick(/(?:^|_)(\d{1,4})s(?:_|$)/i);
    var tmpC = pick(/(?:^|_)(-?\d+)C(?:_|$)/i);

    // Дата (из имени или полного пути), формат YYYY_MM_DD
    var dm = (fname.match(/(20\d{2})[-_\.]?(0[1-9]|1[0-2])[-_\.]?([0-3]\d)/) ||
        p.match(/(20\d{2})[-_\.]?(0[1-9]|1[0-2])[-_\.]?([0-3]\d)/));
    var date = dm ? (dm[1]+"_"+dm[2]+"_"+dm[3]) : "";

    // Нормализация
    function nz(x){ return (x===0 || x==="0" || (x && String(x).length)) ? String(x) : ""; }
    var G   = nz(g)   ? ("G"+g) : "";
    var OS  = nz(os)  ? ("OS"+os) : "";
    var U   = nz(u)   ? ("U"+u) : "";
    var BIN = nz(bin) ? ("bin"+bin.toLowerCase()) : "";
    var TEMP= nz(tmpC)? (tmpC+"C") : "";
    var EXP = nz(expS)? (String(parseInt(expS,10))+"s") : "";

    // Итог: SETUP|MODE|G..|OS..|U..|bin..|TEMP|EXP|DATE
    var out = [];
    if (setup) out.push(setup);
    if (mode)  out.push(mode);
    if (G)     out.push(G);
    if (OS)    out.push(OS);
    if (U)     out.push(U);
    if (BIN)   out.push(BIN);
    if (TEMP)  out.push(TEMP);
    if (EXP)   out.push(EXP);
    if (date)  out.push(date);
    return out.join("|");
}
// Cosmetic group label for UI: SETUP|MODE|G..|OS..|U..|bin..|TEMP|EXP|YYYY_MM_DD
function CP__fmtCosmeticGroupLabel(gkey, g){
    var dark = __cc_getDarkPath(gkey, g);
    var core = __cc_labelFromDarkPath(dark);
    return core || String(gkey);
}

//============================
//1) Single-function process entry points
//(Real executable logic lives in process modules.)
//============================

function ReindexLights(lightsRoot, lightsJsonPath) {
    // Single entry point for Lights indexing: delegate to modules/lights_index.jsh
    return LI_reindexLights(lightsRoot, lightsJsonPath);
}

function ReindexCalibrationFiles(mastersRoot, mastersJsonPath) {
    // Single entry point for Masters indexing: delegate to modules/masters_index.jsh
    return MI_reindexMasters(mastersRoot, mastersJsonPath);
}

// Single entry point for ImageCalibration: delegate to modules/calibration_run.jsh
function RunImageCalibration(plan, options){
    if (typeof CAL_runCalibration !== "function")
        throw new Error("CAL_runCalibration(...) not found in calibration_run.jsh");
    var workFolders = (options && options.workFolders) ? options.workFolders : undefined;
    return CAL_runCalibration(plan, workFolders);
}

// Single entry point for CosmeticCorrection: delegate explicitly to modules/cosmetic_run.jsh
// Do not implement process logic here.
function RunCosmeticCorrection(plan, workFolders, dlg) {
    if (typeof CC_runCosmetic_UI !== "function")
        throw new Error("CC_runCosmetic_UI(...) not found in cosmetic_run.jsh");
    return CC_runCosmetic_UI(plan, workFolders, dlg || null);
}

function SubframeSelector_Measure() {
    // TODO: insert real SubframeSelector Measure code here.
}

function SubframeSelector_Output() {
    // TODO: insert real SubframeSelector Output code here.
}

function StarAlignment() {
    // TODO: insert real StarAlignment code here.
}

function LocalNormalization() {
    // TODO: insert real LocalNormalization code here.
}

function NSG() {
    // TODO: insert Real NSG (instead of LN) code here.
}

function ImageIntegration() {
    // TODO: insert real ImageIntegration code here.
}

function DrizzleIntegration() {
    // TODO: insert real DrizzleIntegration code here.
}

/* ============================
   2) Utilities
   ============================ */

function formatElapsedMS(ms) {
    if (ms < 0) ms = 0;
    var totalSeconds = Math.floor(ms / 1000);
    var hh = Math.floor(totalSeconds / 3600);
    var mm = Math.floor((totalSeconds % 3600) / 60);
    var ss = totalSeconds % 60;
    var hundredths = Math.floor((ms % 1000) / 10);

    function pad2(n) { return (n < 10 ? "0" : "") + n; }
    return pad2(hh) + ":" + pad2(mm) + ":" + pad2(ss) + "." + pad2(hundredths);
}

/* ============================
   3) Progress Dialog (no includes)
   ============================ */

function ProgressDialog() {
    this.__base__ = Dialog;
    this.__base__();

    var self = this;

    this.windowTitle = "ANAWBPPS — Progress";
    this.scaledMinWidth = 1400;
    this.scaledMinHeight = 1000;

    // Tree (table) – same columns as the screenshot.
    this.tree = new TreeBox(this);
    this.tree.alternateRowColor = true;
    this.tree.multipleSelection = false;
    this.tree.headerVisible = true;
    this.tree.rootDecoration = false;
    this.tree.numberOfColumns = 5;
    this.tree.headerSorting = false;

    this.tree.setHeaderText(0, "Operation");
    this.tree.setHeaderText(1, "Group involved");
    this.tree.setHeaderText(2, "Elapsed");
    this.tree.setHeaderText(3, "Status");
    this.tree.setHeaderText(4, "Note");

    // Reasonable initial widths (tweak freely later).
    this.tree.setColumnWidth(0, this.logicalPixelsToPhysical(200));
    this.tree.setColumnWidth(1, this.logicalPixelsToPhysical(700));
    this.tree.setColumnWidth(2, this.logicalPixelsToPhysical(100));
    this.tree.setColumnWidth(3, this.logicalPixelsToPhysical(100));
    this.tree.setColumnWidth(4, this.logicalPixelsToPhysical(200));

    // Bottom bar: Total timer (never pauses) + Cancel button.
    this.totalLabel = new Label(this);
    this.totalLabel.text = "Total: 00:00:00.00";
    //this.totalLabel.textAlignment = TextAlign_Left | TextAlign_VertCenter;
    // безопасное выставление выравнивания
    var _TA_L = (typeof TextAlign_Left       !== "undefined") ? TextAlign_Left       : 0;
    var _TA_V = (typeof TextAlign_VertCenter !== "undefined") ? TextAlign_VertCenter : 0;
    try { this.totalTimeLabel.textAlignment = _TA_L | _TA_V; } catch(_) {}
    this.cancelButton = new PushButton(this);
    this.cancelButton.text = "Cancel";
    this.cancelButton.onClick = function () {
        self.finalizePipeline(); // stop Total timer
        self.cancel();
    };

    // Layout
    this.bottomSizer = new HorizontalSizer;
    this.bottomSizer.spacing = 6;
    this.bottomSizer.add(this.totalLabel, 100);
    this.bottomSizer.addSpacing(8);
    this.bottomSizer.add(this.cancelButton, 0);

    this.sizer = new VerticalSizer;
    this.sizer.margin = 8;
    this.sizer.spacing = 8;
    this.sizer.add(this.tree, 100);
    this.sizer.add(this.bottomSizer);

    // --- Total timer (independent high-level clock) ---
    this._t0 = 0;
    this._totalTimer = new Timer;
    this._totalTimer.period = 30; // ~33 fps; hundredths stay smooth
    this._totalTimer.onTimeout = function () {
        var ms = new Date().getTime() - self._t0;
        self.totalLabel.text = "Total: " + formatElapsedMS(ms);
    };

    this.startTotal = function () {
        this._t0 = new Date().getTime();
        this._totalTimer.start();
    };

    this.finalizePipeline = function () {
        if (this._totalTimer && this._totalTimer.enabled)
            this._totalTimer.stop();
    };

    // Helpers for future: create and manage rows
    this.addRow = function (operationText, groupText) {
        var item = new TreeBoxNode(this.tree);
        item.setText(0, operationText || "");
        item.setText(1, groupText || ""); // Group
        item.setText(2, "00:00:00.00");
        item.setText(3, "Queued");
        item.setText(4, ""); // Note
        return item;
    };
    this.updateRow = function (item, fields) {
        if (!item) return;
        if (fields.group   !== undefined) item.setText(1, fields.group);
        if (fields.elapsed !== undefined) item.setText(2, fields.elapsed);
        if (fields.status  !== undefined) item.setText(3, fields.status);
        if (fields.note    !== undefined) item.setText(4, fields.note);
    };
    // For completeness if you later want to reset the grid:
    this.clearRows = function () {
        this.tree.clear();
    };
}
ProgressDialog.prototype = new Dialog;
//============================
//2) Progress UI orchestrators & generic row runner
//(No process logic here; only UI + timing.)
//============================

// Open the Progress UI and start the Total timer.
function PP_openProgressUI(){
    var dlg = new ProgressDialog();
    if (!dlg._t0) dlg._t0 = Date.now();
    dlg._totalTimer.start();
    try { dlg.show(); } catch(_){}
    return dlg;
}

// Stop Total timer (call once at the very end of the whole pipeline).
function PP_finalizeProgress(dlg){
    if (dlg && typeof dlg.finalizePipeline === "function")
        dlg.finalizePipeline();
}

/* ---------- Generic one-liner runner for any step (universal row renderer) ---------- */
// Runs `stepFn()`, paints a row with op/group, measures elapsed, sets Status and Note.
// If `successNoteFn` is provided, it will be called AFTER success to compute Note text.
function PP_runStep_UI(dlg, opText, groupText, stepFn, successNoteFn){
    if (!dlg) throw new Error("Progress dialog is not provided.");
    var row = dlg.addRow(opText || "", String(groupText||""));
    PP_setStatus(dlg, row, PP_iconRunning());
    try{ processEvents(); }catch(_){}
    var ok = true, err = "", t0 = Date.now();
    try { if (typeof stepFn === "function") stepFn(); } catch(e){ ok=false; err = e.toString(); }
    var dt = Date.now() - t0;
    var elapsedText = (typeof formatElapsedMS === "function")
        ? formatElapsedMS(dt)
        : (function(ms){ var s=Math.floor(ms/1000),hh=Math.floor(s/3600),mm=Math.floor((s%3600)/60),ss=s%60,hs=Math.floor((ms%1000)/10);
            function p2(n){return (n<10?"0":"")+n;} return p2(hh)+":"+p2(mm)+":"+p2(ss)+"."+p2(hs);} )(dt);
    var noteText = "";
    if (ok && typeof successNoteFn === "function"){
        try { noteText = String(successNoteFn()) || ""; } catch(_){ /* ignore */ }
    }
    if (!ok) noteText = err;
    PP_setElapsed(dlg, row, elapsedText);
    PP_setNote(dlg, row, noteText);
    PP_setStatus(dlg, row, ok ? PP_iconSuccess() : PP_iconError());
    try{ processEvents(); }catch(_){}
    if (!ok) throw new Error(err);
    return { ok: ok, elapsedMS: dt, row: row };
}

/* ---------- CosmeticCorrection: pre-add rows per group and run ---------- */
function PP__preAddCosmeticRows(dlg, ccPlan){
    if (!dlg || !ccPlan || !ccPlan.groups) return;
    if (!dlg.ccRowsMap) dlg.ccRowsMap = {};
    var keys = [];
    for (var k in ccPlan.groups) if (ccPlan.groups.hasOwnProperty(k)) keys.push(k);
    for (var i=0;i<keys.length;i++){
        var gkey = keys[i], g = ccPlan.groups[gkey];
        // count subs
        var subs = 0;
        if (g && g.files && g.files.length) subs = g.files.length;
        else if (g && g.items && g.items.length) subs = g.items.length;
        else if (g && g.frames && g.frames.length) subs = g.frames.length;
        // label = formatted passport + " (N subs)"
        var label = CP__fmtCosmeticGroupLabel(gkey, g) + (subs?(" ("+subs+" subs)"):"");
        var node = dlg.addRow("CosmeticCorrection", label);
        var subs = 0;
        if (g && g.files && g.files.length) subs = g.files.length;
        else if (g && g.items && g.items.length) subs = g.items.length;
        else if (g && g.frames && g.frames.length) subs = g.frames.length;
        var label = CP__fmtCosmeticGroupLabel(gkey) + (subs?(" ("+subs+" subs)"):"");
        PP_setStatus(dlg, node, PP_iconQueued());
        PP_setNote(dlg, node, subs? (subs+"/"+subs+" queued") : "");
        dlg.ccRowsMap[gkey] = { node: node, subs: subs };
    }
    try{ processEvents(); }catch(_){}
}

// Public UI entry for CC: adds per-group rows and runs the module
function PP_runCosmeticCorrection_UI(dlg, ccPlan, workFolders){
    if (!dlg) throw new Error("Progress dialog is not provided.");
    if (!ccPlan || !ccPlan.groups) throw new Error("Cosmetic plan is empty (no groups).");

    // 0) Pre-add rows (⏳ queued, "(N subs)" уже добавлено в PP__preAddCosmeticRows)
    PP__preAddCosmeticRows(dlg, ccPlan);

    // 1) Собираем стабильный порядок групп
    var keys = [];
    try{
        if (ccPlan.order && ccPlan.order.length) keys = ccPlan.order.slice(0);
        else for (var k in ccPlan.groups) if (ccPlan.groups.hasOwnProperty(k)) keys.push(k);
    }catch(_){}

    // 2) Построитель мини-плана на одну группу
    function __miniCCPlanFor(key){
        var mp = {};
        for (var p in ccPlan)
            if (ccPlan.hasOwnProperty(p) && p !== "groups" && p !== "order")
                mp[p] = ccPlan[p];
        mp.groups = {}; mp.groups[key] = ccPlan.groups[key];
        mp.order = [key];
        return mp;
    }

    // 3) Бежим по группам: ▶ running → RunCosmeticCorrection(miniPlan) → ✔/✖ processed
    for (var i=0; i<keys.length; i++){
        var gkey = keys[i];
        var rec  = dlg.ccRowsMap && dlg.ccRowsMap[gkey];
        if (!rec || !rec.node) continue;

        // Переводим строку в Running и ставим "N/N running"
        PP_setStatus(dlg, rec.node, PP_iconRunning());
        PP_setNote  (dlg, rec.node, rec.subs ? (rec.subs+"/"+rec.subs+" running") : "running");
        try{ processEvents(); }catch(_){}

        // Если в группе нет кадров — считаем её успешно обработанной «по нулям»
        if (!rec.subs || rec.subs <= 0){
            PP_setStatus(dlg, rec.node, PP_iconSuccess());
            PP_setNote  (dlg, rec.node, "0/0 processed");
            continue;
        }

        // Запуск раннера на мини-план
        var mp   = __miniCCPlanFor(gkey);
        var okG  = true, errG = "", t0 = Date.now();
        try{ RunCosmeticCorrection(mp, workFolders, dlg); }catch(e){ okG=false; errG = e.toString(); }
        var dt = Date.now() - t0;
        try{
            if (typeof formatElapsedMS === "function")
                dlg.updateRow(rec.node, { elapsed: formatElapsedMS(dt) });
        }catch(_){}

        // Отмечаем итог по группе
        PP_setStatus(dlg, rec.node, okG ? PP_iconSuccess() : PP_iconError());
        PP_setNote  (dlg, rec.node, okG ? (rec.subs+"/"+rec.subs+" processed") : (errG || "Error"));
        try{ processEvents(); }catch(_){}

        if (!okG) throw new Error(errG || "CosmeticCorrection failed");
    }

    return { ok:true, groups: keys.length };
    // Finalize Total after the last operation of the pipeline
    try{ PP_finalizeProgress(dlg); }catch(_){}
    return { ok:true, groups: keys.length };
}

// Thin wrappers built on top of the generic runner (kept for clarity/back-compat).
function PP_runReindexLights_UI(dlg, lightsRoot, lightsJsonPath){
    return PP_runStep_UI(
        dlg,
        "Index Lights",
        String(lightsRoot||""),
        function(){ ReindexLights(lightsRoot, lightsJsonPath); },
        function(){
                var LI = PP_getLastLightsIndex();
                var nItems  = (LI && LI.items ) ? LI.items.length  : 0;
                return nItems + " subs";
            }
    );
}
function PP_runIndexCalibrationFiles_UI(dlg, mastersRoot, mastersJsonPath){
    var __MI = null; // capture return value if module provides it
    return PP_runStep_UI(
        dlg,
        "Index Calibration Files",
        String(mastersRoot||""),
        function(){
            try { __MI = ReindexCalibrationFiles(mastersRoot, mastersJsonPath); }
            catch(e){ __MI = null; throw e; }
        },
        function(){
            // Prefer the just-returned object; fallback to last-known index getter.
            var MI = __MI || PP_getLastMastersIndex();
            var c = PP_guessMastersCounts(MI);
            return "B:" + c.b + "  D:" + c.d + "  F:" + c.f;
        }
    );
}

// --- UI wrapper for ImageCalibration (mini-plans: one runner call per group) ---
function PP_runImageCalibration_UI(dlg, plan, options){
    if (!dlg) throw new Error("Progress dialog is not provided.");

    // No groups → один ряд и один запуск, для совместимости
    if (!plan || !plan.groups){
        var r = dlg.addRow("ImageCalibration", "Apply masters to lights");
        PP_setStatus(dlg, r, PP_iconQueued());
        try{ processEvents(); }catch(_){}
        PP_setStatus(dlg, r, PP_iconRunning());
        var ok=true, err="", t0=Date.now();
        try{ RunImageCalibration(plan, options); }catch(e){ ok=false; err=e.toString(); }
        var dt = Date.now()-t0;
        if (typeof formatElapsedMS==="function")
            dlg.updateRow(r, { elapsed: formatElapsedMS(dt), note: ok? "" : "Error" });
        PP_setStatus(dlg, r, ok?PP_iconSuccess():PP_iconError());
        if (!ok) throw new Error(err);
            // If CC is disabled, finish Total here
            try{ if (!dlg.ccEnabled) PP_finalizeProgress(dlg); }catch(_){}
        return { ok:true, groups:1 };
    }

    // Собираем порядок групп
    var keys = [];
    try{
        if (plan.order && plan.order.length) keys = plan.order.slice(0);
        else for (var k in plan.groups) if (plan.groups.hasOwnProperty(k)) keys.push(k);
    }catch(_){}

    // Предсоздаём строки: "паспорт (N subs)" + ⏳ queued
    if (!dlg.icRowsMap) dlg.icRowsMap = {};
    for (var i=0; i<keys.length; i++){
        var gkey   = keys[i];
        var g      = plan.groups[gkey] || {};
        var frames = (g && g.lights && g.lights.length) ? g.lights.length : 0;
        var core   = (typeof CP__fmtGroupForUI==="function" ? CP__fmtGroupForUI(gkey) : String(gkey));
        var label  = core + (frames ? (" ("+frames+" subs)") : "");
        var node   = dlg.addRow("ImageCalibration", label);
        PP_setStatus(dlg, node, PP_iconQueued());
        PP_setNote(dlg, node, frames ? (frames+"/"+frames+" queued") : "");
        dlg.icRowsMap[gkey] = { node: node, frames: frames };
    }
    try{ processEvents(); }catch(_){}

    // Вспомогалка: построить мини-план на одну группу
    function __miniPlanFor(key){
        var mp = {};
        for (var prop in plan)
            if (plan.hasOwnProperty(prop) && prop !== "groups" && prop !== "order")
                mp[prop] = plan[prop];
        mp.groups = {}; mp.groups[key] = plan.groups[key];
        mp.order = [key];
        return mp;
    }

    // Бежим по группам: для каждой — ▶ running → RunImageCalibration(miniPlan) → ✔/✖ processed
    for (var j=0; j<keys.length; j++){
        var gkey = keys[j];
        var rec  = dlg.icRowsMap[gkey];
        if (!rec || !rec.node) continue;

        // ▶ Running
        PP_setStatus(dlg, rec.node, PP_iconRunning());
        PP_setNote  (dlg, rec.node, rec.frames ? (rec.frames+"/"+rec.frames+" running") : "running");
        try{ processEvents(); }catch(_){}

        // Запуск раннера на мини-план
        var mp = __miniPlanFor(gkey);
        var okG = true, errG = "", t0 = Date.now();
        try{ RunImageCalibration(mp, options); }catch(e){ okG=false; errG = e.toString(); }
        var dt = Date.now()-t0;
        try{
            if (typeof formatElapsedMS==="function")
                dlg.updateRow(rec.node, { elapsed: formatElapsedMS(dt) });
        }catch(_){}

        // Попробуем вытащить per-group статистику из calibration_run.jsh (если она есть)
        var processed = null, failed = null, gErr = null;
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

        if (processed==null) processed = okG ? (rec.frames||0) : 0;

        // ✔/✖ + финальная Note
        PP_setStatus(dlg, rec.node, okG ? PP_iconSuccess() : PP_iconError());
        PP_setNote  (dlg, rec.node, okG ? (processed+"/"+(rec.frames||0)+" processed")
            : (gErr || errG || "Error"));
        try{ processEvents(); }catch(_){}
        if (!okG) throw new Error(errG || gErr || "ImageCalibration failed");
    }

    return { ok:true, groups: keys.length };
}



// Helpers to fetch last indices without leaking globals into anawbpps.js
function PP_getLastLightsIndex(){
    try{
        if (typeof LI_GET_LAST_INDEX === "function") return LI_GET_LAST_INDEX();
        if (typeof LI_LAST_INDEX !== "undefined")    return LI_LAST_INDEX;
        if (typeof this !== "undefined" && typeof this.LI_LAST_INDEX !== "undefined") return this.LI_LAST_INDEX;
    }catch(_){}
    return null;
}
function PP_getLastMastersIndex(){
    try{
        if (typeof MI_GET_LAST_INDEX === "function") return MI_GET_LAST_INDEX();
        if (typeof MI_LAST_INDEX !== "undefined")    return MI_LAST_INDEX;
        if (typeof this !== "undefined" && typeof this.MI_LAST_INDEX !== "undefined") return this.MI_LAST_INDEX;
    }catch(_){}
    return null;
}
function PP_getLastImageCalibrationStats(){
    try{
        if (typeof CAL_GET_LAST_STATS === "function") return CAL_GET_LAST_STATS();
        if (typeof IC_GET_LAST_STATS  === "function") return IC_GET_LAST_STATS();
        if (typeof CAL_LAST_STATS     !== "undefined") return CAL_LAST_STATS;
        if (typeof IC_LAST_STATS      !== "undefined") return IC_LAST_STATS;
        if (typeof this !== "undefined"){
            if (typeof this.CAL_LAST_STATS !== "undefined") return this.CAL_LAST_STATS;
            if (typeof this.IC_LAST_STATS  !== "undefined") return this.IC_LAST_STATS;
        }
    }catch(_){}
    return null;
}
