# Initial UI Design Guide — Wellness Sync

> **Temporary design.** This UI will be redesigned later in Stitch. The architecture
> (MVI state, ViewModels, Repositories) is decoupled from the UI layer so Compose
> screens can be replaced without touching business logic. Keep UI components thin
> and state-driven.
>
> **Status (2026-03-23):** Implemented in Phase 1. Two screens: CaptureScreen (main) and SettingsScreen. Color palette, typography, spacing, card patterns, and status indicators are all applied. See `feature/capture/ui/CaptureScreen.kt` and `app/.../settings/SettingsScreen.kt`.

## Design Source

Adapted from the Wellness PWA dark theme (`/home/jtiisto/dev/health/wellness/public/styles.css`),
with emphasis on the coach module's patterns. Translated from CSS custom properties
to Compose/Material 3 equivalents.

---

## Color Palette

### Backgrounds
| Token | Hex | Usage |
|-------|-----|-------|
| Background | `#1a1a2e` | App/screen background |
| Surface | `#16213e` | Headers, navigation, secondary surfaces |
| SurfaceVariant | `#0f3460` | Tertiary surfaces, hover/pressed states |
| Card | `#1f2940` | Card containers, elevated surfaces |
| InputField | `#252540` | Text field backgrounds |

### Text
| Token | Hex | Usage |
|-------|-----|-------|
| OnBackground | `#eaeaea` | Primary text |
| OnSurface | `#a0a0a0` | Secondary text, labels |
| OnSurfaceVariant | `#6a6a6a` | Muted text, placeholders, captions |

### Accents
| Token | Hex | Usage |
|-------|-----|-------|
| Primary | `#e94560` | Primary CTA, active/selected states |
| Success | `#4ade80` | Synced, connected, completed |
| Warning | `#fbbf24` | Pending, caution states |
| Error | `#ef4444` | Failed, disconnected, errors |

### Borders & Dividers
| Token | Hex | Usage |
|-------|-----|-------|
| Outline | `#2a2a4a` | Card borders, dividers, input outlines |

### Sync Status
| State | Color | Label example |
|-------|-------|---------------|
| Synced | `#4ade80` | "Synced up to 14:32" |
| Syncing | `#6b7280` | "Syncing..." |
| Failed / Offline | `#ef4444` | "Server unreachable" |
| Pending | `#fbbf24` | "142 intervals pending" |

---

## Typography

**Font family:** System default (Roboto on Android — no custom fonts needed).

| Scale | Size | Weight | Usage |
|-------|------|--------|-------|
| HeadlineMedium | 24sp | 600 | Screen titles |
| TitleLarge | 20sp | 600 | Section headers, card titles |
| BodyLarge | 16sp | 400 | Default body text |
| BodyMedium | 14sp | 400 | Secondary text, labels |
| LabelLarge | 14sp | 500 | Buttons, important labels |
| LabelSmall | 12sp | 400 | Captions, timestamps |

---

## Spacing

| Token | Value | Usage |
|-------|-------|-------|
| xs | 4dp | Micro spacing, within tight components |
| sm | 8dp | Small gaps, internal padding |
| md | 16dp | Standard padding, component spacing |
| lg | 24dp | Section spacing |
| xl | 32dp | Screen-level padding |

**Touch target minimum:** 44dp (match PWA `--tap-target`)

---

## Border Radius

| Token | Value | Usage |
|-------|-------|-------|
| Small | 4dp | Badges, small chips |
| Medium | 8dp | Buttons, cards, inputs |
| Large | 12dp | Large cards, bottom sheets |
| Full | 9999dp | Pills, circular indicators |

---

## Component Patterns

### Cards
- Background: Card color (`#1f2940`)
- Border: 1dp solid Outline (`#2a2a4a`)
- Corner radius: Medium (8dp) or Large (12dp)
- Padding: md (16dp)
- Interactive cards: on press → Surface background, border → Primary

### Status Indicators
- Small dots (6-8dp circles) for connection/sync states
- Colors map to Success/Warning/Error/Syncing palette above

### Gradient Surfaces (accent headers)
- `LinearGradient(135deg, Surface, SurfaceVariant)`
- Used sparingly for section headers or prominent status cards

### Input Fields
- Background: InputField (`#252540`)
- Border: 1dp solid Outline
- Corner radius: Small (4dp)
- Focused: border → Primary (`#e94560`)
- Text: OnBackground, placeholder: OnSurfaceVariant

### Buttons
**Primary (filled):**
- Background: Primary (`#e94560`)
- Text: white, 500 weight
- Corner radius: Medium (8dp)
- Padding: sm vertical, lg horizontal

**Secondary (outlined/text):**
- No fill, text color contextual
- Border: 1dp Outline (outlined variant)

### Guidance / Info Banners
- Background: SurfaceVariant (`#0f3460`)
- Left border: 3dp solid Warning (`#fbbf24`)
- Text: OnSurface, italic, BodyMedium

---

## Screen Layout Pattern

```
TopAppBar (Surface background, sticky)
  ├── Title
  └── Sync status indicator (dot + label)

Content (scrollable, Background)
  ├── Status Card (gradient surface)
  │   ├── Connection state + device name
  │   ├── Live HR display (large text)
  │   └── Session duration
  │
  ├── Sync Status Card
  │   ├── "Synced up to [time]" or "X pending"
  │   ├── Last sync timestamp
  │   └── "Sync Now" button (secondary)
  │
  └── Data Summary Card
      ├── Interval count
      └── Session info
```

---

## Animations & Transitions

- Standard interactions: 200ms
- Content expand/collapse: 250ms ease
- Pressed feedback: subtle scale (0.98)
- Use Compose `animateContentSize()` for expanding cards

---

## Accessibility Notes

- Minimum contrast ratio maintained by the palette (light text on dark backgrounds)
- All interactive elements meet 44dp touch target
- Semantic descriptions on status indicators (contentDescription for dots)

---

## Material 3 Mapping

When implementing in Compose, map this palette to a custom `darkColorScheme()`:

```kotlin
private val DarkColorScheme = darkColorScheme(
    background = Color(0xFF1A1A2E),
    surface = Color(0xFF16213E),
    surfaceVariant = Color(0xFF0F3460),
    primary = Color(0xFFE94560),
    onBackground = Color(0xFFEAEAEA),
    onSurface = Color(0xFFA0A0A0),
    onSurfaceVariant = Color(0xFF6A6A6A),
    outline = Color(0xFF2A2A4A),
    error = Color(0xFFEF4444),
)

// Extended colors (not in Material 3 scheme, define as top-level)
val CardBackground = Color(0xFF1F2940)
val InputBackground = Color(0xFF252540)
val Success = Color(0xFF4ADE80)
val Warning = Color(0xFFFBBF24)
val SyncGray = Color(0xFF6B7280)
```
