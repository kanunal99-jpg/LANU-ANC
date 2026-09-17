# LANU ANC

LANU ANC is an Android real-time audio application with a safety-gated native ANC path and a conservative fallback path.

## Current audio architecture

When a real compatible route is available and calibration is validated, the application uses:

`physical 2-channel input → CH0 reference → latency alignment → measured secondary-path model → FxLMS → anti-noise output`

The error signal is taken from the **physical CH1 error microphone**. CH1 is not synthesized by software.

The native backend uses Android AAudio with **2 input channels / 1 output channel**, validates the actual input/output device IDs and keeps the real-time DSP path free of heap allocation, mutexes and sleeps. A runtime monitor stops ANC on native faults, XRUNs, route/device changes or a stopped native stream and returns the app to a safe state.

## Calibration gate

Live ANC is not enabled from synthetic calibration data. The calibration flow requires a real physical input/output route, measures the secondary path on the real error channel, estimates latency and validates the secondary-path model before the native engine can start.

## Android app behavior

- Runtime microphone permission flow
- Foreground microphone service for user-initiated background audio processing
- Native AAudio ANC backend when all gates pass
- Safe bypass fallback on unsupported routes or failures
- Diagnostics for sample rate, device IDs, channel topology and fault state
- Unit tests for calibration, latency, secondary-path and FxLMS safety components
- Debug APK generation with SHA-256 manifest through GitHub Actions

## What is and is not proven

A successful build proves the Android application and native library compile, package and pass the automated test gate. It does **not** by itself prove a measured physical ANC reduction. The final physical acceptance gate requires a real device, two independent microphone channels, a real output path and an objective residual-noise measurement.

## Build

GitHub Actions builds an installable debug APK, runs unit tests, computes SHA-256 and publishes the current APK to `downloads/LANU-ANC.apk` plus GitHub Release `v1.0.0`.
