# Phase 17 — Droidcommand AI code assist, secure folder, Dolby/DTS audio, translation & utilities, plugins

Written from scratch. **No MT Manager or NP Manager code was read, copied or decompiled**; only their
public feature lists informed the scope.

## 1. Code assist via Droidcommand AI

The text editor (used for code files and for smali exported from APK surgery) has an
**Ask Droidcommand AI** button (sparkle icon). It offers **Explain** and **Suggest edit** (with an
optional instruction). A suggested edit is split into hunks and shown as a red/green diff; tick the
hunks to keep and **Apply**, or **Reject**. Applied edits go through the editor's undo history and
are not saved until you tap Save.

VictorFiles holds **no AI API key and makes no AI network calls**: Droidcommand AI
(`ai.droidcommand.app`) owns the model, keys and network. If it is missing, the dialog shows
*Get Droidcommand AI*; if it is installed but does not handle the action, *Update Droidcommand AI*.

### Contract v1 (VictorFiles = client, Droidcommand AI = provider)

| | |
|---|---|
| Transport | `startActivityForResult` (ActivityResult API), explicit package `ai.droidcommand.app` |
| Action | `ai.droidcommand.action.CODE_ASSIST` |
| Request extra | `ai.droidcommand.extra.REQUEST_JSON` |
| Result extra | `ai.droidcommand.extra.RESULT_JSON` (with `RESULT_OK`) |

Request:

```json
{"v":1,"task":"explain|suggest_edit","fileName":"Foo.smali","language":"SMALI",
 "code":"…","instruction":"…","truncated":false,"prompt":"ready-made prompt incl. code"}
```

`code` is capped at 60 000 chars (binder limit); `truncated` says so. Result:

```json
{"v":1,"ok":true,"explanation":"…","revisedCode":"full new file (suggest_edit only)"}
{"v":1,"ok":false,"error":"…"}
```

If `revisedCode` is missing, VictorFiles takes the largest fenced code block in `explanation`.
A result with `v` greater than 1 is refused. `RESULT_CANCELED` = user cancelled.

### What Droidcommand AI needs to add (not done here — repo untouched)

As of droidcommand-AI `22ed36e`, its manifest exports only `MainActivity` (launcher) and the
Shizuku provider; it consumes companions (`ICompanionService`, `IHackerAIService`, …) but exposes
no AI entry point to other apps. To light this up it needs:

1. An exported activity with an intent-filter for `ai.droidcommand.action.CODE_ASSIST`
   (default category) that reads `REQUEST_JSON`, shows the request to the user for consent,
   runs it through its configured model, and `setResult(RESULT_OK, Intent().putExtra(RESULT_JSON, …))`.
2. Caller check: `getCallingActivity()` / `callingPackage` must be `ai.victorious.filemanager`
   (or `.debug`) and its signing cert must match the fleet cert (its existing `NodeSignatureGuard`).
3. Optional: `<package android:name="ai.victorious.filemanager"/>` is already in its `<queries>`.

Code: `core/…/ai/CodeAssist.kt` (`CodeAssistContract`, `PromptBuilder`, `EditHunks`),
`app/…/ai/DroidCommandAssist.kt`, `app/…/ui/AiAssist.kt`. Tests: `CodeAssistTest`.
