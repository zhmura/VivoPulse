# Calculation Reference and Research Alignment

This document lists each computed metric, a brief formula/definition, units, and a literature grounding or engineering rationale. Where a metric is only a surrogate for a regulated/clinical index, we use safe naming and explicitly avoid equivalence claims.

Note: Citations are provided as links or standard references; see inline KDoc in code for file-level pointers.

## rPPG/PPG Signal Processing
- Name: Bandpassed PPG/rPPG channel (filtered and z-scored)
- Formula: detrend → Butterworth bandpass 0.7–4.0 Hz → z-score
- Units: arbitrary normalized units (dimensionless)
- Source: Common rPPG/PPG preprocessing practice; band for 42–240 BPM.
- Notes: Current app uses luma-derived PPG channels from ROI (face, finger). We do not yet implement color-projection methods (CHROM/POS); when added, follow:
  - de Haan & Jeanne (CHROM) [PubMed][1]
  - Wang et al. (POS) [PubMed][2]

## Pulse Transit Time (optical dual-site surrogate)
- Name: Face→Finger PTT (ms) via cross-correlation
- Formula: PTT_ms = 1000/fs · argmax_lag NCC(face, finger); sub-sample interpolation for peak; quality gates for stability and plausibility
- Units: milliseconds
- Source: Normalized cross-correlation lag; common optical PTT surrogate (not cfPWV).
- Safety: Do not label as PWV in m/s; not cfPWV. Used as an experimental timing surrogate only.

## Vascular Wave Profile (shape-based, experimental)
- Name: Rise time (ms)
  - Formula: from pre-peak trough “foot” threshold (10% of min→peak) to systolic peak
  - Units: milliseconds
  - Source: PPG contour features (rise/foot to peak) [PPG review, PMC][3]
- Name: Reflection-like ratio (dimensionless)
  - Formula: late-systolic area / early-systolic area above baseline (simple trapezoid integration)
  - Units: dimensionless
  - Safety: Not AIx; labeled “reflection-like ratio”
  - Source: Wave contour/augmentation concepts; simplified surrogate [PPG stiffness reviews][5]
- Name: Dicrotic hint (boolean)
  - Formula: post-peak local min→rebound ≥ 5% of peak amplitude in 80–350 ms window
  - Units: boolean (detected/not)
  - Safety: Heuristic experimental flag only

## Heart Rate and HRV
- HR (bpm): 60,000 / mean(RR_ms); source: standard
- RMSSD (ms): sqrt(mean(diff(RR_ms)^2)); source: standard short-term HRV
- SDNN (ms): stdDev(RR_ms); source: standard HRV
- Units: bpm, milliseconds
- Source: Time-domain HRV practice

## Respiratory Modulation Hint (experimental)
- Name: Respiratory modulation detected (boolean)
- Formula: Build beat-to-beat amplitude series; detrend; normalized autocorrelation; if peak in period matching ~0.1–0.3 Hz (3.3–10 s) exceeds threshold, flag true.
- Units: boolean
- Safety: No respiratory rate claim; presence hint only

## SQI/Confidence
- Per-channel SQI: combines band SNR proxy, peak regularity (RR CV), optional motion
- Combined confidence: weakest-link with correlation factor
- Units: 0–100
- Source: Common PPG SQI components; conservative gating

## Trend Indices (personal, experimental)
- Vascular Trend Index (0–100): z-score-like composite vs own rolling baseline
- Units: 0–100 index (50 ~ baseline)
- Safety: No population norms; personal-only; experimental

## Arterial Stiffness and Risk (context only; not computed)
- cfPWV (m/s), AIx%: Not computed; see:
  - cfPWV prognosis: Vlachopoulos 2010; Zhong 2018 [ScienceDirect][4]
- Safety: App does not report BP, cfPWV, or AIx; features are experimental surrogates only.

---

[1]: https://pubmed.ncbi.nlm.nih.gov/23744659/?utm_source=chatgpt.com
[2]: https://pubmed.ncbi.nlm.nih.gov/28113245/?utm_source=chatgpt.com
[3]: https://pmc.ncbi.nlm.nih.gov/articles/PMC9777579/?utm_source=chatgpt.com
[4]: https://www.sciencedirect.com/science/article/pii/S0735109710002809?utm_source=chatgpt.com
[5]: https://www.mdpi.com/1424-8220/23/24/9882?utm_source=chatgpt.com


