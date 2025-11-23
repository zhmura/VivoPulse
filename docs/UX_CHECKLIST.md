# UX Validation Checklist

This checklist is designed to validate the VivoPulse user experience against established usability heuristics and specific domain requirements for rPPG capture.

## 1. System Status Visibility

- [x] **Capture Screen**: Is it immediately clear whether the app is recording or just previewing? (Yes, red "Recording" timer)
- [x] **Camera State**: Are both camera previews visible and active? (Yes, side-by-side layout)
- [x] **Signal Quality**: Do the real-time indicators clearly reflect signal quality? (Yes, color-coded headers on preview cards and tip banner)
- [x] **Processing**: Is there a clear "Processing..." state? (Yes, indeterminate progress bar on Processing screen)
- [x] **Errors**: Are camera/permission errors shown as distinct banners? (Yes, top banners)

## 2. Error Prevention & Recovery

- [x] **Permissions**: Does the app handle denied permissions gracefully? (Yes, permission request UI)
- [x] **Thermal**: Is the user warned if the device overheats? (Yes, thermal banner and auto-stop)
- [x] **Low Quality**: Does the app warn about quality? (Yes, tips like "Increase light")
- [x] **Short Sessions**: (Partial) App allows stopping anytime, but suggests 30s+ in tips.
- [x] **Storage**: Storage check implemented in export flow.

## 3. User Control & Freedom

- [x] **Stop Recording**: Can the user stop recording at any time? (Yes)
- [x] **Torch**: Is the torch toggle easily accessible? (Yes)
- [x] **Navigation**: Can the user navigate back? (Yes)
- [x] **Retake**: (Implicit via Back button)

## 4. Consistency & Standards

- [x] **Icons**: Standard icons used.
- [x] **Colors**: Green/Yellow/Red status colors used consistent with `ChannelSqi`.
- [x] **Typography**: Material 3 typography.
- [x] **Layout**: Horizontal layout adapts to portrait (stacked) or landscape (side-by-side) naturally via Row/Column or FlowRow (currently Row, optimal for portrait dual-cam).

## 5. Recognition Rather Than Recall

- [x] **Instructions**: Tips appear contextually above controls.
- [x] **Feedback**: Waveforms provide immediate visual feedback of signal presence.

## 6. Aesthetic & Minimalist Design

- [x] **Previews**: Previews take ~80% of screen.
- [x] **Overlays**: Minimal text overlay on previews.
- [x] **Clutter**: Controls are compact at bottom.

## 7. Help Users Recognize, Diagnose, and Recover from Errors

- [x] **Error Messages**: Friendly error messages ("Device not optimized").
- [x] **Guidance**: Smart Coach tips guide user to fix issues.

## 8. Domain-Specific (rPPG)

- [x] **Finger Placement**: Preview + Tip ("Cover back camera").
- [x] **Face Positioning**: Face ROI overlay (if detected) or center fallback.
- [x] **Lighting**: "Increase light" tip implemented.
- [x] **Motion**: "Hold head steady" tip implemented.

## 9. Medical/Wellness Framing

- [x] **Disclaimer**: Educational text used; medical claims avoided.
- [x] **Metrics**: "Estimated HR", "Signal Quality" used.

## 10. Accessibility

- [x] **Color Blindness**: Status text/icon accompanies color.
- [x] **Text Size**: System font scaling supported.
- [x] **Touch Targets**: Standard Material buttons.
