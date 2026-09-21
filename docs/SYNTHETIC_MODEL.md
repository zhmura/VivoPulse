# Synthetic measurement benchmark model

This benchmark tests engineering behavior of VivoPulse's optical-delay pipeline. It does not demonstrate medical accuracy, validate blood pressure, simulate a patient population, or establish arterial stiffness. All generated buffers carry `SYNTHETIC` provenance. The generator is `tools/synthetic-benchmark/src/SyntheticCases.kt`.

## Reproducibility and pulse truth

The base seed is `20260921`. Each case uses the base plus Java/Kotlin's stable string hash of its case ID; sampling jitter, frame removal and Gaussian noise use separate seeded `java.util.Random` streams for each camera. A case's seed is exported. Changes to unrelated fixtures do not change its random sequence.

The source is evaluated at continuous physical times, without truncating shifts to integer samples. One causal pulse component is

`g(t; p) = (t/p)^2 * exp(2 - 2*t/p)` for `t > 0`, and zero otherwise.

It begins at the stated onset and has its analytical component maximum at `p`. The default waveform sums a main component with `p=130 ms` and a reflected component of gain `0.22`, beginning `260 ms` later with its own maximum `100 ms` after that. Component peaks are known; the peak of their sum can occur elsewhere. Contributions older than two seconds are truncated; this introduces a negligible tail discontinuity but is not an arterial wave model. Several earlier beats are generated to avoid a fabricated start transient.

Beat onset intervals are `60 / HR(t)` seconds. HR is constant unless specified as `72 + 8*sin(2*pi*0.08*t)` bpm, evaluated at each source onset. This is deterministic modulation, not a model of autonomic HRV. Exported source onsets describe the latent face-site pulse schedule. The finger waveform is evaluated at `t - propagationDelay - localOpticalShift(t)`.

Face and finger sample independently at 30, 60 or 100 native fps, including unequal-rate cases. Their default acquisition phases are 3 ms and 7 ms. Signals are evaluated at those actual times; phase differences are not added to physiological delay. Timestamps share a positive nanosecond epoch and are rounded only at nanosecond resolution. No 100 Hz interpolation is done by the generator. Raw values are synthetic mean-intensity units (`100 + 3*pulse` for face, `130 + 14*pulse` for finger); RGB image formation is outside the model.

## Three different delays

1. `latentPropagationDelayMs`: imposed source-to-site difference, before local optical distortion. Positive means the finger pulse is later.
2. `effectiveOpticalDelayMs`: a known constant translation between optical source shapes, when one exists. A 25 ms local shift adds to 100 ms propagation to produce 125 ms optical delay.
3. `expectedDelayMs`: the constant delay on the supplied timestamp axes, when one exists. A +40 ms finger clock-label offset makes 100 ms physical optical delay appear as 140 ms.

Morphology changes and variable shifts have no unique whole-wave translation: their scalar optical/estimator truth is null, although their latent onset delay may remain defined. Pure noise and nonphysiological common illumination have no physiological delay truth. A scalar estimate on those cases is diagnostic data, not an accuracy score. A constant unknown clock offset is not identifiable from otherwise identical delayed waveforms alone. Fixtures explicitly distinguish unverified clocks from a deliberately false trust assertion.

## Faults and evaluation contracts

The suite covers clean fractional shifts; zero and negative sign diagnostics; unequal native sampling rates; independent jitter; random losses; short/long internal gaps; clock offsets/drift; missing, flat and noise-only channels; clipping and saturation; low gain and amplitude modulation; wider upstroke and stronger/earlier reflections; local optical delay; varying heart rate; flagged motion; common nonphysiological optical artifacts; and short recordings.

Jitter changes actual acquisition time and the corresponding truthful timestamp together. Clock faults change timestamp labels while leaving physical sampling and waveform generation unchanged. Frame losses remove samples entirely; the generator does not fill gaps. Noise is independent between channels. Finger saturation supplies both constant 255-valued samples and explicit saturation metadata; severe-motion fixtures supply 0.30 g IMU RMS metadata. This does not validate the production image/IMU feature extractors.

The gates are engineering contracts chosen before execution:

| Gate | Contract |
| --- | --- |
| `must_measure` | Clean positive-delay inputs must yield an accepted finite delay within tolerance. Rejection is a failure. |
| `accurate_or_reject` | Disturbed but interpretable inputs may be rejected; an accepted result must meet tolerance. Report coverage so rejecting everything cannot look successful. |
| `must_reject` | Severe acquisition/signal faults must not yield an accepted delay. |
| `diagnostic` | Report outputs/rejections without grading them against an unjustified scalar truth. |

Default delay tolerance is 10 ms, with 15 ms for selected noisy/modulated cases. These are regression targets, not clinical specifications and not claims that 30 fps supplies 10 ms independent temporal resolution. Clean cases across native rates must be reported separately. Zero/negative cases test sign and range behavior without asking the physiological acceptance gate to accept them. No failing case should be removed or reclassified simply to improve results.

## Pressure sensitivity and biomechanics

The pressure-labelled cases are sensitivity experiments. They inject gain, upstroke, reflection or local timing changes independently, with no pressure in mmHg and no quantitative claim that these perturbations reproduce a particular finger force. Their purpose is to reveal when stable latent propagation is confused with changing optical waveform morphology. They do not model vessels, a camera's spectral response, thermal vasoconstriction, hydrostatic pressure, contact mechanics or disease.

[Chandrasekhar et al., IEEE TBME (2020)](https://pmc.ncbi.nlm.nih.gov/articles/PMC8856571/) measured 17 healthy subjects and found contact-pressure-related changes in ECG-to-finger pulse timing as large as 22 +/-2 ms at the foot and 40 +/-7 ms at the peak. That supports testing pressure sensitivity; it does not calibrate this generator. [Dual-depth reflectance PPG research (2018)](https://pubmed.ncbi.nlm.nih.gov/30441494/) also found wavelength- and compression-dependent waveform/phase differences.

Face-finger delay is the difference between two heart-to-site transit times, not a direct face-to-finger arterial transit time. [Block et al. (2020)](https://pmc.ncbi.nlm.nih.gov/articles/PMC7532447/) explicitly distinguishes such dPTT from ECG-based PAT and true segment transit time, and found different delay measures tracked intervention-induced BP changes differently. This generator deliberately supplies no BP truth or PWV conversion.

## Limits and next validation layers

Passing this suite establishes only behavior on its constructed signals. It cannot establish rPPG extraction accuracy, skin-tone fairness, actual camera synchronization, real-world acceptance coverage, calibrated uncertainty, ECG-HRV equivalence or medical utility. A common optical artifact can resemble a physiological waveform exactly in these scalar inputs; absence of nuisance measurements makes its origin unidentifiable.

Next layers are bench optical synchronization tests on supported phones, synchronized reference PPG at matching anatomical sites, ECG for beat identity, measured finger pressure/motion, and prespecified human validation over sessions and relevant conditions. [2024 multi-society PWV recommendations](https://pmc.ncbi.nlm.nih.gov/articles/PMC10734786/) emphasize matching the reference and tested arterial pathways and separating measured from inferred parameters. The [ESH 2023 cuffless BP validation recommendations](https://www.arc-oxtv.nihr.ac.uk/publications/1393612/) require tests selected for the device's actual intended use and calibration; synthetic signal accuracy cannot substitute for them.
