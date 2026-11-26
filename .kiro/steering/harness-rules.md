---
inclusion: always
---
# TinyAgent hard rules
1. app/src/main has exactly 6 Kotlin files: Agent.kt, Llm.kt, Screen.kt, Phone.kt, Main.kt, plus Api.kt
   (package app.tinyagent). Api.kt is the detachable model transport: the only file that talks HTTP to a model server.
   Not counted: Gradle files and wrapper, AndroidManifest.xml, res/xml/agent_service.xml, res/values/strings.xml,
   .github/workflows/build.yml, src/test/**. Main code is 1,200 lines or fewer in total.
2. Runtime dependencies: Kotlin stdlib and the Android SDK only (HttpURLConnection, org.json, Views built in code).
   No Compose, XML layouts, coroutines, OkHttp, or LangChain.
3. Cloud only: one OpenAI-compatible /chat/completions client (Api.kt). No on-device model. A self-hosted
   OpenAI-compatible server (llama.cpp, vLLM, Ollama, LM Studio) reached by URL counts as a cloud endpoint.
4. No task shortcuts: no regex, keyword, intent, or template routing of goals; no skills or playbooks; no code that
   picks deep links or taps buttons by their text. Every goal goes PLAN -> ACT -> CHECK.
   Allowed string plumbing: parsing the model's reply line, matching an app name to launcher labels,
   detecting a URL (contains "." and no spaces).
5. Exactly 5 tools, one plain-text command per reply: open, tap, type, scroll, key.
6. Every LLM call: prompt <= 450 tokens (estimated before sending, confirmed with usage.prompt_tokens after).
   Static prefix (rules + tools) <= 150 tokens, placed first, byte-identical across calls of the same type.
   Screen digest <= 200 tokens. When over budget, shrink the digest, never the prefix.
7. minSdk 30, targetSdk 36. Sideload only (Google Play's Accessibility policy forbids apps that autonomously plan and act).
8. Never type into password fields. Never log the API key. Screen text appears only in the in-app log.
