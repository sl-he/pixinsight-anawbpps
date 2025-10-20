# ANAWBPPS - Automated Night Astrophotography Workflow Batch Pre-Processing Script
It just ANAWBPPS = Automated Night Astrophotography Workflow Batch Pre-Processing Script!

Automated calibration, cosmetic correction, subframe selection, star alignment, local normalization, image integration and drizzle integration for PixInsight.

## Features
- ✅ Automatic lights/masters indexing
- ✅ Smart calibration matching (by gain, offset, USB, temp, binning)
- ✅ CosmeticCorrection with dark grouping
- ✅ SubframeSelector with manual weight computation
- ✅ TOP-5 best frames extraction per group
- ✅ Ranked prefixes (!1-!5) for easy identification
- ✅ Automatic organization by acquisition conditions
- ✅ StarAlignment process with with grouping by objects/targets
- ✅ LocalNormalization process with with grouping by objects/targets
- ✅ ImageIntegration process with with grouping by objects/targets and saving results on disk (with right naming by the groups)
- ✅ DrizzleIntegration process with with grouping by objects/targets and saving results on disk (with right naming by the groups)
- ✅ Two-disk workflow support


## Quick Start
1. Open PixInsight
2. Script → Execute Script File → select `anawbpps.js`
3. Set folders (Lights, Masters, Work1, Work2)
4. Click RUN ☕

## Requirements
- PixInsight 1.9.3+
- Proper FITS headers (GAIN, OFFSET, etc.)
- Masters organized by setup

## Workflow Options

### Standard Workflow (Recommended)
- Fast processing (~1-2 hours, depends on CPU)
- Best SNR (60+ db, depends on subs and its count)
- Good for most targets

**Enabled by default:**
- ☑ ImageCalibration
- ☑ CosmeticCorrection
- ☑ SubframeSelector
- ☑ StarAlignment
- ☑ ImageIntegration

**Disabled by default (enable if needed):**
- ☐ LocalNormalization (for strong gradients)
- ☐ DrizzleIntegration (for undersampled data)

### When to enable LocalNormalization:
- Strong background gradients
- Light pollution
- Vignetting issues
- Different sky conditions between frames

### When to enable DrizzleIntegration:
- Undersampled data (FWHM < 2 pixels)
- Small planetary nebulae
- Distant galaxies with fine details
- Print in large format
- NOTE: Drizzle improves resolution but reduces SNR!!!

## License
GPL-3.0
