# Design: TinyAgent

## Overview

```
Main (Activity) --start(goal, Config)--> Phone (AccessibilityService, implements Device + Ui)
                                            |  worker thread
                                            v
                                          Agent --Brain--> Llm --ChatApi--> Api.kt (OpenAiCompat) --> /chat/completions
                                            |
                                            +--Screen.digest(windows)--> text for the model
```

- **Screen.kt** is pure Kotlin. It turns `List<Win>` into a `Digest` (text, numbered items, hash, counts).
  `N` uses identity equality, so sets of nodes never hash whole subtrees.
- **Llm.kt** owns the exact prompt texts (`Prompts`), the token-fitting loop (`fit`) and the parsers
  (`parse`, `parsePlan`, `firstLine`, `cleanLine`). It never touches HTTP.
- **Api.kt** is the detachable transport. `ChatApi.create(Endpoint)` is the only switch point.
- **Agent.kt** is the loop. It depends only on the `Device`, `Brain` and `Ui` interfaces, so it is JVM-testable.
- **Phone.kt** implements `Device` (real accessibility actions) and `Ui` (overlay pill and blocking `ask`).
- **Main.kt** builds Views in code, stores settings in SharedPreferences, and encrypts keys with the Keystore.

## Frozen interfaces

These are as specified in brief section 2, with these additions:
- `Config` (Agent.kt): the endpoints, plan model, vision endpoint, image size and toggles.
- `Endpoint`, `Msg`, `Reply`, `ApiError`, `ChatApi` (Api.kt).
- `Usage` (Llm.kt): calls, max prompt tokens and vision calls for `runs.csv`.
- `Screen.shown`, `shrink`, `marks`, `grid`, `pickApp`, `isUrl`, `clean`: pure helpers used by Phone, Llm and Agent.

## Key decisions

| Decision | Why |
|---|---|
| Api.kt split from Llm.kt (6th file) | The user asked for a small, clearly marked, detachable OpenAI-compatible file. Llm.kt keeps prompts and parsing. |
| Window selection: popups are the windows above the top window that covers at least 70% of the screen | Dialogs are often the *active* window, so "above the active window" misses them. |
| Hash covers all candidates (pre-budget), plus app package and keyboard flag | Scrolling below the 16-item cut, or opening the keyboard, still counts as a change. |
| Unlabeled clickable: borrow descendant labels before using the view-id name | Text such as "Mom · running late" beats ids such as "row container". |
| Clickable row with a checkable child reads as one `switch on/off <label>` item | One id per real control, which helps tiny models pick correctly. |
| `open` adds a final tier: a launcher label contained in the query (longest wins) | Tiny models write "open the Clock app". This is still launcher-label matching. |
| REPORT runs even after a budget stop, but not after a user Stop | The user gets a result sentence, and Stop is always immediate. |
| API errors after client retries go to `ui.ask("…Retry?")` | This is the "pause and show the error" behaviour. |
| PLAN prompt differs from the brief's exact text (v0.2.1) | Adds "one step per part of the task" for compound goals and an `answer:` line for chat messages like "Hello"; still ≤150 tokens. |
| Reasoning models: on an empty, cut-off reply, raise the output budget to 2048 and send `reasoning_effort` (low, then none) | gpt-5/o-series spent the 24-150 token budgets on reasoning and returned nothing, which made every plan fall back to one giant step. |
| `usesCleartextTraffic=true` | Self-hosted servers on a LAN usually serve plain HTTP. The README tells users to use HTTPS elsewhere. |

## Speed

- Each action needs one `windows()` snapshot. The post-action ACT digest is reused as the next iteration's screen,
  and the CHECK digest is built from the same snapshot.
- Own and IME windows are not walked. Node conversion stops at 1,500 nodes per window or depth 60.
- The null-root retry (300 ms) runs only when the *active* window's root is missing, not for decorative windows.
- `settle` ignores events from TinyAgent's own package, so pill updates don't extend the wait.
- HttpURLConnection keep-alive is reused (the connection is never `disconnect()`ed). System prompts are
  byte-identical, so the server can reuse its prefix cache.
- CHECK is skipped when the screen is unchanged. Launcher labels are cached per task and app labels per service.
- The release build uses R8 (minify and resource shrink). The APK is about 136 KB.

## Error handling

- `Halt(status, message)` ends a task: stopped, locked, or "stuck at step k".
- `InterruptedException` (Stop interrupts the worker) means stopped.
- Any other exception is caught by the worker in Phone and logged as "Crash".

## Testing

- `ScreenTest`: the exact Chrome example from the brief, popups and keyboard, password masking, switches, budgets,
  trimming, truncation, dedupe, ordering, footers, hash stability, `pickApp`, `isUrl`, marks and grid.
- `LlmTest`: parser cases (wrappers, aliases, labels, errors, vision marks), plan parsing and fallback, sampling per
  call type, prefix budget and identity, digest shrinking, usage.
- `ApiTest`: the real HttpURLConnection client against a local HTTP server: request shape, extra body merge,
  no-key mode, image data URL, retry and give-up, 4xx without retry, key never in errors.
- `AgentTest`: fake Device and Brain covering the happy path, label-form history, unchanged and repeated commands,
  replan limit, missing app, password handoff, re-ask, risky confirm, locked, stop, time budget, API retry,
  vision grid with zoom, protected screen, no vision without a model.
- On-device behaviour (gestures, volume-down kill switch, screenshots, real apps) cannot run in CI and needs manual
  testing.
