
# Epic: NuGet-like Dependency Manager for IntelliJ (Gradle Sync + Installed/Updates/Browse)

## Summary
As a developer using IntelliJ with Gradle, I want a NuGet Package Manager–style UI that **syncs with my existing Gradle build files**, shows **what dependencies are installed**, highlights **available updates**, and allows me to **add/update/remove** dependencies with **preview + undo**, so I can manage dependencies faster and more safely than editing build files manually.

---

## Background / Problem Statement
IntelliJ users often manage Gradle dependencies by manually editing `build.gradle` / `build.gradle.kts`. This makes it harder to:
- quickly see **what is already installed** across modules,
- discover **available packages** and versions in one place,
- identify **updates** and apply them safely,
- manage changes with confidence (preview diffs, undo, minimal formatting disruption).

This epic adds a **dependency management tool window** similar in workflow to Microsoft’s NuGet Package Manager.

---

## Goals
- Provide a **Tool Window** to manage Gradle dependencies like NuGet:
    - **Installed** (what’s declared in Gradle files)
    - **Updates** (what can be upgraded)
    - **Browse/Search** (what’s available)
- Maintain accuracy by **syncing with Gradle files** (including multi-module).
- Enable safe edits with **Preview Diff** and **Undo** support.

---

## Non-Goals (for initial release)
- Full semantic rewriting of every edge-case Gradle script (custom functions, complex logic).
- Deep Gradle resolution (full “resolved dependency graph”) beyond declared dependencies (unless added later).
- Full support for every repository type; focus on Maven Central + existing supported repos first.

---

# User Stories

## Story 1 — Installed Dependencies View (Sync with Gradle)
**As a developer**, I want to see a list of **installed (declared) dependencies** pulled from my `build.gradle` / `build.gradle.kts` files, so I can quickly understand what my project uses.

### Acceptance Criteria
- **Given** a Gradle project with one or more modules  
  **When** I open the Dependency Manager tool window  
  **Then** I see an **Installed** tab listing dependencies declared in each module’s Gradle build file.
- The Installed list includes at minimum:
    - `group:artifact`
    - version (or version spec/constraint if used)
    - configuration (e.g., `implementation`, `api`, `testImplementation`)
    - owning module (for multi-module projects)
- **When** a Gradle build file changes on disk  
  **Then** the Installed view updates automatically (or via a visible Refresh action) within a reasonable time (e.g., ≤ 2 seconds after debounce).
- If parsing fails for a file, the UI shows a **non-blocking error** indicating which file couldn’t be parsed and continues showing results for other modules.

### Notes / Implementation Hints
- Support both Groovy DSL and Kotlin DSL for reading installed dependencies.
- Prefer stable parsing approaches; fall back gracefully when encountering complex scripts.

---

## Story 2 — Browse/Search Packages and Add to Project
**As a developer**, I want to search for packages and add them to my Gradle project from a UI, so I don’t have to manually copy/paste coordinates.

### Acceptance Criteria
- **Given** I am on the **Browse/Search** tab  
  **When** I search for a package (by group/artifact keywords)  
  **Then** I see results with available versions and basic metadata when available.
- **When** I select a package and click **Add**  
  **Then** I can choose:
    - target module (or default module if single-module)
    - configuration (e.g., `implementation`, `api`, `testImplementation`)
    - version to add (default to latest stable)
- **When** I confirm Add  
  **Then** the dependency is inserted into the correct `dependencies {}` block (or equivalent), and:
    - the change is applied through IntelliJ write actions
    - the user can **Undo** the change
    - the Installed tab shows the new dependency after refresh/sync
- If the tool cannot find an appropriate insertion point (e.g., highly custom Gradle scripts), it:
    - shows a friendly message explaining why
    - offers a fallback (e.g., copy snippet to clipboard + open file at appropriate location)

---

## Story 3 — Updates Tab (Detect Updates for Installed Dependencies)
**As a developer**, I want to see which installed dependencies have newer versions available, so I can keep my project up to date with minimal effort.

### Acceptance Criteria
- **Given** Installed dependencies are detected  
  **When** I open the **Updates** tab  
  **Then** I see a list of dependencies where a newer version is available.
- Each update entry shows:
    - current version (or version spec)
    - latest stable version
    - optionally latest pre-release version (if setting enabled)
- The Updates view supports:
    - filtering/searching (by group/artifact)
    - sorting (e.g., by module, name, or update magnitude)
- If version lookup fails (offline/network errors), the UI:
    - shows a clear error state
    - allows retry
    - does not block other features (Installed list still works)

---

## Story 4 — Update a Dependency Version (Preview + Undo)
**As a developer**, I want to update a dependency version with a preview of changes, so I can confidently upgrade without breaking my build.

### Acceptance Criteria
- **Given** a dependency has an update available  
  **When** I click **Update**  
  **Then** I can select the target version (default to latest stable).
- **When** I proceed  
  **Then** I see a **Preview Diff** of the affected file(s) before applying changes.
- **When** I apply the update  
  **Then**:
    - only the relevant version text changes (minimal diff)
    - formatting is preserved as much as possible
    - the action is undoable via IntelliJ Undo
- **When** the dependency is managed via a version catalog (if detected)  
  **Then** the update prefers modifying the catalog entry rather than inline strings (where feasible).

---

## Story 5 — Remove an Installed Dependency (Preview + Undo)
**As a developer**, I want to remove an installed dependency from the UI, so I can clean up unused libraries without manually editing Gradle files.

### Acceptance Criteria
- **Given** a dependency appears in Installed  
  **When** I click **Remove**  
  **Then** the tool shows a **Preview Diff**.
- **When** I confirm removal  
  **Then** the dependency declaration is removed from the correct module build file, and:
    - the change is undoable
    - the Installed list updates accordingly
- The tool prevents or warns on ambiguous removal if:
    - the same dependency appears in multiple configurations or multiple lines
    - the dependency is declared via variables/indirection that cannot be safely rewritten
    - In these cases it offers to navigate to the declaration and/or copy guidance.

---

## Story 6 — Multi-Module Support (Module-Aware Management)
**As a developer**, I want dependency management to work across multi-module Gradle projects, so I can manage dependencies per-module like I do in a solution.

### Acceptance Criteria
- **Given** a multi-module Gradle project  
  **When** I view Installed dependencies  
  **Then** dependencies are grouped by module and show module ownership.
- **When** I add a dependency  
  **Then** I can choose the target module.
- **When** I update/remove a dependency  
  **Then** the tool applies the change only to the selected module’s build file.

---

## Story 7 — File Watching and Refresh Controls
**As a developer**, I want the tool window to stay in sync as Gradle files change, so the UI is always accurate.

### Acceptance Criteria
- The tool watches relevant files:
    - `build.gradle`, `build.gradle.kts`
    - optionally `libs.versions.toml` (if catalogs supported)
- Changes trigger a debounced refresh.
- There is a manual **Refresh** action available in the tool window toolbar.
- Refresh does not freeze the UI; scanning happens in background with progress indication when needed.

---

## Story 8 — Settings / Preferences
**As a developer**, I want simple settings to control repository usage and update behavior, so the tool fits my workflow.

### Acceptance Criteria
- Provide settings for:
    - include pre-release versions (on/off)
    - auto-refresh on file change (on/off)
    - repository selection (if supported by underlying search features)
- Settings are persisted between IDE restarts.
- Default settings are sensible (pre-release off, auto-refresh on).

---

# Definition of Done (Epic)
- Tool Window provides **Installed / Updates / Browse** tabs.
- Installed dependencies are correctly detected from Gradle files for:
    - single-module and multi-module projects
    - Groovy DSL and Kotlin DSL (at least for common patterns)
- Add/Update/Remove operations:
    - apply minimal file changes
    - show preview diff
    - support Undo
- UX is responsive and non-blocking (background tasks).
- README updated with features + limitations + how to run/build.
- Automated tests exist for parsing and transformations (golden-file tests acceptable).

---

# Out of Scope / Future Enhancements
- Show resolved dependency graph (transitives, conflicts) by importing Gradle model.
- “Consolidate versions” suggestions across modules.
- Automatic migration to version catalogs.
- Security alerts/vulnerability scanning integrations.

---
