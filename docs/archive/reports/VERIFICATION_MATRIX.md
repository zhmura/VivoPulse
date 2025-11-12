| ID  | Capability                     | Method              | Input/Fixture                    | Acceptance Criteria                                                       | Status    |
| --- | ------------------------------ | ------------------- | -------------------------------- | ------------------------------------------------------------------------- | --------- |
| V1  | Dual camera concurrent preview | Instrumented        | Live device                      | Both previews render at ≥ 30 fps, torch toggle works                      | N/A |
| V2  | Timestamp drift detection      | Unit                | Synthetic ts with ±10 ms/s drift | Drift measured within ±2 ms/s; resampler aligns                           | PASS |
| V3  | Face ROI stability             | Instrumented        | Live device                      | ROI stays on forehead, recovery < 1 s after brief loss                    | N/A |
| V4  | Raw signal extraction          | Instrumented        | Live device                      | Visible ~1 Hz oscillation on finger; face oscillation with good light     | N/A |
| V5  | DSP correctness                | Unit                | Synthetic multi-sine + noise     | Passband preserved (1–2 Hz), stopbands attenuated ≥ 15 dB                 | PASS |
| V6  | Peak detection & HR sanity     | Unit + Instrumented | Synthetic + Live                 | HR(face) ≈ HR(finger) within ±5 bpm under good signal                     | PASS |
| V7  | PTT estimation (xcorr)         | Unit + Instrumented | Simulated mode lag=100 ms        | Reported PTT 100±5 ms; corrScore ≥ 0.90                                   | PASS |
| V8  | PTT on live                    | Instrumented        | Live device (60 s)               | corrScore ≥ 0.70; PTT in [50..150] ms; stability (SD) ≤ 25 ms             | N/A |
| V9  | SQI & guidance                 | Instrumented        | Live device (bad light/motion)   | SQI drop < 60 with correct tips; good case ≥ 70                           | N/A |
| V10 | Export                         | Unit + Instrumented | Live/Sim sessions                | ZIP with session.json + face_signal.csv + finger_signal.csv; schema valid | PASS |
| V11 | Performance                    | Instrumented        | Live device (60 s)               | Proc/frame avg < 8 ms; RSS < 200 MB; no sustained jank                    | N/A |
| V12 | Regression smoke               | Instrumented        | Prerecorded assets               | Pipeline produces expected HR/PTT within tolerances                       | N/A |


