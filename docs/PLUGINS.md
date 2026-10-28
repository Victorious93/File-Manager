# Plugins and scripts (Phase 9)

## Design

Plugins are **declarative JSON manifests**. An action is a shell command template run with
`sh -c`; there is **no dex/class loading** and no script engine inside the app process.
Every manifest declares capabilities; the validator rejects actions that look like they
need more than they declare. Plugins install **disabled**; the user reviews the capability
list and enables each plugin individually. Remove deletes the manifest.

Tools → **Plugins**: *Install selected .json* (select one manifest in Files first),
*Add samples*, enable switch, action chips, Remove, output log.

## Manifest (apiVersion 1)

```json
{
  "id": "sample.checksums",
  "name": "Checksums",
  "version": "1.0",
  "author": "you",
  "description": "Print hashes of the selected files.",
  "apiVersion": 1,
  "capabilities": ["shell", "read_files"],
  "actions": [
    { "id": "sha256", "title": "SHA-256", "command": "sha256sum {file}",
      "target": "each_file", "extensions": [], "timeoutSec": 60 }
  ]
}
```

Unknown fields are rejected (so a manifest can't smuggle e.g. a `"dex"` entry).

### Targets and placeholders

| target | runs | placeholders |
|--------|------|--------------|
| `each_file` | once per selected file | `{file}`, `{name}`, `{dir}` |
| `all_files` | once with all files | `{files}`, `{dir}` |
| `folder` | once in the focused folder | `{dir}` |

All substituted paths are single-quoted (`'…'` with `'"'"'` escaping). `extensions` filters
the selection. `timeoutSec` 1–600.

### Capabilities

| capability | meaning / required when |
|------------|-------------------------|
| `shell` | always (every action is a shell command) |
| `read_files` | the command uses `{file}`, `{files}` or `{name}` |
| `write_files` | the command redirects (`>`) or uses `rm mv cp mkdir touch tee chmod chown unzip tar dd sed -i` |
| `network` | the command uses `curl wget nc ncat telnet ssh scp ftp` |
| `elevated` | run via the root/Shizuku shell when that is enabled (otherwise normal) |

The keyword checks are heuristics that make declared capabilities honest for common
cases; they are not a sandbox. Plugins run as the app's user (or root/Shizuku when
`elevated` and enabled) — only install plugins you trust and have read.

## Samples

`app/src/main/assets/plugins/`: **Checksums** (SHA-256 / MD5) and **File info**
(`wc` on text files, `ls -la` on the folder). Both are read-only.

## Scripts (automation runner)

Tools → **Scripts**: saved `sh` scripts (stored in app-private `scripts.json`).

- **Per-file** scripts run once per selected file with `$FILE`, `$NAME`, `$DIR` set
  (quoted) and the working directory set to the focused folder.
- **Once** scripts get the selection as `"$@"`.
- Output (stdout, stderr, exit code) goes to the on-screen log. Scripts run without root.

## Code

`core/…/plugins/Plugins.kt` (model, parser/validator, command builder, script wrapper),
`app/…/plugins/PluginRepository.kt`, `app/…/ui/Phase9Screens.kt`.
Tests: `PluginsTest` (parse, capability checks, ids/placeholders/unknown fields, quoting,
script wrapping, bundled samples valid).

## API v2: app plugins (Phase 17)

A plugin can also be a separate APK. VictorFiles never loads its code; Android's app sandbox isolates it.

```xml
<activity android:name=".HashPluginActivity" android:exported="true">
    <intent-filter><action android:name="ai.victorious.filemanager.action.PLUGIN" /></intent-filter>
    <meta-data android:name="ai.victorious.filemanager.plugin" android:value="@string/vf_plugin" />
</activity>
<!-- @string/vf_plugin: {"id":"com.example.hash","name":"Hash it","apiVersion":2,
     "extensions":["apk"],"minFiles":1,"maxFiles":1,"wantsWrite":false} -->
```

- Discovered with `queryIntentActivities(ACTION)`; listed in Tools → Plugins with a switch. Starts **disabled**.
- Invalid descriptors (bad id, apiVersion ≠ 2, minFiles/maxFiles out of 0..500, dotted extensions) are shown with the reason and cannot be enabled.
- On "Run on selection" the activity receives `ClipData` of `content://<pkg>.files/...` URIs (plus `data` when one file),
  `FLAG_GRANT_READ_URI_PERMISSION` (write only when `wantsWrite`, shown to the user), `extra.API_VERSION=2` and
  `extra.DISPLAY_NAMES`. No paths, no shell, no other storage access.
- Code: `core/.../plugins/AppPlugins.kt` (contract, parser, selection rules; `AppPluginsTest`) and `app/.../plugins/AppPluginHost.kt`.
