/*
  ANAWBPPS — UI-only scaffold (folder picker)
  Author: https://github.com/sl-he/
  Target: PixInsight 1.9.3+ (PJSR)

  Purpose
  -------
  Minimal UI to select input/output folders and toggle workflow steps.
  • No Temp folder.
  • Work1/!!!WORK_LIGHTS contains: Calibrated, !Approved, !!!TRASH, and final integrations.
  • Work2/!!!WORK_LIGHTS contains: CosmeticCorrection (if two-disk mode is enabled).
  • In two-disk mode: !Approved is in Work1, while !Approved/Lights_Cal_CC_Reg is in Work2.
  • NO settings files: no reads, no writes. All defaults are hardcoded below.
  • All constant strings (folder/file names, UI sizes) live in anawbpps.constants.jsh.
*/

#include <pjsr/StdDialogCode.jsh>
#include <pjsr/Sizer.jsh>
#include "anawbpps.constants.jsh"
// Optional external modules (commented until implemented)
#include "modules/masters_parse.jsh"
#include "modules/lights_parse.jsh"
#include "modules/masters_index.jsh"
#include "modules/lights_index.jsh"
#include "modules/calibration_match.jsh"
#include "modules/calibration_run.jsh"
#include "modules/preprocessing_progress.jsh"
#include "modules/cosmetic_plan.jsh"
#include "modules/cosmetic_run.jsh"

// ============================================================================
// Hardcoded defaults (edit these to your liking)
// ============================================================================
var HARDCODED_DEFAULTS = {
    lights:  "D:/!!!WORK/ASTROFOTO/!!!WORK_LIGHTS", // sample
    masters: "D:/!!!WORK/ASTROFOTO/!!!!!MASTERS",   // sample
    work1:   "V:/!!!WORK/ASTROFOTO/",              // base root (script will append !!!WORK_LIGHTS if needed)
    work2:   "W:/!!!WORK/ASTROFOTO/",              // base root (optional)
    useTwo:  true,                                   // two-disk mode on/off

    // workflow toggles
    doCal: true,
    doCC:  true,
    doSSMeasure: true,
    doSSOutput:  true,
    doSA:  true,
    doLN:  true,
    doNSG: true, // alternative to LN
    doII:  true,
    doDrizzle: true
};

// ============================================================================
// File-system helpers (no config I/O here)
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
        File.createDirectory(dir, true /* recursive */);
}

// ============================================================================
// Project base helpers
// ============================================================================
function endsWithProjectBase(p){
    var s = String(p||"").replace(/\\/g,'/').replace(/\/+/g,'/');
    if (s.length === 0) return false;
    if (s.length > 1 && s.charAt(s.length-1) === '/') s = s.substring(0, s.length-1);
    var i = s.lastIndexOf('/');
    var tail = (i>=0) ? s.substring(i+1) : s;
    return tail === PROJECT_DEFAULTS_DIRNAME; // e.g. "!!!WORK_LIGHTS"
}

// Normalize a Work root to the actual project base path
function projectBase(root){
    return endsWithProjectBase(root) ? root : joinPath(root, PROJECT_DEFAULTS_DIRNAME);
}

// ============================================================================
// Work folders creation
// ============================================================================
// In two-disk mode:
//   • Work1: !!!WORK_LIGHTS, !Approved, Calibrated, !!!TRASH
//   • Work2: !!!WORK_LIGHTS/CosmeticCorrection and !!!WORK_LIGHTS/!Approved/Lights_Cal_CC_Reg
// In single-disk mode: everything lives under Work1.
function makeWorkFolders(work1Root, work2Root, useTwo){
    // Base in Work1 (avoid duplicating !!!WORK_LIGHTS if user already selected it)
    var base1 = projectBase(work1Root);
    ensureDir(base1);

    // !Approved in Work1
    var approved1 = joinPath(base1, DIR_APPROVED);
    ensureDir(approved1);

    // Calibrated in Work1
    var calibrated = joinPath(base1, DIR_CALIBRATED);
    ensureDir(calibrated);

    // !!!TRASH in Work1
    var trash = joinPath(base1, DIR_TRASH);
    ensureDir(trash);

    // Cosmetic + ApprovedSet (Reg set)
    var base2 = "";
    var cosmetic = "";
    var approvedSet;

    if (useTwo && work2Root){
        // Base in Work2 (normalized)
        base2 = projectBase(work2Root);
        ensureDir(base2);

        // CosmeticCorrection in Work2
        cosmetic = joinPath(base2, DIR_COSMETIC);
        ensureDir(cosmetic);

        // Reg set in Work2 under !Approved/Lights_Cal_CC_Reg
        var approved2 = joinPath(base2, DIR_APPROVED);
        ensureDir(approved2);
        approvedSet = joinPath(approved2, DIR_APPROVED_SET);
        ensureDir(approvedSet);
    } else {
        // Single-disk mode: Cosmetic + Reg set live in Work1
        cosmetic = joinPath(base1, DIR_COSMETIC);
        ensureDir(cosmetic);
        approvedSet = joinPath(approved1, DIR_APPROVED_SET);
        ensureDir(approvedSet);
    }

    return {
        base1: base1,
        base2: base2,
        approved: approved1,      // !Approved (Work1)
        approvedSet: approvedSet, // Reg set (Work2 if two-disk mode, else Work1)
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
    this.edit.readOnly = true;
    this.edit.minWidth = 420;
    if (tooltip) this.edit.toolTip = tooltip;

    this.btn = new ToolButton(parent);
    this.btn.icon = parent.scaledResource(":/browser/select-file.png");
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
    d.execute();
}

// ============================================================================
// Main dialog
// ============================================================================
function ANAWBPPSDialog(){
    this.__base__ = Dialog; this.__base__();
    this.windowTitle = "ANAWBPPS — Project folders (UI-only)";
    // Masters folder row + Reindex button (stub)
    this.rowMasters = new PathRow(this, "Masters Folder:", "Folder with master frames (optional)");
    this.rowLights  = new PathRow(this, "Lights Folder:",  "Folder with light frames (.xisf/.fits)");

    // --- RUN Calibration button ---
    this.btnRun = new PushButton(this);
    this.btnRun.text = "RUN";
    this.btnRun.toolTip = "RUN";

    var mastersRowSizer = new HorizontalSizer;
    mastersRowSizer.spacing = 6;
    mastersRowSizer.add(this.rowMasters.sizer, 100);

    var lightsRowSizer = new HorizontalSizer;
    lightsRowSizer.spacing = 6;
    lightsRowSizer.add(this.rowLights.sizer, 100);

    // Work folders
    this.rowWork1 = new PathRow(this, "Work1 Folder:", "Primary working folder: will create !!!WORK_LIGHTS here");
    this.cbTwoWork  = new CheckBox(this);
    this.cbTwoWork.text = "Use two working folders (Work1 + Work2)";
    this.cbTwoWork.checked = !!HARDCODED_DEFAULTS.useTwo;
    this.rowWork2   = new PathRow(this, "Work2 Folder:", "Secondary working folder (CosmeticCorrection, Reg set)");
    this.rowWork2.sizer.visible = this.cbTwoWork.checked;

    // Apply hardcoded defaults into UI
    if (HARDCODED_DEFAULTS.lights)  this.rowLights.edit.text  = HARDCODED_DEFAULTS.lights;
    if (HARDCODED_DEFAULTS.masters) this.rowMasters.edit.text = HARDCODED_DEFAULTS.masters;
    if (HARDCODED_DEFAULTS.work1)   this.rowWork1.edit.text   = HARDCODED_DEFAULTS.work1;
    if (HARDCODED_DEFAULTS.work2)   this.rowWork2.edit.text   = HARDCODED_DEFAULTS.work2;

    // Workflow toggles (initialized from hardcoded defaults)
    this.gbOptions = new GroupBox(this);
    this.gbOptions.title = "Workflow steps";
    this.gbOptions.sizer = new VerticalSizer;
    this.gbOptions.sizer.margin = 6;
    this.gbOptions.sizer.spacing = 4;

    this.cbCal = new CheckBox(this); this.cbCal.text = "ImageCalibration";           this.cbCal.checked = !!HARDCODED_DEFAULTS.doCal;
    this.cbCC  = new CheckBox(this); this.cbCC.text  = "CosmeticCorrection";         this.cbCC.checked  = !!HARDCODED_DEFAULTS.doCC;
    this.cbSSM = new CheckBox(this); this.cbSSM.text = "SubframeSelector — Measure"; this.cbSSM.checked = !!HARDCODED_DEFAULTS.doSSMeasure;
    this.cbSSO = new CheckBox(this); this.cbSSO.text = "SubframeSelector — Output";  this.cbSSO.checked = !!HARDCODED_DEFAULTS.doSSOutput;
    this.cbSA  = new CheckBox(this); this.cbSA.text  = "StarAlignment";              this.cbSA.checked  = !!HARDCODED_DEFAULTS.doSA;
    this.cbLN  = new CheckBox(this); this.cbLN.text  = "LocalNormalization";         this.cbLN.checked  = !!HARDCODED_DEFAULTS.doLN;
    this.cbNSG = new CheckBox(this); this.cbNSG.text = "NSG (instead of LN)";        this.cbNSG.checked = !!HARDCODED_DEFAULTS.doNSG;
    this.cbII  = new CheckBox(this); this.cbII.text  = "ImageIntegration";           this.cbII.checked  = !!HARDCODED_DEFAULTS.doII;
    this.cbDrz = new CheckBox(this); this.cbDrz.text = "DrizzleIntegration";         this.cbDrz.checked = !!HARDCODED_DEFAULTS.doDrizzle;

    this.gbOptions.sizer.add(this.cbCal);
    this.gbOptions.sizer.add(this.cbCC);
    this.gbOptions.sizer.add(this.cbSSM);
    this.gbOptions.sizer.add(this.cbSSO);
    this.gbOptions.sizer.add(this.cbSA);
    this.gbOptions.sizer.add(this.cbLN);
    this.gbOptions.sizer.add(this.cbNSG);
    this.gbOptions.sizer.add(this.cbII);
    this.gbOptions.sizer.add(this.cbDrz);

    // Dialog buttons
    this.ok_Button = new PushButton(this);     this.ok_Button.text = "OK";
    this.cancel_Button = new PushButton(this); this.cancel_Button.text = "Cancel";
    var buttons = new HorizontalSizer; buttons.addStretch(); buttons.spacing = 6; buttons.add(this.btnRun); buttons.add(this.ok_Button); buttons.add(this.cancel_Button);

    // Layout
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

    // Handlers
    var self = this;

    this.cbTwoWork.onCheck = function(checked){
        self.rowWork2.sizer.visible = checked;
        self.adjustToContents();
    };

// RUN button handler
    this.btnRun.onClick = function(){
        var lightsRoot  = self.rowLights.edit.text.trim();
        var mastersRoot = self.rowMasters.edit.text.trim();
        if (!lightsRoot){
            showDialogBox("ANAWBPPS", "Please select Lights folder first.");
            return;
        }
        if (!mastersRoot){
            showDialogBox("ANAWBPPS", "Please select Masters folder first.");
            return;
        }

        Console.show();
        Console.noteln("[run] START");
        Console.writeln("  Lights root : " + lightsRoot);
        Console.writeln("  Masters root: " + mastersRoot);

        // small path normalizer without regex traps
        function _norm(p){
            var s = String(p||"");
            var out = "";
            for (var i=0;i<s.length;i++){
                var c = s.charAt(i);
                out += (c === "\\") ? "/" : c;
            }
            // collapse // -> /
            var res = "";
            for (var j=0;j<out.length;j++){
                var ch = out.charAt(j);
                if (!(ch === "/" && res.length>0 && res.charAt(res.length-1) === "/"))
                    res += ch;
            }
            return res;
        }

        Console.noteln("[run] Reindex Lights & Masters, then build calibration plan…");

        // paths for control JSON outputs (not read back)
        var lightsJson  = _norm(lightsRoot  + "/lights_index.json");
        var mastersJson = _norm(mastersRoot + "/masters_index.json");
        var planPath    = _norm(lightsRoot  + "/calibration_plan.json");

        var LI = null, MI = null;

        // 1) Reindex Lights (headers-only) → returns in-memory index and writes JSON
        try{
            LI = LI_reindexLights(lightsRoot, lightsJson);
            if (!LI || !LI.items || !LI.items.length){
                Console.warningln("[run] LI_reindexLights() returned empty. Trying in-memory fallback …");
                if (typeof LI_GET_LAST_INDEX === "function")
                    LI = LI_GET_LAST_INDEX();
                else if (typeof LI_LAST_INDEX !== "undefined")
                    LI = LI_LAST_INDEX;
                else if (typeof this !== "undefined" && typeof this.LI_LAST_INDEX !== "undefined")
                    LI = this.LI_LAST_INDEX;
            }
            if (!LI || !LI.items || !LI.items.length){
                throw new Error("Lights index is empty after reindex and fallback.");
            }
        } catch(e){
            Console.criticalln("[run] Lights reindex failed: " + e);
            showDialogBox("ANAWBPPS — Error", "Lights reindex failed:\n" + e);
            return;
        }

        // 2) Reindex Masters (name-only) → returns in-memory index and writes JSON
        try{
            MI = MI_reindexMasters(mastersRoot, mastersJson);
            if (!MI || !MI.items || !MI.items.length){
                Console.warningln("[run] MI_reindexMasters() returned empty. Trying in-memory fallback …");
                if (typeof MI_GET_LAST_INDEX === "function")
                    MI = MI_GET_LAST_INDEX();
                else if (typeof MI_LAST_INDEX !== "undefined")
                    MI = MI_LAST_INDEX;
                else if (typeof this !== "undefined" && typeof this.MI_LAST_INDEX !== "undefined")
                    LI = this.MI_LAST_INDEX;
            }
            if (!MI || !MI.items || !MI.items.length){
                throw new Error("Masters index is empty after reindex and fallback.");
            }
        } catch(e){
            Console.criticalln("[run] Masters reindex failed: " + e);
            showDialogBox("ANAWBPPS — Error", "Masters reindex failed:\n" + e);
            return;
        }
        Console.noteln(
            "[run] Pools: lights=" + LI.items.length +
            ", biases=" + (MI._pools && MI._pools.biases ? MI._pools.biases.length : 0) +
            ", darks="  + (MI._pools && MI._pools.darks  ? MI._pools.darks.length  : 0) +
            ", flats="  + (MI._pools && MI._pools.flats  ? MI._pools.flats.length  : 0)
        );

// 3) Build plan entirely in-memory (group by triads) + save plan JSON
        var PLAN = null;
        try{
            PLAN = CM_buildPlanInMemory(LI, MI, planPath); // теперь возвращает объект
        } catch(e){
            Console.criticalln("[run] Plan build failed: " + e);
            showDialogBox("ANAWBPPS — Error", "Failed to build calibration plan:\n" + e);
            return;
        }

// 3.1) Верификация: план в памяти + файл на диске
        if (!PLAN || !PLAN.groups){
            Console.criticalln("[run] Plan is not present in memory (no groups).");
            showDialogBox("ANAWBPPS — Error", "Plan is not present in memory.");
            return;
        }

// Короткая проверка: количество групп и пропусков
        var groupKeys = [];
        for (var k in PLAN.groups) if (PLAN.groups.hasOwnProperty(k)) groupKeys.push(k);

        Console.noteln("[run] Plan in memory — groups=" + groupKeys.length + ", skipped=" + (PLAN.skipped?PLAN.skipped.length:0));

// Показать первые 3 ключа групп для визуальной проверки
        if (groupKeys.length){
            var head = groupKeys.slice(0,3).join(", ");
            Console.writeln("       sample groups: " + head + (groupKeys.length>3 ? " ..." : ""));
        }

// Проверка, что файл действительно лежит на диске
        try{
            if (File.exists(planPath))
                Console.writeln("[run] Plan file exists: " + planPath);
            else
                Console.warningln("[run] Plan file NOT found (expected at): " + planPath);
        } catch(_){ /* File.exists может бросить в старых сборках */ }


        // 4) Create work folder structure
        var work1Root = self.rowWork1.edit.text.trim();
        var work2Root = self.rowWork2.edit.text.trim();
        var useTwo    = self.cbTwoWork.checked;

        var wf = makeWorkFolders(work1Root, work2Root, useTwo);

        Console.noteln("[run] Work folders created:");
        Console.writeln("  Calibrated:   " + wf.calibrated);
        Console.writeln("  Approved:     " + wf.approved);
        Console.writeln("  ApprovedSet:  " + wf.approvedSet);
        Console.writeln("  Cosmetic:     " + wf.cosmetic);
        Console.writeln("  Trash:        " + wf.trash);
// 3.2) Optional: run ImageCalibration if checkbox is ON
        // --- Открываем общий прогресс-диалог заранее ---
        var ppDlg = new CalibrationProgressDialog();
        try { ppDlg.show(); } catch(_){}

        /* --- Build Cosmetic plan EARLY; DO NOT add rows yet (defer to dlg.flushDeferredCC) --- */
        var CC_PLAN = CC_makeCosmeticPlan(PLAN, wf /*, { outputPostfix: "_c", outputExtension: ".xisf" } */);
        CC_printPlanSummary(CC_PLAN);

//        var ccPlanPath = (wf && wf.calibrated) ? (wf.calibrated + "/cosmetic_plan.json") : "cosmetic_plan.json";
        var ccPlanPath = _norm(lightsRoot  + "/cosmetic_plan.json");
        CC_savePlan(CC_PLAN, ccPlanPath);

        /* Defer CC row insertion until IC rows are created */
        try{
            if (ppDlg){
                ppDlg.__ccPlan = CC_PLAN;
                ppDlg.flushDeferredCC = function(){
                    try{
                        var plan = this.__ccPlan;
                        if (!plan || !plan.groups) return;
                        if (!this.ccRowsMap) this.ccRowsMap = {};
                        var keys = []; for (var k in plan.groups) if (plan.groups.hasOwnProperty(k)) keys.push(k);
                        for (var i=0;i<keys.length;i++){
                            var gkey = keys[i], g = plan.groups[gkey];
                            var total = 0;
                            if (g && g.files && g.files.length) total = g.files.length;
                            else if (g && g.items && g.items.length) total = g.items.length;
                            else if (g && g.frames && g.frames.length) total = g.frames.length;
                            var core = (typeof CP__fmtCosmeticGroupLabel==="function") ? CP__fmtCosmeticGroupLabel(gkey) : String(gkey);
                            var label = core + " (" + total + " subs)";
                            var node = this.addRow("CosmeticCorrection", label);
                            try{ node.setText(2, "00:00:00"); }catch(_){}
                            /* queued rows: no note, keep it empty (match IC behavior) */
                            try{ if (this.setRowNote) this.setRowNote(node, ""); }catch(_){}
                            this.ccRowsMap[gkey] = { node: node, total: total };
                        }
                        try{ processEvents(); }catch(_){}
                        this.__ccPreAdded = true;
                    }catch(_){}
                };
            }
        }catch(_){}

        /* --- Now run Calibration --- */
        if (self.cbCal && self.cbCal.checked){
            Console.noteln("[run] Calibration checkbox is ON — running ImageCalibration…");
            try{
                CAL_runCalibration_UI(PLAN, wf, ppDlg);
            } catch(e){
                Console.criticalln("[run] Calibration failed: " + e);
            }
        } else {
            Console.writeln("[run] Calibration checkbox is OFF — skipping ImageCalibration.");
        }


        // --- Запуск CosmeticCorrection, если включена галочка ---
        if (self.cbCC && self.cbCC.checked){
            Console.noteln("[run] CosmeticCorrection checkbox is ON — running CosmeticCorrection…");
            try{
                CC_runCosmetic_UI(CC_PLAN, wf, ppDlg);
            } catch(e){
                Console.criticalln("[run] CosmeticCorrection failed: " + e);
            }
        } else {
            Console.writeln("[run] CosmeticCorrection checkbox is OFF — skipping CosmeticCorrection.");
        }
    };
    this.ok_Button.onClick = function(){ self.ok(); };
    this.cancel_Button.onClick = function(){ self.cancel(); };
}

ANAWBPPSDialog.prototype = new Dialog;

// ============================================================================
// Entry point
// ============================================================================
function main(){
    Console.show();

    var dlg = new ANAWBPPSDialog();
    if (!dlg.execute()){
        Console.writeln("[info] Canceled");
        return;
    }

    var lights  = dlg.rowLights.edit.text.trim();
    var masters = dlg.rowMasters.edit.text.trim();
    var work1   = dlg.rowWork1.edit.text.trim();
    var work2   = dlg.rowWork2.edit.text.trim();
    var useTwo  = dlg.cbTwoWork.checked;

    // Workflow toggles
    var doCal = dlg.cbCal.checked;
    var doCC  = dlg.cbCC.checked;
    var doSSM = dlg.cbSSM.checked;
    var doSSO = dlg.cbSSO.checked;
    var doSA  = dlg.cbSA.checked;
    var doLN  = dlg.cbLN.checked;
    var doNSG = dlg.cbNSG.checked;
    var doII  = dlg.cbII.checked;
    var doDrz = dlg.cbDrz.checked;

    // Basic validation
    if (!lights){ showDialogBox("ANAWBPPS", "Please select Lights folder."); return; }
    if (!work1){  showDialogBox("ANAWBPPS", "Please select Work1 folder.");  return; }
    if (useTwo && !work2){ showDialogBox("ANAWBPPS", "Two-disk mode is enabled: please select Work2 folder."); return; }

    // Summary
    var summary =
        "Lights : " + lights + "\n" +
        "Masters: " + (masters || "—") + "\n" +
        "Work1  : " + work1 + "\n" +
        (useTwo ? ("Work2  : " + work2 + "\n") : "") +
        "\n" +
        "Workflow: " +
        (doCal ? "Cal "   : "") +
        (doCC  ? "CC "    : "") +
        (doSSM ? "SS-M "  : "") +
        (doSSO ? "SS-O "  : "") +
        (doSA  ? "SA "    : "") +
        (doLN  ? "LN "    : "") +
        (doNSG ? "NSG "   : "") +
        (doII  ? "II "    : "") +
        (doDrz ? "Drz "   : "");

    Console.writeln("[ANAWBPPS] Selected settings and work folders:\n" + summary);
    showDialogBox("ANAWBPPS — Summary", "Folders and options have been selected. Work structure created:\n\n" + summary);
}

try {
    main();
} catch (e) {
    Console.criticalln("[FATAL] " + e);
}
