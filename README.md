# ANAWBPPS - Automated Night Astrophotography Workflow Batch Pre-Processing Script
It just ANAWBPPS = Automated Night Astrophotography Workflow Batch Pre-Processing Script!

Automated calibration, cosmetic correction, subframe selection and star alignment for PixInsight.

## Features
- ✅ Automatic lights/masters indexing
- ✅ Smart calibration matching (by gain, offset, USB, temp, binning)
- ✅ CosmeticCorrection with dark grouping
- ✅ SubframeSelector with manual weight computation
- ✅ TOP-5 best frames extraction per group
- ✅ Ranked prefixes (!1-!5) for easy identification
- ✅ Automatic organization by acquisition conditions
- ✅ StarAlignment process with with grouping by objects/targets
- ✅ LocalNormalization process with with grouping by objects/targets (switching OFF by default)
- ✅ ImageIntegration process with with grouping by objects/targets and saving results on disk (with right naming by the groups)
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

## License
GPL-3.0
