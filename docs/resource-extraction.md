# Resource extraction

This document records the pass that moved hardcoded UI values out of Kotlin
and into `app/src/main/res/`. The goal was not to resource-ise everything: only
values that describe a **user-facing default, label, size or colour** moved.
Engine tuning (cache sizes, render timings, native rewrite flags) stays in
Kotlin, because it describes how PDFr talks to MuPDF/qpdf rather than what the
UI shows.

Every entry below lists the old home, the new resource and the code that reads
it.

## Booleans — `res/values/bools.xml`

The true/false defaults used to be `const val`s in `ReaderConfig` and
constructor defaults in `NormalizeOptions`. They are now declarative.

| Resource | Old value | Read by |
| --- | --- | --- |
| `reader_single_page_default` | `ReaderConfig.DEFAULT_SINGLE_PAGE = true` | `ReaderDefaults.singlePage` |
| `reader_line_wrap_default` | `ReaderConfig.DEFAULT_LINE_WRAP = true` | `ReaderDefaults.lineWrap` |
| `reader_align_right_default` | `ReaderConfig.DEFAULT_ALIGN_RIGHT = false` | `ReaderDefaults.alignRight` |
| `reader_line_numbers_default` | `ReaderConfig.DEFAULT_LINE_NUMBERS = true` | `ReaderDefaults.lineNumbers` |
| `reader_monospace_default` | `ReaderConfig.DEFAULT_MONOSPACE = true` | `ReaderDefaults.monospace` |
| `normalize_compress_streams_default` | `NormalizeOptions.compressStreams = true` | `NormalizeOptions.fromResources` |
| `normalize_decode_all_streams_default` | `decodeAllStreams = false` | `NormalizeOptions.fromResources` |
| `normalize_recompress_flate_default` | `recompressFlate = false` | `NormalizeOptions.fromResources` |
| `normalize_compression_level_9_default` | `compressionLevel9 = false` | `NormalizeOptions.fromResources` |
| `normalize_optimize_images_default` | `optimizeImages = false` | `NormalizeOptions.fromResources` |
| `normalize_newline_before_endstream_default` | `newlineBeforeEndstream = true` | `NormalizeOptions.fromResources` |
| `normalize_remove_unreferenced_resources_default` | `removeUnreferencedResources = true` | `NormalizeOptions.fromResources` |
| `normalize_externalize_inline_images_default` | `externalizeInlineImages = false` | `NormalizeOptions.fromResources` |
| `normalize_linearize_default` | `linearize = false` | `NormalizeOptions.fromResources` |
| `normalize_pdfa_default` | `pdfA = false` | `NormalizeOptions.fromResources` |

`ReaderDefaults` (`data/ReaderDefaults.kt`) is constructed once from
`Application.resources` in `ReaderViewModel`. The view model then passes the
values explicitly into `ReaderState.Ready`, which no longer has constructor
defaults for them. That keeps a single source of truth for "what the reader
starts with" and makes the state construction site readable.

`NormalizeOptions()` still has Kotlin defaults so JVM tests and non-Android
callers work without a `Resources` instance. The dialog uses
`NormalizeOptions.fromResources(...)`; the data-class defaults are the
fallback.

## Integers — `res/values/integers.xml`

| Resource | Old value | Read by |
| --- | --- | --- |
| `text_size_min_pt` | `TextSettings.MIN_SIZE_PT = 5` | `TextSettings.fromResources`, `TextSizeDialog` |
| `text_size_max_pt` | `TextSettings.MAX_SIZE_PT = 40` | `TextSettings.fromResources`, `TextSizeDialog` |
| `text_size_default_pt` | `TextSettings.DEFAULT_SIZE_PT = 12` | `TextSettings.fromResources` |
| `normalize_jpeg_quality_default` | `NormalizeOptions.DEFAULT_JPEG_QUALITY = 70` | `NormalizeOptions.fromResources` |

`TextSettings` is now a small class holding `minSizePt`, `maxSizePt` and
`defaultSizePt`; it validates that the default is inside the range and keeps
`clampSizePt` / `sizePtOrDefault` as instance methods. The clamping logic is
still plain Kotlin, so `TextSettingsTest` runs on the JVM. `TextSizeDialog`
reads the same two integers directly with `integerResource`, so the slider
range and the menu's "5-40 pt" promise cannot drift apart.

## Colours — `res/values/colors.xml`

| Resource | Old value | Read by |
| --- | --- | --- |
| `selection_fill` | `ReaderConfig.SELECTION_FILL = Color(0x992196F3)` | `SelectionHighlight` in `ReaderApp.kt` |
| `selection_outline` | `ReaderConfig.SELECTION_OUTLINE = Color(0xE60D47A1)` | `SelectionHighlight` in `ReaderApp.kt` |

These stay fixed blue rather than theme colours, because reader pages are
always white; the comment in `colors.xml` records that reasoning.

## Dimensions — `res/values/dimens.xml`

| Resource | Value | Read by |
| --- | --- | --- |
| `language_menu_width` | `280dp` | `SettingsDialog.LanguageCombo` |

The fixed width is what makes the three caption columns line up across every
dropdown entry: the popup is wider than the trigger, so the weighted columns
resolve against the same width for all rows.

Existing dimensions (`info_dialog_min_height`, `info_label_width`,
`normalize_dialog_max_height`, `empty_state_icon_size`,
`selection_outline_width`, `plain_text_padding`) were already resources and
were left in place.

## Strings — `res/values/strings.xml`

| Resource | Old value | Read by |
| --- | --- | --- |
| `metadata_icc_profile_size` | `"ICC profile (${it.sizeBytes} bytes)"` | `ImageMetadata.read(..., iccProfileLabel)` |
| `language_system` | `"System default"` (new label) | `AppSettings.languages` / `SettingsDialog` |
| `settings_page_direction` | `"Page bar direction"` (new label) | `SettingsDialog` |
| `page_direction_language` | `"By language"` (new label) | `SettingsDialog` |
| `page_direction_ltr` / `page_direction_rtl` | `"Left to right"` / `"Right to left"` | `SettingsDialog` |
| `page_go` | `"Go to page"` (new label) | `PageToolbar.PageJumpButton` |

The ICC fallback is the only string that was assembled in the data layer. It is
now passed as an `iccProfileLabel: (Int) -> String` lambda from
`ReaderViewModel`, which resolves `R.string.metadata_icc_profile_size` through
the application context. `ImageMetadata` and `MuPdfDocument` keep a plain
English default for JVM/instrumented callers that do not supply one, so the
data layer still has no `Context` dependency.

## Drawables

| File | Purpose |
| --- | --- |
| `res/drawable/ic_check.xml` | Check mark on the selected language/direction row, and the page jump button. |
| `res/drawable/ic_arrow_drop_down.xml` | Closed-combo affordance on the language and page-direction triggers. |

Both are simple vector paths, matching the existing `ic_*` drawables.

## Translations

Seventeen locale folders ship with the app: thirteen full translations and
three regional overlays.

| Folder | Language | Notes |
| --- | --- | --- |
| `values-iw` | Hebrew | Kept as `iw` because that is the qualifier Android resolves; the generated tag is `he`. |
| `values-ar` | Arabic | Plurals use `zero/one/two/few/many/other`. |
| `values-fr` | French | Plurals use `one/other`. |
| `values-de` | German | Plurals use `one/other`. |
| `values-es` | Spanish | Full translation. |
| `values-pt` | Portuguese | Full translation. The Brazilian overlay is `values-pt-rBR`. |
| `values-ru` | Russian | Plurals use `one/few/many/other`. |
| `values-ja` | Japanese | Full translation. |
| `values-ko` | Korean | Full translation. |
| `values-zh-rCN` | Simplified Chinese | Full translation. |
| `values-zh-rTW` | Traditional Chinese | Full translation. |
| `values-hi` | Hindi | Full translation. |
| `values-en-rGB`, `values-fr-rCA`, `values-b+es+419` | Regional overlays | Only the strings that differ from the base translation. |

The language picker is not maintained by hand. The `buildSrc` task
`GenerateLanguageResources` scans every `values-*/strings.xml`, converts the
folder qualifier to a canonical BCP-47 tag (so `values-iw` becomes `he` and
`values-b+es+419` becomes `es-419`), and writes:

- `values/generated_languages.xml`, containing the
  `supported_language_tags` string-array;
- `xml/locales_config.xml`, wired to the manifest through
  `android:localeConfig`.

`AppSettings.languages` reads that array and uses the platform locale data for
the English and native names. Adding a translation folder is therefore enough
to offer the language, and there is no separate list to edit. Rebuild with
`.\gradlew.bat :app:generateLanguageResources` to refresh the files.

`TranslationResourcesTest` enforces the same contract in the JVM test suite:
every full folder must define every base string except the non-translatable
`date_hint`, keep every format placeholder, and avoid unknown keys. Overlays are
allowed to contain only their overrides. This replaces the old ad-hoc
PowerShell completeness snippet; run it with:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.karako.pdfr.data.TranslationResourcesTest"
```

## What deliberately stays in Kotlin

- `ReaderConfig` layout/timing constants (viewport minimums, settle delays,
  zoom animation, page-badge fade, saved-state keys). These are floats,
  milliseconds and opaque keys shared with the view model; they are not
  inflated XML.
- `MuPdfConfig` cache sizes, reflow limits and rewrite flags. These describe
  native engine behaviour, not a user-facing choice.
- Saved-state keys. They are an on-device format; moving them to resources
  would make renames look harmless when they silently lose state.
- The English fallbacks in `ImageMetadata` / `MuPdfDocument` for callers that
  do not pass a localised ICC label.

## Verification

- `.\gradlew.bat :app:processDebugResources` — every resource name resolves in
  all locales, and the generated language array plus `locales_config.xml` are
  linked.
- `.\gradlew.bat :app:compileDebugKotlin` — the Kotlin readers match the new
  resource types.
- `.\gradlew.bat :app:testDebugUnitTest` — `TextSettingsTest`,
  `NormalizeOptionsTest`, `AppSettingsTest`, `ReaderConfigTest` and
  `TranslationResourcesTest` cover the moved values, the translated resource
  contract and the fallback behaviour.
