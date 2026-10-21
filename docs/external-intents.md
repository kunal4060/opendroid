# External intent API (Tasker / ADB / automation apps)

OpenDroid can be driven by other apps and tools on the same device —
Tasker, MacroDroid, ADB, or your own app — through a small, explicit intent
API. This document is the contract: action names, extras, result channels,
and the security model. The code mirror of this contract (and the single
source of truth for the constant values) is
`app/src/main/java/com/opendroid/ai/core/external/ExternalIntentContract.kt`.

## Turning it on

1. Open **Settings → External automation** and switch it on (it is **off by
   default**; while off, every external intent is ignored and logged).
2. Tap **Generate token**. Copy the API token — it is shown only once. Paste
   it into your Tasker tasks / scripts. Regenerating invalidates the old
   token immediately; **Revoke** kills it entirely.

Only the token's salted hash is stored on the device, in an app-private
preference file. The token itself is never written to storage or to any log.

## Actions

All actions are app-namespaced. Every action requires the `auth_token`
extra (see *Security model*). Two entry components accept the same actions
with the same extras:

| Component | Type | Use it from |
|---|---|---|
| `com.opendroid.ai.core.external.ExternalCommandReceiver` | broadcast receiver | Tasker *Send Intent (broadcast)*, `adb shell am broadcast` |
| `com.opendroid.ai.core.external.ExternalApiActivity` | activity | Tasker *Send Intent (activity)*, `adb shell am start`, `startActivityForResult` callers |

| Action | What it does | Required extras | Optional extras |
|---|---|---|---|
| `com.opendroid.ai.action.RUN_COMMAND` | Runs a natural-language instruction through the agent (same path as typing it in chat) | `instruction` | `session`, `request_id`, reply target |
| `com.opendroid.ai.action.RUN_MACRO` | Runs a saved macro's steps (the same `RUN_MACRO` action the agent uses) | `macro_id` **or** `macro_name` | `request_id`, reply target |
| `com.opendroid.ai.action.QUERY_STATUS` | Reports the last external run and the most recent recorded task | — | `request_id`, reply target |

### Extras

| Extra | Type | Notes |
|---|---|---|
| `auth_token` | String | **Required on every action.** Missing/wrong → rejected + logged (code `AUTH_FAILED`). |
| `instruction` | String | The command to run. Max 2,000 characters. |
| `session` | String | Target chat-session id for `RUN_COMMAND`. Default: the dedicated **"External commands"** session, created automatically. An unknown id is rejected (`NOT_FOUND`) rather than silently creating a chat. |
| `macro_id` / `macro_name` | String | Identify the macro for `RUN_MACRO`. Disabled macros are refused. |
| `request_id` | String | Your correlation id (max 100 chars). Echoed in results and written to the history. |
| `reply_package` | String | Package to receive the result broadcast. |
| `reply_component` | String | Flattened component (`your.package/.YourReceiver`) to receive the result broadcast. Wins over `reply_package`. |
| `reply_action` | String | Action for the result broadcast. Default: `com.opendroid.ai.action.COMMAND_RESULT`. |

## Results

External commands are asynchronous: `RUN_COMMAND` is *accepted* immediately
and runs in the chat session; use `QUERY_STATUS` (or open the chat) for the
outcome. `RUN_MACRO` completes synchronously inside the handler, so its
result is final.

Results travel on up to two channels:

1. **Result broadcast** — sent only when you supplied a reply target, and
   always **explicit** (addressed to your component/package), never an
   implicit broadcast: results can contain personal data and are not shouted
   to the whole device.
2. **Activity result** — when `ExternalApiActivity` is started *for result*,
   it calls `setResult` with the same extras. (Plain `am start` and
   Tasker's fire-and-forget Send Intent cannot receive this; use the result
   broadcast or `QUERY_STATUS` there.)

### Result extras

| Extra | Values |
|---|---|
| `request_id` | Echo of your `request_id`, if you sent one. |
| `success` | `"true"` / `"false"` |
| `code` | `OK`, `MALFORMED`, `API_DISABLED`, `AUTH_FAILED`, `APP_LOCKED`, `NOT_FOUND`, `EXECUTION_FAILED` |
| `summary` | Short human-readable outcome. |

`QUERY_STATUS` additionally returns:

| Extra | Meaning |
|---|---|
| `status_state` | Last external run: `NONE`, `RUNNING`, `AWAITING_APPROVAL` (a plan is waiting for in-app approval), `COMPLETED`, `FAILED` |
| `status_kind` | `COMMAND` or `MACRO` |
| `status_label` | The instruction or macro name |
| `status_summary` | Final agent reply / error summary, when finished |
| `status_updated_at` | Epoch millis of the last state change |
| `last_task_description` / `last_task_action` / `last_task_success` / `last_task_timestamp` | The most recent entry in OpenDroid's task history (any source, not just external) |

While the app lock is engaged, `QUERY_STATUS` returns coarse state only —
the `status_state`/`last_task_success`/timestamps, without labels, summaries
or task descriptions.

## Examples

Run a command from ADB (broadcast path):

```sh
adb shell am broadcast \
  -a com.opendroid.ai.action.RUN_COMMAND \
  -n com.opendroid.ai/.core.external.ExternalCommandReceiver \
  --es auth_token 'YOUR_TOKEN' \
  --es instruction 'Turn on the torch' \
  --es request_id 'demo-1'
```

Run a macro by name (activity path):

```sh
adb shell am start \
  -a com.opendroid.ai.action.RUN_MACRO \
  -n com.opendroid.ai/.core.external.ExternalApiActivity \
  --es auth_token 'YOUR_TOKEN' \
  --es macro_name 'Morning routine'
```

Ask for status, with the answer broadcast back to your own receiver:

```sh
adb shell am broadcast \
  -a com.opendroid.ai.action.QUERY_STATUS \
  -n com.opendroid.ai/.core.external.ExternalCommandReceiver \
  --es auth_token 'YOUR_TOKEN' \
  --es reply_component 'your.package/.YourResultReceiver'
```

In Tasker: *Task → System → Send Intent*, set the Action to one of the
actions above, choose the receiver or activity above as the target
(package `com.opendroid.ai`, class as listed), and add the extras —
`auth_token` first among them.

## Security model

Defence in depth; **all** layers apply to every intent:

1. **Master toggle, default off.** Nothing is even evaluated while
   Settings → External automation is off.
2. **API token.** A 192-bit random token, generated on-device, stored only
   as a salted SHA-256 hash, compared in constant time. Missing and wrong
   tokens are indistinguishable (`AUTH_FAILED` either way), and the token
   value never appears in logs — the audit entry records only
   present/absent/invalid.
3. **App lock.** While the OpenDroid app lock (biometric/PIN) is engaged,
   execution intents are refused (`APP_LOCKED`). A locked phone does not
   become remotely drivable because a toggle was left on.
4. **Same agent, same rules.** Accepted commands run through the normal
   agent path: planning, action risk tiers, and the chat's approval mode
   (MANUAL/AUTO) apply exactly as if the user had typed the command. A
   Tasker-triggered payment in MANUAL mode still waits for approval; the
   run shows up in the "External commands" chat and in the task history.
5. **Full audit trail.** Every intent — accepted, rejected, malformed — is
   written to the task history (`actionType = EXTERNAL_API`) with its
   outcome code and caller type, and visible in the app's history surfaces.
6. **Malformed intents never crash.** Unknown actions, missing extras and
   oversized values are rejected with `MALFORMED` and logged.

Honest limitations:

- **ADB is trusted by design.** Anyone with working ADB/USB debugging
  access to an unlocked device already controls the device; this API's
  gates protect against *apps on the device*, not against ADB access.
  (That is also why no signature-level manifest permission is used: Tasker
  could never hold one, and ADB bypasses app permissions anyway — the
  token is the gate that works for both.)
- An external `RUN_COMMAND` is a new agent task: like a voice command, it
  supersedes a task the agent is currently running.
- Status tracking for a running command follows the agent's state; if you
  start chatting with the agent yourself mid-run, the reported state can
  reflect your in-app activity.
