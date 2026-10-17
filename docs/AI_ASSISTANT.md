# AI assistant (Phase 9)

An optional **Explain** / **Suggest edit** panel in the text/code editor (which is also where smali
opens from APK surgery). It talks to an **OpenAI-compatible** endpoint that *you* configure.

## Setup

Settings → **AI assistant**:

| Field | Example |
|-------|---------|
| Enable | off by default |
| Base URL | `https://<your-provider>/v1` (the app appends `/chat/completions`) |
| Model | whatever your provider calls it |
| API key | your key — write-only in the UI; *Remove key* deletes it |

Nothing is bundled: no endpoint, model, or key ships in the APK or the repo. All three values
are stored in `EncryptedSharedPreferences` (AES-256, master key in the Android Keystore).
Plain `http://` is allowed for local servers but shows a warning.

## What is sent, and when

1. Select code (or nothing = whole file), tap the ✨ icon, pick **Explain** or **Suggest edit**.
2. A confirmation dialog shows the exact URL, model, character count, and how many likely
   secrets will be masked. **Nothing leaves the device until you tap Send.**
3. Before sending, `SecretRedactor` masks PEM private keys, common token formats
   (`sk-…`, `ghp_…`, `AKIA…`, `AIza…`, `xox…`, `Bearer …`) and `password=`/`token:`-style
   assignments. This is a safety net, not a guarantee — review what you send.
4. Snippets are capped at 24 000 characters.

The app never logs the key, the code, or the response. Error messages are scrubbed of the
key before being shown.

## Suggested edits

The model is asked for a single fenced code block. The app extracts it and shows a line diff
against your selection; **Accept** replaces the selection (undo works), **Reject** discards.
If no code block comes back, nothing is applied.

## Errors

Missing config, no key, invalid URL, HTTP errors (with the provider's message), and
offline/timeout each produce a clear message in the panel.

## Code

- `core/…/ai/AiAssistant.kt` — `AiEndpoint`, `SecretRedactor`, `PromptBuilder`, `AiResponseParser`
- `core/…/ai/AiClient.kt` — OkHttp client
- `app/…/ai/AiSettings.kt`, `app/…/ui/AiPanel.kt`
- Tests: `AiAssistantTest` (redaction, prompt, URL, parsing/diff/apply, MockWebServer, offline)
