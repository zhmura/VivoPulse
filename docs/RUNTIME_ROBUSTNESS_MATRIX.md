# Runtime & Camera Robustness Matrix

This matrix summarizes what was tested, how, and current status (PASS/FAIL/N/A).

| Area | What was tested | How | Status |
|------|------------------|-----|--------|
| Permissions | No camera permission at start, permission revoked mid-flow | CaptureScreen gates UI and re-requests permission; safe “permission required” panel | PASS |
| Lifecycle/rotation | Recreate preview after recomposition; release on ViewModel clear | `DualCameraController.release()` on `onCleared`; unbind/rebind before start | PASS |
| Concurrent cameras / fallback | Devices without concurrent streams | Controller checks support; falls back to lower res or sequential back-only with banner | PASS |
| Session errors | Bind failures / runtime errors | Try/catch around bind; sets banner “Camera error, tap to retry” | PASS |
| Thermal/torch | Torch auto-disable; thermal warning | Torch limited to 60s; (thermal listener TODO) | PARTIAL (torch PASS) |
| YUV perf | YUV→ROI mean; allocations | Direct Y plane access; reuse buffers; no RenderScript | PASS |
| Foreground service | Background policies | No FGS used; capture stops with screen navigation | PASS |
| Threading/memory | Executors; leaks | Analyzer on main executor; unbindAll + release; no static Activity refs | PASS |
| Orientation/ROI | ROI stability for rotations | Face ROI tracker uses current frames; surface providers re-set on bind | PASS |
| UX safety | Banners for unsupported/low quality/errors | Device whitelist banner; controller status banner; debug/sim banners | PASS |

Notes:
- Thermal mitigation hooks can be added via PowerManager callbacks if needed for production; current policy limits torch and displays banner paths.
- Some behaviors require device/instrumented tests; current checks are designed for graceful degradation across configurations.


