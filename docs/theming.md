# Custom themes

Users can recolor any theme attribute or palette color, tint icons, set a background image, replace fonts and sounds, and import Aliucord theme JSON.
The code lives in `app/src/main/java/com/discord/utilities/theme/`; the screens are in `widgets/settings/themes/`.
Custom themes need Android 11 (API 30). Below that the Themes screen explains this, and the built-in themes are unchanged.

## How a theme reaches the screen

No framework hooks are involved; everything goes through the public `ResourcesLoader` API.

1. **Startup.** `ThemeRuntime.install` (in `App.onCreate`) adds one `ResourcesLoader` to the application's resources. Activities created afterwards inherit it.
2. **Layer.** Several themes can be enabled; `ThemeDefinition.layer` merges them in order, later ones winning key by key, as Aliucord does. Material You references (`system_accent1_500`, …) are resolved against the device here.
3. **Resolve.** `ThemeResolver` turns the theme into theme attributes, palette colors and drawable tints, all keyed by name. `ThemeLinks` then carries each overridden attribute to every attribute with the same color on the base theme (the tab bar, message box and tertiary background share one on Ash). Pure white, pure black and transparent are not linked.
4. **Build the table.** `ThemeTableBuilder` writes a small `resources.arsc` (`ArscWriter`) plus the files it points at:
   - Attributes become the items of `ThemeOverlay.Microcord.Runtime`, a placeholder style in `res/values/microcord_theme.xml`. The table replaces the style wholesale.
   - Colors become plain color entries that replace the APK's.
   - **Tinted drawables** point at a copy of the APK's compiled XML with `android:tint` added (`BinaryXml`). PNGs get a `<bitmap>` wrapper around a copy of the image, which is added as a new entry so the wrapper does not load itself. Selectors and layer lists tint the drawables they reference through new tinted entries.
   - **Fonts and sounds** point at copies of the theme's files.
   - A sentinel color is always written. Reading it back proves the device honoured the table.
5. **Load.** The table is checked (`ArscReader`) and served through `ResourcesProvider.loadFromTable`. Its files are served by an `AssetsProvider` under `res/microcord/`.
6. **Apply the overlay.** Every themed context layers the placeholder style over its base theme with `ThemeRuntime.applyOverlay`. That overlay beats every base layer, including literal hex values and Onyx's shared `@color/black`.

Places that apply the overlay today:
- `AppActivity.onApplyThemeResource`
- `AppDialog.onCreateDialog`
- `AppBottomSheet.onCreateDialog`

**A new dialog or sheet base class, or a `ContextThemeWrapper` over an `AppTheme_*` style, must call `ThemeRuntime.applyOverlay` too.** Otherwise its own theme's colors win.

**Refreshing existing screens.** Views that already exist keep their colors. A change bumps `ThemeRuntime.generation`; the current activity recreates itself, and others recreate when they next resume.

**Caching.** Building takes a few hundred resource lookups (around 150 ms, more with icon tints), so a build is cached in `codeCacheDir/themes/<key>/`. The key covers the theme files, their downloaded assets, the mapping and writer versions, the installed APK and, when used, the device's Material You colors. A startup with unchanged themes only maps the cached files: about 10 ms. Startup never touches the network; links are downloaded when a theme is turned on (`ThemeAssets`).

**Table configs.** A loader entry only wins against a base entry whose config is not better:
- Colors, styles, fonts and sounds are written for `sdkVersion` 23, 31, 34 and 35. aapt2 folds `-v23` into the default config because minSdk is 23, so the v23 chunk always wins.
- Drawables are written as `anydpi` for `sdkVersion` 24, 28, 31, 34 and 35, which outranks every density bucket.

**Boot guard.** A themed launch counts as unconfirmed until its first screen has stayed up for 3 seconds (`confirmHealthy`). After two unconfirmed launches in a row, the themes are turned off and the Themes screen says why. One is not enough: the app may just have been swiped away.

## Background image

`ThemeWallpaper` bakes the black scrim (`overlay_alpha`) and the blur into a JPEG when the table is built, as Aliucord's Themer does. Where it shows follows Themer's transparency modes (`microcord.transparency`):

| Mode | Shown behind |
|---|---|
| `none` | Nothing. |
| `chat` | The chat panel (`WidgetChatList`'s `panel_center`). |
| `chat_settings` | The chat and every `WidgetSettings*` page. |
| `full` | The whole activity. |

Without a mode, a theme whose main background is see-through gets `full`, otherwise `chat`. The image only shows where the theme's own colors are see-through. Animated images keep their first frame.

## File format

Themes are stored as `filesDir/themes/<id>.json`; the enabled ids are in `filesDir/themes/active`, one per line. Downloaded and picked files are in `filesDir/themes/assets`. They live in files rather than preferences so they survive logout and account switches.

The format is Aliucord's JSON plus two sections of our own:

| Section | Meaning |
|---|---|
| `manifest` | Name, author, version, license and updater, as in Aliucord. |
| `simple_colors`, `colors`, `drawable_tints` | Aliucord's sections. Values are signed or unsigned 32-bit ARGB ints, `#RGB`, `#ARGB`, `#RRGGBB` or `#AARRGGBB`, or a Material You color name (`system_accent1_500`). Exported as signed ints, which Aliucord requires. |
| `background` | `url`, `overlay_alpha` (0–255) and `blur_radius` (0–25), as in Aliucord. |
| `fonts`, `raws` | Font (`*` for all) and sound name → `https://` link or `file://` path, as in Aliucord. |
| `attrs` | Ours. Theme attribute name → color, exported as `#AARRGGBB`. Names prefixed `android:` refer to framework attributes. |
| `microcord` | Ours. `formatVersion`, `base` (`dark`, `darker`, `midnight` or `light`) and `transparency`. |
| Unknown keys | Kept verbatim. |

Aliucord's legacy flat format (`simple_accent_color`, `color_*`, `drawablecolor_*`, …) is converted on import. Themes can also be installed from a `.json` attachment through the message actions, and `manifest.updater` is checked when the Themes screen opens.

## Aliucord mapping

Aliucord's Themer swaps colors **by value**, and this app repainted its palette, so names alone would miss. `ThemeResolver` replays the swap per name:

- **`simple_colors`** keys expand as Themer's `ThemeLoader` does, tints included (`AliucordMapping.SIMPLE`). The few deliberate differences are documented there.
- **A palette color** also sets the theme attributes that held its value in Discord 126.21's dark theme but that this app repainted to other colors (`AliucordMapping.REPAINTED`). Every other attribute still uses the 126.21 color name, so the override reaches it directly. This step only runs on dark bases.
- **Selector names** that Aliucord recolors (`uikit_btn_bg_color_selector_*`, …) are redirected to their enabled or checked leaf color (`SELECTOR_LEAVES`). Recoloring the whole selector with one color would flatten its states.
- **Other state lists** are skipped with a warning.

Bump `AliucordMapping.VERSION` whenever a table changes. `ThemeCatalogContractTest` pins every mapped name to the real resources.

## Limits

- **Colors in drawables.** Colors hardcoded in drawables are only reachable through `drawable_tints`, which tints the whole drawable.
- **Status bar.** Themer's `statusbar` key sets nothing: the app draws edge to edge, so the status bar shows the surface behind it.
- **File paths.** `file://` paths in imported Aliucord themes usually point at shared storage the app cannot read; pick the file in the editor instead.
- **Name-based enumeration.** The editor enumerates tokens from the `R` classes, and the table looks names up with `getIdentifier`. Resource shrinking or obfuscation would break both.
- **Device coverage.** Behaviour on Android 11–13 has not been checked on a device. The runtime sentinel disables the feature where a table is ignored.
