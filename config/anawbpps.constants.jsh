// ============================================================================
// ANAWBPPS (Advanced Not Another WBPP Script) — Constants
// ============================================================================

// --- project defaults (stored inside Work1)
var PROJECT_DEFAULTS_DIRNAME  = "!!!WORK_LIGHTS";            // main working dir name
var PROJECT_DEFAULTS_FILENAME = "anawbpps.project.json";     // project settings file name

// --- global defaults (single file next to the script, in /config)
var GLOBAL_DEFAULTS_FILENAME  = "anawbpps.defaults.json";    // global settings file name

// --- subfolder names inside Work1/!!!WORK_LIGHTS
var DIR_APPROVED       = "!Approved";            // approved frames (SubframeSelector Output)
var DIR_APPROVED_SET   = "Lights_Cal_CC_Reg";    // registered frames
var DIR_CALIBRATED     = "Calibrated";           // calibrated frames
var DIR_COSMETIC       = "CosmeticCorrection";   // cosmetic correction results
var DIR_TRASH          = "!!!TRASH";             // rejected frames

// --- UI parameters
var SUMMARY_WIDTH  = 640;  // summary dialog width
var SUMMARY_HEIGHT = 220;  // summary dialog height

// --- workflow toggles metadata (so UI can be generated programmatically later)
var WORKFLOW_FLAGS = [
  { key: "doCal",       label: "ImageCalibration",              short: "Cal"  },
  { key: "doCC",        label: "CosmeticCorrection",            short: "CC"   },
  { key: "doSSMeasure", label: "SubframeSelector — Measure",    short: "SS-M" },
  { key: "doSSOutput",  label: "SubframeSelector — Output",     short: "SS-O" },
  { key: "doSA",        label: "StarAlignment",                 short: "SA"   },
  { key: "doLN",        label: "LocalNormalization",            short: "LN"   },
  { key: "doNSG",       label: "NSG (instead of LN)",           short: "NSG"  },
  { key: "doII",        label: "ImageIntegration",              short: "II"   },
  { key: "doDrizzle",   label: "DrizzleIntegration",            short: "Drz"  }
];
