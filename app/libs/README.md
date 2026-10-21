# Vendored libraries

## sherpa-onnx-1.13.8.aar — offline keyword spotting (feature D1)

- **What:** official prebuilt Android AAR of [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
  (Apache-2.0), used by `core/voice/kws/SherpaKwsEngine` for the fully
  on-device wake-word engine. No other distribution channel is used: the
  Maven Central group for this project does not exist and the JitPack build
  compiles the native layer from source, so the pinned release AAR is the
  deterministic artifact (same practice as the project's other pinned deps).
- **Version:** 1.13.8 (release tag `v1.13.8`, pinned — upgrade deliberately).
- **Source URL:** https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
- **Size:** 50,129,134 bytes (all four ABIs; `app/build.gradle` restricts
  packaging to `arm64-v8a` + `armeabi-v7a` via `ndk.abiFilters`).
- **SHA-256:** `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`
- **Model:** the KWS model itself is bundled separately under
  `app/src/main/assets/kws/` (`sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`,
  int8, chunk-16-left-64) — see `feature-d1-report.md` for its checksums.
