 ---                                                                                                                                                                                                                                            

1. COMPLIANCE & LISTING QUALITY -- Issues Found

Plugin Name (plugin.xml:8)

- Currently: Unified Dependency Manager (UDP) -- the abbreviation says UDP but everywhere else it's UDM. This
  inconsistency will hurt credibility.
- JetBrains recommends: 1-4 words, max 20 characters, no generic terms like "Manager". Consider just Unified Dependency
  Manager or a shorter brand name.

Vendor Info (plugin.xml:12)

- Currently: <vendor>Star</vendor> -- missing email and url attributes. JetBrains requires:
  <vendor url="https://github.com/maddrobot" email="development@maddrobot.tech">Madd Robot Tech</vendor>

Plugin URL (plugin.xml:2)

- Missing url attribute on <idea-plugin>. Should be:
  <idea-plugin url="https://github.com/maddrobot/udm">

Plugin Description (README.md lines 23-37)

- The Marketplace description is too sparse -- just a bullet list. Per the guidelines:
    - First 40 characters must contain a clear English summary (your "Search and manage packages..." is fine)
    - Missing: clear value proposition, what makes this different from existing tools
    - Missing: "Getting Started" instructions for the Marketplace listing
    - Should avoid marketing adjectives but should clearly explain why a user needs this

Screenshots

- Must be minimum 1200x760 pixels for Marketplace
- Must show relevant IDE interface with default theme (not custom themes)
- You have screenshots but need to verify they meet the size requirement

  ---

2. PLUGIN QUALITY & UX -- Issues Found

Non-null assertions (!!) -- These can crash the IDE:
┌───────────────────────────────┬───────────────────────────────────┐
│ File │ Issue │
├───────────────────────────────┼───────────────────────────────────┤
│ PackageListPanel.kt │ pkg.description!!.length │
├───────────────────────────────┼───────────────────────────────────┤
│ RepositoryConfigWriter.kt │ groups[2]!!.range (2 occurrences) │
├───────────────────────────────┼───────────────────────────────────┤
│ RepositoryDiscoveryService.kt │ foundUrl!!.startsWith()           │
└───────────────────────────────┴───────────────────────────────────┘
Hardcoded UI strings (should use i18n bundle):

- "Version:" in PackageDetailsPanel.kt
- "Install from:" in PackageDetailsPanel.kt
- "Filter:" in MainToolWindowPanel.kt
- "Group by:" in BulkUpgradeDialog.kt

You have 422 i18n keys which is great, but these stragglers will look bad in review.

Incomplete features (TODO comments left in code):

- RepositoryManagerDialog.kt: "TODO: Store in plugin settings"
- RepositoryManagerDialog.kt: "TODO: Remove from plugin settings"
- MainToolWindowPanel.kt: "TODO: Implement log level filtering"
- UnifiedDependencyPanel.kt: "TODO: Apply prerelease filter"

Deprecated API usage:

- TextComponentEmptyText.setupPlaceholderVisibility() in 3 files (MavenToolWindow, GradlePluginToolWindow,
  NpmToolWindow)

Hardcoded license salt (LicenseChecker.kt:39):

- LICENSE_SALT = "UDM-2026-KEYSTONE-SALT" -- should be externalized, and "KEYSTONE" (your banking project name)
  shouldn't be in a published plugin

  ---

3. TESTING -- Weak Area

This is likely your biggest gap. JetBrains explicitly evaluates whether the plugin is "thoroughly tested":
┌────────────────────────────────┬────────────────────────────────────────┐
│ What Exists │ Assessment │
├────────────────────────────────┼────────────────────────────────────────┤
│ 7 test files total │ Low for 98 source files │
├────────────────────────────────┼────────────────────────────────────────┤
│ MyPluginTest.kt │ Disabled (@Ignore)                     │
├────────────────────────────────┼────────────────────────────────────────┤
│ DependencyTest.kt │ Good unit tests for dependency formats │
├────────────────────────────────┼────────────────────────────────────────┤
│ Integration test (IDE Starter) │ Good infrastructure, limited coverage │
├────────────────────────────────┼────────────────────────────────────────┤
│ UI component tests │ None visible │
├────────────────────────────────┼────────────────────────────────────────┤
│ Error scenario tests │ None visible │
├────────────────────────────────┼────────────────────────────────────────┤
│ Gradle modifier tests │ None visible │
└────────────────────────────────┴────────────────────────────────────────┘
Recommendation: You need tests for your core differentiator -- the NuGet-style dependency management (scanner, modifier,
update service).

  ---

4. WHAT'S WORKING WELL
   ┌────────────────────────┬──────────────────────────────────────────────────────────┐
   │ Area │ Status │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ Build configuration │ Modern Gradle 9, Kotlin 2.2, version catalog │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ Plugin verification │ Tests against 5 IDE versions (2024.2 - 2025.3)           │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ K2 Kotlin mode │ Declared compatible (supportsK2="true")                  │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ Code signing │ Properly configured │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ Changelog integration │ Automated via Gradle Changelog Plugin │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ i18n │ 422 resource keys -- comprehensive │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ Thread safety │ 52 BGT declarations, proper ReadAction/WriteAction usage │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ Disposable management │ 38 proper disposable registrations │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ Settings persistence │ PersistentStateComponent done correctly │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ Error handling pattern │ Sealed RequestResult<T> for HTTP operations │
   ├────────────────────────┼──────────────────────────────────────────────────────────┤
   │ License │ Apache 2.0 properly declared │
   └────────────────────────┴──────────────────────────────────────────────────────────┘

  ---

5. PRIORITY ACTION ITEMS

Must fix (blocking):

1. Fix the UDP/UDM name inconsistency in plugin.xml
2. Add email and url to <vendor> tag
3. Add url to <idea-plugin> tag
4. Remove all !! non-null assertions (replace with safe calls)
5. Remove or externalize "KEYSTONE" from the license salt
6. Internationalize remaining hardcoded UI strings
7. Resolve or remove TODO comments

Should fix (strongly affects Staff Pick evaluation):

8. Expand the plugin description with a value proposition and getting-started section
9. Add tests for GradleDependencyScanner, GradleDependencyModifier, GradleUpdateService
10. Verify screenshots are 1200x760+ pixels
11. Replace deprecated TextComponentEmptyText API calls
12. Delete or update the disabled MyPluginTest

Nice to have (shows polish):

13. Add a short demo video (< 5 min) for the Marketplace listing
14. Increase download count and collect positive user reviews before applying
15. Submit a YouTrack ticket targeting the April, July, or November IDE release cycle

  ---
Sources

- https://plugins.jetbrains.com/docs/marketplace/staff-picks.html
- https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html
- https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html
- https://plugins.jetbrains.com/docs/marketplace/prepare-your-plugin-for-publication.html

Want me to start working on any of these items?
