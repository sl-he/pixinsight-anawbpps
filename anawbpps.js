/*
 * ANAWBPPS - Automated Night Astrophotography Workflow Batch Pre-Processing Script
 * Main entry point and UI
 *
 * Copyright (C) 2024-2025 sl-he
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Repository: https://github.com/sl-he/pixinsight-anawbpps
 */

#include <pjsr/StdDialogCode.jsh>
#include <pjsr/Sizer.jsh>
#include "anawbpps.constants.jsh"
#include "modules/masters_parse.jsh"
#include "modules/lights_parse.jsh"
#include "modules/masters_index.jsh"
#include "modules/lights_index.jsh"
#include "modules/calibration_match.jsh"
#include "modules/calibration_run.jsh"
#include "modules/cosmetic_plan.jsh"
#include "modules/cosmetic_run.jsh"
#include "modules/subframe_selector.jsh"
#include "modules/star_alignment.jsh"

// ============================================================================
// Progress UI (inline - no external module to avoid reload crashes)
// ============================================================================

/* Status icons */
function PP_iconQueued(){  return "⏳ Queued";  }
function PP_iconRunning(){ return "▶ Running"; }
function PP_iconSuccess(){ return "✔ Success"; }
function PP_iconError(){   return "✖ Error";   }

/* Status helpers */
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

function PP_setElapsed(dlg, node, elapsedText){
    try{
        if (dlg && typeof dlg.updateRow === "function")
            dlg.updateRow(node, { elapsed: elapsedText });
        else if (node && typeof node.setText === "function")
            node.setText(2, elapsedText);
    }catch(_){}
}

/* Utilities */
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

function PP_guessMastersCounts(MI){
    var b=0,d=0,f=0;
    if (!MI) return {b:b,d:d,f:f};
    if (MI.biases && typeof MI.biases.length === "number") b = MI.biases.length;
    if (MI.darks  && typeof MI.darks.length  === "number") d = MI.darks.length;
    if (MI.flats  && typeof MI.flats.length  === "number") f = MI.flats.length;
    if (b||d||f) return {b:b,d:d,f:f};
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

/* Label formatters */
function CP__splitByPipe(s){
    try{ return String(s).split("|"); }catch(_){ return [String(s)]; }
}

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

function __cc_getDarkPath(gkey, g){
    if (g){
        var names = ["darkPath","dark","masterDark","path","file"];
        for (var i=0;i<names.length;i++){
            var v = g[names[i]];
            if (v && typeof v === "string" && v.length) return v;
        }
    }
    try{
        var s = String(gkey);
        var idx = s.lastIndexOf("::");
        if (idx >= 0) return s.substring(idx+2);
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

    var toks = fname.split("_");
    function findTok(pred){
        for (var i=0;i<toks.length;i++) if (pred(toks[i])) return toks[i];
        return "";
    }
    var modeTok = findTok(function(t){ return /mode/i.test(t); });
    var mode = modeTok ? modeTok.replace(/[-]+/g," ").replace(/\s+/g," ").trim() : "";

    function pick(re){ var m=fname.match(re); return m?m[1]:""; }
    var g    = pick(/(?:^|_)G(\d+)(?:_|$)/i);
    var os   = pick(/(?:^|_)OS(\d+)(?:_|$)/i);
    var u    = pick(/(?:^|_)U(\d+)(?:_|$)/i);
    var bin  = pick(/(?:^|_)Bin(\d+x\d+)(?:_|$)/i);
    var expS = pick(/(?:^|_)(\d{1,4})s(?:_|$)/i);
    var tmpC = pick(/(?:^|_)(-?\d+)C(?:_|$)/i);

    var dm = (fname.match(/(20\d{2})[-_\.]?(0[1-9]|1[0-2])[-_\.]?([0-3]\d)/) ||
        p.match(/(20\d{2})[-_\.]?(0[1-9]|1[0-2])[-_\.]?([0-3]\d)/));
    var date = dm ? (dm[1]+"_"+dm[2]+"_"+dm[3]) : "";

    function nz(x){ return (x===0 || x==="0" || (x && String(x).length)) ? String(x) : ""; }
    var G   = nz(g)   ? ("G"+g) : "";
    var OS  = nz(os)  ? ("OS"+os) : "";
    var U   = nz(u)   ? ("U"+u) : "";
    var BIN = nz(bin) ? ("bin"+bin.toLowerCase()) : "";
    var TEMP= nz(tmpC)? (tmpC+"C") : "";
    var EXP = nz(expS)? (String(parseInt(expS,10))+"s") : "";

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

function CP__fmtCosmeticGroupLabel(gkey, g){
    var dark = __cc_getDarkPath(gkey, g);
    var core = __cc_labelFromDarkPath(dark);
    return core || String(gkey);
}

/* ProgressDialog class */
function ProgressDialog() {
    this.__base__ = Dialog;
    this.__base__();
    var self = this;

    this.windowTitle = "ANAWBPPS — Progress";
    this.scaledMinWidth = 1400;
    this.scaledMinHeight = 1000;
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
    this.tree.setColumnWidth(0, this.tree.logicalPixelsToPhysical(200));
    this.tree.setColumnWidth(1, this.tree.logicalPixelsToPhysical(700));
    this.tree.setColumnWidth(2, this.tree.logicalPixelsToPhysical(100));
    this.tree.setColumnWidth(3, this.tree.logicalPixelsToPhysical(100));
    this.tree.setColumnWidth(4, this.tree.logicalPixelsToPhysical(200));
    this.totalLabel = new Label(this);
    this.totalLabel.text = "Total: 00:00:00.00";
    this.cancelButton = new PushButton(this);
    this.cancelButton.text = "Cancel";
    this.cancelButton.onClick = function () {
        try{
            if (self._totalTimer && self._totalTimer.enabled)
                self._totalTimer.stop();
        }catch(_){}
        try{
            if (self.tree && typeof self.tree.clear === "function")
                self.tree.clear();
        }catch(_){}
        self.finalizePipeline();
        self.cancel();
    };
    this.setDone = function () {
        this.cancelButton.text = "Done";
        this.cancelButton.toolTip = "Close progress window";
        this.cancelButton.onClick = function () {
            try{
                if (self._totalTimer && self._totalTimer.enabled)
                    self._totalTimer.stop();
            }catch(_){}
            try{
                if (self.tree && typeof self.tree.clear === "function")
                    self.tree.clear();
            }catch(_){}
            self.ok();
        };
    };
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
    this._t0 = 0;
    this._totalStopped = false;
    this._totalTimer = new Timer;
    this._totalTimer.period = 30;
    this._totalTimer.onTimeout = function () {
        try{
            if (!self || self._totalStopped) return;
            if (typeof self._t0 === "undefined") return;
        }catch(_){ return; }
        
        if (self._totalStopped) return;
        
        try{
            var ms = new Date().getTime() - self._t0;
            self.totalLabel.text = "Total: " + formatElapsedMS(ms);
        }catch(_){}
    };
    this.startTotal = function () {
        this._t0 = new Date().getTime();
        this._totalTimer.start();
    };

    this.finalizePipeline = function () {
        this._totalStopped = true;
        try{
            if (this._totalTimer){
                if (this._totalTimer.enabled) this._totalTimer.stop();
                this._totalTimer.onTimeout = null;
                this._totalTimer = null;
            }
        }catch(e){
        }
    };

    this.addRow = function (operationText, groupText) {
        var item = new TreeBoxNode(this.tree);
        item.setText(0, operationText || "");
        item.setText(1, groupText || "");
        item.setText(2, "00:00:00.00");
        item.setText(3, "Queued");
        item.setText(4, "");
        return item;
    };

    this.updateRow = function (item, fields) {
        if (!item) return;
        if (fields.group   !== undefined) item.setText(1, fields.group);
        if (fields.elapsed !== undefined) item.setText(2, fields.elapsed);
        if (fields.status  !== undefined) item.setText(3, fields.status);
        if (fields.note    !== undefined) item.setText(4, fields.note);
    };

    this.clearRows = function () {
        this.tree.clear();
    };
}
ProgressDialog.prototype = new Dialog;
// Stub for compatibility
ProgressDialog.prototype.execute = function() {
    try { this.show(); } catch(_){}
    return true;
};
/* Entry points (direct delegation to modules) */
function ReindexLights(lightsRoot, lightsJsonPath) {
    return LI_reindexLights(lightsRoot, lightsJsonPath);
}

function ReindexCalibrationFiles(mastersRoot, mastersJsonPath) {
    return MI_reindexMasters(mastersRoot, mastersJsonPath);
}

function RunImageCalibration(plan, options){
    if (typeof CAL_runCalibration !== "function")
        throw new Error("CAL_runCalibration not found");
    var workFolders = (options && options.workFolders) ? options.workFolders : undefined;
    return CAL_runCalibration(plan, workFolders);
}

function RunCosmeticCorrection(plan, workFolders, dlg) {
    if (typeof CC_runCosmetic_UI !== "function")
        throw new Error("CC_runCosmetic_UI not found");
    return CC_runCosmetic_UI(plan, workFolders, dlg || null);
}

/* Getters for last indices */
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

/* Generic step runner */
function PP_runStep_UI(dlg, opText, groupText, stepFn, successNoteFn){
    if (!dlg) throw new Error("Progress dialog is not provided.");
    var row = dlg.addRow(opText || "", String(groupText||""));
    PP_setStatus(dlg, row, PP_iconRunning());
    try{ processEvents(); }catch(_){}
    var ok = true, err = "", t0 = Date.now();
    try { if (typeof stepFn === "function") stepFn(); } catch(e){ ok=false; err = e.toString(); }
    var dt = Date.now() - t0;
    var elapsedText = formatElapsedMS(dt);
    var noteText = "";
    if (ok && typeof successNoteFn === "function"){
        try { noteText = String(successNoteFn()) || ""; } catch(_){}
    }
    if (!ok) noteText = err;
    PP_setElapsed(dlg, row, elapsedText);
    PP_setNote(dlg, row, noteText);
    PP_setStatus(dlg, row, ok ? PP_iconSuccess() : PP_iconError());
    try{ processEvents(); }catch(_){}
    if (!ok) throw new Error(err);
    return { ok: ok, elapsedMS: dt, row: row };
}

/* UI orchestrators */
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
    var __MI = null;
    return PP_runStep_UI(
        dlg,
        "Index Calibration Files",
        String(mastersRoot||""),
        function(){
            try { __MI = ReindexCalibrationFiles(mastersRoot, mastersJsonPath); }
            catch(e){ __MI = null; throw e; }
        },
        function(){
            var MI = __MI || PP_getLastMastersIndex();
            var c = PP_guessMastersCounts(MI);
            return "B:" + c.b + "  D:" + c.d + "  F:" + c.f;
        }
    );
}

function PP_runImageCalibration_UI(dlg, plan, options){
    if (!dlg) throw new Error("Progress dialog is not provided.");

    if (!plan || !plan.groups){
        var r = dlg.addRow("ImageCalibration", "Apply masters to lights");
        PP_setStatus(dlg, r, PP_iconQueued());
        try{ processEvents(); }catch(_){}
        PP_setStatus(dlg, r, PP_iconRunning());
        var ok=true, err="", t0=Date.now();
        try{ RunImageCalibration(plan, options); }catch(e){ ok=false; err=e.toString(); }
        var dt = Date.now()-t0;
        dlg.updateRow(r, { elapsed: formatElapsedMS(dt), note: ok? "" : "Error" });
        PP_setStatus(dlg, r, ok?PP_iconSuccess():PP_iconError());
        if (!ok) throw new Error(err);
        return { ok:true, groups:1 };
    }

    var keys = [];
    try{
        if (plan.order && plan.order.length) keys = plan.order.slice(0);
        else for (var k in plan.groups) if (plan.groups.hasOwnProperty(k)) keys.push(k);
    }catch(_){}

    if (!dlg.icRowsMap) dlg.icRowsMap = {};
    for (var i=0; i<keys.length; i++){
        var gkey   = keys[i];
        var g      = plan.groups[gkey] || {};
        var frames = (g && g.lights && g.lights.length) ? g.lights.length : 0;
        var core   = CP__fmtGroupForUI(gkey);
        var label  = core + (frames ? (" ("+frames+" subs)") : "");
        var node   = dlg.addRow("ImageCalibration", label);
        PP_setStatus(dlg, node, PP_iconQueued());
        PP_setNote(dlg, node, frames ? (frames+"/"+frames+" queued") : "");
        dlg.icRowsMap[gkey] = { node: node, frames: frames };
    }
    try{ processEvents(); }catch(_){}

    function __miniPlanFor(key){
        var mp = {};
        for (var prop in plan)
            if (plan.hasOwnProperty(prop) && prop !== "groups" && prop !== "order")
                mp[prop] = plan[prop];
        mp.groups = {}; mp.groups[key] = plan.groups[key];
        mp.order = [key];
        return mp;
    }

    for (var j=0; j<keys.length; j++){
        var gkey = keys[j];
        var rec  = dlg.icRowsMap[gkey];
        if (!rec || !rec.node) continue;

        PP_setStatus(dlg, rec.node, PP_iconRunning());
        PP_setNote  (dlg, rec.node, rec.frames ? (rec.frames+"/"+rec.frames+" running") : "running");
        try{ processEvents(); }catch(_){}

        var mp = __miniPlanFor(gkey);
        var okG = true, errG = "", t0 = Date.now();
        try{ RunImageCalibration(mp, options); }catch(e){ okG=false; errG = e.toString(); }
        var dt = Date.now()-t0;
        try{ dlg.updateRow(rec.node, { elapsed: formatElapsedMS(dt) }); }catch(_){}

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

        PP_setStatus(dlg, rec.node, okG ? PP_iconSuccess() : PP_iconError());
        PP_setNote  (dlg, rec.node, okG ? (processed+"/"+(rec.frames||0)+" processed")
            : (gErr || errG || "Error"));
        try{ processEvents(); }catch(_){}
        if (!okG) throw new Error(errG || gErr || "ImageCalibration failed");
    }

    return { ok:true, groups: keys.length };
}

function PP__preAddCosmeticRows(dlg, ccPlan){
    if (!dlg || !ccPlan || !ccPlan.groups) return;
    if (!dlg.ccRowsMap) dlg.ccRowsMap = {};
    var keys = [];
    for (var k in ccPlan.groups) if (ccPlan.groups.hasOwnProperty(k)) keys.push(k);
    for (var i=0;i<keys.length;i++){
        var gkey = keys[i], g = ccPlan.groups[gkey];
        var subs = 0;
        if (g && g.files && g.files.length) subs = g.files.length;
        else if (g && g.items && g.items.length) subs = g.items.length;
        else if (g && g.frames && g.frames.length) subs = g.frames.length;
        var label = CP__fmtCosmeticGroupLabel(gkey, g) + (subs?(" ("+subs+" subs)"):"");
        var node = dlg.addRow("CosmeticCorrection", label);
        PP_setStatus(dlg, node, PP_iconQueued());
        PP_setNote(dlg, node, subs? (subs+"/"+subs+" queued") : "");
        dlg.ccRowsMap[gkey] = { node: node, subs: subs };
    }
    try{ processEvents(); }catch(_){}
}

function PP_runCosmeticCorrection_UI(dlg, ccPlan, workFolders){
    if (!dlg) throw new Error("Progress dialog is not provided.");
    if (!ccPlan || !ccPlan.groups) throw new Error("Cosmetic plan is empty (no groups).");

    PP__preAddCosmeticRows(dlg, ccPlan);

    var keys = [];
    try{
        if (ccPlan.order && ccPlan.order.length) keys = ccPlan.order.slice(0);
        else for (var k in ccPlan.groups) if (ccPlan.groups.hasOwnProperty(k)) keys.push(k);
    }catch(_){}

    function __miniCCPlanFor(key){
        var mp = {};
        for (var p in ccPlan)
            if (ccPlan.hasOwnProperty(p) && p !== "groups" && p !== "order")
                mp[p] = ccPlan[p];
        mp.groups = {}; mp.groups[key] = ccPlan.groups[key];
        mp.order = [key];
        return mp;
    }

    for (var i=0; i<keys.length; i++){
        var gkey = keys[i];
        var rec  = dlg.ccRowsMap && dlg.ccRowsMap[gkey];
        if (!rec || !rec.node) continue;

        PP_setStatus(dlg, rec.node, PP_iconRunning());
        PP_setNote  (dlg, rec.node, rec.subs ? (rec.subs+"/"+rec.subs+" running") : "running");
        try{ processEvents(); }catch(_){}

        if (!rec.subs || rec.subs <= 0){
            PP_setStatus(dlg, rec.node, PP_iconSuccess());
            PP_setNote  (dlg, rec.node, "0/0 processed");
            continue;
        }

        var mp   = __miniCCPlanFor(gkey);
        var okG  = true, errG = "", t0 = Date.now();
        try{ RunCosmeticCorrection(mp, workFolders, dlg); }catch(e){ okG=false; errG = e.toString(); }
        var dt = Date.now() - t0;
        try{ dlg.updateRow(rec.node, { elapsed: formatElapsedMS(dt) }); }catch(_){}

        PP_setStatus(dlg, rec.node, okG ? PP_iconSuccess() : PP_iconError());
        PP_setNote  (dlg, rec.node, okG ? (rec.subs+"/"+rec.subs+" processed") : (errG || "Error"));
        try{ processEvents(); }catch(_){}

        if (!okG) throw new Error(errG || "CosmeticCorrection failed");
    }

    return { ok:true, groups: keys.length };
}

function PP_runSubframeSelector_UI(dlg, PLAN, workFolders, options){
    if (!dlg) throw new Error("Progress dialog is not provided.");
    if (!PLAN || !PLAN.groups) throw new Error("Plan is empty (no groups).");
    var cameraGain = (options && options.cameraGain) || 0.7720;
    var scale = (options && options.subframeScale) || 2.4230;
    var preferCC = (options && options.preferCC !== false);

    Console.noteln("[ss] Running SubframeSelector (Measure+Output) for all groups...");
    Console.noteln("[ss]   Scale: " + scale + " arcsec/px, Gain: " + cameraGain);
    SS_runForAllGroups({
        PLAN: PLAN,
        workFolders: workFolders,
        preferCC: preferCC,
        cameraGain: cameraGain,
        subframeScale: scale,
        dlg: dlg
    });

    Console.noteln("[ss] SubframeSelector complete.");
}

function PP_runStarAlignment_UI(dlg, PLAN, workFolders){
    Console.noteln("[sa] Running StarAlignment...");
    SA_runForAllTargets({
        PLAN: PLAN,
        workFolders: workFolders,
        dlg: dlg
    });
    Console.noteln("[sa] StarAlignment complete.");
}

function PP_finalizeProgress(dlg){
    if (dlg && typeof dlg.finalizePipeline === "function")
        dlg.finalizePipeline();
    if (dlg && typeof dlg.setDone === "function") {
        try {
            dlg.setDone();
        } catch(e) {
            Console.warningln("[progress] Failed to set Done button: " + e);
        }
    }
}

// ============================================================================
// Hardcoded defaults
// ============================================================================
var HARDCODED_DEFAULTS = {
    lights:  "D:/!!!WORK/ASTROFOTO/!!!WORK_LIGHTS",
    masters: "D:/!!!WORK/ASTROFOTO/!!!!!MASTERS",
    work1:   "V:/!!!WORK/ASTROFOTO/",
    work2:   "W:/!!!WORK/ASTROFOTO/",
    useTwo:  true,
    doCal: true,
    doCC:  true,
    doSS: true,
    doSA:  true,
    doLN:  true,
    doNSG: true,
    doII:  true,
    doDrizzle: true
};

// ============================================================================
// File-system helpers
// ============================================================================
function joinPath(){
    var a = [];
    for (var i = 0; i < arguments.length; ++i)
        if (arguments[i]) a.push(arguments[i]);
    var p = a.join('/');
    return p.replace(/\\/g, '/').replace(/\/+/g, '/');
}

function ensureDir(dir){
    if (!File.directoryExists(dir))
        File.createDirectory(dir, true);
}

function endsWithProjectBase(p){
    var s = String(p||"").replace(/\\/g,'/').replace(/\/+/g,'/');
    if (s.length === 0) return false;
    if (s.length > 1 && s.charAt(s.length-1) === '/') s = s.substring(0, s.length-1);
    var i = s.lastIndexOf('/');
    var tail = (i>=0) ? s.substring(i+1) : s;
    return tail === PROJECT_DEFAULTS_DIRNAME;
}

function projectBase(root){
    return endsWithProjectBase(root) ? root : joinPath(root, PROJECT_DEFAULTS_DIRNAME);
}

function makeWorkFolders(work1Root, work2Root, useTwo){
    var base1 = projectBase(work1Root);
    ensureDir(base1);

    var approved1 = joinPath(base1, DIR_APPROVED);
    ensureDir(approved1);

    var calibrated = joinPath(base1, DIR_CALIBRATED);
    ensureDir(calibrated);

    var trash = joinPath(base1, DIR_TRASH);
    ensureDir(trash);

    var base2 = "";
    var cosmetic = "";
    var approvedSet;

    if (useTwo && work2Root){
        base2 = projectBase(work2Root);
        ensureDir(base2);

        cosmetic = joinPath(base2, DIR_COSMETIC);
        ensureDir(cosmetic);

        var approved2 = joinPath(base2, DIR_APPROVED);
        ensureDir(approved2);
        approvedSet = joinPath(approved2, DIR_APPROVED_SET);
        ensureDir(approvedSet);
    } else {
        cosmetic = joinPath(base1, DIR_COSMETIC);
        ensureDir(cosmetic);
        approvedSet = joinPath(approved1, DIR_APPROVED_SET);
        ensureDir(approvedSet);
    }

    return {
        base1: base1,
        base2: base2,
        approved: approved1,
        approvedSet: approvedSet,
        calibrated: calibrated,
        cosmetic: cosmetic,
        trash: trash
    };
}

// ============================================================================
// UI helpers
// ============================================================================
function PathRow(parent, labelText, tooltip){
    this.label = new Label(parent);
    this.label.text = labelText;
    this.label.minWidth = 170;
    this.edit = new Edit(parent);
    this.edit.minWidth = 420;
    if (tooltip) this.edit.toolTip = tooltip;
    this.btn = new ToolButton(parent);
    this.btn.icon = this.btn.scaledResource(":/browser/select-file.png");
    this.btn.setScaledFixedSize(20, 20);
    this.btn.toolTip = "Select folder";

    var self = this;
    this.btn.onClick = function(){
        var d = new GetDirectoryDialog;
        d.caption = labelText;
        if (d.execute())
            self.edit.text = d.directory;
    };

    this.sizer = new HorizontalSizer;
    this.sizer.spacing = 6;
    this.sizer.add(this.label);
    this.sizer.add(this.edit, 100);
    this.sizer.add(this.btn);

    this.destroy = function(){
        try{
            if (this.btn && this.btn.onClick) this.btn.onClick = null;
            this.btn = null;
            this.edit = null;
            this.label = null;
        }catch(_){}
    };
}

function showDialogBox(title, text){
    var d = new Dialog;
    d.windowTitle = title;

    var tb = new TextBox(d);
    tb.readOnly  = true;
    tb.wordWrap  = false;
    tb.multiline = true;
    tb.minWidth  = SUMMARY_WIDTH;
    tb.minHeight = SUMMARY_HEIGHT;
    tb.text      = text;

    var ok = new PushButton(d);
    ok.text = "OK";
    ok.onClick = function(){ d.ok(); };

    var btns = new HorizontalSizer;
    btns.addStretch();
    btns.add(ok);

    d.sizer = new VerticalSizer;
    d.sizer.margin = 6;
    d.sizer.spacing = 6;
    d.sizer.add(tb, 100);
    d.sizer.add(btns);

    d.adjustToContents();

    // Safe execute with fallback
    try {
        if (typeof d.execute === "function") {
            d.execute();
        } else {
            Console.warningln("[showDialogBox] execute() not available, dialog not shown");
        }
    } catch(e) {
        Console.warningln("[showDialogBox] Error showing dialog: " + e);
    }
}
// ============================================================================
// Main dialog
// ============================================================================
function ANAWBPPSDialog(){
    this.__base__ = Dialog;
    this.__base__();

    var self = this;

    this.windowTitle = "ANAWBPPS — Configuration";

    this.rowLights  = new PathRow(this, "Lights Folder:",  "Folder with light frames (.xisf/.fits)");
    this.rowMasters = new PathRow(this, "Masters Folder:", "Folder with master frames");

    var lightsRowSizer = new HorizontalSizer;
    lightsRowSizer.spacing = 6;
    lightsRowSizer.add(this.rowLights.sizer, 100);

    var mastersRowSizer = new HorizontalSizer;
    mastersRowSizer.spacing = 6;
    mastersRowSizer.add(this.rowMasters.sizer, 100);

    this.rowWork1 = new PathRow(this, "Work1 Folder:", "Primary working folder");
    this.cbTwoWork  = new CheckBox(this);
    this.cbTwoWork.text = "Use two working folders (Work1 + Work2)";
    this.cbTwoWork.checked = !!HARDCODED_DEFAULTS.useTwo;
    this.rowWork2   = new PathRow(this, "Work2 Folder:", "Secondary working folder");
    this.rowWork2.sizer.visible = this.cbTwoWork.checked;

    if (HARDCODED_DEFAULTS.lights)  this.rowLights.edit.text  = HARDCODED_DEFAULTS.lights;
    if (HARDCODED_DEFAULTS.masters) this.rowMasters.edit.text = HARDCODED_DEFAULTS.masters;
    if (HARDCODED_DEFAULTS.work1)   this.rowWork1.edit.text   = HARDCODED_DEFAULTS.work1;
    if (HARDCODED_DEFAULTS.work2)   this.rowWork2.edit.text   = HARDCODED_DEFAULTS.work2;

    this.gbOptions = new GroupBox(this);
    this.gbOptions.title = "Workflow steps";
    this.gbOptions.sizer = new VerticalSizer;
    this.gbOptions.sizer.margin = 6;
    this.gbOptions.sizer.spacing = 4;

    this.cbCal = new CheckBox(this); this.cbCal.text = "ImageCalibration";     this.cbCal.checked = !!HARDCODED_DEFAULTS.doCal;
    this.cbCC  = new CheckBox(this); this.cbCC.text  = "CosmeticCorrection";   this.cbCC.checked  = !!HARDCODED_DEFAULTS.doCC;
    this.cbSS  = new CheckBox(this); this.cbSS.text  = "SubframeSelector";     this.cbSS.checked  = !!HARDCODED_DEFAULTS.doSS;
    this.cbSA  = new CheckBox(this); this.cbSA.text  = "StarAlignment";        this.cbSA.checked  = !!HARDCODED_DEFAULTS.doSA;
    this.cbLN  = new CheckBox(this); this.cbLN.text  = "LocalNormalization";   this.cbLN.checked  = !!HARDCODED_DEFAULTS.doLN;
    this.cbNSG = new CheckBox(this); this.cbNSG.text = "NSG (instead of LN)";  this.cbNSG.checked = !!HARDCODED_DEFAULTS.doNSG;
    this.cbII  = new CheckBox(this); this.cbII.text  = "ImageIntegration";     this.cbII.checked  = !!HARDCODED_DEFAULTS.doII;
    this.cbDrz = new CheckBox(this); this.cbDrz.text = "DrizzleIntegration";   this.cbDrz.checked = !!HARDCODED_DEFAULTS.doDrizzle;

    this.gbOptions.sizer.add(this.cbCal);
    this.gbOptions.sizer.add(this.cbCC);
    this.gbOptions.sizer.add(this.cbSS);
    this.gbOptions.sizer.add(this.cbSA);
    this.gbOptions.sizer.add(this.cbLN);
    this.gbOptions.sizer.add(this.cbNSG);
    this.gbOptions.sizer.add(this.cbII);
    this.gbOptions.sizer.add(this.cbDrz);

    this.btnRun = new PushButton(this);
    this.btnRun.text = "RUN";
    this.btnRun.toolTip = "Start preprocessing pipeline";

    this.ok_Button = new PushButton(this);
    this.ok_Button.text = "OK";

    this.cancel_Button = new PushButton(this);
    this.cancel_Button.text = "Cancel";

    this.pipelineCompleted = false;

    this.setDone = function () {
        this.pipelineCompleted = true;
        this.cancel_Button.text = "Done";
        this.cancel_Button.toolTip = "Close configuration window";
    };

    var buttons = new HorizontalSizer;
    buttons.addStretch();
    buttons.spacing = 6;
    buttons.add(this.btnRun);
    buttons.add(this.ok_Button);
    buttons.add(this.cancel_Button);

    this.sizer = new VerticalSizer;
    this.sizer.margin = 6;
    this.sizer.spacing = 6;
    this.sizer.add(lightsRowSizer);
    this.sizer.add(mastersRowSizer);
    this.sizer.add(this.rowWork1.sizer);
    this.sizer.add(this.cbTwoWork);
    this.sizer.add(this.rowWork2.sizer);
    this.sizer.add(this.gbOptions);
    this.sizer.add(buttons);

    this.adjustToContents();

    this.onClose = function(){
        try{
            if (self.rowLights)  self.rowLights.destroy();
            if (self.rowMasters) self.rowMasters.destroy();
            if (self.rowWork1)   self.rowWork1.destroy();
            if (self.rowWork2)   self.rowWork2.destroy();
        }catch(e){
            Console.warningln("[ui] Cleanup error: " + e);
        }
    };

    this.cbTwoWork.onCheck = function(checked){
        self.rowWork2.sizer.visible = checked;
        self.adjustToContents();
    };

    this.btnRun.onClick = function(){
        var lightsRoot  = self.rowLights.edit.text.trim();
        var mastersRoot = self.rowMasters.edit.text.trim();

        if (!lightsRoot || !mastersRoot){
            showDialogBox("ANAWBPPS", "Please select both Lights and Masters folders.");
            return;
        }

        Console.show();
        Console.noteln("[run] START");
        function _norm(p){
            var s = String(p||"");
            var out = "";
            for (var i=0;i<s.length;i++){
                var c = s.charAt(i);
                out += (c === "\\") ? "/" : c;
            }
            var res = "";
            for (var j=0;j<out.length;j++){
                var ch = out.charAt(j);
                if (!(ch === "/" && res.length>0 && res.charAt(res.length-1) === "/"))
                    res += ch;
            }
            return res;
        }

        var lightsJson  = _norm(lightsRoot  + "/lights_index.json");
        var mastersJson = _norm(mastersRoot + "/masters_index.json");
        var planPath    = _norm(lightsRoot  + "/calibration_plan.json");

        var ppDlg = new ProgressDialog();

        ppDlg.startTotal();
        // Show progress dialog non-blocking
        try { ppDlg.show(); } catch(_){}
        try{
            PP_runReindexLights_UI(ppDlg, lightsRoot, lightsJson);
            PP_runIndexCalibrationFiles_UI(ppDlg, mastersRoot, mastersJson);

            var LI = PP_getLastLightsIndex();
            var MI = PP_getLastMastersIndex();

            if (!LI || !LI.items || !LI.items.length){
                throw new Error("Lights index is empty");
            }
            if (!MI || !MI.items || !MI.items.length){
                throw new Error("Masters index is empty");
            }

            var PLAN = CM_buildPlanInMemory(LI, MI, planPath);
            if (!PLAN || !PLAN.groups){
                throw new Error("Plan build failed - no groups");
            }

            var work1Root = self.rowWork1.edit.text.trim();
            var work2Root = self.rowWork2.edit.text.trim();
            var useTwo = self.cbTwoWork.checked;
            var wf = makeWorkFolders(work1Root, work2Root, useTwo);

            if (self.cbCal && self.cbCal.checked){
                PP_runImageCalibration_UI(ppDlg, PLAN, { workFolders: wf });
            }

            if (self.cbCC && self.cbCC.checked){
                var CC_PLAN = CC_makeCosmeticPlan(PLAN, wf);
                PP_runCosmeticCorrection_UI(ppDlg, CC_PLAN, wf);
            }

            if (self.cbSS && self.cbSS.checked){
                PP_runSubframeSelector_UI(ppDlg, PLAN, wf, {
                    preferCC: (self.cbCC && self.cbCC.checked),
                    cameraGain: 0.3330,
                    subframeScale: 2.4230
                });
            }
            if (self.cbSA && self.cbSA.checked){
                PP_runStarAlignment_UI(ppDlg, PLAN, wf);
                }

            PP_finalizeProgress(ppDlg);

            try {
                self.setDone();
            } catch(_){}

        } catch(e){
            Console.criticalln("[run] Pipeline error: " + e);
            showDialogBox("ANAWBPPS — Error", "Pipeline failed:\n" + e);
        }
    };

    this.ok_Button.onClick = function(){
        try{
            if (self.rowLights)  self.rowLights.destroy();
            if (self.rowMasters) self.rowMasters.destroy();
            if (self.rowWork1)   self.rowWork1.destroy();
            if (self.rowWork2)   self.rowWork2.destroy();
        }catch(e){
            Console.warningln("[ui] Cleanup error (OK): " + e);
        }
        self.ok();
    };

    this.cancel_Button.onClick = function(){
        if (self.pipelineCompleted) {
            try{
                if (self.rowLights)  self.rowLights.destroy();
                if (self.rowMasters) self.rowMasters.destroy();
                if (self.rowWork1)   self.rowWork1.destroy();
                if (self.rowWork2)   self.rowWork2.destroy();
            }catch(_){}
            self.ok();
        } else {
            try{
                if (self.rowLights)  self.rowLights.destroy();
                if (self.rowMasters) self.rowMasters.destroy();
                if (self.rowWork1)   self.rowWork1.destroy();
                if (self.rowWork2)   self.rowWork2.destroy();
            }catch(_){}
            self.cancel();
        }
    };
}

ANAWBPPSDialog.prototype = new Dialog;

// ============================================================================
// Entry point
// ============================================================================
function main(){
    Console.show();
    var dlg = new ANAWBPPSDialog();

    try {
        if (!dlg.execute()){
            Console.writeln("[info] Canceled");
            return;
        }
    } catch(e) {
        Console.criticalln("[FATAL] Error executing dialog: " + e);
        return;
    }
}

try{
    main();
}catch(e){
    Console.criticalln("[FATAL] " + e);
}
