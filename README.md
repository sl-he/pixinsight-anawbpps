# ANAWBPPS
**Automated Night Astrophotography Workflow Batch Pre-Processing Script**

Fully automated PixInsight script for batch preprocessing of deep-sky astrophotography data.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![PixInsight](https://img.shields.io/badge/PixInsight-1.8.9+-green.svg)](https://pixinsight.com/)
[![Version](https://img.shields.io/badge/version-0.9.5-blue.svg)](https://github.com/sl-he/pixinsight-anawbpps/releases)
[![GitHub stars](https://img.shields.io/github/stars/sl-he/pixinsight-anawbpps.svg)](https://github.com/sl-he/pixinsight-anawbpps/stargazers)

---

## 🌟 Features

- ✅ **Fully automated workflow** (7 processing stages)
- ✅ **Master calibration creation** (Bias, Dark, DarkFlat, Flat)
- ✅ **Flexible Bias usage** (optional, can be disabled for Dark Library workflow)
- ✅ **Automatic reference selection** (best subframe per group, manual mode available)
- ✅ **Intelligent grouping** by camera, target, filter, binning, exposure
- ✅ **Quality-based selection** via SubframeSelector with approval
- ✅ **Smart calibration matching** (optimal master frame selection)
- ✅ **Auto hot/cold pixel detection** (no master dark required for cosmetic correction)
- ✅ **32-bit Float output** for maximum dynamic range
- ✅ **Progress tracking** with detailed UI
- ✅ **Flexible configuration** (enable/disable any stage)
- ✅ **Batch processing** multiple nights/objects/setups/filters simultaneously

---

## 📊 Processing Stages

| # | Stage | Description | Default |
|---|-------|-------------|---------|
| 1 | **ImageCalibration** | Apply master calibration frames (Bias optional) | ✅ ON |
| 2 | **CosmeticCorrection** | Auto-detect and remove hot/cold pixels | ✅ ON |
| 3 | **SubframeSelector** | Measure quality, select best subframes | ✅ ON |
| 4 | **StarAlignment** | Register all frames to reference (Auto TOP-1 or Manual TOP-5) | ✅ ON |
| 5 | **LocalNormalization** | Normalize gradients (optional) | ☐ OFF |
| 6 | **ImageIntegration** | Stack approved frames | ✅ ON |
| 7 | **DrizzleIntegration** | Increase resolution (optional) | ☐ OFF |

**Processing time (standard workflow):** ~0.65-0.7 hours for 300 subframes **(AMD Ryzen 7950X/192GB RAM/4x4TB NVMe Seagate Firecuda 530)**

**Processing time (full workflow):** ~1.4-1.5 hours for 300 subframes **(AMD Ryzen 7950X/192GB RAM/4x4TB NVMe Seagate Firecuda 530)**

---

## 📦 Master Calibration Creation

Before processing your light frames, you need master calibration files (Bias, Dark, DarkFlat, Flat).

**ANAWBPPS includes a built-in tool to create them:**

1. Open **ANAWBPPS** script
2. Click **"Create Masters"** button
3. Select folder with calibration frames
4. Script automatically:
   - Groups frames by camera, temperature, exposure, gain, offset, binning
   - Creates master files with proper naming
   - Saves to hierarchical structure: `Masters_Path/!!!{TYPE}_LIB/SETUP/{TYPE}_YYYY_MM_DD/`

**Supported master types:**
- **Bias** - Zero-exposure frames (remove readout noise)
- **Dark** - Dark current frames (remove thermal noise)
- **DarkFlat** - Darks matching flat exposure (for flat calibration)
- **Flat** - Flat field frames (remove vignetting and dust)

**File naming format:**
```
TELESCOP_INSTRUME_Master{Type}_EXPTIME_GAIN_OFFSET_BINNING_TEMP.xisf
```

**Example:**
```
Edge_HD_8__QHY600M_MasterDark_300s_Gain56_Offset10_1x1_-10C.xisf
```

See [Master Calibration Guide](MASTER_CALIBRATION.md) for detailed instructions.

---

## 🚀 Quick Start

### Requirements

- **PixInsight** 1.8.9 or later
- **Calibrated master frames** (bias, dark, flat)
- **Light frames** in XISF or FITS format
- **64GB+ of free RAM** (recommended for 200+ subs)
- **SSD/NVMe** for working files (recommended)

### Installation

**Method 1: PixInsight Repository (Recommended)**

1. Open **PixInsight**
2. Go to **Resources** → **Updates** → **Manage Repositories**
3. Click **Add** and enter repository URL:
```
https://sl-he.github.io/pixinsight-anawbpps/
```
4. Click **OK** and close the dialog
5. Go to **Resources** → **Updates** → **Check for Updates**
6. Select **ANAWBPPS** and click **Install/Update**
7. Restart **PixInsight**

**Method 2: Manual Installation**

1. Download latest release from: https://github.com/sl-he/pixinsight-anawbpps/releases

2. Extract and copy to PixInsight scripts folder:

   **Windows:**
```
   C:\Program Files\PixInsight\src\scripts\pixinsight-anawbpps\
```

   **macOS:**
```
   ~/Applications/PixInsight/src/scripts/pixinsight-anawbpps/
```

   **Linux:**
```
   ~/.local/share/PixInsight/src/scripts/pixinsight-anawbpps/
```

3. Restart **PixInsight**

### Usage

1. **Script** → **Script Editor**
2. Open `anawbpps.js`
3. Press **Execute (F5)**
4. Configure paths and camera settings
5. Select workflow stages
6. Click **RUN**

---

## 📂 Output File Structure
```
WORK2/!Integrated/
├── Object_Filter_COUNTxEXPOSURE.xisf              ← ImageIntegration (main result)
├── Object_Filter_COUNTxEXPOSURE_rejection_low.xisf   ← Low rejection map
├── Object_Filter_COUNTxEXPOSURE_rejection_high.xisf  ← High rejection map
└── Object_Filter_COUNTxEXPOSURE_drz1x.xisf        ← DrizzleIntegration (optional)
```

**Example:** `Barnard_202_B_263x120s.xisf`
- **Object:** Barnard 202
- **Filter:** B (blue)
- **Count:** 263 subframes
- **Exposure:** 120 seconds each
- **Total integration:** 263 × 120s = 8.8 hours

**Format:** 32-bit Float XISF with full FITS keywords

---

## ⚙️ Configuration

### Camera & Optics Settings

#### **Camera Gain (e-/ADU)**

Camera gain coefficient. Examples:
- **QHY600M @ Gain 56:** 0.333 e-/ADU
- **ASI2600MM @ Gain 100:** 0.158 e-/ADU
- **ASI294MM Pro @ Gain 120:** 0.29 e-/ADU

Find in camera specifications or manufacturer documentation.

#### **Subframe Scale (arcsec/pixel)**

Angular size of pixel on sky. Formula:
```
scale = (pixel_size_µm / focal_length_mm) × 206.265
```

**Examples:**

| Camera | Pixel | Telescope | Focal | Scale |
|--------|-------|-----------|-------|-------|
| QHY600M | 3.76µm | Esprit 150 | 1050mm | 0.721" |
| ASI2600MM | 3.76µm | Newt 200/800 | 800mm | 0.970" |
| ASI2600MM | 3.76µm | RC 8" F8 | 1600mm | 0.485" |

**Online calculator:** [Astronomy Tools Online Calculator](https://astronomy.tools/calculators/ccd)

### Workflow Options

#### **Enabled by default:**
- ✅ ImageCalibration
- ✅ CosmeticCorrection (auto-detect mode)
- ✅ SubframeSelector
- ✅ Automatic reference selection (can be switched to manual TOP-5 mode)
- ✅ StarAlignment
- ✅ ImageIntegration

#### **Disabled by default (enable when needed):**

**LocalNormalization** - enable if:
- Strong background gradients
- Light pollution
- Vignetting issues
- Different sky conditions between sessions

**DrizzleIntegration** - enable if:
- Undersampled data (FWHM < 2 pixels)
- Small planetary nebulae
- Distant galaxies with fine details
- Printing in large format
- ⚠️ **Warning:** Drizzle improves resolution but **reduces SNR by 8-10 db**!

---

## 🧪 Testing Results (Measuring for now)

Tested on 21 subframes (only last stage):

| Method | SNR | FWHM | Time | Recommendation |
|--------|-----|------|------|----------------|
| **ImageIntegration** | **62.99 db** | 2.671 px | 01:09 | ✅ **Main result** |
| ImageIntegration+DrizzleIntegration (Square) | 54.69 db | 2.223 px | 03:30 | ⚠️ For undersampling |
| ImageIntegration+DrizzleIntegration (Gaussian) | 52.94 db | 2.129 px | 04:37 | ⚠️ Best FWHM, slower |
| ImageIntegration+DrizzleIntegration 2x | 52.81 db | 4.076 px | 06:15 | ⚠️ For resolution only |

**Conclusion:** Use **ImageIntegration** as main result. DrizzleIntegration only for specific needs.

---

## 📖 Documentation (in progress)

- **[User Guide](docs/USER_GUIDE.md)** - Detailed step-by-step instructions
- **[Camera Settings](docs/CAMERA_SETTINGS.md)** - How to configure gain & scale
- **[Folder Structure](docs/FOLDER_STRUCTURE.md)** - Required folder organization
- **[Troubleshooting](docs/TROUBLESHOOTING.md)** - Common issues and solutions
- **[API Reference](docs/API.md)** - For developers

---

## 🎯 Workflow Diagram
```
Calibration Frames → [Create Masters] → Master Calibration Files
                                                ↓
Light Frames ────────────────────────→ ImageCalibration (Bias optional)
                                                ↓
                                       CosmeticCorrection
                                                ↓
                                       SubframeSelector
                                                ↓
                                [Select Reference: Auto TOP-1 or Manual TOP-5]
                                                ↓
                                         StarAlignment
                                                ↓
                              ┌─────────────────┴─────────────────┐
                              ↓                                   ↓
                    LocalNormalization (optional)        ImageIntegration
                              ↓                                   ↓
                    ImageIntegration                      MAIN RESULT ✓
                              ↓
                    DrizzleIntegration (optional)
                              ↓
                      High-resolution result
```

---

## ⚠️ Important Notes

### Reference Selection (Automatic or Manual)

**Default mode: Automatic (recommended)**

By default, the script **automatically selects** the best reference frame for each group:

1. **SubframeSelector** measures all subframes and ranks them by quality
2. Best subframe (TOP-1) is saved to: `WORK1/!!!WORK_LIGHTS/!Approved/!Approved_Best5/<group>/`
3. This file is automatically used as reference in **StarAlignment**

**Manual mode (optional):**

Uncheck "Automatic reference selection (TOP-1 only)" to enable manual selection:

1. **SubframeSelector** saves TOP-5 best subframes to: `WORK1/!!!WORK_LIGHTS/!Approved/!Approved_Best5/<group>/`
2. Files are named: `!1_***.xisf` (best), `!2_***.xisf`, ..., `!5_***.xisf`
3. **You must manually select** one file (delete others) before running **StarAlignment**
4. Useful for visual inspection or specific reference requirements

### Performance Recommendations

- **SSD1** for Work1 (intermediate files)
- **SSD2** for Work2 (large final files)
- **64GB RAM** minimum, **128GB+** recommended
- **Close other applications** during processing

### Processing Time Estimates (AMD Ryzen 7950X/192GB RAM/4x4TB Seagate Firecuda 530)

| Subframe Count | Standard Workflow | + LocalNorm | + Drizzle |
|----------------|------------------|-------------|-----------|
| 100 | 30-60 min | +20 min | +10 min |
| 300 | 1-2 hours | +45 min | +20 min |
| 500 | 2-3 hours | +75 min | +30 min |

### Testing Time for 353 subs (AMD Ryzen 7950X/192GB RAM/4x4TB Seagate Firecuda 530)

| Workflow Stage | Subs | Time, hh:mm:ss.ss | Rejected by some reason | Reject Reason |
|----------------|------|------|-------------------------|---------------|
| Index Lights | 353 | 00:00:02.61 | 36 | There were no necessary calibration files|
| Index Calibration Files | 972 | 00:00:00.24 | - | B:58 D:164 F:414 |
| Image Calibration | 316 | 00:10:52.15 | 0 | - |
| Subframe Selector - Measure | 316 | 00:05:13.01 | 0 | - |
| Subframe Selector - Output | 234 | 00:02:38.74 | 82 | By rejecting formula (bad SNR/FWHM/etc) |
| StarAlignment | 234+1 (reference) | 00:08:30.01 | 3 | not enough stars |
| LocalNormalization | 232 | 00:14:25.56 | 0 | - |
| ImageIntegration | 232 | 00:11:16.10 | 0 | - |
| DrizzleIntegration | 232 | 00:20:39.65 | 0 | - |

#### Total: 01:05:30.51

---

## 🛠️ Troubleshooting

### "File not found" errors
- Check folder structure
- Ensure paths don't contain special characters
- Verify file permissions

### "Out of memory" errors
- Close other applications
- Reduce number of simultaneously processed files
- Increase RAM or use fewer groups

### "No stars detected" in StarAlignment
- Check reference (should have good stars)
- Verify subframe quality
- Exposure might be too short

### SubframeSelector skips files
- Verify files are not corrupted
- Ensure FITS keywords are correct
- Some files may be automatically rejected by quality

---

## 🤝 Contributing

Contributions are welcome! Process:

1. Fork the repository
2. Create feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to branch (`git push origin feature/AmazingFeature`)
5. Open Pull Request

### Development Roadmap (v1.0)

**Completed:**
- [x] ✅ Automatic reference selection (TOP-1)
- [x] ✅ CosmeticCorrection auto-detect mode
- [x] ✅ Optional Bias usage (Dark Library workflow support)
- [x] ✅ Master calibration creation tool
- [x] ✅ Code quality improvements (centralized utilities, ~500 lines refactored)

**Planned:**
- [ ] Auto-detect Scale from FITS keywords (XPIXSZ, FOCALLEN)
- [ ] Camera Gain lookup table by camera model
- [ ] UI for Camera Gain and Subframe Scale configuration
- [ ] Presets for popular cameras/telescopes
- [ ] Save/load configuration profiles

---

## 📜 License

GNU General Public License v3.0 - see [LICENSE](LICENSE)

This project is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License.

---

## 👨‍💻 Author

**sl-he** 
- GitHub: [@sl-he](https://github.com/sl-he)
- Repository: [pixinsight-anawbpps](https://github.com/sl-he/pixinsight-anawbpps)

---

## 🙏 Acknowledgments

- **PixInsight development team** - for the excellent platform
- **Astrophotography community** - for feedback and testing
- **All contributors** - for helping to improve the project

---

## 📞 Support

- **Issues:** [GitHub Issues](https://github.com/sl-he/pixinsight-anawbpps/issues)
- **Discussions:** [GitHub Discussions](https://github.com/sl-he/pixinsight-anawbpps/discussions)
- **Wiki:** [GitHub Wiki](https://github.com/sl-he/pixinsight-anawbpps/wiki)

---

## 🌟 If this project is useful - give it a star! ⭐

---

*Made with ❤️ for the astrophotography community*
