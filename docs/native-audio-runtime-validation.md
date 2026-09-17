# Native AAudio runtime validation

The native ANC path requires a verified two-channel input route and a validated calibration model. The runtime stream is hard-checked to 48 kHz, 2 input channels, and 1 output channel before activation.

Fault codes:
- 2: native input ring overflow
- 3: AAudio XRUN / native stream starvation
- 4: non-finite DSP value
- 5: adaptive coefficient safety fault
- 6: real-time callback budget fault
- 7: audio route or native stream setup fault

The application must not claim physical ANC effectiveness from build or unit-test success. Physical validation requires a real supported two-channel reference/error input path and output route.
