# User Extension `.user.js` Meta Design

## Goal

Redesign `createDefaultExtensions()` and the user extension storage model so scripts behave more like `.user.js` files:

- the metadata lives at the top of the JavaScript file
- the script body stays in the same file
- bundled extensions, user-created extensions, backup, import, and edit all use the same file format

This document describes the target design. It is a proposal, not the current implementation.

## Current State

Today `UserExtensionRepository` stores data in two parts:

- metadata in `SharedPreferences`
- script content in `filesDir/user_extensions/*.js`

Default extensions are currently created from Kotlin string literals in `createDefaultExtensions()`.

That has a few drawbacks:

- the source of truth is split
- default scripts are hard to maintain
- backup/export needs special handling
- deleting all extensions causes default extensions to be recreated

## Proposed Direction

Use a single script file format compatible with Greasemonkey-style `.user.js`.

Every extension file starts with a meta block, followed by the actual JavaScript body.

Example:

```javascript
// ==UserScript==
// @name        图片反色
// @namespace   einkbro
// @match       *://*/*
// @version     1.0.0
// @description Invert all images on the page
// ==/UserScript==

var images = document.getElementsByTagName('img');
for (var i = 0; i < images.length; i++) {
    images[i].style.filter = 'invert(100%)';
}
```

Auto-run example:

```javascript
// ==UserScript==
// @name        错误日志
// @namespace   einkbro
// @match       *://*/*
// @run-at      document-start
// @version     1.0.0
// ==/UserScript==

(function() {
    console.log('error log enabled');
})();
```

## Meta Fields

Recommended supported fields:

- `@name`
  - display name
- `@match`
  - can appear multiple times
  - preferred over app-specific match rules
- `@include`
- `@exclude`
- `@run-at`
  - use values such as `document-start`, `document-end`, `document-idle`
- `@version`
  - bundled script version for upgrade decisions
- `@description`
  - optional UI/help text
- `@namespace`
  - useful for distinguishing scripts with the same name
- `@author`
- `@grant`
  - should be parsed and preserved even if EinkBro does not support all grants yet

Optional future fields:

- `@homepage`
- `@requires`

To keep compatibility with Greasemonkey scripts, do not introduce app-specific meta fields such as:

- `@type`
- `@enabled`
- `@source`
- `@id`

If the app still needs those concepts, store them outside the Greasemonkey header.

Recommended app-managed fields:

- internal stable id
- enabled state
- script origin: bundled, user, imported, legacy
- install and update timestamps

## Parsing Rules

The parser should read only the header block at the top of the file.

Rules:

- the meta block must start with `// ==UserScript==`
- the meta block must end with `// ==/UserScript==`
- each meta line uses `// @key value`
- repeated `@match` lines are allowed
- unknown keys are ignored for forward compatibility
- if the block is missing, treat the whole file as script body and generate fallback metadata only for legacy compatibility
- preserve unknown standard keys when rewriting if possible

Suggested parse result:

- `ParsedUserScriptMeta`
- `scriptBody`

Then map `ParsedUserScriptMeta` into the app model.

Important mapping rules:

- `active` versus `passive` should not come from `@type`
- a script with `@match` or `@include` can be treated as auto-run
- a script without URL rules can be treated as manual-run
- enabled state should come from app-managed metadata, not the script header

## Storage Model

Target storage should move toward file-first, metadata-second.

Recommended model:

- each extension is stored as one `.user.js` file in `filesDir/user_extensions`
- repository scans that directory plus app-managed index data to build the extension list
- `SharedPreferences` keeps only small state flags

Keep only lightweight preferences such as:

- `user_extensions_initialized`
- optional migration version
- optional deleted bundled ids, if needed
- optional enabled-state index, if not stored elsewhere

Do not keep the primary extension metadata in `SharedPreferences` once the migration is complete.

## Bundled Extensions

Bundled extensions should also use the same `.user.js` format.

Recommended layout in `assets`:

- `app/src/main/assets/extensions/default_image_invert.user.js`
- `app/src/main/assets/extensions/default_error_log.user.js`
- `app/src/main/assets/extensions/default_reader_mode.user.js`

`createDefaultExtensions()` should be replaced by something like:

- `installBundledExtensionsIfNeeded()`

Behavior:

- on first run, copy bundled `.user.js` files from `assets` to `filesDir/user_extensions`
- do not regenerate bundled files from Kotlin strings
- do not recreate bundled files just because the repository is empty

That means `assets` should be changed in this design.

## User-Created Extensions

When the user adds a new extension:

- create a new `.user.js` file
- write a Greasemonkey-compatible header plus the script body into the same file
- create app-managed metadata separately for internal id and enabled state

When the user edits an extension:

- parse the current file
- update meta fields from the UI
- preserve the script body
- rewrite the file in normalized format

## Backup And Restore

Backup and restore must include the script files themselves, not only UI metadata.

Recommended backup unit:

- one script file per extension, preserving the exact `.user.js` content
- one metadata snapshot for app-managed state such as enabled flag and internal id

Possible export formats:

- zip containing:
  - all `.user.js` files
  - `extensions.json` for app-managed metadata
- JSON containing entries such as `{ fileName, content, enabled, internalId }`

Recommended behavior:

- bundled extensions are backed up the same way as user extensions if they are installed locally
- restore must restore the script files themselves
- restore must also restore app-managed state such as enabled flag
- import should parse the Greasemonkey header and then save both file content and app metadata
- duplicate detection should use something like `@name + @namespace`, or file identity, not app-specific `@id`

This keeps backup and restore symmetrical.

## Deletion And Reinstallation

Deleting a bundled extension should mean real deletion from the installed set.

Important rule:

- an empty installed directory must not automatically recreate bundled extensions after first initialization

Recommended approach:

- add `user_extensions_initialized = true`
- install bundled extensions only when this flag is false

Optional enhancement:

- support a manual "restore bundled extensions" action in settings

## Migration Plan

Suggested migration order:

1. Keep current runtime behavior working.
2. Add `.user.js` parser and serializer.
3. Change new save flows to write a single `.user.js` file.
4. Migrate existing `SharedPreferences` metadata plus script files into `.user.js` files.
5. Stop using `user_extensions_meta` as the primary source.
6. Replace `createDefaultExtensions()` with asset-based bundled install.
7. Remove the old Kotlin hardcoded default scripts.

Legacy compatibility:

- continue reading old `user_scripts` during migration
- mark migrated legacy scripts in app-managed metadata as `legacy`

## Suggested Repository API Changes

Possible repository methods:

- `listExtensions()`
- `getExtension(id: String)`
- `saveExtension(extension: UserExtension)`
- `deleteExtension(id: String)`
- `installBundledExtensionsIfNeeded()`
- `importExtension(rawScript: String)`
- `exportExtensions(): List<ExportedExtension>`

Possible helper methods:

- `parseUserScript(raw: String): ParsedUserScript`
- `serializeUserScript(extension: UserExtension): String`
- `readInstalledExtensionFiles()`

## UI Impact

The current edit UI can still work with small adjustments.

UI edits:

- continue editing name, enabled, match type, match values, runAt, and script body
- internally convert those fields into `.user.js` format on save
- `matchValue` in UI can still show one item per line temporarily, but file format should prefer repeated `@match`
- enabled state should stay outside the script header

Recommended medium-term UI improvement:

- show the parsed standard meta separately from the body
- optionally allow advanced users to edit the raw `.user.js` file directly

## Decision Summary

Recommended decisions:

- yes, change `assets`
- use Greasemonkey-compatible `.user.js` headers as the canonical script format
- make installed extension files the main source of truth
- treat bundled and user scripts with the same file format
- backup and restore both full script text and app-managed state
- initialize bundled scripts once, not whenever the list becomes empty

## Open Questions

These can be decided during implementation:

- whether exported files should preserve original formatting exactly or be normalized on save
- whether file name should be based on internal id, sanitized name, or remain independent
- whether duplicate scripts on import should overwrite, duplicate, or prompt
- whether the edit screen should offer a raw mode for advanced users
