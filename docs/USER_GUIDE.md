# VivoPulse — User Guide (Experimental)

This app estimates experimental digital biomarkers from dual-camera PPG/rPPG capture. It is not a medical device.

## What you’ll see
- Pulse Transit Time (PTT): An optical timing surrogate between face and finger signals (ms). Not cfPWV, not BP.
- Vascular wave (experimental): A pattern hint from the waveform shape, and a personal Vascular Trend Index when enough prior good sessions exist.
- Biomarkers (experimental):
  - Heart rate (bpm)
  - Heart rhythm variability (SDNN, RMSSD) when signal quality is high
  - Respiratory modulation: detected or not detected (no respiratory rate)
- Signal quality: Face/Finger SQIs and a combined score.

See “Important notes” on the Results screen for limitations and safe wording.

## How to capture
1. Ensure good lighting:
   - Face: even, diffuse light from the front.
   - Finger: cover back camera completely; enable torch if available.
2. Minimize motion: hold the phone steady or rest it on a stable surface.
3. Record 30–40 seconds for typical measurements; longer (30–60 s) improves stability.
4. Process the session to view results.

## Vascular Reactivity protocol (experimental)
Use the “Vascular Reactivity (experimental)” flow:
1. Rest measurement (30–40 s).
2. Light load: 20–30 squats or brisk stepping for ~30–60 s.
3. Post-load measurement (30–40 s).
4. Optional recovery after 3–5 minutes rest.
The analyzer shows directional changes (PTT, HR, wave features) and a recovery score (0–100), with reliability gating.

## How to interpret (non-diagnostic)
- PTT, wave pattern hints, HRV, and trend indices are experimental and relative to your own captures.
- When quality is low, advanced metrics are hidden or flagged as uncertain.
- For any health concern, confirm with standard, regulated measurements.

## Metric meanings (short)
- PTT (ms): Time offset between face and finger signals via cross-correlation. Higher/lower relates to signal timing only (not calibrated PWV/BP).
- Wave pattern hint: A lightweight, text-friendly tag based on rise time and reflection-like ratio under high-quality gating.
- HRV (SDNN, RMSSD): Time-domain variability from RR intervals; shown only when data is long and stable enough.
- Respiratory modulation: A boolean hint that the waveform shows coherent low-frequency modulation (~0.1–0.3 Hz).

For formula details and references, see `docs/CALCULATION_REFERENCE.md`.


