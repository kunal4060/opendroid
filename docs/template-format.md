# OpenDroid Template Format (C6)

Plan and macro templates are exchanged as a single JSON file:

- **Extension:** `.opendroid.json` (e.g. `morning-routine.opendroid.json`)
- **MIME type:** `application/json` (deliberately not a custom type, so
  system file pickers handle it everywhere)
- **Transport:** Android share sheet, or save/open through the Storage Access
  Framework — no storage permission is involved on either side.

## Envelope

```json
{
  "format": "opendroid-template",
  "schemaVersion": 1,
  "kind": "macro",
  "name": "Morning Routine",
  "exportedAt": 1790000000000,
  "appVersion": "1.0.7",
  "trigger": "cron:0 9 * * 1-5",
  "estimatedDuration": "",
  "steps": [ /* List<PlanStep> — see below */ ],
  "notes": "optional human description"
}
```

| Field | Meaning |
|---|---|
| `format` | Always `opendroid-template`. Any other value is rejected. |
| `schemaVersion` | Integer, currently `1`. A file from a newer schema is rejected with an "update the app" error; unknown older schemas are rejected too. |
| `kind` | `macro` or `plan`. |
| `name` | Template name (for a plan, its goal). On import, name collisions are resolved with an `(imported)` suffix — imports never overwrite. |
| `exportedAt` | Epoch millis when the file was produced. Informational. |
| `appVersion` | App version that produced the file. Informational. |
| `trigger` | Macro only: voice phrase or cron string, exactly as stored. |
| `estimatedDuration` | Plan only: free-text estimate, as stored. |
| `steps` | The step list — the **exact JSON** the app already stores in `MacroEntity.stepsJson` / `PlanEntity.stepsJson` (`List<PlanStep>` via kotlinx-serialization). Runtime fields (`status`, `result`, `error`) are always written reset (`PENDING` / `null`). |
| `notes` | Optional, may be absent or null. |

## Import validation (all layers must pass; any failure imports nothing)

1. **Size & syntax** — payload ≤ 1,000,000 chars, parses as a JSON object.
2. **Marker & version** — `format` must be `opendroid-template`;
   `schemaVersion` must be present and supported.
3. **Structure** — known `kind`; non-blank `name`; 1–500 steps; every step
   has a non-blank, unique `stepId`, a non-blank `action`, and ≤ 50 params.
   `dependsOn` links to steps not present in the file are dropped and
   reported as warnings.
4. **Action registry** — every step's `action` must be registered in this
   build (checked with `PlanValidator.validateStep`). A single unknown action
   blocks the whole import with a per-step error list — the file is
   incompatible with this build, and no partial entity is created.

Device-specific references are **flagged, not rejected**: steps whose params
name a contact (e.g. `contact = "Mom"`) or an app (`appName`, `package`, …)
produce preview warnings. The reference is imported verbatim and resolved by
the normal runtime paths on the importing device, which ask the user or fail
clearly — never crash.

## Safety defaults on import

- Fresh UUID — the imported entity never collides with or replaces an
  existing one.
- Macros import with `isSystem = false`, `isEnabled = false`: template files
  are untrusted input, and the user enables an imported macro explicitly.
- Plans import with status `PENDING`.

## Round-trip guarantee

Export a macro → delete it → import the file → the macro exists again with
the same name, trigger and steps (ids, order, descriptions, actions, params,
dependencies, fallbacks), ready to run the same sequence.
