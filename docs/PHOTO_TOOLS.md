# Photo tools

## EXIF (androidx.exifinterface)
View common tags, edit a tag, or **strip** by rewriting pixels (no metadata).

## Batch rename
Pattern tokens: `{name}` `{ext}` `{index}` `{index:00}` `{date:yyyyMMdd}` `{parent}`.
Preview before apply.

## Batch resize / convert / compress
Fit within max width/height (no upscale), JPEG/PNG/WebP, quality slider. Writes beside the folder into the focused pane directory.

## Code
- `core/.../photo/BatchRename.kt`, `ImageBatch.kt`
- `app/.../photo/ExifTools.kt`
- Tools → **Photo tools**

## Tests
`PhotoToolsTest`.
