# Accessibility audit — D8 pass (2026-10-21)

Systematic accessibility pass over the screens in the A+B+C tree:
TalkBack semantics, font-scale clipping, reduce motion, and WCAG AA
contrast in the shared theme tokens. Every row below is a screen that
was actually opened and edited (or explicitly verified); screens not
listed here were **not** audited — see the coverage section of
`feature-d8-report.md` at the workspace root of this project.

## Audit table — screen × issue × fix

### Chat (`ui/screens/ChatScreen.kt`)
| Issue | Fix |
|---|---|
| Voice orb was a raw `clickable` Box — no role, no label, TalkBack stopped on an unnamed control | `role = Role.Button`, `contentDescription` "Voice input" / "Stop voice input" (by state), `stateDescription` = Listening / Thinking / Executing plan / Speaking / Idle |
| Agent status line (Thinking / approval needed…) changed silently | Polite live region on `AgentStatusSubtitle` |
| Thinking indicator was three visual-only pulsing dots | Merged semantics node, polite live region, `contentDescription` "OpenDroid is thinking" |
| Voice waveform bars were visual-only; the live transcript was a separate stop | Merged node, polite live region, `contentDescription` "Listening" / "Listening: \<transcript\>" |
| Message bubble read as many fragmented stops; the A3 long-press actions (Copy / Read aloud / Share / Regenerate / Edit) are unreachable by TalkBack gesture | Merged bubble semantics (one stop per message), `stateDescription` "Being read aloud" while TTS speaks, all five actions re-exposed as `CustomAccessibilityAction`s |
| Plan-approval prompt appeared silently | Assertive live region on the prompt card; its title marked `heading()` |
| Error/recovery card appeared silently | Assertive live region |
| Composer field labelled only by its placeholder (lost once text is typed) | `contentDescription` "Message OpenDroid" |
| Auto-mode chip used a fixed `height(28.dp)` — label could clip at large font scales | `heightIn(min = 28.dp)` |
| Screen title not navigable as a heading | `heading()` on "OPENDROID" |

### Plan (`ui/screens/PlanScreen.kt`, `ui/components/PlanStepCard.kt`)
| Issue | Fix |
|---|---|
| Step status icon announced the raw enum name (e.g. "AUTO_FIXING") | Plain-language `accessibleLabel()` per state (Running / Auto-fixing / Repaired / Blocked…) |
| Step card read as many fragmented stops; expanded params/result/error easy to miss | Merged card semantics: "Step N. \<description\>" + `stateDescription`; the composed description folds in result/error (existence note when collapsed, full text when expanded) so merging never hides the outcome |
| Edit/Delete step buttons would be swallowed by the merge | Re-exposed as custom actions when the card is editable; no merging while the edit form is open, so its fields stay focusable |
| Plan status (RUNNING → COMPLETED/FAILED) changed silently | Polite live region + composed status description on the header status row |
| Past-plan selection was colour-only | `stateDescription` "Selected" / "Not selected" + composed description (goal, step count, status) |
| Past-plan delete target was 24 dp | 40 dp |
| "Viewing Past Run" was a clickable Text with no role | `Role.Button` + description of what activating it does |
| Warning-state colours hard-coded `#FFB300` (1.79:1 as text on light) / `#FF5722` (3.16:1) in step card, plan header (CANCELLED) | Theme `AccentOrange` token (same amber family, AA in both themes); state is never carried by colour alone — icon + text label remain |

### Memory (`ui/screens/MemoryScreen.kt`, `ui/screens/graph/MemoryGraphView.kt`)
| Issue | Fix |
|---|---|
| Working-memory plan steps showed status as glyph text (● ▶ ✖ ○) — meaningless to a screen reader | Merged row semantics speaking step number, plain-word state (completed / running / failed / pending), description, result and error |
| Screen title not a heading | `heading()` on "PERSONAL MEMORY" |
| C5 knowledge-graph canvas had a text summary but no counts | Summary enriched with per-tier counts computed from the same filtered node set the canvas draws (sensitive memories counted, never named — they render locked). The List view (C5) already provides the same memories in list form; verified, unchanged |

### Settings (`ui/screens/SettingsScreen.kt`, new `ui/screens/AccessibilitySettingsSection.kt`)
| Issue | Fix |
|---|---|
| No reduce-motion control existed anywhere | New **Accessibility** section in Settings: "Reduce motion" Switch, persisted (`LLMConfig.reduceMotion` → `SettingsViewModel.updateReduceMotion`), with a note when the system animator scale already forces it |
| Provider dropdown trigger was a custom Box — no role/label; fixed `height(56.dp)` could clip the provider name at large font scales | `Role.Button` + `contentDescription` "Active brain provider: \<name\>. Tap to change"; `heightIn(min = 56.dp)` |

### Logs (`ui/screens/LogsScreen.kt`, `ui/screens/AuditLogTab.kt`)
| Issue | Fix |
|---|---|
| Audit entries read as fragmented stops; the "Save completed task as macro" button sat deep inside the card | Merged entry semantics (badge, time, description, module, denied-explainer read in order); save-as-macro re-exposed as a custom action when available |
| Day-group labels not navigable | `heading()` on day labels |
| Unknown-action (error) cards read as fragmented stops | Merged semantics + `stateDescription` "Expanded" / "Collapsed" |
| Screen title not a heading | `heading()` on "AUDIT LOG" |

### Splash (`ui/screens/SplashScreen.kt`)
| Issue | Fix |
|---|---|
| Loading dots pulsed on an infinite loop | Gated by `LocalReduceMotion` — dots rest at a fixed 0.85 opacity; one-shot logo fade/scale and navigation timing unchanged |

### Template import (`ui/templates/TemplateImportDialog.kt`)
| Issue | Fix |
|---|---|
| Warning text hard-coded `#FFB300` (1.79:1 on light) | Theme `AccentOrange` token |

## Reduce motion — design

- `ui/theme/Motion.kt`: `LocalReduceMotion` CompositionLocal +
  `systemReduceMotionEnabled(context)` (true when
  `Settings.Global.ANIMATOR_DURATION_SCALE == 0`; read failures default
  to *not* reduced so a platform quirk can never freeze motion state on).
- `OpenDroidTheme(reduceMotion = …)` resolves the **effective** value —
  user toggle **OR** system scale — and provides it to the whole tree.
  `MainActivity` passes the persisted toggle from `LLMConfig`.
- Gated continuous animations (render a static frame instead):
  chat voice-orb pulse, thinking-indicator dots, voice waveform bars,
  splash dot pulse.
- One-shot transitions (tab fades, dialog entries, the splash logo
  fade/scale) are deliberately **not** gated: they are brief and carry
  state-change information.

## Contrast — tokens changed (same hues, lightness adjusted)

Ratios computed with the WCAG relative-luminance formula.

| Token | Old | New | Pair | Before | After |
|---|---|---|---|---|---|
| `Color.kt` LightPalette `accentOrange` | #D97706 | #B45309 | on white | 3.19:1 | 5.02:1 |
| `Color.kt` LightPalette `accentGreenButton` | #0E8A4C | #0B7A44 | white text on fill | 4.41:1 | 5.41:1 |
| `Accent.kt` dark CYAN `fill` | #0284C7 | #0369A1 | white `onFill` on fill | 4.10:1 | 5.93:1 |
| `Accent.kt` light SIGNAL_GREEN `fill` | #0E8A4C | #0B7A44 | white `onFill` on fill | 4.41:1 | 5.41:1 |
| `Accent.kt` light CYAN `marks` | #0284C7 | #0369A1 | on white | 4.10:1 | 5.93:1 |

All other checked text/background token pairs already met AA and were
left untouched (including light `textSecondary` on surface at 4.58:1 —
passes; left unchanged to preserve the visual identity). Call-site
hard-coded ambers (`#FFB300`, `#FF5722`) were replaced by the theme
`AccentOrange` token rather than new literals (PlanStepCard, PlanScreen,
TemplateImportDialog).

## Font scale (1.3×) — findings

- Fixed-height traps on text-bearing controls were found and fixed in
  exactly two places: the Settings provider dropdown and the Chat
  auto-mode chip (both now `heightIn(min = …)`).
- The Chat composer already used `heightIn(min = 56.dp, max = 120.dp)`
  (verified).
- All other core-screen controls are Material 3 components that size to
  content, and the core screens scroll (`LazyColumn` / vertical scroll),
  so primary actions stay reachable at large font scales.

## Focus / traversal order

Core screens were checked: composition order matches visual order
(chat list in message order, plan timeline in step order top→bottom,
settings top→bottom), and the merged semantics collapse multi-element
cards into single stops in that same order. No `traversalIndex`
overrides were needed on the core screens.

## Known limitations

- The assistant-session surface composes `OpenDroidTheme` without the
  user's config, so its animations honour the **system** animator scale
  (via the theme) but not the in-app Reduce motion toggle.
- Inside a merged chat bubble, per-code-block copy buttons of the
  markdown renderer merge into the bubble stop; the whole-message Copy
  custom action covers the content.
- Live-region behaviour is implemented to the Compose semantics spec
  but has not been exercised with a physical TalkBack device in this
  phase.
