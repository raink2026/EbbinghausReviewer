# App Theme System Design

## Goal

Replace the single background-color preference with a complete per-user theme system, provide WeChat, Twitter, Minimal, and Custom themes, and replace the full-width bottom navigation with a compact centered dock whose colors come entirely from the active theme.

## Theme model

Each legacy `User` stores a preset id and, for Custom, a dark-base flag, a font-family id, plus four optional ARGB roles:

- background: page canvas;
- surface: cards, top bars, dialogs, and the bottom dock;
- primary: selected navigation, progress, buttons, and stage rail;
- text: page and surface foreground.

Derived roles such as containers, dividers, muted text, and disabled colors are computed centrally. The UI never reads raw persisted colors directly.

Custom themes also choose one bundled Android font family: System Default, Sans Serif, Serif, or Monospace. Font selection changes the Material typography family while preserving the existing user font-scale setting. It requires no downloaded font or third-party dependency and is retained while another preset is active.

Preset behavior is fixed:

- WeChat always uses a light white and pale-green palette with `#07C160` primary.
- Twitter always uses a black, graphite, and white palette.
- Minimal follows the system light/dark setting and uses neutral grayscale palettes.
- Custom uses the user's light/dark base choice and any supplied role colors.

Unknown stored preset ids fall back to WeChat. Invalid or absent custom roles fall back to accessible values for the selected base.

## Persistence and compatibility

Room moves from version 6 to 7. The `users` table gains `themePreset`, `customThemeDark`, `customThemeFont`, `customThemeBackground`, `customThemeSurface`, `customThemePrimary`, and `customThemeText`. Existing rows default to WeChat. Rows with a non-null legacy `themeColor` become Custom and retain that value as their background. The legacy column remains readable for downgrade/data compatibility but is no longer written by the new UI.

This appearance configuration remains local to `User`; sync profiles, repository formats, review data, and synchronization protocols do not change.

## UI

`我的 → 个性化` replaces the background swatches with theme preview cards. Each card previews canvas, surface, accent, and text. Selecting WeChat, Twitter, or Minimal applies immediately. Selecting Custom opens an editor with:

- light/dark base choice;
- four role rows with live swatches;
- a font-family row with System Default, Sans Serif, Serif, and Monospace choices;
- a live miniature screen and bottom-dock preview;
- reset-to-safe-default and save actions.

The bottom dock is centered, capped at `320dp`, inset from screen edges and system navigation, and gives every destination at least a `48dp` target. Its surface, selected indicator, selected/unselected icons, and labels all use the active `MaterialTheme.colorScheme`.

## Verification

Pure JVM tests cover preset parsing, forced/follow-system modes, exact preset colors, custom role/font application, and legacy background conversion. Room migration coverage verifies the version 6 to 7 columns and backfill. Compose checks cover theme preview selection semantics and the bottom dock's root-route behavior. Final verification builds the debug APK and reviews the UI diff for contrast, touch size, typography fallback, and business-contract preservation.
