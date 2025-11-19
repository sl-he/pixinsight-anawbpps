/*
 * ANAWBPPS - Constants and configuration
 *
 * Copyright (C) 2024-2025 sl-he
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Repository: https://github.com/sl-he/pixinsight-anawbpps
 */

// ============================================================================
// Hardcoded defaults (used only on first run, before settings.json exists)
// ============================================================================
var HARDCODED_DEFAULTS = {
    lights:  "",
    masters: "",
    work1:   "",
    work2:   "",
    useTwo:  true,
    useBias: false,
    doCal: true,
    doCC: true,
    doSS: true,
    doAutoRef: true,
    doSA:  true,
    doLN:  false,
    doII:  true,
    doDrizzle: false,
    drizzleScale: 1,  // 1x or 2x
    // SubframeSelector reject thresholds
    ssFwhmMin: 0.5,   // Minimum FWHM (pixels)
    ssFwhmMax: 6.0,   // Maximum FWHM (pixels)
    ssPsfThreshold: 4.0  // PSF Signal threshold divisor (4.0 = 25% of max, 2.0 = 50%, 10.0 = 10%)
};

// --- project defaults (stored inside Work1)
var PROJECT_DEFAULTS_DIRNAME  = "!!!WORK_LIGHTS";            // main working dir name

// --- subfolder names inside Work1/!!!WORK_LIGHTS
var DIR_APPROVED       = "!Approved";            // approved frames (SubframeSelector Output)
var DIR_APPROVED_SET   = "Lights_Cal_CC_Reg";    // registered frames
var DIR_CALIBRATED     = "Calibrated";           // calibrated frames
var DIR_COSMETIC       = "CosmeticCorrection";   // cosmetic correction results
var DIR_DEBAYERED      = "Debayered";            // debayered CFA frames (TODO-32)
var DIR_TRASH          = "!!!TRASH";             // rejected frames

// --- UI parameters
var SUMMARY_WIDTH  = 640;  // summary dialog width
var SUMMARY_HEIGHT = 220;  // summary dialog height

// --- workflow flags metadata
// This can be used to auto-generate checkboxes in the UI and summary output
var WORKFLOW_FLAGS = [
  { key: "doCal",       label: "ImageCalibration",              short: "Cal"  },
  { key: "doCC",        label: "CosmeticCorrection",            short: "CC"   },
  { key: "doSSMeasure", label: "SubframeSelector — Measure",    short: "SS-M" },
  { key: "doSSOutput",  label: "SubframeSelector — Output",     short: "SS-O" },
  { key: "doSA",        label: "StarAlignment",                 short: "SA"   },
  { key: "doLN",        label: "LocalNormalization",            short: "LN"   },
  { key: "doII",        label: "ImageIntegration",              short: "II"   },
  { key: "doDrizzle",   label: "DrizzleIntegration",            short: "Drz"  }
];
