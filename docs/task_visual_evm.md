# Visual Pulse Overlay — Декоративная визуализация пульсации на лице

> **Цель:** Показать пользователю пульсацию крови прямо на превью камеры — от простого «румянца» до пространственной карты кровотока.
>
> **Риск для pipeline:** Нулевой — чисто визуальный слой, `SignalPipeline` и `PttEngine` не затрагиваются.

---

## Шаг 1 — Pulse Blush Overlay (3 зоны)

**Effort:** 1–2 часа · **CPU:** ~0.1ms/кадр

### Суть

Три мягких овальных «пятна» (лоб + левая щека + правая щека) пульсируют в ритме BVP-сигнала.
Используется `RadialGradient` с низким alpha (12–20%), `drawOval`, `BlendMode.SOFT_LIGHT`.

### Задачи

- [ ] **1.1 Расширить `FaceRoi`** — добавить `leftCheekCenter`, `rightCheekCenter`, `cheekRadius`
  - Файл: [`RoiState.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/roi/RoiState.kt)
  - ML Kit уже возвращает `FaceLandmark.LEFT_CHEEK` / `RIGHT_CHEEK` (detector настроен с `LANDMARK_MODE_ALL`)

- [ ] **1.2 Извлечь landmarks щёк в `FaceRoiTracker`**
  - Файл: [`FaceRoiTracker.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/roi/FaceRoiTracker.kt)
  - В `handleDetectionResult` после `computeForeheadRoi`:
    ```kotlin
    val leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position
    val rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position
    val cheekRadius = eyeDistance * 0.25f
    ```
  - Передать координаты в `FaceRoi`

- [ ] **1.3 Добавить pulse amplitude в `RoiOverlayView`**
  - Файл: [`RoiOverlayView.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/roi/RoiOverlayView.kt)
  - Новый метод `updatePulse(amplitude: Float)` (0.0 = диастола, 1.0 = систола)
  - `onDraw`: рисовать `RadialGradient` + `drawOval` для каждой из 3 зон
  - Цвет: `Color.argb(alpha, 255, 80, 80)` — мягкий розоватый, alpha = `amplitude * 40`

- [ ] **1.4 Подключить waveform → overlay в `CaptureScreen`**
  - Файл: [`CaptureScreen.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/screens/CaptureScreen.kt)
  - В `update` блоке `AndroidView` (строки 701–706): передать `waveform?.lastOrNull()` → нормализовать к 0..1 → `overlay.updatePulse(amp)`

- [ ] **1.5 Тест на устройстве** — визуальная проверка с реальным лицом

### Данные, которые уже доступны

| Данные | Источник | Flow |
|:---|:---|:---|
| `FaceRoi.rect` (лоб) | `FaceRoiTracker.roiState` | `StateFlow<FaceRoi?>` |
| BVP amplitude | `DualCameraController._faceWave` | `SharedFlow<Double>` |
| Cheek landmarks | `face.getLandmark(LEFT_CHEEK/RIGHT_CHEEK)` | Уже вычислены ML Kit |

---

## Шаг 2 — Downscaled EVM (пространственная карта пульсации)

**Effort:** 4–6 часов · **CPU:** ~0.5ms/кадр

### Суть

Per-pixel temporal filtering на downscaled ROI (20×30 = 600 px).
Показывает **реальную** пространственную вариацию кровотока: волна распространения по лицу, различие между лбом, щеками, висками.

### Принцип работы

```
Каждый кадр:
  1. Crop face ROI из Y-plane (уже есть)
  2. Downscale Green channel → 20×30 px
  3. Push в RingBuffer[600][60] (per-pixel temporal)
  4. IIR bandpass 0.75–3.5 Hz per pixel (DspFunctions)
  5. Amplify ×30 → map to HeatMap color
  6. Upscale 20×30 → ROI size (bilinear)
  7. Overlay через Bitmap + BlendMode.SOFT_LIGHT
```

### Задачи

- [ ] **2.1 Создать `PulseHeatMapEngine`** — новый класс
  - Модуль: `feature-capture` (рядом с `FaceRoiTracker`)
  - Ответственность: per-pixel temporal buffer + IIR filter + amplification
  - Вход: `ByteArray` (Y-plane), `Rect` (face ROI)
  - Выход: `Bitmap` (ARGB, 20×30) — heat map кадр

- [ ] **2.2 Ring buffer per pixel**
  - `FloatArray(600 * 60)` — flat buffer, ~144 KB
  - Circular write pointer, одинаковый для всех пикселей (синхронная запись)

- [ ] **2.3 Per-pixel IIR bandpass**
  - Упрощённый Butterworth 2-го порядка (causal, **не** filtfilt — нам не нужна phase integrity для визуализации)
  - Полоса: 0.75–3.5 Hz при fs = 30 Hz (1 кадр из 2, чтобы снизить нагрузку)
  - Можно переиспользовать коэффициенты из `DspFunctions.butterworthBandpass`

- [ ] **2.4 Color mapping**
  - Filtered deviation → HeatMap:
    - `< -σ` → холодный (синий/прозрачный)
    - `≈ 0` → нейтральный (прозрачный)
    - `> +σ` → тёплый (красный/розовый, alpha ~30-50)
  - Результат: `Bitmap.Config.ARGB_8888`, 20×30 px

- [ ] **2.5 Upscale + overlay в `RoiOverlayView`**
  - `canvas.drawBitmap(heatBitmap, null, scaledRect, blendPaint)`
  - `blendPaint.blendMode = BlendMode.SOFT_LIGHT`
  - Bilinear filtering через `paint.isFilterBitmap = true`

- [ ] **2.6 Интеграция: подключить к frame callback**
  - В `FaceRoiTracker.processFrame` (или рядом с ним в `DualCameraController`):
    - После crop Y-plane → передать ROI-слайс в `PulseHeatMapEngine`
    - Получить heat map bitmap → передать в `RoiOverlayView`

- [ ] **2.7 Feature flag** — `FeatureFlags.PULSE_HEATMAP_ENABLED`
  - По умолчанию `false` (декоративная фича, не для всех устройств)
  - Включается из Debug Menu

- [ ] **2.8 Тест на устройстве** — визуальная проверка с реальным лицом

### Бюджет ресурсов

| Ресурс | Значение |
|:---|:---|
| RAM (ring buffer) | ~144 KB (600 px × 60 frames × 4 bytes) |
| CPU per frame | ~0.3–0.5ms (600 IIR ops + downscale + upscale) |
| Bitmap allocation | 1 × ARGB_8888 20×30 = 2.4 KB (переиспользуется) |
| GC pressure | Нулевое (pre-allocated buffers) |

---

## Затрагиваемые файлы (оба шага)

| Файл | Шаг | Действие |
|:---|:---|:---|
| [`RoiState.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/roi/RoiState.kt) | 1 | Расширить `FaceRoi` |
| [`FaceRoiTracker.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/roi/FaceRoiTracker.kt) | 1, 2 | Извлечь cheek landmarks; передать Y-plane в HeatMapEngine |
| [`RoiOverlayView.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/roi/RoiOverlayView.kt) | 1, 2 | Pulse blush + heat map overlay |
| [`CaptureScreen.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/screens/CaptureScreen.kt) | 1 | Передать waveform amplitude в overlay |
| `PulseHeatMapEngine.kt` | 2 | **[NEW]** Per-pixel temporal filter engine |
| [`FeatureFlags.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/util/FeatureFlags.kt) | 2 | Добавить `PULSE_HEATMAP_ENABLED` |
| [`CaptureScreen.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/screens/CaptureScreen.kt) (Debug Menu) | 2 | Toggle для heat map |

---

## Безопасность и ограничения

> [!IMPORTANT]
> - Визуализация **чисто декоративная** — не имеет диагностической ценности
> - Не влияет на `SignalPipeline`, `PttEngine`, `RealTimeQualityEngine`
> - Heat map (Шаг 2) скрыт за feature flag, по умолчанию выключен
> - Никакие кадры/данные не сохраняются и не передаются — всё in-memory, frame-to-frame

> [!WARNING]
> - На слабых устройствах (< Snapdragon 680) Шаг 2 может потреблять >1ms/кадр
> - При `ANALYSIS_ONLY` camera mode (без превью) overlay не отображается — это ожидаемо
