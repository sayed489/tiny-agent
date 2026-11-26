# TinyAgent

An Android phone agent for tiny cloud models (Qwen3.5-0.8B, LFM 1-2B class). Type a goal such as
"set an alarm for 7:30" or "weather in Tokyo". TinyAgent reads the accessibility tree and completes the goal with
5 tools: `open`, `tap`, `type`, `scroll`, `key`.

The harness does the hard work so the model doesn't have to. It keeps all state and makes every model call stateless
and under 450 tokens. Each call asks one small question (PLAN, ACT, CHECK, REPORT, or LOOK for vision), and the
harness validates every answer and decides when to retry, look at a screenshot or replan.

**Sideload only.** Google Play's Accessibility policy forbids apps that autonomously plan and act.

## Install and set up

1. Install `TinyAgent.apk` (allow installs from your browser or file manager).
2. Open the app. It asks you to turn on the accessibility service. Android only lets you flip that switch yourself, in system settings.
   On Android 13+ the switch may say "Restricted setting": open **App info > ⋮ > Allow restricted settings**, then try again. The app's prompt has an **App info** button for this.
3. Open **Settings**, pick your provider (OpenAI, OpenRouter, Groq, your own server), paste your API key, enter a model name and tap **Test connection**.
4. Allow background activity when Android asks. Some phone makers kill accessibility services otherwise.

A yellow banner at the top of the chat always shows the next missing step.

TinyAgent does not declare `isAccessibilityTool`, because it is not an assistive tool and faking that flag gets apps
blocked. As a result it **will not run with Android 16 Advanced Protection turned on**.

## Configure a model

Any OpenAI-compatible `/chat/completions` endpoint works, cloud or self-hosted. Nothing is tied to one provider.
Open **Settings** and paste what your provider gives you:

| Field | What to enter |
|---|---|
| Base URL | The part before `/chat/completions`, for example `https://your-provider.com/v1` or `http://<pc-ip>:8000/v1`. A URL without `/v1` is retried with `/v1` automatically. |
| API key | Your provider's key. Leave it empty for servers without one. Keys are encrypted with an Android Keystore AES-GCM key. |
| Model | The model name exactly as your provider lists it. |

- **Test connection** runs one real PLAN call and one real ACT call on a sample screen and shows what the model wrote
  and what TinyAgent understood. Use it first whenever you change models.
- **Vision-language models**: turn on "My model can see images". The same endpoint then also reads screenshots when
  the screen has few labelled items. Set the screenshot size under Advanced to what your model's docs recommend.
- Provider-specific sampling settings (for example `top_k`) go in Extra body under Advanced.
- Plain `http://` is allowed so LAN servers work. Use `https://` for anything outside your own network.
- **Thinking (reasoning) models are switched off for quick calls** (choosing an action, checking a step, the report).
  TinyAgent tries the provider's documented switch first and remembers the one that works:

  | Provider | Switch sent |
  |---|---|
  | OpenAI, Azure OpenAI, Fireworks, Groq, Gemini | `reasoning_effort: "none"` (then `"minimal"`, then `"low"` for models without `none`) |
  | DeepSeek, Z.ai GLM, Moonshot Kimi, Volcengine | `thinking: {"type": "disabled"}` |
  | vLLM, SGLang, llama.cpp, LM Studio (your own server) | `chat_template_kwargs: {"enable_thinking": false}` |
  | Alibaba DashScope (Qwen), SiliconFlow | `enable_thinking: false` |
  | OpenRouter | `reasoning: {"enabled": false}` |
  | Ollama | `think: false` |

  Settings → Test connection shows which switch worked and how long one action took.
- **Token use and cost:** every task ends with a line such as `86 calls, 24370 in + 1062 out tokens, $0.0030 (₹0.27)`.
  Enter your model's price per 1M input and output tokens and the dollar-to-rupee rate in Settings (gpt-6-luna lists
  $0.10 in / $0.50 out).
- Local and cloud endpoints are handled the same way.
- Parameters a server rejects (for example `top_k` on Azure) are dropped automatically and not sent again.
- Small models are the target. Reasoning models also work: when a reply comes back empty because the model spent its
  token budget thinking, TinyAgent raises the budget and asks for low reasoning effort, but each step is slower.
- Extra body (Advanced) is merged into every request. For Qwen3-style models on vLLM or llama.cpp, set
  `{"chat_template_kwargs":{"enable_thinking":false}}`.
- Plan model (Advanced): a stronger model used only for PLAN.
- "Constrain CHECK to Y/N" adds vLLM's `structured_outputs: {"choice": ["Y","N"]}`.

## Detaching or replacing the model API

`app/src/main/java/app/tinyagent/Api.kt` is the only file that talks to a model server. The rest of the app sees
the small `ChatApi` interface:

```kotlin
interface ChatApi {
    fun chat(msgs: List<Msg>, temperature: Double, maxTokens: Int, extra: Map<String, Any?> = emptyMap()): Reply
    companion object { fun create(ep: Endpoint): ChatApi = OpenAiCompat(ep) }   // the single switch point
}
```

- To change providers, edit Settings. No code change is needed. `Api.kt` also adapts to newer OpenAI models that want `max_completion_tokens` and a fixed temperature.
- To change the transport (another wire format, a proxy, a mock), write a class that implements `ChatApi` and
  return it from `ChatApi.create`.

## Using it

The app is a chat. Type a goal ("set an alarm for 7:30") and tap **Send**. TinyAgent goes to the home screen, shows a status pill at the top, and works. Come back to the chat to see the plan, each step and the final result.
The button turns into **Stop** while a task runs.
- **Stop** on the pill, or **volume-down**, stops the task at once.
- Steps that send, post, buy, book or delete ask for confirmation first ("Confirm risky steps", on by default).
- When a step needs a password, TinyAgent never types it. It asks you to type it and tap **Continue**.
- **Settings > Copy debug log** copies every model call (type, prompt tokens, completion tokens, ms, reply) and every action and result. Each task also appends a row to `files/runs.csv` in the app's private storage.
- **Settings > Dump screen** saves the current window tree as JSON to Downloads after 5 s and opens the share sheet. The file
  contains the text on that screen. These dumps can become test fixtures.

Privacy: screen text (and screenshots, if vision is on) is sent to the API provider you configure. It is shown only in
the in-app log. The API key is never logged.

## Build from source

Requires JDK 17+ and the Android SDK (platform 36).

```
./gradlew :app:testDebugUnitTest      # JVM unit tests (Screen, Llm, Api, Agent)
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk (R8-shrunk, debug-signed)
```

The release build is signed with the local debug key, which is fine for sideloading. A build from another machine
has a different key, so uninstall the old APK before installing it.

## Layout

| File | Owns |
|---|---|
| `Screen.kt` | Pure Kotlin: node model, screen digest, token estimate, screen hash, vision marks |
| `Llm.kt` | The 5 prompts, token budgeting, reply and plan parsers |
| `Api.kt` | **Detachable** OpenAI-compatible HTTP transport (`ChatApi`) |
| `Agent.kt` | The PLAN → ACT → CHECK loop, budgets, loop detection, vision trigger, replans, run log |
| `Phone.kt` | AccessibilityService: node conversion, the 5 actions, settle wait, screenshots, overlay, kill switch |
| `Main.kt` | Chat screen (`Main`), settings screen (`Options`), saved settings and Keystore (`Store`); Views built in code |

Hard rules for contributors are in `.kiro/steering/harness-rules.md`. The spec is in `.kiro/specs/tinyagent/`.
