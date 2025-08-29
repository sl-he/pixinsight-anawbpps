/*
  ANAWBPPS — UI-only scaffold (folder picker)
  Author: https://github.com/sl-he
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
// #include "modules/masters_index.jsh"

// ============================================================================
// Hardcoded defaults (edit these to your liking)
// ============================================================================
var HARDCODED_DEFAULTS = {
  lights:  "D:/!!!WORK/ASTROFOTO/!!!WORK_LIGHTS", // sample
  masters: "D:/!!!WORK/ASTROFOTO/!!!!!MASTERS",   // sample
  work1:   "V:/!!!WORK/ASTROFOTO/1",              // base root (script will append !!!WORK_LIGHTS if needed)
  work2:   "V:/!!!WORK/ASTROFOTO/2",              // base root (optional)
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

  // Folder pickers
  this.rowLights  = new PathRow(this, "Lights Folder:",  "Folder with light frames (.xisf/.fits)");

  // Masters folder row + Reindex button (stub)
  this.rowMasters = new PathRow(this, "Masters Folder:", "Folder with master frames (optional)");
  this.btnReindexMasters = new PushButton(this);
  this.btnReindexMasters.text = "Reindex Masters";
  this.btnReindexMasters.toolTip = "Scan the selected Masters folder and rebuild index (stub)";

  var mastersRowSizer = new HorizontalSizer;
  mastersRowSizer.spacing = 6;
  mastersRowSizer.add(this.rowMasters.sizer, 100);
  mastersRowSizer.add(this.btnReindexMasters);

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
  var buttons = new HorizontalSizer; buttons.addStretch(); buttons.spacing = 6; buttons.add(this.ok_Button); buttons.add(this.cancel_Button);

  // Layout
  this.sizer = new VerticalSizer;
  this.sizer.margin = 6;
  this.sizer.spacing = 6;
  this.sizer.add(this.rowLights.sizer);
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

  this.btnReindexMasters.onClick = function(){
    var mastersPath = self.rowMasters.edit.text.trim();
    if (!mastersPath){
      showDialogBox("ANAWBPPS", "Please select a Masters folder first.");
      return;
    }
    // Stub: to be implemented later
    Console.writeln("[masters] Reindex requested for: " + mastersPath);
    showDialogBox("ANAWBPPS", "Reindex Masters is not implemented yet.\nPath: " + mastersPath);
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

  // Create work folders (according to the two-disk rule)
  var wf = makeWorkFolders(work1, work2, useTwo);

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
      (doDrz ? "Drz "   : "") +
    "\n\n" +
    "Created work folders:\n" +
    "  Work1 base: " + wf.base1 + "\n" +
    (useTwo ? ("  Work2 base: " + wf.base2 + "\n") : "") +
    "  Calibrated: " + wf.calibrated + "\n" +
    "  Cosmetic:   " + wf.cosmetic + "\n" +
    "  Approved:   " + wf.approved + "\n" +
    "  ApprovedSet:" + wf.approvedSet + "\n" +
    "  Trash:      " + wf.trash + "\n";

  Console.writeln("[ANAWBPPS] Selected settings and work folders:\n" + summary);
  showDialogBox("ANAWBPPS — Summary", "Folders and options have been selected. Work structure created:\n\n" + summary);
}

try {
  main();
} catch (e) {
  Console.criticalln("[FATAL] " + e);
}
