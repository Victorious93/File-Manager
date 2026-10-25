# VictorSuite file panel (VictorFiles side)

VictorSuite's right-hand drawer shows a file manager window. VictorFiles supplies that window as a
right-edge sheet: `ai.victorious.filemanager.panel.PanelActivity`.

## Why a sheet activity and not an embedded view

VictorFiles and VictorSuite are separate APKs. Android cannot place one app's Activity inside another
app's view tree (the only route, `SurfaceControlViewHost`, needs API 30+ and VictorSuite is minSdk 23).
So the panel is a translucent activity that slides in from the right over the host, with a scrim, and
returns to the host on back, scrim tap, or a drag back to the right from its inner edge.

## Contract

| Item | Value |
|---|---|
| Component | `ai.victorious.filemanager/.panel.PanelActivity` (debug: `ai.victorious.filemanager.debug`) |
| Action | `ai.victorious.filemanager.action.PANEL` (category `DEFAULT`) |
| Extra (optional) | `ai.victorious.filemanager.extra.OPEN_PATH` (String): folder to open |
| Launch flags | Do **not** add `FLAG_ACTIVITY_NEW_TASK`: the sheet should stack over the host in the host's task. |
| Launch mode | `singleTop`, excluded from recents |

## Security

The panel shows the same UI as `MainActivity` (already an exported launcher activity) and grants the
caller no file access: files are read and written only by the user, through the normal storage
permission prompts. It is therefore not caller-gated. The fleet bridge (`FileManagerBridgeService`)
is unchanged and still off by default.

## Not covered

- `OPEN_PATH` can only open folders VictorFiles itself may read. VictorSuite's Termux home
  (`/data/data/com.termux/files/home`) is private to that app, so it is reachable only with root or
  Shizuku enabled in VictorFiles.
- Not run on a device; `:app` could not be compiled in the authoring environment (see PR notes).
