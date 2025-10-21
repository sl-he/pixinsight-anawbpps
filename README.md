# ANAWBPPS
**Automated Night Astrophotography Workflow Batch Pre-Processing Script**

Fully automated PixInsight script for batch preprocessing of deep-sky astrophotography data.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![PixInsight](https://img.shields.io/badge/PixInsight-1.8.9+-green.svg)](https://pixinsight.com/)

---

## 🌟 Features

- ✅ **Fully automated workflow** (8 processing stages)
- ✅ **Intelligent grouping** by camera, target, filter, binning, exposure
- ✅ **Quality-based selection** via SubframeSelector with approval
- ✅ **Smart calibration matching** (optimal master frame selection)
- ✅ **32-bit Float output** for maximum dynamic range
- ✅ **Progress tracking** with detailed UI
- ✅ **Flexible configuration** (enable/disable any stage)
- ✅ **Batch processing multiple nights/objects/setups/filters at the same time**

---

## 📊 Processing Stages

| # | Stage | Description | Default |
|---|-------|-------------|---------|
| 1 | **ImageCalibration** | Apply master calibration frames | ✅ ON |
| 2 | **CosmeticCorrection** | Remove hot/cold pixels using darks | ✅ ON |
| 3 | **SubframeSelector** | Measure quality, approve best subframes | ✅ ON |
| 4 | **⚠️ Manual Reference** | Manual reference selection from TOP-5 folder | ⚠️ MANUAL |
| 5 | **StarAlignment** | Register all frames to reference | ✅ ON |
| 6 | **LocalNormalization** | Normalize gradients (optional) | ☐ OFF |
| 7 | **ImageIntegration** | Stack approved frames | ✅ ON |
| 8 | **DrizzleIntegration** | Increase resolution (optional) | ☐ OFF |

**Processing time:** ~1-2 hours for 300 subframes (standard workflow) **(AMD Ryzen 7950X/192GB RAM/4x4TB Seagate Firecuda 530)**

---

## 🚀 Quick Start

### Requirements

- **PixInsight** 1.8.9 or later
- **Calibrated master frames** (bias, dark, flat)
- **Light frames** in XISF or FITS format
- **16GB+ RAM** (recommended)
- **SSD** for working files (recommended)

### Installation

1. **Clone or download the repository:**
```bash
   git clone https://github.com/sl-he/pixinsight-anawbpps.git
```
or here https://github.com/sl-he/pixinsight-anawbpps/releases

2. **Copy to PixInsight scripts folder:**

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

3. **Restart PixInsight** (if it was open)

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

**Online calculator:** [AstroBin Image Scale Calculator](https://www.astrob.in/equipment/scale/)

### Workflow Options

#### **Enabled by default:**
- ✅ ImageCalibration
- ✅ CosmeticCorrection
- ✅ SubframeSelector
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
Light Frames → ImageCalibration → CosmeticCorrection → SubframeSelector
    ↓                                                          ↓
Master Frames                                        [Manual: Select Reference]
                                                               ↓
                                                      StarAlignment
                                                               ↓
                                           ┌───────────────────┴───────────────────┐
                                           ↓                                       ↓
                                  LocalNormalization (optional)          ImageIntegration
                                           ↓                                       ↓
                                  ImageIntegration                        MAIN RESULT ✓
                                           ↓
                                  DrizzleIntegration (optional)
                                           ↓
                                  High-res result
```

---

## ⚠️ Important Notes

### Manual Reference Selection

After **SubframeSelector** completes, you must **manually** verify the reference:

1. Navigate to: `WORK1/!!!WORK_LIGHTS/!Approved/!Approved_Best5/`
2. Each group has a folder (e.g., `Setup_Object_Filter_bin1x1_120s`)
3. Each folder must contain **exactly 1 file** (best subframe)
4. This file will be automatically used as reference in StarAlignment

⚠️ **If folder contains multiple files or is empty** - script will show error!

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

### Development Roadmap

- [ ] UI for Camera Gain and Subframe Scale configuration
- [ ] Presets for popular cameras/telescopes
- [ ] Save/load configuration
- [ ] Advanced settings for experts

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
