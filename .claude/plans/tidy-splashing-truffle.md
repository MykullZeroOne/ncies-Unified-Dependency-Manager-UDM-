# UDM Plugin UI Refactor Plan

## Overview

Refactor the Unified Dependency Manager (UDM) plugin from a legacy tab-based UI to a JetBrains Rider-inspired unified view with semantic grouping, rich context menus, and modern IntelliJ Platform patterns.

## Architecture Validation

**Current stack is modern and correct:**
- Swing + Kotlin UI DSL 3.x (Compose not recommended for plugins)
- `@Service` annotations with proper scoping
- `SimpleToolWindowPanel`, `DialogWrapper` patterns
- Threading via `executeOnPooledThread` + `invokeLater` (valid; coroutines optional)
- No deprecated APIs detected

---

## Implementation Phases

### Phase 1: Status Indicators & Badge System

**New file:** `ui/StatusBadge.kt`
- Badge/pill component with rounded corners
- Badge types with color schemes:
    - `UPDATE` - green (#4CAF50) - update available
    - `OUTDATED` - orange (#FF9800) - newer version exists
    - `VULNERABLE` - red (#F44336) - security advisory
    - `TRANSITIVE` - gray (#9E9E9E) - implicit dependency
    - `PRERELEASE` - purple (#9C27B0) - alpha/beta/RC version
    - `DEPRECATED` - dark gray (#616161) - package deprecated

**New file:** `ui/PackageIcons.kt`
- Icon provider for package states:
    - Checkmark (installed)
    - Up arrow (update available)
    - Shield with X (vulnerable)
    - Lock (transitive/locked)
    - Plus (available to install)
    - Warning triangle (deprecated)

**Modify:** `PackageListPanel.kt`
- Add status icons to `PackageItemPanel` renderer
- Color-coded version display (orange=outdated, red=vulnerable)
- Parenthesized versions for transitive deps `(1.0.0)`
- Tooltip with full status details on hover

---

### Phase 2: Toolbar Actions

**New file:** `ui/UdmToolbarActions.kt`

| Action Class | Type | Description | Keyboard Shortcut |
|--------------|------|-------------|-------------------|
| `SearchPackagesAction` | Custom | Embedded `SearchTextField` with debounce (150ms) | `Ctrl+F` |
| `ModuleSelectorAction` | `ComboBoxAction` | Filter by module (All Modules, specific modules) | - |
| `FeedSelectorAction` | `ComboBoxAction` | Filter by repository feed | - |
| `PrereleaseToggleAction` | `ToggleAction` | Include/exclude prerelease versions | `Ctrl+P` |
| `RefreshAction` | `DumbAwareAction` | Refresh all dependency data | `F5` |
| `UpgradeAllAction` | `DumbAwareAction` | Open bulk upgrade dialog | `Ctrl+Shift+U` |
| `ConsolidateAction` | `DumbAwareAction` | Consolidate package versions across modules | - |
| `CollapseAllSectionsAction` | `DumbAwareAction` | Collapse all list sections | - |
| `ExpandAllSectionsAction` | `DumbAwareAction` | Expand all list sections | - |

**Modify:** `UnifiedDependencyPanel.kt`
- Replace manual filter bar with `ActionToolbar`
- Wire action callbacks to existing service methods
- Add keyboard shortcut registration

---

### Phase 3: Unified List Component

**Modify:** `PackageListPanel.kt`
- Remove `FilterMode` enum and tab buttons
- Implement single grouped list with collapsible sections

**Data model:**
```kotlin
sealed class ListItem {
    data class SectionHeader(
        val title: String,
        val count: Int,
        val sectionType: SectionType,
        val isCollapsed: Boolean = false
    ) : ListItem()

    data class PackageItem(
        val pkg: UnifiedPackage,
        val status: PackageStatus
    ) : ListItem()
}

enum class SectionType { INSTALLED, IMPLICIT, AVAILABLE }

data class PackageStatus(
    val hasUpdate: Boolean,
    val isVulnerable: Boolean,
    val isTransitive: Boolean,
    val isDeprecated: Boolean,
    val isPrerelease: Boolean,
    val updateVersion: String?,
    val vulnerabilityInfo: VulnerabilityInfo?
)
```

**Section headers:**
- Click to collapse/expand
- Show count badge: "Installed Packages (21)"
- Visual indicator (chevron) for expand/collapse state

**List interactions:**
- Hover: Background highlight with 150ms transition
- Selection: Indigo border-left (3px) + background
- Double-click: Open package in browser
- Transitive items: Cursor `not-allowed`, tooltip explains locked state

---

### Phase 4: Context Menu Actions

**New file:** `ui/PackageContextMenuActions.kt`

| Action | Condition | Description |
|--------|-----------|-------------|
| **Version Actions** | | |
| `UpgradePackageAction` | Installed + hasUpdate | Upgrade {pkg} from {v1} → {v2} |
| `DowngradePackageAction` | Installed | Select older version to install |
| `InstallPackageAction` | Not installed | Install to selected modules |
| `RemoveFromModuleAction` | Installed | Remove from {module} |
| `RemoveFromAllModulesAction` | Installed in multiple | Remove from all modules |
| **Navigation Actions** | | |
| `ViewOnRepositoryAction` | Always | Open package page in browser |
| `OpenInstallationFolderAction` | Installed (local) | Open in file explorer |
| `SearchPackageOnWebAction` | Always | Search "{pkg}" on Google |
| **Copy Actions** | | |
| `CopyMavenCoordinateAction` | Always | Copy `<dependency>...</dependency>` |
| `CopyGradleGroovyCoordinateAction` | Always | Copy `implementation 'group:artifact:version'` |
| `CopyGradleKotlinCoordinateAction` | Always | Copy `implementation("group:artifact:version")` |
| `CopyNpmCoordinateAction` | NPM packages | Copy `"package": "^version"` |
| `CopyFullMetadataAction` | Always | Copy JSON with all package info |
| **Dependency Analysis** | | |
| `ShowDependencyTreeAction` | Always | Show what this package depends on |
| `ShowReverseDependentsAction` | Installed | Show what depends on this package |
| `WhyInstalledAction` | Transitive | Explain why this transitive dep exists |
| **Multi-select Actions** | | |
| `RemoveSelectedAction` | Multiple selected | Remove all selected packages |
| `UpgradeSelectedAction` | Multiple with updates | Upgrade all selected |

**Modify:** `PackageListPanel.kt`
- Add `MouseListener` for right-click popup trigger
- Support multi-selection with `Ctrl+Click` / `Shift+Click`
- Wire to `ActionManager.createActionPopupMenu()`

---

### Phase 5: Details Pane Refactor

**Modify:** `PackageDetailsPanel.kt`
- Convert to Kotlin UI DSL `panel { }` builder
- Expandable sections using `collapsibleGroup()`

**Layout structure:**
```
┌─────────────────────────────────────────────────────────┐
│ [Icon] Package Name                    ✓ Installed      │  ← Sticky header
│ Version: [14.0.0 ▼]  Source: [nuget.org ▼]  [Remove]   │
├─────────────────────────────────────────────────────────┤
│ ▼ Description                                           │  ← Expanded by default
│   A convention-based object-object mapper for .NET...   │
│   [Read more]                                           │
├─────────────────────────────────────────────────────────┤
│ ▶ Frameworks & Dependencies (12)                        │  ← Collapsed by default
├─────────────────────────────────────────────────────────┤
│ ▶ Modules Using This Package                            │  ← Collapsed by default
│   [x] module-api                                        │
│   [x] module-service                                    │
│   [ ] module-worker                                     │
├─────────────────────────────────────────────────────────┤
│ ▶ Version History                                       │  ← NEW: Collapsed
│   14.0.0 (latest) - 2024-01-15                         │
│   13.0.3 - 2023-08-20                                  │
│   13.0.2 - 2023-06-10                                  │
├─────────────────────────────────────────────────────────┤
│ ▶ License & Links                                       │  ← NEW: Collapsed
│   License: MIT                                          │
│   [Project URL] [Source] [Report Issue]                │
├─────────────────────────────────────────────────────────┤
│ Actions                                                 │
│ [Install] [Update] [Uninstall] [Pin Version]           │
└─────────────────────────────────────────────────────────┘
```

**Details pane actions:**
| Button | Condition | Action |
|--------|-----------|--------|
| Install | Not installed | Show module selection dialog, add to build files |
| Update | Has update | Show version picker, apply update |
| Uninstall | Installed | Remove from selected modules |
| Pin Version | Installed | Lock to current version (ignore updates) |
| Copy Coordinate | Always | Dropdown with format options |

---

### Phase 6: Dialogs & Workflows

**New file:** `ui/BulkUpgradeDialog.kt`
- Checkbox tree of packages with available updates
- Group by: Module / Package / Update type (major/minor/patch)
- Select All / Deselect All / Select Patch Only / Select Minor Only
- Preview changes before applying
- Progress indicator during update

**New file:** `ui/InstallPackageDialog.kt`
- Package search field with live results
- Version selector dropdown
- Module checkboxes for multi-module install
- Configuration scope selector (implementation/api/testImplementation/etc)
- Preview of build file changes

**New file:** `ui/ConsolidateVersionsDialog.kt`
- Show packages with inconsistent versions across modules
- Select target version for consolidation
- Preview all affected build files

**New file:** `ui/DependencyTreeDialog.kt`
- Tree view of transitive dependencies
- Expand/collapse nodes
- Search within tree
- Highlight conflicts (version mismatches)
- Export to text/image

---

### Phase 7: Enhanced Features (Feature-Rich Additions)

#### 7.1 Vulnerability Scanning Integration
**New file:** `service/VulnerabilityService.kt`
- Integration with OSV (Open Source Vulnerabilities) database
- Background scan on project open
- Notification for new vulnerabilities
- Details: CVE ID, severity, affected versions, fix version

**UI additions:**
- Red shield icon on vulnerable packages
- "Security" section in details pane with advisory links
- "Fix Vulnerabilities" action in toolbar

#### 7.2 Dependency Graph Visualization
**New file:** `ui/DependencyGraphPanel.kt`
- Interactive graph view of dependencies
- Zoom, pan, search within graph
- Highlight paths to specific package
- Color-coded by: module, update status, vulnerability
- Export as PNG/SVG

#### 7.3 Search History & Favorites
**New file:** `ui/SearchHistoryPanel.kt`
- Recent searches (last 20)
- Favorite/starred packages for quick access
- Pinned packages always shown at top

**Persistence:** Store in `PackageFinderSetting`

#### 7.4 Keyboard Navigation
| Shortcut | Action |
|----------|--------|
| `↑/↓` | Navigate list |
| `Enter` | Show details / toggle section |
| `Delete` | Remove selected package |
| `Ctrl+C` | Copy coordinate |
| `Ctrl+Shift+C` | Copy with format picker |
| `Ctrl+D` | Show dependency tree |
| `Ctrl+I` | Install selected |
| `Ctrl+U` | Update selected |
| `Escape` | Clear search / close dialog |

#### 7.5 Notifications & Background Updates
**New file:** `service/UpdateNotificationService.kt`
- Check for updates on IDE startup (configurable)
- Balloon notification for available updates
- "Check for Updates" in Tools menu
- Settings: frequency (startup/daily/weekly/never)

#### 7.6 License Compliance
**New file:** `ui/LicenseCompliancePanel.kt`
- Scan all dependencies for licenses
- Flag incompatible licenses (GPL in proprietary project)
- Export license report (CSV, HTML)
- License allowlist/blocklist in settings

#### 7.7 Package Comparison
**New file:** `ui/PackageComparisonDialog.kt`
- Compare two versions of same package
- Show changelog/release notes diff
- Highlight breaking changes (if available from API)

#### 7.8 Quick Actions Popup
- `Alt+Enter` on any package in code editor
- Actions: Update, View in UDM, Show usages, Copy coordinate

---

## Files Summary

### Files to Modify

| File | Changes |
|------|---------|
| `ui/PackageListPanel.kt` | Unified grouped list, status indicators, context menu, multi-select |
| `ui/PackageDetailsPanel.kt` | Kotlin UI DSL, collapsible sections, new sections |
| `ui/UnifiedDependencyPanel.kt` | ActionToolbar, keyboard shortcuts, service wiring |
| `ui/MainToolWindowPanel.kt` | Toolbar positioning, new tabs |
| `model/UnifiedPackage.kt` | Add vulnerability, license, deprecation fields |
| `setting/PackageFinderSetting.kt` | Search history, favorites, notification prefs |

### New Files to Create

| File | Purpose |
|------|---------|
| `ui/StatusBadge.kt` | Badge/pill component |
| `ui/PackageIcons.kt` | Icon provider for package states |
| `ui/UdmToolbarActions.kt` | All toolbar AnAction classes |
| `ui/PackageContextMenuActions.kt` | Context menu action group |
| `ui/BulkUpgradeDialog.kt` | Upgrade All dialog |
| `ui/InstallPackageDialog.kt` | Enhanced install dialog |
| `ui/ConsolidateVersionsDialog.kt` | Version consolidation dialog |
| `ui/DependencyTreeDialog.kt` | Dependency tree visualization |
| `ui/DependencyGraphPanel.kt` | Interactive dependency graph |
| `ui/SearchHistoryPanel.kt` | Search history & favorites |
| `ui/LicenseCompliancePanel.kt` | License scanning panel |
| `ui/PackageComparisonDialog.kt` | Version comparison |
| `service/VulnerabilityService.kt` | OSV integration |
| `service/UpdateNotificationService.kt` | Background update checks |
| `action/QuickActionsProvider.kt` | Alt+Enter intentions in editor |

---

## Key IntelliJ Platform APIs

- `com.intellij.ui.dsl.builder.*` - Kotlin UI DSL 3.x
- `com.intellij.openapi.actionSystem.*` - Actions, toolbars, menus
- `com.intellij.ui.components.*` - JBLabel, JBList, JBColor, JBScrollPane
- `com.intellij.ui.treeStructure.Tree` - Tree component
- `com.intellij.ui.JBSplitter` - Split panes
- `com.intellij.openapi.ui.popup.JBPopupFactory` - Context menus
- `com.intellij.notification.NotificationGroup` - Balloon notifications
- `com.intellij.codeInsight.intention.IntentionAction` - Quick fixes in editor
- `com.intellij.openapi.keymap.KeymapManager` - Keyboard shortcuts
- `com.intellij.openapi.graph.*` - Graph visualization (if available)

---

## Verification

1. **Build:** `./gradlew build`
2. **Run IDE:** `./gradlew runIde`
3. **Manual testing checklist:**
    - [ ] Unified list shows Installed/Implicit/Available sections
    - [ ] Sections collapse/expand on click
    - [ ] Status badges display correctly (update, vulnerable, transitive)
    - [ ] Right-click context menu shows appropriate actions
    - [ ] All context menu actions work (upgrade, remove, copy, etc.)
    - [ ] Details pane sections expand/collapse
    - [ ] Version selector works in details pane
    - [ ] Module checkboxes work for multi-module install
    - [ ] Bulk upgrade dialog shows all updates
    - [ ] Search filters list with debounce
    - [ ] Keyboard shortcuts work
    - [ ] Vulnerability indicators show for affected packages
4. **Unit tests:** `./gradlew test`

---

## Implementation Priority

**P0 - Core (Must have):**
- Phase 1: Status indicators
- Phase 2: Toolbar actions
- Phase 3: Unified list
- Phase 4: Context menus
- Phase 5: Details pane refactor

**P1 - Important:**
- Phase 6: Dialogs (Bulk upgrade, Install, Consolidate)
- 7.4: Keyboard navigation
- 7.5: Notifications

**P2 - Nice to have:**
- 7.1: Vulnerability scanning
- 7.2: Dependency graph
- 7.3: Search history & favorites

**P3 - Future:**
- 7.6: License compliance
- 7.7: Package comparison
- 7.8: Quick actions in editor
