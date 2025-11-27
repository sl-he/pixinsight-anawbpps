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

#feature-id    sl-he > ANAWBPPS
#feature-info  Automated Night Astrophotography Workflow Batch Pre-Processing Script. \
               Automates the entire preprocessing workflow from calibration to final integration.
//#feature-icon  ANAWBPPS.xpm
#define TITLE "ANAWBPPS"
#define VERSION "1.0.0.1"

#include <pjsr/StdDialogCode.jsh>
#include <pjsr/Sizer.jsh>
#include "ANAWBPPS.constants.jsh"
#include "modules/common_utils.jsh"
#include "modules/fits_indexing.jsh"
#include "modules/masters_create.jsh"
#include "modules/image_calibration.jsh"
#include "modules/cosmetic_correction.jsh"
#include "modules/debayer.jsh"
#include "modules/subframe_selector.jsh"
#include "modules/star_alignment.jsh"
#include "modules/local_normalization.jsh"
#include "modules/image_integration.jsh"
#include "modules/drizzle_integration.jsh"
#include "modules/notifications.jsh"
#include "modules/logging.jsh"

// ============================================================================
// Progress UI (inline - no external module to avoid reload crashes)
// ============================================================================

/* Status icons */
function PP_iconQueued(){  return "⏳ Queued";  }
function PP_iconRunning(){ return "▶ Running"; }
function PP_iconSuccess(){ return "✔ Success"; }
function PP_iconError(){   return "✖ Error";   }
function PP_iconSkipped(){ return "⊘ Skipped"; }

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

// Format elapsed time in seconds to HH:MM:SS.hh format (same as SubframeSelector)
// Time formatting functions moved to common_utils.jsh (CU_fmtHMS, CU_fmtElapsedMS)
// Keeping wrapper functions for backward compatibility
function PP_fmtHMS(sec){
    return CU_fmtHMS(sec);
}

function formatElapsedMS(ms) {
    return CU_fmtElapsedMS(ms);
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

/* ProgressDialog class */
function ProgressDialog() {
    this.__base__ = Dialog;
    this.__base__();
    var self = this;

    this.windowTitle = "ANAWBPPS v" + VERSION + " — Progress";
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
    return FI_indexLights(lightsRoot, lightsJsonPath);
}

function ReindexCalibrationFiles(mastersRoot, mastersJsonPath) {
    return FI_indexMasters(mastersRoot, mastersJsonPath);
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
    var __LI = null;
    return PP_runStep_UI(
        dlg,
        "Index Lights",
        String(lightsRoot||""),
        function(){
            try {
                __LI = ReindexLights(lightsRoot, lightsJsonPath);
                // Store globally for later access
                LI_LAST_INDEX = __LI;
            }
            catch(e){ __LI = null; throw e; }
        },
        function(){
            var LI = __LI || PP_getLastLightsIndex();
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
            try {
                __MI = ReindexCalibrationFiles(mastersRoot, mastersJsonPath);
                // Store globally for later access
                MI_LAST_INDEX = __MI;
            }
            catch(e){ __MI = null; throw e; }
        },
        function(){
            var MI = __MI || PP_getLastMastersIndex();
            var c = PP_guessMastersCounts(MI);
            return "B:" + c.b + "  D:" + c.d + "  F:" + c.f;
        }
    );
}

// ============================================================================
// Settings Save/Load
// ============================================================================

function PP_getDefaultSettingsPath(){
    // Get home directory and build path to ~/.anawbpps/settings.json
    var homeDir = File.homeDirectory;
    var settingsDir = homeDir + "/.anawbpps";
    var settingsFile = settingsDir + "/settings.json";

    // Ensure directory exists
    try{
        if (!File.directoryExists(settingsDir)){
            File.createDirectory(settingsDir, true);
        }
    }catch(e){
        Console.warningln("[settings] Failed to create settings directory: " + e);
    }

    return settingsFile;
}

function PP_collectSettings(dlg){
    if (!dlg) return null;

    return {
        version: "1.0",
        appName: "ANAWBPPS",
        paths: {
            lights: dlg.rowLights.edit.text || "",
            masters: dlg.rowMasters.edit.text || "",
            raw: dlg.editRaw.text || "",
            work1: dlg.rowWork1.edit.text || "",
            work2: dlg.rowWork2.edit.text || ""
        },
        options: {
            useTwoWork: !!dlg.cbTwoWork.checked,
            doCal: !!dlg.cbCal.checked,
            useBias: !!dlg.cbUseBias.checked,
            doCC: !!dlg.cbCC.checked,
            doSS: !!dlg.cbSS.checked,
            autoRef: !!dlg.cbAutoRef.checked,
            doSA: !!dlg.cbSA.checked,
            doLN: !!dlg.cbLN.checked,
            doII: !!dlg.cbII.checked,
            doDrizzle: !!dlg.cbDrz.checked,
            drizzleScale: (dlg.comboDrzScale.currentItem + 1), // 0=1x, 1=2x -> 1, 2
            saveLog: !!dlg.cbSaveLog.checked,
            // SubframeSelector reject thresholds
            ssFwhmMin: parseFloat(dlg.editSSFwhmMin.text) || 0.5,
            ssFwhmMax: parseFloat(dlg.editSSFwhmMax.text) || 6.0,
            ssEccentricityMax: parseFloat(dlg.editSSEccentricity.text) || 0.70,
            ssPsfThreshold: parseFloat(dlg.editSSPsfThreshold.text) || 4.0
        },
        notifications: {
            telegramEnabled: !!dlg.telegramEnabled,
            telegramBotToken: dlg.telegramBotToken || "",
            telegramChatId: dlg.telegramChatId || ""
        }
    };
}

function PP_applySettings(dlg, settings){
    if (!dlg || !settings) return;

    // Apply paths
    if (settings.paths){
        if (settings.paths.lights !== undefined) dlg.rowLights.edit.text = settings.paths.lights;
        if (settings.paths.masters !== undefined) dlg.rowMasters.edit.text = settings.paths.masters;
        if (settings.paths.raw !== undefined) dlg.editRaw.text = settings.paths.raw;
        if (settings.paths.work1 !== undefined) dlg.rowWork1.edit.text = settings.paths.work1;
        if (settings.paths.work2 !== undefined) dlg.rowWork2.edit.text = settings.paths.work2;
    }

    // Apply options
    if (settings.options){
        if (settings.options.useTwoWork !== undefined){
            dlg.cbTwoWork.checked = !!settings.options.useTwoWork;
            dlg.rowWork2.sizer.visible = dlg.cbTwoWork.checked;
        }
        if (settings.options.doCal !== undefined) dlg.cbCal.checked = !!settings.options.doCal;
        if (settings.options.useBias !== undefined) dlg.cbUseBias.checked = !!settings.options.useBias;
        if (settings.options.doCC !== undefined) dlg.cbCC.checked = !!settings.options.doCC;
        if (settings.options.doSS !== undefined) dlg.cbSS.checked = !!settings.options.doSS;
        if (settings.options.autoRef !== undefined) dlg.cbAutoRef.checked = !!settings.options.autoRef;
        if (settings.options.doSA !== undefined) dlg.cbSA.checked = !!settings.options.doSA;
        if (settings.options.doLN !== undefined) dlg.cbLN.checked = !!settings.options.doLN;
        if (settings.options.doII !== undefined) dlg.cbII.checked = !!settings.options.doII;
        if (settings.options.doDrizzle !== undefined) dlg.cbDrz.checked = !!settings.options.doDrizzle;
        if (settings.options.drizzleScale !== undefined){
            var scaleIdx = (settings.options.drizzleScale || 1) - 1; // 1,2 -> 0,1
            if (scaleIdx >= 0 && scaleIdx <= 1) dlg.comboDrzScale.currentItem = scaleIdx;
        }
        if (settings.options.saveLog !== undefined) dlg.cbSaveLog.checked = !!settings.options.saveLog;
        // SubframeSelector reject thresholds
        if (settings.options.ssFwhmMin !== undefined) dlg.editSSFwhmMin.text = String(settings.options.ssFwhmMin);
        if (settings.options.ssFwhmMax !== undefined) dlg.editSSFwhmMax.text = String(settings.options.ssFwhmMax);
        if (settings.options.ssEccentricityMax !== undefined) dlg.editSSEccentricity.text = String(settings.options.ssEccentricityMax);
        if (settings.options.ssPsfThreshold !== undefined) dlg.editSSPsfThreshold.text = String(settings.options.ssPsfThreshold);
    }

    // Apply notifications
    if (settings.notifications){
        if (settings.notifications.telegramEnabled !== undefined) dlg.telegramEnabled = !!settings.notifications.telegramEnabled;
        if (settings.notifications.telegramBotToken !== undefined) dlg.telegramBotToken = settings.notifications.telegramBotToken;
        if (settings.notifications.telegramChatId !== undefined) dlg.telegramChatId = settings.notifications.telegramChatId;
    }
}

function PP_saveSettings(dlg){
    if (!dlg) return;

    var sfd = new SaveFileDialog();
    sfd.caption = "Save ANAWBPPS Settings";
    sfd.filters = [["JSON files", "*.json"], ["All files", "*"]];
    sfd.defaultExtension = "json";
    sfd.initialPath = PP_getDefaultSettingsPath();

    if (!sfd.execute()) return;

    try{
        var settings = PP_collectSettings(dlg);
        var json = JSON.stringify(settings, null, 2);

        var f = new File();
        f.createForWriting(sfd.fileName);
        f.outTextLn(json);
        f.close();

        Console.noteln("[settings] Settings saved to: " + sfd.fileName);
        showDialogBox("ANAWBPPS Settings", "Settings saved successfully to:\n\n" + sfd.fileName);
    }catch(e){
        Console.criticalln("[settings] Failed to save settings: " + e);
        showDialogBox("ANAWBPPS Settings Error", "Failed to save settings:\n\n" + e.toString());
    }
}

function PP_loadSettings(dlg){
    if (!dlg) return;

    var ofd = new OpenFileDialog();
    ofd.caption = "Load ANAWBPPS Settings";
    ofd.filters = [["JSON files", "*.json"], ["All files", "*"]];
    ofd.initialPath = PP_getDefaultSettingsPath();

    if (!ofd.execute()) return;

    try{
        // Read file using File.readTextFile
        var text = File.readTextFile(ofd.fileName);
        var settings = JSON.parse(text);

        // Validate settings structure
        if (!settings || !settings.appName || settings.appName != "ANAWBPPS"){
            throw new Error("Invalid settings file format");
        }

        PP_applySettings(dlg, settings);

        Console.noteln("[settings] Settings loaded from: " + ofd.fileName);
        showDialogBox("ANAWBPPS Settings", "Settings loaded successfully from:\n\n" + ofd.fileName);
    }catch(e){
        Console.criticalln("[settings] Failed to load settings: " + e);
        showDialogBox("ANAWBPPS Settings Error", "Failed to load settings:\n\n" + e.toString());
    }
}

function PP_autoLoadSettings(dlg){
    if (!dlg) return;

    var settingsFile = PP_getDefaultSettingsPath();

    if (!File.exists(settingsFile)){
        Console.writeln("[settings] No saved settings found at: " + settingsFile);
        return;
    }

    try{
        // Read file using File.readTextFile
        var text = File.readTextFile(settingsFile);
        var settings = JSON.parse(text);

        // Validate settings structure
        if (!settings || !settings.appName || settings.appName != "ANAWBPPS"){
            Console.warningln("[settings] Invalid settings file format, skipping auto-load");
            return;
        }

        PP_applySettings(dlg, settings);

        Console.noteln("[settings] Auto-loaded settings from: " + settingsFile);
    }catch(e){
        Console.warningln("[settings] Failed to auto-load settings: " + e);
    }
}

// ============================================================================
// Notifications Settings Dialog
// ============================================================================

function NotificationsSettingsDialog(parentDlg){
    this.__base__ = Dialog;
    this.__base__();

    var self = this;

    // Store parent reference
    this.parentDlg = parentDlg;

    // Title
    this.titleLabel = new Label(this);
    this.titleLabel.text = "Notifications Settings";
    this.titleLabel.styleSheet = "QLabel { font-weight: bold; font-size: 11pt; }";

    // Enable Telegram checkbox
    this.cbEnableTelegram = new CheckBox(this);
    this.cbEnableTelegram.text = "Enable Telegram notifications";
    this.cbEnableTelegram.checked = !!(parentDlg.telegramEnabled);
    this.cbEnableTelegram.toolTip = "Send notification when pipeline completes";

    // Instructions
    this.instructionsLabel = new Label(this);
    this.instructionsLabel.text =
        "To receive Telegram notifications:\n" +
        "1. Create a bot via @BotFather on Telegram (get bot token)\n" +
        "2. Get your chat ID via @userinfobot or @RawDataBot\n" +
        "3. Enter the credentials below";
    this.instructionsLabel.wordWrapping = true;
    this.instructionsLabel.styleSheet = "QLabel { margin: 8px 0; color: #666; }";

    // Bot Token
    this.tokenLabel = new Label(this);
    this.tokenLabel.text = "Bot Token:";
    this.tokenLabel.setFixedWidth(80);

    this.tokenEdit = new Edit(this);
    this.tokenEdit.text = parentDlg.telegramBotToken || "";
    this.tokenEdit.toolTip = "Enter your Telegram bot token (from @BotFather)";
    this.tokenEdit.setMinWidth(400);

    this.tokenSizer = new HorizontalSizer;
    this.tokenSizer.spacing = 6;
    this.tokenSizer.add(this.tokenLabel);
    this.tokenSizer.add(this.tokenEdit, 100);

    // Chat ID
    this.chatIdLabel = new Label(this);
    this.chatIdLabel.text = "Chat ID:";
    this.chatIdLabel.setFixedWidth(80);

    this.chatIdEdit = new Edit(this);
    this.chatIdEdit.text = parentDlg.telegramChatId || "";
    this.chatIdEdit.toolTip = "Enter your Telegram chat ID (from @userinfobot)";

    this.chatIdSizer = new HorizontalSizer;
    this.chatIdSizer.spacing = 6;
    this.chatIdSizer.add(this.chatIdLabel);
    this.chatIdSizer.add(this.chatIdEdit, 100);

    // Test button
    this.testButton = new PushButton(this);
    this.testButton.text = "Send Test Message";
    this.testButton.icon = this.scaledResource(":/icons/network.png");
    this.testButton.toolTip = "Test Telegram notification";
    this.testButton.onClick = function(){
        var token = self.tokenEdit.text.trim();
        var chatId = self.chatIdEdit.text.trim();

        if (!token || !chatId){
            showDialogBox("Telegram Settings", "Please enter both Bot Token and Chat ID");
            return;
        }

        Console.writeln("=".repeat(60));
        Console.writeln("[notifications] Testing Telegram notification...");

        var success = NOTIF_sendTelegram(token, chatId, "🔭 ANAWBPPS Test: Telegram notifications are working!");

        if (success){
            showDialogBox("Telegram Test", "Test message sent successfully!\nCheck your Telegram app.");
        }else{
            showDialogBox("Telegram Test", "Failed to send test message.\nCheck the Console for details.");
        }
    };

    // Buttons
    this.okButton = new PushButton(this);
    this.okButton.text = "OK";
    this.okButton.icon = this.scaledResource(":/icons/ok.png");
    this.okButton.onClick = function(){
        // Save values back to parent dialog
        self.parentDlg.telegramEnabled = self.cbEnableTelegram.checked;
        self.parentDlg.telegramBotToken = self.tokenEdit.text.trim();
        self.parentDlg.telegramChatId = self.chatIdEdit.text.trim();

        Console.writeln("[notifications] Settings updated");
        self.ok();
    };

    this.cancelButton = new PushButton(this);
    this.cancelButton.text = "Cancel";
    this.cancelButton.icon = this.scaledResource(":/icons/cancel.png");
    this.cancelButton.onClick = function(){
        self.cancel();
    };

    this.buttonsSizer = new HorizontalSizer;
    this.buttonsSizer.spacing = 6;
    this.buttonsSizer.add(this.testButton);
    this.buttonsSizer.addStretch();
    this.buttonsSizer.add(this.okButton);
    this.buttonsSizer.add(this.cancelButton);

    // Main layout
    this.sizer = new VerticalSizer;
    this.sizer.margin = 12;
    this.sizer.spacing = 8;
    this.sizer.add(this.titleLabel);
    this.sizer.add(this.cbEnableTelegram);
    this.sizer.add(this.instructionsLabel);
    this.sizer.addSpacing(6);
    this.sizer.add(this.tokenSizer);
    this.sizer.add(this.chatIdSizer);
    this.sizer.addSpacing(10);
    this.sizer.add(this.buttonsSizer);

    this.windowTitle = "ANAWBPPS - Notifications Settings";
    this.adjustToContents();
}

NotificationsSettingsDialog.prototype = new Dialog;

function PP_runSubframeSelector_UI(dlg, PLAN, workFolders, LI, options){
    if (!dlg) throw new Error("Progress dialog is not provided.");
    if (!PLAN || !PLAN.groups) throw new Error("Plan is empty (no groups).");
    var cameraGain = (options && options.cameraGain) || 0.3330;
    var scale = (options && options.subframeScale) || 0.7210; //ES150_F7

    var preferCC = (options && options.preferCC !== false);
    var autoReference = (options && options.autoReference !== false);

    // Extract SS threshold parameters
    var ssFwhmMin = (options && options.ssFwhmMin) || 0.5;
    var ssFwhmMax = (options && options.ssFwhmMax) || 6.0;
    var ssEccentricityMax = (options && options.ssEccentricityMax) || 0.70;
    var ssPsfThreshold = (options && options.ssPsfThreshold) || 4.0;

    Console.noteln("[ss] Running SubframeSelector (Measure+Output) for all groups...");
    Console.noteln("[ss]   Scale: " + scale + " arcsec/px, Gain: " + cameraGain);
    Console.noteln("[ss]   FWHM Thresholds: " + ssFwhmMin.toFixed(2) + " - " + ssFwhmMax.toFixed(2) + " px");
    Console.noteln("[ss]   Eccentricity Max: " + ssEccentricityMax.toFixed(2));
    Console.noteln("[ss]   PSF Threshold: " + ssPsfThreshold.toFixed(2) + " (" + (100/ssPsfThreshold).toFixed(1) + "% of max)");

    // Convert LI to items array if needed
    var lightsIndex = (LI && LI.items) ? LI.items : LI;

    var result = SS_runForAllGroups({
        PLAN: PLAN,
        workFolders: workFolders,
        LI: lightsIndex,
        preferCC: preferCC,
        autoReference: autoReference,
        cameraGain: cameraGain,
        subframeScale: scale,
        ssFwhmMin: ssFwhmMin,
        ssFwhmMax: ssFwhmMax,
        ssEccentricityMax: ssEccentricityMax,
        ssPsfThreshold: ssPsfThreshold,
        dlg: dlg
    });

    Console.noteln("[ss] SubframeSelector complete.");
    return result;
}

function PP_runStarAlignment_UI(dlg, PLAN, workFolders){
    Console.noteln("[sa] Running StarAlignment...");
    var result = SA_runForAllTargets({
        PLAN: PLAN,
        workFolders: workFolders,
        dlg: dlg
    });
    Console.noteln("[sa] StarAlignment complete.");
    return result;
}
function PP_runLocalNormalization_UI(dlg, PLAN, workFolders){
    Console.noteln("[ln] Running LocalNormalization...");
    var result = LN_runForAllGroups({
        PLAN: PLAN,
        workFolders: workFolders,
        dlg: dlg
    });
    Console.noteln("[ln] LocalNormalization complete.");
    return result;
}
function PP_runImageIntegration_UI(dlg, PLAN, workFolders, useLN){
    Console.noteln("[ii] Running ImageIntegration...");
    var result = II_runForAllGroups({
        PLAN: PLAN,
        workFolders: workFolders,
        useLN: useLN,
        dlg: dlg
    });
    Console.noteln("[ii] ImageIntegration complete.");
    return result;
}

function PP_runDrizzleIntegration_UI(dlg, PLAN, workFolders, useLN, scale){
    Console.noteln("[di] Running DrizzleIntegration...");
    var result = DI_runForAllGroups({
        PLAN: PLAN,
        workFolders: workFolders,
        useLN: useLN,
        scale: scale || 1.0,
        dlg: dlg
    });
    Console.noteln("[di] DrizzleIntegration complete.");
    return result;
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

    // TODO-32: Debayered folder always in WORK1
    var debayered = joinPath(base1, DIR_DEBAYERED);
    ensureDir(debayered);

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
        debayered: debayered,
        trash: trash
    };
}

// ============================================================================
// Master Calibration Files Creation with Progress UI
// ============================================================================
function PP_runCreateMasters_UI(dlg, rawPath, mastersPath, work1Path, work2Path){
    if (!dlg) throw new Error("Progress dialog is not provided.");

    // Map to store progress rows for each master file
    if (!dlg.masterRows) dlg.masterRows = {};

    var ok = true, err = "";

    // Progress callback to handle both initialization and progress updates
    function progressCallback(phase, data){
        if (phase === 'init'){
            // Create all rows when groups are known
            var groups = data.groups;

            // MasterDark Integration rows
            for (var i = 0; i < groups.darks.length; i++){
                var darkGroup = groups.darks[i];
                var fileName = MC_generateMasterFileName(darkGroup, "Dark");
                var key = "dark_" + (i+1);
                var row = dlg.addRow("MasterDark Integration", fileName);
                dlg.masterRows[key] = row;
                PP_setStatus(dlg, row, PP_iconQueued());
                PP_setNote(dlg, row, darkGroup.items.length + "/" + darkGroup.items.length + " queued");
            }

            // MasterDarkFlat Integration rows
            for (var i = 0; i < groups.darkFlats.length; i++){
                var dfGroup = groups.darkFlats[i];
                var fileName = MC_generateMasterFileName(dfGroup, "DarkFlat");
                var key = "darkflat_" + (i+1);
                var row = dlg.addRow("MasterDarkFlat Integration", fileName);
                dlg.masterRows[key] = row;
                PP_setStatus(dlg, row, PP_iconQueued());
                PP_setNote(dlg, row, dfGroup.items.length + "/" + dfGroup.items.length + " queued");
            }

            // Flat Calibration rows - create for all groups
            for (var i = 0; i < groups.flats.length; i++){
                var flatGroup = groups.flats[i];
                var key = "calibflat_" + (i + 1);
                var fileName = MC_generateMasterFileName(flatGroup, "Flat");
                var row = dlg.addRow("Flat Calibration", fileName);
                dlg.masterRows[key] = row;
                PP_setStatus(dlg, row, PP_iconQueued());
                PP_setNote(dlg, row, flatGroup.items.length + "/" + flatGroup.items.length + " queued");
            }

            // MasterFlat Integration rows
            for (var i = 0; i < groups.flats.length; i++){
                var flatGroup = groups.flats[i];
                var fileName = MC_generateMasterFileName(flatGroup, "Flat");
                var key = "flat_" + (i+1);
                var row = dlg.addRow("MasterFlat Integration", fileName);
                dlg.masterRows[key] = row;
                PP_setStatus(dlg, row, PP_iconQueued());
                PP_setNote(dlg, row, flatGroup.items.length + "/" + flatGroup.items.length + " queued");
            }

            try { processEvents(); } catch(_){}

        } else if (phase === 'dfmatches'){
            // Mark unmatched Flat Calibration rows as Skipped
            var dfMatches = data.dfMatches;
            var flatGroups = data.flatGroups;

            Console.noteln("[UI] dfMatches phase: " + Object.keys(dfMatches).length + " matched groups");
            for (var k in dfMatches){
                if (dfMatches.hasOwnProperty(k)){
                    Console.noteln("[UI]   Matched group index: " + k + " (type: " + typeof k + ")");
                }
            }

            // Check each Flat group
            for (var i = 0; i < flatGroups.length; i++){
                var calibKey = "calibflat_" + (i + 1);
                var row = dlg.masterRows[calibKey];

                if (!row){
                    Console.warningln("[UI] Row not found for " + calibKey);
                    continue;
                }

                // Check if this group is matched (check both numeric and string keys)
                var isMatched = dfMatches.hasOwnProperty(i) || dfMatches.hasOwnProperty(String(i));

                Console.noteln("[UI] Group " + i + ": " + (isMatched ? "matched" : "unmatched"));

                if (!isMatched){
                    // Mark as skipped
                    PP_setStatus(dlg, row, PP_iconSkipped());
                    PP_setNote(dlg, row, "No DarkFlat found");
                }
            }

            try { processEvents(); } catch(_){}

        } else if (phase === 'progress'){
            // Update row status during processing
            var type = data.type;
            var index = data.index;
            var groupInfo = data.groupInfo;
            var itemPhase = groupInfo.phase; // 'start' or 'complete'

            var key = type + "_" + index;
            var row = dlg.masterRows[key];

            if (!row){
                Console.warningln("[mc] Progress callback: row not found for " + key);
                return;
            }

            if (itemPhase === 'start'){
                // Determine operation type for note
                var operationType = "";
                if (type === "dark" || type === "darkflat" || type === "flat"){
                    operationType = "integrating";
                } else if (type === "calibflat"){
                    operationType = "calibrating";
                }

                // Mark as running
                var fileCount = groupInfo.fileCount || 0;
                PP_setStatus(dlg, row, PP_iconRunning());
                PP_setNote(dlg, row, "0/" + fileCount + " " + operationType);
                try { processEvents(); } catch(_){}

            } else if (itemPhase === 'complete'){
                // Mark as complete with elapsed time
                var elapsed = groupInfo.elapsed || 0; // in seconds
                var fileCount = groupInfo.fileCount || 0;
                PP_setElapsed(dlg, row, PP_fmtHMS(elapsed));
                PP_setStatus(dlg, row, groupInfo.success ? PP_iconSuccess() : PP_iconError());

                // Determine completion note
                var noteText = "";
                if (groupInfo.success){
                    if (type === "dark" || type === "darkflat" || type === "flat"){
                        noteText = fileCount + "/" + fileCount + " integrated";
                    } else if (type === "calibflat"){
                        noteText = fileCount + "/" + fileCount + " calibrated";
                    }
                } else {
                    noteText = "Failed: " + (groupInfo.error || "Unknown error");
                }
                PP_setNote(dlg, row, noteText);
                try { processEvents(); } catch(_){}
            }
        }
    }

    try {
        // Run master creation with progress callback
        MC_createMasters(rawPath, mastersPath, work1Path, work2Path, progressCallback);
        // Statuses and elapsed times are updated via callbacks
    } catch(e){
        ok = false;
        err = e.toString();

        // Mark running rows as error if any failed
        for (var key in dlg.masterRows){
            if (dlg.masterRows.hasOwnProperty(key)){
                var row = dlg.masterRows[key];
                // Only mark as error if it's still running or queued
                var currentStatus = "";
                try {
                    if (row && row.text) currentStatus = row.text(3);
                } catch(_){}
                if (currentStatus.indexOf("Running") >= 0 || currentStatus.indexOf("Queued") >= 0){
                    PP_setStatus(dlg, row, PP_iconError());
                    PP_setNote(dlg, row, "Failed: " + (err || "Unknown error"));
                }
            }
        }
    }

    try { processEvents(); } catch(_){}

    if (!ok) throw new Error(err);
    return { ok: true };
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

    this.windowTitle = "ANAWBPPS v" + VERSION + " — Configuration";

    this.rowLights  = new PathRow(this, "Lights Folder:",  "Folder with light frames (.xisf/.fits)");
    this.rowMasters = new PathRow(this, "Masters Folder:", "Folder with master frames");

    var lightsRowSizer = new HorizontalSizer;
    lightsRowSizer.spacing = 6;
    lightsRowSizer.add(this.rowLights.sizer, 100);

    var mastersRowSizer = new HorizontalSizer;
    mastersRowSizer.spacing = 6;
    mastersRowSizer.add(this.rowMasters.sizer, 100);

    // Raw Calibration Files row with Create Masters button
    this.lblRaw = new Label(this);
    this.lblRaw.text = "Raw Calibration Files:";
    this.lblRaw.minWidth = 170;

    this.editRaw = new Edit(this);
    this.editRaw.minWidth = 420;
    this.editRaw.toolTip = "Folder with raw bias/dark/flat frames for master creation";
    this.editRaw.onEditCompleted = function(){
        // Enable/disable Create button based on path
        self.btnCreateMasters.enabled = (self.editRaw.text.trim().length > 0);
    };

    this.btnBrowseRaw = new ToolButton(this);
    this.btnBrowseRaw.icon = this.scaledResource(":/browser/select-file.png");
    this.btnBrowseRaw.setScaledFixedSize(20, 20);
    this.btnBrowseRaw.toolTip = "Select folder";
    this.btnBrowseRaw.onClick = function(){
        var d = new GetDirectoryDialog;
        d.caption = "Raw Calibration Files";
        if (d.execute())
            self.editRaw.text = d.directory;
        // Enable/disable Create button based on path
        self.btnCreateMasters.enabled = (self.editRaw.text.trim().length > 0);
    };

    this.btnCreateMasters = new PushButton(this);
    this.btnCreateMasters.text = "🛠️ Create Masters";
    this.btnCreateMasters.toolTip = "Create master calibration files from raw frames";
    this.btnCreateMasters.enabled = false; // Disabled by default until path is set
    this.btnCreateMasters.onClick = function(){
        var rawPath = self.editRaw.text.trim();
        if (!rawPath){
            showDialogBox("ANAWBPPS", "Please select Raw Calibration Files folder.");
            return;
        }

        var mastersPath = self.rowMasters.edit.text.trim();
        var work1Path = self.rowWork1.edit.text.trim();
        var work2Path = self.rowWork2.edit.text.trim();

        if (!work1Path){
            showDialogBox("ANAWBPPS", "Work1 folder is required for master creation.");
            return;
        }

        Console.show();
        Console.noteln("========================================");
        Console.noteln("ANAWBPPS - Master Calibration Files Creation");
        Console.noteln("========================================");

        var ppDlg = new ProgressDialog();
        ppDlg.startTotal();
        try { ppDlg.show(); } catch(_){}

        try {
            PP_runCreateMasters_UI(ppDlg, rawPath, mastersPath, work1Path, work2Path);
            Console.noteln("========================================");
            Console.noteln("Master calibration files created successfully!");
            Console.noteln("========================================");
            ppDlg.setDone();
            showDialogBox("ANAWBPPS", "Master calibration files created successfully!\n\nCheck Console for details.");
        } catch(e){
            Console.criticalln("========================================");
            Console.criticalln("ERROR: " + e);
            Console.criticalln("========================================");
            ppDlg.setDone();
            showDialogBox("ANAWBPPS", "Error creating master calibration files:\n\n" + e + "\n\nCheck Console for details.");
        }
    };

    var rawRowSizer = new HorizontalSizer;
    rawRowSizer.spacing = 6;
    rawRowSizer.add(this.lblRaw);
    rawRowSizer.add(this.editRaw, 100);
    rawRowSizer.add(this.btnBrowseRaw);
    rawRowSizer.add(this.btnCreateMasters);

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
    this.cbUseBias = new CheckBox(this); this.cbUseBias.text = "Use Bias"; this.cbUseBias.checked = !!HARDCODED_DEFAULTS.useBias;
    this.calRowSizer = new HorizontalSizer;
    this.calRowSizer.spacing = 6;
    this.calRowSizer.add(this.cbCal);
    this.calRowSizer.add(this.cbUseBias);
    this.calRowSizer.addStretch();
    this.cbCC  = new CheckBox(this); this.cbCC.text  = "CosmeticCorrection";   this.cbCC.checked  = !!HARDCODED_DEFAULTS.doCC;

    // SubframeSelector with reject thresholds in same row
    this.cbSS  = new CheckBox(this);
    this.cbSS.text  = "SubframeSelector";
    this.cbSS.checked  = !!HARDCODED_DEFAULTS.doSS;

    this.lblSSFwhmMin = new Label(this);
    this.lblSSFwhmMin.text = "  FWHM Min:";
    this.lblSSFwhmMin.setFixedWidth(110);
    this.lblSSFwhmMin.styleSheet = "QLabel { padding-top: 2px; }";

    this.editSSFwhmMin = new Edit(this);
    this.editSSFwhmMin.text = String(HARDCODED_DEFAULTS.ssFwhmMin || 0.5);
    this.editSSFwhmMin.setFixedWidth(50);
    this.editSSFwhmMin.toolTip = "Minimum FWHM in pixels (reject if FWHM < this value). Default: 0.5";

    this.lblSSFwhmMax = new Label(this);
    this.lblSSFwhmMax.text = "  FWHM Max:";
    this.lblSSFwhmMax.setFixedWidth(115);
    this.lblSSFwhmMax.styleSheet = "QLabel { padding-top: 2px; }";

    this.editSSFwhmMax = new Edit(this);
    this.editSSFwhmMax.text = String(HARDCODED_DEFAULTS.ssFwhmMax || 6.0);
    this.editSSFwhmMax.setFixedWidth(50);
    this.editSSFwhmMax.toolTip = "Maximum FWHM in pixels (reject if FWHM > this value). Default: 6.0";

    this.lblSSEccentricity = new Label(this);
    this.lblSSEccentricity.text = "  Ecc Max:";
    this.lblSSEccentricity.setFixedWidth(90);
    this.lblSSEccentricity.styleSheet = "QLabel { padding-top: 2px; }";

    this.editSSEccentricity = new Edit(this);
    this.editSSEccentricity.text = String(HARDCODED_DEFAULTS.ssEccentricityMax || 0.70);
    this.editSSEccentricity.setFixedWidth(55);
    this.editSSEccentricity.toolTip = "Maximum Eccentricity (reject if Eccentricity > this value). 0=round, 1=elongated. Default: 0.70";

    this.lblSSPsfThreshold = new Label(this);
    this.lblSSPsfThreshold.text = "  PSF:";
    this.lblSSPsfThreshold.setFixedWidth(55);
    this.lblSSPsfThreshold.styleSheet = "QLabel { padding-top: 2px; }";

    this.editSSPsfThreshold = new Edit(this);
    this.editSSPsfThreshold.text = String(HARDCODED_DEFAULTS.ssPsfThreshold || 4.0);
    this.editSSPsfThreshold.setFixedWidth(50);
    this.editSSPsfThreshold.toolTip = "PSF Signal threshold divisor (reject if PSF < max/N). 4.0 = 25%, 2.0 = 50%, 10.0 = 10%. Default: 4.0";

    this.ssRowSizer = new HorizontalSizer;
    this.ssRowSizer.spacing = 4;
    this.ssRowSizer.add(this.cbSS);
    this.ssRowSizer.add(this.lblSSFwhmMin);
    this.ssRowSizer.add(this.editSSFwhmMin);
    this.ssRowSizer.add(this.lblSSFwhmMax);
    this.ssRowSizer.add(this.editSSFwhmMax);
    this.ssRowSizer.add(this.lblSSEccentricity);
    this.ssRowSizer.add(this.editSSEccentricity);
    this.ssRowSizer.add(this.lblSSPsfThreshold);
    this.ssRowSizer.add(this.editSSPsfThreshold);
    this.ssRowSizer.addStretch();

    // Auto reference selection (nested under SS)
    this.cbAutoRef = new CheckBox(this);
    this.cbAutoRef.text = "Automatic reference selection (TOP-1 only)";
    this.cbAutoRef.checked = !!HARDCODED_DEFAULTS.doAutoRef;
    this.cbAutoRef.toolTip = "Automatically select best reference frame. If unchecked, TOP-5 files will be saved for manual selection.";

    // INFO: After SS, manually select reference for each group in TOP-5 folders
    this.lblRefInfo = new Label(this);
    this.lblRefInfo.text = "⚠ After SubframeSelector you need MANUALLY select reference for each group";
//    this.lblRefInfo.textAlignment = TextAlign_Left;
    this.lblRefInfo.styleSheet = "QLabel { color: #0000FF; font-style: bold; }";

    this.cbSA  = new CheckBox(this); this.cbSA.text  = "StarAlignment";        this.cbSA.checked  = !!HARDCODED_DEFAULTS.doSA;
    this.cbLN  = new CheckBox(this); this.cbLN.text  = "LocalNormalization";   this.cbLN.checked  = !!HARDCODED_DEFAULTS.doLN;
    this.cbII  = new CheckBox(this); this.cbII.text  = "ImageIntegration";     this.cbII.checked  = !!HARDCODED_DEFAULTS.doII;

    // DrizzleIntegration with Scale selector
    this.cbDrz = new CheckBox(this);
    this.cbDrz.text = "DrizzleIntegration";
    this.cbDrz.checked = !!HARDCODED_DEFAULTS.doDrizzle;

    this.comboDrzScale = new ComboBox(this);
    this.comboDrzScale.addItem("1x");
    this.comboDrzScale.addItem("2x");
    this.comboDrzScale.currentItem = HARDCODED_DEFAULTS.drizzleScale ? Math.min(HARDCODED_DEFAULTS.drizzleScale - 1, 1) : 0; // 0=1x, 1=2x
    this.comboDrzScale.toolTip = "Drizzle scale factor (1x = native resolution, 2x = 2x upsampling)";
    this.comboDrzScale.setFixedWidth(80);

    this.drzRowSizer = new HorizontalSizer;
    this.drzRowSizer.spacing = 6;
    this.drzRowSizer.add(this.cbDrz);
    this.drzRowSizer.add(this.comboDrzScale);
    this.drzRowSizer.addStretch();

    this.gbOptions.sizer.add(this.calRowSizer);
    this.gbOptions.sizer.add(this.cbCC);
    this.gbOptions.sizer.add(this.ssRowSizer);
    this.gbOptions.sizer.add(this.cbAutoRef);
    this.gbOptions.sizer.add(this.lblRefInfo);
    this.gbOptions.sizer.add(this.cbSA);
    this.gbOptions.sizer.add(this.cbLN);
    this.gbOptions.sizer.add(this.cbII);
    this.gbOptions.sizer.add(this.drzRowSizer);

    // Save log to file checkbox
    this.cbSaveLog = new CheckBox(this);
    this.cbSaveLog.text = "Save Console log to file";
    this.cbSaveLog.checked = false;
    this.cbSaveLog.toolTip = "Save all console output to YYYY-MM-DD_HH-MM-SS.log in the !Integrated folder";
    this.gbOptions.sizer.add(this.cbSaveLog);

    // Initialize notification settings (will be saved/loaded)
    this.telegramEnabled = false;
    this.telegramBotToken = "";
    this.telegramChatId = "";

    // Load/Save Settings buttons (icon only)
    this.btnLoad = new ToolButton(this);
    this.btnLoad.text = "📂";
    this.btnLoad.toolTip = "Load settings from file";
    this.btnLoad.onClick = function() {
        PP_loadSettings(this.dialog);
    };

    this.btnSave = new ToolButton(this);
    this.btnSave.text = "💾";
    this.btnSave.toolTip = "Save settings to file";
    this.btnSave.onClick = function() {
        PP_saveSettings(this.dialog);
    };

    this.btnNotifications = new ToolButton(this);
    this.btnNotifications.text = "🔔";
    this.btnNotifications.toolTip = "Notifications settings (Telegram, etc.)";
    this.btnNotifications.onClick = function() {
        var notifDlg = new NotificationsSettingsDialog(this.dialog);
        notifDlg.execute();
    };

    this.btnRun = new PushButton(this);
    this.btnRun.text = "RUN";
    this.btnRun.icon = this.scaledResource(":/icons/power.png");
    this.btnRun.toolTip = "Start preprocessing pipeline";

    this.ok_Button = new PushButton(this);
    this.ok_Button.text = "OK";
    this.ok_Button.icon = this.scaledResource(":/icons/ok.png");
    this.ok_Button.toolTip = "Close dialog and keep settings";

    this.cancel_Button = new PushButton(this);
    this.cancel_Button.text = "Cancel";
    this.cancel_Button.icon = this.scaledResource(":/icons/cancel.png");
    this.cancel_Button.toolTip = "Close dialog without saving";

    this.pipelineCompleted = false;

    this.setDone = function () {
        this.pipelineCompleted = true;
        this.cancel_Button.text = "Done";
        this.cancel_Button.icon = this.scaledResource(":/icons/ok.png");
        this.cancel_Button.toolTip = "Close configuration window";
    };

    var buttons = new HorizontalSizer;
    buttons.spacing = 6;
    buttons.add(this.btnLoad);
    buttons.add(this.btnSave);
    buttons.add(this.btnNotifications);
    buttons.addStretch();
    buttons.add(this.btnRun);
    buttons.add(this.ok_Button);
    buttons.add(this.cancel_Button);

    this.sizer = new VerticalSizer;
    this.sizer.margin = 6;
    this.sizer.spacing = 6;
    this.sizer.add(lightsRowSizer);
    this.sizer.add(mastersRowSizer);
    this.sizer.add(rawRowSizer);
    this.sizer.add(this.rowWork1.sizer);
    this.sizer.add(this.cbTwoWork);
    this.sizer.add(this.rowWork2.sizer);
    this.sizer.add(this.gbOptions);
    this.sizer.add(buttons);

    this.adjustToContents();

    // Auto-load settings from default location
    PP_autoLoadSettings(this);

    this.onClose = function(){
        try{
            if (self.rowLights)  self.rowLights.destroy();
            if (self.rowMasters) self.rowMasters.destroy();
            if (self.rowWork1)   self.rowWork1.destroy();
            if (self.rowWork2)   self.rowWork2.destroy();
            if (self.btnBrowseRaw && self.btnBrowseRaw.onClick) self.btnBrowseRaw.onClick = null;
            if (self.btnCreateMasters && self.btnCreateMasters.onClick) self.btnCreateMasters.onClick = null;
            if (self.editRaw && self.editRaw.onEditCompleted) self.editRaw.onEditCompleted = null;
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
        var t0 = Date.now();
        var errorCount = 0;

        // Initialize logging early if enabled
        if (self.cbSaveLog && self.cbSaveLog.checked){
            var work1Root = self.rowWork1.edit.text.trim();
            var work2Root = self.rowWork2.edit.text.trim();
            var useTwo = self.cbTwoWork.checked;

            // Determine base directory (same logic as makeWorkFolders)
            var base1 = work1Root;
            if (base1 && !base1.match(/!!!WORK_LIGHTS$/)){
                base1 = base1 + "/!!!WORK_LIGHTS";
            }

            var base = base1;
            if (useTwo && work2Root){
                var base2 = work2Root;
                if (base2 && !base2.match(/!!!WORK_LIGHTS$/)){
                    base2 = base2 + "/!!!WORK_LIGHTS";
                }
                base = base2;
            }

            var integratedFolder = base + "/!Integrated";

            // Create folder if not exists
            if (!File.directoryExists(integratedFolder)){
                File.createDirectory(integratedFolder, true);
            }

            LOG_init(integratedFolder);
            LOG_hijackConsole(); // Redirect all Console.* calls to log file
        }

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

            var useBias = self.cbUseBias ? self.cbUseBias.checked : false;
            var PLAN = IC_buildCalibrationPlan(LI, MI, planPath, useBias);
            if (!PLAN || !PLAN.groups){
                throw new Error("Plan build failed - no groups");
            }

            var work1Root = self.rowWork1.edit.text.trim();
            var work2Root = self.rowWork2.edit.text.trim();
            var useTwo = self.cbTwoWork.checked;
            var wf = makeWorkFolders(work1Root, work2Root, useTwo);

            if (self.cbCal && self.cbCal.checked){
                IC_runForAllGroups({
                    plan: PLAN,
                    workFolders: wf,
                    useBias: useBias,
                    dlg: ppDlg
                });
            }

            if (self.cbCC && self.cbCC.checked){
                Console.noteln("[cc] Running CosmeticCorrection...");
                CC_runForAllGroups({
                    PLAN: PLAN,
                    workFolders: wf,
                    dlg: ppDlg
                });
                Console.noteln("[cc] CosmeticCorrection complete.");

                // TODO-32: Debayer runs automatically for CFA groups after CC
                Console.noteln("[debayer] Running Debayer (automatic for CFA groups)...");
                DEBAYER_runForAllGroups({
                    calibPlan: PLAN,
                    workFolders: wf,
                    dlg: ppDlg
                });
                Console.noteln("[debayer] Debayer complete.");
            }

            if (self.cbSS && self.cbSS.checked){
                // Read SS threshold values from UI
                var ssFwhmMin = parseFloat(self.editSSFwhmMin.text) || 0.5;
                var ssFwhmMax = parseFloat(self.editSSFwhmMax.text) || 6.0;
                var ssEccentricityMax = parseFloat(self.editSSEccentricity.text) || 0.70;
                var ssPsfThreshold = parseFloat(self.editSSPsfThreshold.text) || 4.0;

                PP_runSubframeSelector_UI(ppDlg, PLAN, wf, LI, {
                    preferCC: (self.cbCC && self.cbCC.checked),
                    autoReference: (self.cbAutoRef && self.cbAutoRef.checked),
                    cameraGain: 0.3330,
                    subframeScale: 0.7210, //ES150_F7
                    ssFwhmMin: ssFwhmMin,
                    ssFwhmMax: ssFwhmMax,
                    ssEccentricityMax: ssEccentricityMax,
                    ssPsfThreshold: ssPsfThreshold
                });
            }

            if (self.cbSA && self.cbSA.checked){
                PP_runStarAlignment_UI(ppDlg, PLAN, wf);
            }

            if (self.cbLN && self.cbLN.checked){
                PP_runLocalNormalization_UI(ppDlg, PLAN, wf);
            }

            if (self.cbII && self.cbII.checked){
                var useLN = (self.cbLN && self.cbLN.checked);
                PP_runImageIntegration_UI(ppDlg, PLAN, wf, useLN);
            }

            if (self.cbDrz && self.cbDrz.checked){
                var useLN = (self.cbLN && self.cbLN.checked);
                var scale = (self.comboDrzScale.currentItem + 1); // 0=1x, 1=2x -> 1.0, 2.0
                PP_runDrizzleIntegration_UI(ppDlg, PLAN, wf, useLN, scale);
            }

            PP_finalizeProgress(ppDlg);

            try {
                self.setDone();
            } catch(_){}

            // Send completion notification
            var totalTime = Date.now() - t0;
            Console.noteln("[run] COMPLETE - Total time: " + (totalTime/1000).toFixed(1) + "s");

            if (self.telegramEnabled && self.telegramBotToken && self.telegramChatId){
                try{
                    NOTIF_sendCompletionTelegram({
                        botToken: self.telegramBotToken,
                        chatId: self.telegramChatId,
                        totalTime: totalTime,
                        errors: errorCount
                    });
                }catch(notifErr){
                    Console.warningln("[notifications] Failed to send completion notification: " + notifErr);
                }
            }

            // Close log file and restore Console
            LOG_restoreConsole();
            LOG_close();

        } catch(e){
            errorCount++;
            Console.criticalln("[run] Pipeline error: " + e);
            showDialogBox("ANAWBPPS — Error", "Pipeline failed:\n" + e);

            // Send error notification
            if (self.telegramEnabled && self.telegramBotToken && self.telegramChatId){
                try{
                    var totalTime = Date.now() - t0;
                    NOTIF_sendCompletionTelegram({
                        botToken: self.telegramBotToken,
                        chatId: self.telegramChatId,
                        totalTime: totalTime,
                        errors: errorCount
                    });
                }catch(notifErr){
                    Console.warningln("[notifications] Failed to send error notification: " + notifErr);
                }
            }

            // Close log file and restore Console
            LOG_restoreConsole();
            LOG_close();
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
