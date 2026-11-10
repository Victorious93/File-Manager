# Notes vault (Obsidian-style)

A markdown vault inside VictorFiles. **Written from scratch** — no Obsidian source was used.

Code: `core/.../notes/` (wikilinks, backlinks, vault, preview) and `app/.../notes/` (`NotesVaultPane`, `FileNoteIo`).

## Features

| Feature | How |
|---|---|
| Any folder as a vault | Pick a path (or use app `Notes/`). All `*.md` files are notes. |
| Markdown editor + live preview | Editor / Preview / Split panes. Preview renders headings, nested lists, tappable task lists (`- [ ]` / `- [x]`, ticks write back to the note; notes with Windows `\r\n` line endings work too and keep them), pipe tables with `:---:` alignment, quotes, fences, bold/italic/`~~strike~~`/`==highlight==`, `[text](url)` and `<url>` links (http/https/mailto only), `[[wikilinks]]`, `#tags`. |
| `[[wikilinks]]` | `[[Target]]`, `[[Target\|Alias]]`, `[[Target#Heading]]`, `[[Target#Heading\|Alias]]`. Autocomplete while typing `[[`. Unresolved links can create the note. |
| Backlinks / outgoing | Side panes on the open note. Index rebuilt on vault open and on save. |
| `#tags` | Parsed outside frontmatter and wikilinks; ATX headings are not tags. |
| Graph view | Compose `Canvas` nodes + edges from the backlink index; pan/zoom; list under the canvas to open a note. |
| Quick switcher | Fuzzy title suggest from the index. |
| Full-text search | Vault body search + optional `FileIndex` hits under the vault root. |
| Daily notes | `Daily/yyyy-MM-dd.md`, optional `Templates/Daily.md` with `{{date}}` / `{{title}}`. |
| Templates | `Templates/*.md`; `{{title}}` / `{{date}}` substitution on new note / daily. |
| Frontmatter | YAML-ish `---` block; stripped for preview; fields available to parsers. |
| Code fences | Highlighted with the existing in-house `SyntaxHighlighter` (kotlin/java/js/python/shell/xml/json/md). |

## Tests

`NotesVaultTest` — wikilink/tag/frontmatter parsing, backlink index (outgoing/backlinks/unresolved/suggest/orphans), vault daily/templates/search, markdown preview blocks. `MarkdownPreviewExtrasTest` — task line numbers (frontmatter included) round-trip through `NoteOps.toggleTask`, tables (alignment, escaped pipes, short rows), inline strike/highlight/links, unsafe link schemes stay text, nested list depth. `NoteOpsTest.toggleTasksInWindowsLineEndingNote` — CRLF notes (with CRLF frontmatter) toggle the line the preview reports and keep every `\r\n`.
