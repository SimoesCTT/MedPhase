# MedPhase

A phase-domain processing tool for DICOM medical imaging data.

## Paper

[MedPhase: An Open-Source Android Application for Phase-Domain Processing of DICOM Medical Images](paper/medphase.tex) ([PDF](paper/medphase.pdf))

## Features

- Load DICOM files (Explicit VR LE and Implicit VR LE)
- 8-bit, 16-bit, 32-bit integer and float pixel data
- Apply window/level or min-max normalization
- Denoise with:
  - Gaussian blur
  - Median 3×3
  - Non-local means
  - Riemann-zero comb filter
- PSNR comparison against the original
- All processing on-device, no network

## Status

Research and educational tool. **Not a medical device.** Does not diagnose,
treat, cure, or prevent any condition. Do not use for clinical decisions.

## Building

Requires JDK 17+, Android SDK 34.

    ./gradlew assembleDebug

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
