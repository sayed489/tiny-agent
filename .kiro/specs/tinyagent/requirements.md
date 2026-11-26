# Requirements: TinyAgent

TinyAgent is an Android app that completes a plain-language goal in any app or website by reading the accessibility
tree and acting through 5 tools, driven by a tiny model behind an OpenAI-compatible API. The harness owns all state.

Note: the source brief was cut off in section 9 (Keystore key storage). Sections 10 and later were not received.
Requirements R13 to R15 (tests, CI, README) are inferred from the rest of the brief.

## R1 Code shape
1. The app SHALL have exactly 6 Kotlin files in `app/src/main` (Agent, Llm, Screen, Phone, Main, Api) in package
   `app.tinyagent`, totalling 1,550 lines or fewer.
2. The app SHALL have no runtime dependencies other than the Kotlin stdlib and the Android SDK.
3. The app SHALL set minSdk 30 and targetSdk 36.

## R2 Detachable model transport
1. The app SHALL send model requests only through the `ChatApi` interface in `Api.kt`.
2. WHEN the user changes Base URL, key or model, the app SHALL use the new endpoint without code changes.
3. WHERE the API key is blank, the app SHALL send no `Authorization` header.

## R3 Tools and reply parsing
1. The app SHALL accept exactly one command per reply: `open`, `tap`, `type`, `scroll` or `key`.
2. WHEN a reply contains `<think>…</think>`, list markers, backticks, quotes, or a leading `Action:` or `Command:`,
   the parser SHALL strip them and use the first non-empty line.
3. WHEN a reply uses click, press, swipe, launch or input, the parser SHALL treat it as tap, key, scroll, open or type.
4. IF an id is not in the current digest, or a direction, key or text length is out of range, THEN the parser SHALL
   return an error reason.
5. WHEN a tap or type argument is not a number, the parser SHALL accept it only as an exact, unique item label.

## R4 Screen digest
1. The digest SHALL list popups first, flag `popup` and `keyboard`, and never list the app's own or IME windows.
2. The digest SHALL skip invisible, zero-area and off-screen nodes, and SHALL drop bare layout containers.
3. WHEN a clickable node has no label, the digest SHALL borrow up to 2 descendant labels.
4. WHEN an actionable node has no label at all, the digest SHALL print `(icon, <position>)` and count it as unlabeled.
5. The digest SHALL never print the text of a password field.
6. The digest SHALL apply the ACT budget (all inputs, 16 buttons or switches, 4 texts) or the READ budget
   (6 actionable, 16 texts), then trim until it fits the token limit.
7. The screen hash SHALL depend on item kind, label and state, and not on ids or bounds.

## R5 Token budgets
1. Every model call SHALL be estimated at 450 tokens or fewer before sending.
2. IF an estimate exceeds 430 tokens, THEN the client SHALL shrink the digest (16, 10, then 6 items) and never the
   static prefix.
3. Static system prompts SHALL be 150 tokens or fewer and byte-identical across calls of the same type.
4. The app SHALL record `usage.prompt_tokens` from every response and log a warning above 450.

## R6 The loop
1. WHEN a task starts on a locked phone, the agent SHALL stop with "Unlock the phone first".
2. The agent SHALL run PLAN, then for each step ACT and CHECK, then REPORT.
3. WHEN a step is not the first, or follows a replan, the agent SHALL CHECK whether it is already done first.
4. WHEN a command was already run on the same screen hash in this task, the agent SHALL refuse it and count no progress.
5. WHEN the screen hash is unchanged after an action, the agent SHALL count no progress and SHALL NOT call CHECK.
6. WHEN a step reaches 4 no-progress actions or 8 actions, the agent SHALL add a fact and replan.
7. IF the agent has already replanned twice, THEN it SHALL stop with "Stuck at step k".
8. The agent SHALL enforce 40 actions and 6 minutes per task.
9. WHEN `open` finds no matching app, the agent SHALL add "<app> is not installed; use its website" and replan.

## R7 Safety and handoffs
1. WHEN a step is risky and "Confirm risky steps" is on, or "Confirm every step" is on, the agent SHALL ask first.
2. WHEN a command would type into a password field, the agent SHALL ask the user to type it and SHALL NOT type it.
3. WHEN the user presses Stop or volume-down during a task, the agent SHALL stop.
4. The app SHALL never log the API key and SHALL store it encrypted with an Android Keystore AES-GCM key.

## R8 Vision fallback
1. WHERE a vision model is configured, WHEN a screen has fewer than 3 actionable items, more than half unlabeled, or
   the step has 2 no-progress actions, the agent SHALL use LOOK with a numbered screenshot.
2. WHEN fewer than 3 nodes have bounds, the agent SHALL use a 4x6 grid, then a 3x3 zoom pass on the chosen cell.
3. IF the screen is protected, THEN the agent SHALL turn vision off for the task and add "screen is protected".

## R9 Device actions
1. `open` SHALL match launcher labels exactly, then by starts-with, then by contains (shortest wins), and SHALL open
   URLs in the default browser.
2. `tap` SHALL try ACTION_CLICK, then the nearest clickable ancestor, then a gesture at the centre.
3. `type` SHALL try ACTION_SET_TEXT, verify the text, then fall back to clipboard paste.
4. `scroll` SHALL use scroll actions on the largest scrollable node, with a swipe gesture as fallback.
5. `settle` SHALL wait for 300 ms without layout events (200 ms minimum, 2.5 s maximum, 5 s while a ProgressBar is visible); text-only and state-description changes (timers, video progress) SHALL NOT count as layout events.

## R10 Model client
1. The client SHALL use a 10 s connect timeout and a 30 s read timeout.
2. WHEN a response is 429, 5xx or a timeout, the client SHALL retry twice (after 1 s, then 3 s).
3. The client SHALL merge the user's Extra body JSON into every request.

## R11 UI
1. The main screen SHALL be a chat: the user's goal as a bubble, a live progress bubble, then a result bubble, with one input and a Send button that becomes Stop while a task runs.
   A separate Settings screen SHALL hold the provider preset, Base URL, API key, model, a Test connection button, permissions, safety toggles, advanced options and tools.
   WHEN a setup step is missing (accessibility service, API key, background permission), the chat SHALL show a banner that opens the right prompt, and SHALL ask on first launch.
2. The app SHALL show a one-time privacy notice.
3. Dump screen SHALL write the window tree as JSON and open the share sheet, warning that it contains screen text.

## R12 Run log
1. The app SHALL log every call and every action, and append one CSV row per task to `files/runs.csv`.

## R13 Tests (inferred)
1. Screen, Llm, Api and Agent SHALL have JVM unit tests that run without a device.

## R14 CI (inferred)
1. `.github/workflows/build.yml` SHALL check hard rules 1, 2 and 4, run tests and lint, and upload the APK.

## R15 README (inferred)
1. The README SHALL cover sideloading, onboarding, model configuration, detaching the API, and Advanced Protection.
