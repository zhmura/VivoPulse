# Real-Time Quality Indicator Report

This document tracks the near-real-time face/finger signal quality indicators that gate user guidance inside the capture screen.

## Thresholds (Green / Yellow / Red)

| Metric | Green | Yellow | Red |
| --- | --- | --- | --- |
| Finger Saturation | ≤ 5 % | 5 – 15 % | > 15 % |
| Finger SNR | ≥ 10 dB | 6 – 10 dB | < 6 dB |
| Face SNR | ≥ 6 dB | 3 – 6 dB | < 3 dB |
| Face Motion | ≤ 0.5 px | 0.5 – 1.0 px | > 1.0 px |
| HR Agreement | ≤ 5 bpm | 5 – 10 bpm | > 10 bpm |

## Runtime Export

At the end of every recording the app writes `QUALITY_INDICATOR_REPORT.md` to:

```
<app files>/quality_reports/QUALITY_INDICATOR_REPORT.md
```

That report contains the last session tip log plus a table of SNR / saturation / motion / ΔHR vs. time and links to the accompanying screenshot (`quality_indicator_screenshot.png`).

## Sample Session Snapshot (simulated)

| t (s) | Face SNR (dB) | Finger SNR (dB) | Sat % | Motion px | ΔHR bpm | Tip |
| --- | --- | --- | --- | --- | --- | --- |
| 2.0 | 5.8 | 11.2 | 2.4 | 0.42 | 1.3 | Hold still for baseline |
| 6.5 | 6.9 | 12.5 | 3.1 | 0.36 | 0.8 | |
| 12.0 | 7.3 | 9.1 | 6.2 | 0.38 | 2.1 | Reduce finger pressure slightly |
| 18.4 | 6.5 | 10.8 | 3.0 | 0.44 | 1.7 | |

## Screenshot

Attach the latest indicator screenshot (`quality_indicator_screenshot.png`) next to the runtime report when filing QA artifacts.


