# Tasks: TinyAgent

Waves run in order. Tasks inside a wave are independent: each edits only its own source file and its own test file.

## Wave 1: frozen interfaces and scaffolding
- [x] 1.1 Gradle project, wrapper, manifest, `res/xml/agent_service.xml`, `res/values/strings.xml` (R1, R9)
- [x] 1.2 Steering file `.kiro/steering/harness-rules.md` (R1)
- [x] 1.3 Interfaces exactly as in brief section 2, plus `ChatApi`/`Endpoint` (Api.kt) and `Config` (Agent.kt) (R1, R2)

## Wave 2: independent modules (parallel)
- [x] 2.1 `Screen.kt` digest pipeline and helpers, with `ScreenTest` (R4)
- [x] 2.2 `Api.kt` OpenAI-compatible client with retries, extra body and images, with `ApiTest` (R2, R10)
- [x] 2.3 `Llm.kt` prompts, token fitting, reply and plan parsers, with `LlmTest` (R3, R5)

## Wave 3: loop and device (parallel)
- [x] 3.1 `Agent.kt` loop, budgets, loop detection, vision trigger, replans, handoffs, CSV, with `AgentTest` (R6, R7, R8, R12)
- [x] 3.2 `Phone.kt` node conversion, 5 actions, settle, screenshot and marks, overlay, kill switch, dump (R7, R8, R9)

## Wave 4: UI
- [x] 4.1 `Main.kt` onboarding, settings, Keystore, goal, run/stop, log, copy, dump screen (R7.4, R11)

## Wave 5: verification and delivery
- [x] 5.1 Hold main code to 1,200 lines or fewer and 6 files (R1)
- [x] 5.2 Run unit tests (55 passing) and lint (0 errors) (R13)
- [x] 5.3 `.github/workflows/build.yml` with hard-rule checks (R14)
- [x] 5.4 README (R15)
- [x] 5.5 Build the R8 release APK and verify its manifest, service config and signature
- [ ] 5.6 Manual on-device test: onboarding, the 3 sample goals, volume-down kill switch, vision fallback (needs a phone)
- [ ] 5.7 Open a PR (needs a GitHub repository; none was provided)
