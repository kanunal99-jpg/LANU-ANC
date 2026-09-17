# LANU-ANC active-noise-control topology

## Current status

The repository now contains a safety-first ANC processing topology abstraction. It is **not** a claim of working physical ANC.

Activation requires all four conditions:

1. Reference microphone/channel present.
2. Error microphone/channel present.
3. Output/input route validated as the intended physical topology.
4. Reference-to-error/output latency alignment validated.

Until all four are true, the topology remains in `BYPASS` and produces no anti-noise output.

## Processing contract

`reference -> adaptive filter -> anti-noise candidate -> safety limiter -> output`

The measured error is then supplied to the adaptive update step.

The existing normalized-LMS primitive is used only as a DSP building block. A production ANC implementation still needs a measured secondary-path model and a physically validated reference/error microphone topology. The next algorithmic step is therefore an FxLMS/secondary-path implementation rather than directly routing the current LMS output to an audio device.

## Validation requirements

Physical-device testing must record at minimum:

- input/output device IDs
- sample rate
- frames per burst
- buffer size
- xrun count
- end-to-end/reference latency
- reference and error channel levels
- coefficient magnitude/stability
- limiter activations

No release build should advertise "real ANC" until these measurements demonstrate stable closed-loop operation on the target hardware.
