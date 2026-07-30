# App Theme System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a complete per-user theme system and a compact theme-aware bottom dock.

**Architecture:** Persist a typed preset and four optional custom color roles on `User`, resolve them into one centralized Material 3 `ColorScheme`, and expose stateless Compose theme-selection components. Existing business state, routes, sync schemas, and review behavior remain unchanged.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room 6→7 migration, JUnit, Compose UI tests.

## Global Constraints

- WeChat is fixed light, Twitter is fixed dark, Minimal follows the system, and Custom chooses its own base.
- Do not change sync profiles, repository formats, review algorithms, routes, or ViewModel business callbacks.
- Preserve existing users' legacy `themeColor` by converting it to a Custom background.
- Bottom-dock destinations remain at least `48dp` and appear only on root routes.
- Custom themes offer System Default, Sans Serif, Serif, and Monospace families while preserving `fontScale`.
- Do not add third-party UI, font, or color-picker dependencies.

---

### Task 1: Theme domain and resolver

**Files:**
- Create: `app/src/main/java/com/ebbinghaus/review/ui/theme/AppThemeConfig.kt`
- Modify: `app/src/main/java/com/ebbinghaus/review/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/ebbinghaus/review/ui/theme/Theme.kt`
- Test: `app/src/test/java/com/ebbinghaus/review/ui/theme/AppThemeConfigTest.kt`

**Interfaces:**
- Produces: `AppThemePreset`, `AppThemeFont`, `AppThemeConfig`, `ResolvedAppTheme`, `resolveAppTheme(systemDark, config)`.

- [ ] Write failing tests for preset mode rules, exact palettes, custom roles/fonts, unknown ids, and legacy background fallback.
- [ ] Run the focused JVM test and confirm failures are caused by missing theme-domain symbols.
- [ ] Implement the preset/config types and centralized resolver.
- [ ] Route `EbbinghausReviewTheme` through `ResolvedAppTheme.colorScheme` and `ResolvedAppTheme.dark`.
- [ ] Run the focused JVM test and confirm it passes.

### Task 2: Persist theme configuration

**Files:**
- Modify: `app/src/main/java/com/ebbinghaus/review/data/User.kt`
- Modify: `app/src/main/java/com/ebbinghaus/review/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/ebbinghaus/review/ui/ProfileViewModel.kt`
- Modify: `app/src/main/java/com/ebbinghaus/review/MainActivity.kt`
- Test: `app/src/androidTest/java/com/ebbinghaus/review/data/AppDatabaseMigrationTest.kt`

**Interfaces:**
- Produces: `ProfileViewModel.updateTheme(config)` and `User.toAppThemeConfig()`.

- [ ] Add a failing migration test that opens a v6 database with default and legacy-colored users and verifies the custom font default.
- [ ] Add v7 entity fields and `MIGRATION_6_7`, including legacy-color backfill.
- [ ] Register the migration and update the exported Room schema.
- [ ] Replace `updateThemeColor` with one atomic theme update and map the current user into the app theme.
- [ ] Run migration and focused resolver tests.

### Task 3: Theme picker and custom editor

**Files:**
- Create: `app/src/main/java/com/ebbinghaus/review/ui/components/ThemePicker.kt`
- Modify: `app/src/main/java/com/ebbinghaus/review/ui/ProfileScreen.kt`
- Test: `app/src/androidTest/java/com/ebbinghaus/review/ui/components/ThemePickerTest.kt`

**Interfaces:**
- Consumes: `AppThemeConfig`, `AppThemePreset`, `(AppThemeConfig) -> Unit`.
- Produces: stateless `ThemePicker` plus state-local `CustomThemeDialog`.

- [ ] Write failing Compose tests for four preview choices, selected semantics, custom mode, role/font editing, reset, cancel, and save.
- [ ] Replace the old background color section with preview cards.
- [ ] Implement the custom editor using the existing RGB input dialog pattern, four bundled font choices, and a live miniature preview.
- [ ] Ensure labels wrap, swatches have descriptions, and all controls meet `48dp`.
- [ ] Compile Android tests and run focused tests when an emulator is available.

### Task 4: Compact theme-aware bottom dock

**Files:**
- Create: `app/src/main/java/com/ebbinghaus/review/ui/components/AppBottomDock.kt`
- Modify: `app/src/main/java/com/ebbinghaus/review/ui/MainScreen.kt`
- Test: `app/src/androidTest/java/com/ebbinghaus/review/ui/components/AppBottomDockTest.kt`

**Interfaces:**
- Produces: stateless `AppBottomDock(items, selectedRoute, showLabels, onSelect)`.

- [ ] Write failing Compose tests for selection colors/semantics, hidden labels, `48dp` targets, and the `320dp` width cap.
- [ ] Implement a centered dock with theme-derived surface, primary container, icons, and labels.
- [ ] Replace the root `NavigationBar` while preserving icon customization, route selection, saved state, and secondary-route hiding.
- [ ] Compile and run focused tests.

### Task 5: Review and completion audit

**Files:**
- Review all files listed above plus `app/src/main/java/com/ebbinghaus/review/ui/components/WechatComponents.kt`.

- [ ] Run focused JVM and Android compile checks, `assembleDebug`, and `git diff --check` for scoped files.
- [ ] Independently review migration safety, contrast, custom-theme derivation, bottom-dock sizing, and route/business preservation.
- [ ] Fix every Critical or Important finding and re-run the proving command.
- [ ] Audit each explicit objective against source and build evidence before marking the goal complete.
