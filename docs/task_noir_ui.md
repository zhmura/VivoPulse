# Noir UI — Кинематографический тёмный стиль VivoPulse

> **Цель:** Переработать визуальный стиль Capture-экрана из стандартного Material3 в иммерсивный, кинематографический «нуарный» дизайн.
>
> **Референсы:** Два приложенных скриншота — «SIGNAL LOCKED» (стабильный сигнал) и «SIGNAL UNSTABLE» (движение/шум).

---

## Дизайн-система Noir

### Цветовая палитра

| Токен | Hex | Применение |
|:---|:---|:---|
| `Noir.bg` | `#0A0A0A` | Фон за превью, скаффолд |
| `Noir.surface` | `#111111` | Карточки, панели |
| `Noir.surfaceDim` | `#1A1A1A` | Вторичные поверхности |
| `Noir.amber` | `#FFB300` | Акцент: иконки, бейджи, glow |
| `Noir.amberDim` | `#CC8800` | Приглушённый акцент |
| `Noir.pulseRed` | `#FF3D00` | Waveform, пульсация, ошибки |
| `Noir.pulseGlow` | `#FF6E40` | Glow вокруг waveform |
| `Noir.textPrimary` | `#E0E0E0` | Основной текст |
| `Noir.textMuted` | `#757575` | Вторичный, подписи |
| `Noir.green` | `#00E676` | Signal LOCKED (стабильно) |
| `Noir.warning` | `#FFD600` | MOVE LESS, нестабильно |

### Типографика

- Заголовки: **Inter / Outfit**, weight 600, tracking +0.05em, UPPERCASE
- Подписи: **Inter**, weight 400, tracking +0.02em
- Моноширинный (HR, BPM, dB): **JetBrains Mono** или `FontFamily.Monospace`

---

## Задачи

### Фаза 1 — Тема и палитра

- [ ] **1.1 Переписать `Color.kt`** — заменить Purple/Pink на палитру Noir
  - Файл: [`Color.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/theme/Color.kt)
  - Текущая: `Purple80`, `PurpleGrey80`, `Pink80` (дефолтный шаблон Android Studio)
  - Новая: `NoirAmber`, `NoirPulseRed`, `NoirSurface`, и т.д.

- [ ] **1.2 Переписать `Theme.kt`** — принудительно тёмная тема, без dynamic colors
  - Файл: [`Theme.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/theme/Theme.kt)
  - Убрать `dynamicColor` / `lightColorScheme`
  - Принудительно `darkColorScheme` с noir-палитрой
  - Status bar: transparent + dark icons
  - Navigation bar: transparent (edge-to-edge)

- [ ] **1.3 Обновить `Type.kt`** — добавить Inter / monospace стили
  - Файл: [`Type.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/theme/Type.kt)
  - Title: UPPERCASE tracking
  - Добавить Google Font (Inter) или системный sans-serif medium

---

### Фаза 2 — Capture Screen: иммерсивный режим

- [ ] **2.1 Убрать `Scaffold` + `TopAppBar`** — заменить на full-bleed layout
  - Файл: [`CaptureScreen.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/screens/CaptureScreen.kt)
  - Камерный превью занимает весь экран (`fillMaxSize`)
  - Все элементы UI — overlay поверх превью (абсолютное позиционирование)

- [ ] **2.2 Status Badge** — верхний центр
  - Иконка: стилизованный diamond/pulse (как на скриншоте)
  - Два состояния:
    - `SIGNAL LOCKED` — зелёный/amber glow, статичная иконка
    - `SIGNAL UNSTABLE` — красный/amber, мигающая иконка
  - Маппинг из `QualityStatus.GREEN` → LOCKED, `.YELLOW/.RED` → UNSTABLE
  - Текст: uppercase, tracking, light font

- [ ] **2.3 Waveform overlay** — нижняя часть экрана
  - Текущий: тонкая голубая линия `Color(0xFF80DEEA)` внутри карточки
  - Новый: gradient stroke (pulseRed → amber → pulseGlow) + glow-shadow
  - Рисовать `drawPath` с `BlendMode.SCREEN` + размытый дубликат под ним для glow-эффекта
  - Позиция: нижние 20% экрана, поверх камеры, без карточки

- [ ] **2.4 Убрать Card-обёртки** с превью камер
  - Текущий: `CameraPreviewCard` с Material Card, surface color, elevation
  - Новый: прямая вставка `PreviewView` в `Box` с чёрным фоном
  - Виньетирование: gradient overlay по краям (центр прозрачный → края `#0A0A0A`)

- [ ] **2.5 Quality indicators** — переработать из Card-based в overlay
  - Текущий: цветные карточки с текстом `"Conf: 85%"`, `"12.3 dB"`
  - Новый: минималистичные floating badges с glow
  - SNR: маленький моноширинный текст, amber color, верхний правый угол
  - FPS: аналогично, но muted color

---

### Фаза 3 — Контекстные оповещения (Motion / Quality)

- [ ] **3.1 Предупреждение «MOVE LESS»** — центральный overlay
  - Когда: `QualityStatus.RED` или IMU motion > threshold
  - Иконка: ⚠ triangle в amber
  - Текст: `MOVE LESS` — крупный uppercase с amber glow
  - Анимация: fade in/out (300ms), мягкий пульс alpha

- [ ] **3.2 Glitch-эффект при нестабильном сигнале** (декоративный)
  - Когда: `QualityStatus.RED` (опционально, за Feature Flag)
  - Эффект: горизонтальные полосы chromatic aberration поверх превью
  - Реализация: `Canvas` overlay с randomized horizontal lines + offset color channels
  - **Не трогает камерный поток** — чисто декоративный overlay

- [ ] **3.3 Tip banner** — переработать
  - Текущий: Card с иконкой Lightbulb и текстом
  - Новый: полупрозрачная полоса в нижней части, мягкий fade, amber текст

---

### Фаза 4 — Контролы

- [ ] **4.1 Кнопка записи** — стилизовать
  - Текущий: Material `Button` с текстом
  - Новый: круглая кнопка с glow-border, пульсирующая анимация при записи
  - Стоп-состояние: красный квадрат внутри круга

- [ ] **4.2 Torch toggle** — иконка без текста
  - Минификация: только иконка flashlight с amber tint
  - При активном torch: glow-эффект вокруг иконки

- [ ] **4.3 Debug Menu** — адаптировать к тёмной теме
  - Файл: `CaptureScreen.kt` (функция `DebugMenu`)
  - `AlertDialog` → стилизованный dialog с Noir-поверхностью

---

### Фаза 5 — Прочие экраны

- [ ] **5.1 Processing Screen** — адаптировать к noir
  - Файл: [`ProcessingScreen.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/screens/ProcessingScreen.kt)
  - Анимация обработки: amber пульс, progress ring

- [ ] **5.2 Result Screen** — адаптировать к noir
  - Файл: [`ResultScreen.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/screens/ResultScreen.kt)
  - Результаты: карточки с noir-поверхностью, amber акценты

- [ ] **5.3 Компоненты** — обновить стили
  - [`PulseGraph.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/components/PulseGraph.kt) — amber/red gradient stroke + glow
  - [`QualityBadge.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/components/QualityBadge.kt) — floating amber badge
  - [`CoachOverlay.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/components/CoachOverlay.kt) — тёмная полупрозрачная панель

---

## Затрагиваемые файлы

| Файл | Фаза | Действие |
|:---|:---|:---|
| [`Color.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/theme/Color.kt) | 1 | Полная замена палитры |
| [`Theme.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/theme/Theme.kt) | 1 | Dark-only, edge-to-edge |
| [`Type.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/theme/Type.kt) | 1 | Uppercase tracking, monospace |
| [`CaptureScreen.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/screens/CaptureScreen.kt) | 2, 3, 4 | Основная переработка |
| [`RoiOverlayView.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/roi/RoiOverlayView.kt) | 2 | Amber цвета для ROI frame |
| [`ProcessingScreen.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/screens/ProcessingScreen.kt) | 5 | Адаптация |
| [`ResultScreen.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/screens/ResultScreen.kt) | 5 | Адаптация |
| [`PulseGraph.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/components/PulseGraph.kt) | 5 | Gradient + glow |
| [`QualityBadge.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/components/QualityBadge.kt) | 5 | Amber floating badge |
| [`CoachOverlay.kt`](file:///home/ext.siarhei.zhmura/Work/pulse/app/src/main/java/com/vivopulse/app/ui/components/CoachOverlay.kt) | 5 | Dark панель |

---

## Оценка по фазам

| Фаза | Scope | Effort |
|:---|:---|:---|
| **1. Тема** | 3 файла (Color, Theme, Type) | 1–2 ч |
| **2. Capture immersive** | CaptureScreen.kt (основная переработка) | 4–6 ч |
| **3. Alerts** | MOVE LESS + glitch overlay | 2–3 ч |
| **4. Контролы** | Кнопки, toggle, debug menu | 1–2 ч |
| **5. Прочие экраны** | Processing, Result, компоненты | 3–4 ч |
| **Итого** | | **~12–17 ч** |

---

## Зависимости

> [!IMPORTANT]
> - **Фаза 1** — делается первой, все остальные зависят от неё
> - **Фаза 2 + 3** — основное WOW-впечатление, максимальный приоритет после темы
> - **Фазы 4–5** — полировка, можно делать итеративно
> - `task_visual_evm.md` (пульсация на лице) — **независим**, но визуально дополняет Фазу 2

> [!WARNING]
> - Переход на full-bleed layout (2.1) сломает текущие landscape/tablet адаптации
> - Нужно перепроверить portrait + landscape после переработки
> - Glitch-эффект (3.2) — за Feature Flag, может вызвать эпилептические реакции у чувствительных пользователей
