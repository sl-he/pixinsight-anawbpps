# Master Calibration Creation Guide

This guide explains how to create master calibration files (Bias, Dark, DarkFlat, Flat) using ANAWBPPS built-in tool.

---

## Table of Contents

- [Overview](#overview)
- [Preparation](#preparation)
- [Step-by-Step Guide](#step-by-step-guide)
- [Master Types](#master-types)
- [File Naming Convention](#file-naming-convention)
- [Output Structure](#output-structure)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

---

## Overview

Master calibration files are essential for removing various types of noise and artifacts from your light frames:

- **Bias** - Removes readout noise (offset pattern)
- **Dark** - Removes thermal noise (hot pixels, amp glow)
- **DarkFlat** - Dark frames matching flat exposure time (for flat calibration)
- **Flat** - Removes vignetting, dust shadows, and optical imperfections

ANAWBPPS automatically creates masters by:
1. Reading FITS keywords from calibration frames
2. Grouping by camera setup (TELESCOP, INSTRUME, GAIN, OFFSET, BINNING, TEMPERATURE, EXPOSURE)
3. Integrating each group using ImageIntegration
4. Saving with descriptive names to organized folder structure

---

## Preparation

### 1. Organize Your Calibration Frames

Place all calibration frames in a single source folder. Subfolders are supported.

**Example structure:**
```
D:/Calibration_2025_11_10/
├── bias/
│   ├── bias_001.xisf
│   ├── bias_002.xisf
│   └── ... (50+ frames recommended)
├── darks/
│   ├── dark_120s_001.xisf
│   ├── dark_120s_002.xisf
│   ├── dark_300s_001.xisf
│   └── ... (20+ per exposure time)
├── darkflats/
│   ├── darkflat_0.5s_001.xisf
│   └── ... (20+ per exposure time)
└── flats/
    ├── flat_B_001.xisf
    ├── flat_G_001.xisf
    └── ... (30+ per filter)
```

### 2. Verify FITS Keywords

Master creation relies on FITS keywords. Ensure your files contain:

**Required keywords:**
- `TELESCOP` - Telescope name
- `INSTRUME` - Camera/instrument name
- `EXPTIME` - Exposure time in seconds
- `SET-TEMP` or `CCD-TEMP` - Sensor temperature
- `IMAGETYP` - Frame type (BIAS, DARK, FLAT, etc.)

**Optional but recommended:**
- `GAIN` - Sensor gain setting
- `OFFSET` - Sensor offset setting
- `XBINNING`, `YBINNING` - Binning mode
- `FILTER` - Filter name (for flats)
- `DATE-OBS` - Observation date

**Check keywords in PixInsight:**
1. Open a calibration file
2. **Process** → **FITS Header**
3. Verify required keywords are present

### 3. Configure Master Output Path

In ANAWBPPS main dialog:
1. Set **"Masters path"** to your master library location
   - Example: `E:/Masters_Library/`
2. This is where organized master files will be saved

---

## Step-by-Step Guide

### Step 1: Open ANAWBPPS

**PI Menu** → **SCRIPT** → **sl-he** → **ANAWBPPS**

### Step 2: Launch Master Creation Tool

1. In ANAWBPPS main dialog, click **"Create Masters"** button
2. A file selection dialog appears

### Step 3: Select Source Folder

1. Navigate to folder containing your calibration frames
2. Click **"Select"** or **"OK"**
3. Script will scan all subfolders recursively

### Step 4: Monitor Progress

The script will:
1. **Index files** - Read FITS keywords from all calibration frames
2. **Group by setup** - Organize by camera, temperature, exposure, etc.
3. **Create masters** - Integrate each group automatically

**Progress tracking:**
- Each group shows in progress UI with:
  - **Master type** (Bias, Dark, DarkFlat, Flat)
  - **File name** being created
  - **Status** (Queued → Running → Success)
  - **Time elapsed**

**Typical processing time:**
- **Bias** (50 frames): ~90 seconds
- **Dark** (20 frames): ~40 seconds per group
- **DarkFlat** (20 frames): ~40 seconds per group
- **Flat** (30 frames): ~60 seconds per group

### Step 5: Verify Output

After completion, check output folder:

```
E:/Masters_Library/
├── !!!BIASES_LIB/
│   └── Edge_HD_8__QHY600M/
│       └── BIASES_2025_11_10/
│           └── Edge_HD_8__QHY600M_MasterBias_0s_Gain56_Offset10_1x1_-10C.xisf
├── !!!DARKS_LIB/
│   └── Edge_HD_8__QHY600M/
│       └── DARKS_2025_11_10/
│           ├── Edge_HD_8__QHY600M_MasterDark_120s_Gain56_Offset10_1x1_-10C.xisf
│           └── Edge_HD_8__QHY600M_MasterDark_300s_Gain56_Offset10_1x1_-10C.xisf
├── !!!DARKFLATS_LIB/
│   └── Edge_HD_8__QHY600M/
│       └── DARKFLATS_2025_11_10/
│           └── Edge_HD_8__QHY600M_MasterDarkFlat_0.5s_Gain56_Offset10_1x1_-10C.xisf
└── !!!FLATS_LIB/
    └── Edge_HD_8__QHY600M/
        └── FLATS_2025_11_10/
            ├── Edge_HD_8__QHY600M_MasterFlat_B_Gain56_Offset10_1x1_-10C.xisf
            ├── Edge_HD_8__QHY600M_MasterFlat_G_Gain56_Offset10_1x1_-10C.xisf
            └── Edge_HD_8__QHY600M_MasterFlat_R_Gain56_Offset10_1x1_-10C.xisf
```

---

## Master Types

### Bias Frames

**Purpose:** Remove readout noise pattern (electronic offset)

**Requirements:**
- Shortest possible exposure (0s or near-zero)
- Camera shutter closed
- Same GAIN, OFFSET, BINNING as lights
- Temperature doesn't matter (some prefer matching)

**Recommended count:** 50-100-200 frames

**When to use:**
- Always recommended for best quality
- Can be skipped if using **Dark Library workflow** (ANAWBPPS supports this)

**Created from:** Files with `IMAGETYP = 'BIAS'` or `IMAGETYP = 'Bias Frame'`

---

### Dark Frames

**Purpose:** Remove thermal noise (hot pixels, amp glow)

**Requirements:**
- Same EXPOSURE as lights
- Camera shutter closed
- Same GAIN, OFFSET, BINNING, TEMPERATURE (±1°C) as lights

**Recommended count:** 30-50 frames per exposure/temperature combination

**Temperature matching:**
- ±1°C tolerance for matching
- Colder is better (less thermal noise)
- Create separate masters for different temperature ranges

**Created from:** Files with `IMAGETYP = 'DARK'` or `IMAGETYP = 'Dark Frame'`

---

### DarkFlat Frames

**Purpose:** Calibrate flat frames (remove thermal noise from flats)

**Requirements:**
- Same EXPOSURE as flat frames (typically 0.5s - 5s)
- Camera shutter closed
- Same GAIN, OFFSET, BINNING as flats
- TEMPERATURE matching (±3 hours by observation time)

**Recommended count:** 30-50 frames per flat exposure time

**Important notes:**
- DarkFlat exposure must match Flat exposure exactly
- Used during flat calibration, not light calibration
- Script automatically matches DarkFlat to Flat by exposure time (±3 hours)

**DarkFlat optional behavior:**
- **If DarkFlat not found:** Script will use regular Dark with matching exposure time
- **This is acceptable** but less optimal (darks may have different temperature)
- **Flat calibration still works** without dedicated DarkFlats
- **Best practice:** Always create DarkFlats when shooting flats (within ±3 hours)

**When DarkFlat is critical:**
- Long flat exposures (>2s) - more thermal noise accumulation
- Warm sensor temperatures - higher thermal noise levels
- High thermal noise cameras - visible hot pixels in flats

**Created from:** Files with `IMAGETYP = 'DARK'` where exposure matches flat exposure

---

### Flat Frames

**Purpose:** Remove vignetting, dust shadows, optical imperfections

**Requirements:**
- Evenly illuminated surface (light panel, twilight sky)
- Median ADU ~50% of camera range (e.g., ~32700 for 16-bit)
- Same FILTER, GAIN, OFFSET, BINNING as lights
- Same focus/rotation as lights (critical!)

**Recommended count:** 30-50 frames per filter

**Calibration:**
- Flat frames are first calibrated with DarkFlat
- Then integrated into MasterFlat
- This removes thermal noise from flats

**Created from:** Files with `IMAGETYP = 'FLAT'` or `IMAGETYP = 'Flat Frame'`

---

## File Naming Convention

Master files follow this format:

```
{TELESCOP}_{INSTRUME}_Master{TYPE}_{EXPOSURE}_{GAIN}_{OFFSET}_{BINNING}_{TEMP}.xisf
```

**Components:**

| Component | Description | Example |
|-----------|-------------|---------|
| `TELESCOP` | Telescope name from FITS | `Edge_HD_8_` |
| `INSTRUME` | Camera name from FITS | `QHY600M` |
| `Master{TYPE}` | Frame type | `MasterDark`, `MasterFlat` |
| `EXPOSURE` | Exposure time | `120s`, `0.5s`, `0s` (bias) |
| `GAIN` | Gain setting | `Gain56` |
| `OFFSET` | Offset setting | `Offset10` |
| `BINNING` | Binning mode | `1x1`, `2x2` |
| `TEMP` | Sensor temperature | `-10C`, `-15C` |

**Special cases:**

**Bias files:** Exposure is `0s`
```
Edge_HD_8__QHY600M_MasterBias_0s_Gain56_Offset10_1x1_-10C.xisf
```

**Flat files:** No exposure, adds filter name
```
Edge_HD_8__QHY600M_MasterFlat_B_Gain56_Offset10_1x1_-10C.xisf
```

**Character substitution:**
- Spaces → underscores: `Edge HD 8` → `Edge_HD_8_`
- Slashes → underscores: `Bin 1x1` → `Bin_1x1`
- Special chars removed or replaced

---

## Output Structure

Masters are organized in hierarchical folders:

```
{MastersPath}/
├── !!!BIASES_LIB/
│   └── {SETUP}/                    ← TELESCOP_INSTRUME
│       └── BIASES_{YYYY_MM_DD}/    ← Date from first file
│           └── MasterBias files
├── !!!DARKS_LIB/
│   └── {SETUP}/
│       └── DARKS_{YYYY_MM_DD}/
│           └── MasterDark files
├── !!!DARKFLATS_LIB/
│   └── {SETUP}/
│       └── DARKFLATS_{YYYY_MM_DD}/
│           └── MasterDarkFlat files
└── !!!FLATS_LIB/
    └── {SETUP}/
        └── FLATS_{YYYY_MM_DD}/
            └── MasterFlat files
```

**Folder naming:**
- `{SETUP}` = `{TELESCOP}_{INSTRUME}` with sanitized characters
- Date = `YYYY_MM_DD` from `DATE-OBS` of first file in group
- Multiple dates = Multiple folders (if frames from different nights)

**Benefits of this structure:**
- Easy to find masters by camera setup
- Organized by date (useful for tracking sensor health)
- Can maintain multiple master sets for different conditions
- Compatible with ANAWBPPS automatic master selection

---

## Best Practices

### 1. Frame Count Recommendations

| Frame Type | Minimum | Recommended | Optimal |
|------------|---------|-------------|---------|
| **Bias** | 50      | 100         | 200     |
| **Dark** | 15      | 30          | 50      |
| **DarkFlat** | 15      | 30          | 50      |
| **Flat** | 20      | 30          | 50      |

More frames = better noise reduction (√N law)

### 2. Temperature Management

**For Darks:**
- Use same temperature as lights (±1°C)
- Colder darks work better (thermal noise scales with temperature)
- Create separate masters for different temperature bins:
  - Summer: -5°C to 0°C
  - Winter: -10°C to -15°C
  - Cooled: -20°C to -30°C

**For Flats:**
- Temperature less critical
- Can use flats from ±5°C range

### 3. Dark Library Workflow

If you have extensive dark library:
- Disable **"Use Bias"** checkbox in ANAWBPPS
- Use only darks for calibration
- Darks already include bias component
- Reduces one calibration step

**When to use Dark Library:**
- Consistent cooling (regulated camera)
- Large dark collection (multiple exposures/temps)
- Minimal bias pattern changes

### 4. Flat Frame Quality

**Critical factors:**
- Even illumination (no gradients, no bright spots)
- Correct ADU level (~50% of range)
- Same optical train (focus, rotation, spacing)
- Taken close to imaging session (dust changes!)

**Check flat quality:**
1. Open master flat in PixInsight
2. **Process** → **Statistics**
3. Verify:
   - Mean ≈ 0.5 (for normalized 0-1 range)
   - StdDev low (< 0.1)
   - No obvious gradients or patterns

### 5. Master Refresh Schedule

**Bias:** Every 1-2 months (or after camera firmware update)

**Dark:**
- Every imaging session (if temperature varies)
- Monthly if temperature-controlled
- After camera cooldown changes

**Flat:**
- Every imaging session (dust changes!)
- After cleaning optics
- After rotating/adjusting camera (if camera not rotate with filters)
- After changing filters

**DarkFlat:** Same schedule as flats

### 6. File Format

**Use XISF format** (PixInsight native):
- ✅ Preserves all FITS keywords
- ✅ 32-bit float support
- ✅ Fast read/write
- ✅ Lossless compression
- ✅ Metadata preservation

FITS format also supported but XISF preferred.

---

## Troubleshooting

### Problem: "No calibration files found"

**Cause:** Script couldn't find frames with proper `IMAGETYP` keyword

**Solution:**
1. Open a frame in PixInsight
2. **Process** → **FITS Header**
3. Verify `IMAGETYP` exists and has correct value:
   - `BIAS` or `Bias Frame`
   - `DARK` or `Dark Frame`
   - `FLAT` or `Flat Frame`
4. If missing, add manually:
   - **Process** → **FITS Header** → **Add**
   - Keyword: `IMAGETYP`
   - Value: `DARK` (or appropriate type)
   - Comment: `Type of image`

### Problem: "Multiple groups created for same setup"

**Cause:** FITS keyword variations or different temperatures

**Solution:**
1. Check if temperature varied (masters grouped by ±3°C bins)
2. Verify GAIN/OFFSET consistent across all frames
3. Check TELESCOP/INSTRUME spelling (case-sensitive)

**Expected behavior:**
- Darks at -10°C, -10.1°C, -10.2°C → Single group (within ±1°C)
- Darks at -10°C and -12°C → Two groups (>1°C difference)

### Problem: "Master file looks wrong (too bright/dark)"

**Cause:** Wrong frame type in source folder

**Solution:**
1. Verify `IMAGETYP` keywords
2. Check no light frames mixed with darks
3. Check no uncovered darks mixed with flats

**Common mistakes:**
- Light frames with `IMAGETYP = DARK` (caps exposed)
- Flat frames without darkflats (thermal pattern visible)

### Problem: "Out of memory during integration"

**Cause:** Too many frames or insufficient RAM

**Solution:**
1. Reduce frame count (15-20 minimum is still good)
2. Close other applications
3. Process fewer groups at once

**RAM requirements:**
- ~2GB per 20MP frame
- 50 x 20MP frames ≈ 16GB of free RAM needed
- 100 x 60MP frames ≈ 48GB of free RAM needed

### Problem: "DarkFlat not matched to Flat"

**Cause:** Exposure time mismatch or ±3 hour observation time difference

**Solution:**
1. Verify darkflat exposure matches flat exposure exactly
2. Check `DATE-OBS` timestamps (must be within ±3 hours)
3. Take darkflats immediately before/after flats

**Time matching logic:**
- Script matches by observation time (±3 hours window)
- Prefers future darkflats over past (for thermal drift)

### Problem: "Master creation crashes or hangs"

**Cause:** Corrupted files or PixInsight issue

**Solution:**
1. Check all source files open in PixInsight individually
2. Remove any corrupted files
3. Restart PixInsight
4. Try processing smaller batches

### Problem: "Temperature keyword missing"

**Cause:** Camera doesn't write temperature to FITS

**Solution:**
1. Update camera software/drivers
2. If not fixable, script will group all as "NO_TEMP"
3. Less optimal but still works

---

## Advanced Topics

### Custom Master Organization

If you need different folder structure:
1. Create masters normally
2. Use provided `reorganize_masters.js` script
3. Or manually reorganize (script matches by filename patterns)

### Reusing Existing Masters

ANAWBPPS automatically finds and uses existing masters if:
- Located in configured `Masters path`
- Proper folder structure (!!!{TYPE}_LIB/SETUP/{TYPE}_DATE/)
- Filename matches expected pattern
- Parameters match (GAIN, OFFSET, BINNING, TEMP, EXPOSURE)

No need to recreate if you already have valid masters!

### Master Quality Verification

After creation, verify master quality:

1. **Open master in PixInsight**
2. **Process** → **Statistics**
3. Check metrics:
   - **Bias:** Median near offset value, low StdDev
   - **Dark:** Check hot pixel map, look for amp glow pattern
   - **Flat:** Even illumination, no gradients, no vignetting pattern

4. **Visual inspection:**
   - **STF (ScreenTransferFunction)** → Auto stretch
   - Look for:
     - Bias: Should look like noise
     - Dark: Hot pixels and thermal pattern visible
     - Flat: Dust donuts, vignetting, optical artifacts

---

## Summary

**Quick checklist:**

1. ✅ Organize calibration frames in source folder
2. ✅ Verify FITS keywords present
3. ✅ Configure Masters path in ANAWBPPS
4. ✅ Click "Create Masters" button
5. ✅ Select source folder
6. ✅ Wait for processing to complete
7. ✅ Verify output files created
8. ✅ Masters automatically used in light frame processing

**That's it!** ANAWBPPS handles all the complexity automatically.

---

## Related Documentation

- [User Guide](USER_GUIDE.md) - Complete workflow walkthrough
- [Camera Settings](CAMERA_SETTINGS.md) - Gain and scale configuration
- [Folder Structure](FOLDER_STRUCTURE.md) - Required folder organization
- [Troubleshooting](TROUBLESHOOTING.md) - Common issues and solutions

---

*Last updated: 2025-11-10*
