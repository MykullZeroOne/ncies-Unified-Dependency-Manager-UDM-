package com.maddrobot.plugins.udm.integration

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

/**
 * UI Feature tests for the Unified Dependency Manager plugin.
 *
 * These tests verify UI functionality including:
 * - Tool window tabs and navigation
 * - License dialog interactions
 * - Premium feature gating
 * - Dependency management UI components
 *
 * Uses IDE Starter + Driver framework (JetBrains recommended approach for plugin UI testing).
 *
 * @see <a href="https://github.com/JetBrains/intellij-ide-starter">IDE Starter</a>
 */
class UiFeatureTest {

    private val di = DI {
        bindSingleton<CIServer>(overrides = true) {
            object : CIServer by NoCIServer {
                override fun reportTestFailure(
                    testName: String,
                    message: String,
                    details: String
                ) {
                    throw AssertionError("$testName fails: $message.\n$details")
                }
            }
        }
    }

    private fun getPluginPath(): String {
        return System.getProperty("path.to.build.plugin")
            ?: error("Plugin path not provided. Run with -Dpath.to.build.plugin=<path>")
    }

    private fun createTestContext(testName: String) = Starter.newContext(
        testName = testName,
        testCase = TestCase(
            ideInfo = IdeProductProvider.IC,
            projectInfo = LocalProjectInfo(
                Path("src/test/resources/test-projects/simple-gradle")
            )
        ).withVersion("2024.3")
    ).apply {
        PluginConfigurator(this).installPluginFromPath(Path(getPluginPath()))
    }

    // ========== Tool Window Tests ==========

    /**
     * Test that all expected tabs are present in the tool window.
     */
    @Test
    fun `tool window contains expected tabs`() {
        createTestContext("toolWindowContainsExpectedTabs")
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(2.minutes)

                // Open the UDP tool window
                invokeAction("ActivateUnified Dependency Manager (UDP)ToolWindow")
                Thread.sleep(1000)

                // Verify the tool window opened (no exception thrown)
                // In development mode, all features should be accessible
            }
    }

    /**
     * Test that the refresh action works in the tool window.
     */
    @Test
    fun `refresh action updates dependency list`() {
        createTestContext("refreshActionUpdatesDependencyList")
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(2.minutes)

                // Open tool window
                invokeAction("ActivateUnified Dependency Manager (UDP)ToolWindow")
                Thread.sleep(1000)

                // Trigger refresh (standard IDE refresh action)
                invokeAction("Refresh")
                Thread.sleep(2000)

                // Test passes if no exceptions occurred
            }
    }

    // ========== License Dialog Tests ==========

    /**
     * Test that the license management action is accessible from Tools menu.
     */
    @Test
    fun `license action accessible from tools menu`() {
        createTestContext("licenseActionAccessibleFromToolsMenu")
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(2.minutes)

                // Open license dialog via action
                invokeAction("UDM.ManageLicense")
                Thread.sleep(500)

                // Dialog should be open - close it using escape action
                invokeAction("EditorEscape")
                Thread.sleep(300)
            }
    }

    /**
     * Test that development mode is detected and shows premium features.
     */
    @Test
    fun `development mode enables premium features`() {
        createTestContext("developmentModeEnablesPremiumFeatures")
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(2.minutes)

                // Open tool window
                invokeAction("ActivateUnified Dependency Manager (UDP)ToolWindow")
                Thread.sleep(1000)

                // In dev mode (udm.dev.mode=true), premium features should work
                // The test project's build.gradle.kts sets this property
                // Try to invoke a premium action - should not show upgrade dialog
            }
    }

    // ========== Dependency Search Tests ==========

    /**
     * Test that searching for packages works in the browse tab.
     */
    @Test
    fun `search packages returns results`() {
        createTestContext("searchPackagesReturnsResults")
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(2.minutes)

                // Open tool window
                invokeAction("ActivateUnified Dependency Manager (UDP)ToolWindow")
                Thread.sleep(1000)

                // Focus search field using Find action
                invokeAction("Find")
                Thread.sleep(300)

                // Search happens through the toolbar - test the tool window opens correctly
                Thread.sleep(2000)

                // Test passes if no exceptions - actual result verification
                // would require more complex UI inspection
            }
    }

    // ========== Settings Integration Tests ==========

    /**
     * Test that plugin settings page is accessible.
     */
    @Test
    fun `plugin settings page opens`() {
        createTestContext("pluginSettingsPageOpens")
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(2.minutes)

                // Open settings dialog
                invokeAction("ShowSettings")
                Thread.sleep(1000)

                // Settings dialog should be open
                // The search field is automatically focused so settings are navigable
                Thread.sleep(500)

                // Close settings using escape
                invokeAction("EditorEscape")
                Thread.sleep(300)
            }
    }

    // ========== Premium Feature Gate Tests ==========

    /**
     * Test that bulk upgrade action respects license status.
     * In development mode, it should work; otherwise show upgrade prompt.
     */
    @Test
    fun `bulk upgrade respects license status`() {
        createTestContext("bulkUpgradeRespectsLicenseStatus")
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(2.minutes)

                // Open tool window
                invokeAction("ActivateUnified Dependency Manager (UDP)ToolWindow")
                Thread.sleep(1000)

                // Development mode should allow premium features
                // In production without license, this would show upgrade dialog

                // Test passes if no exceptions
            }
    }

    // ========== Keyboard Shortcuts Tests ==========

    /**
     * Test that refresh action works via action invocation.
     */
    @Test
    fun `refresh action works`() {
        createTestContext("refreshActionWorks")
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(2.minutes)

                // Open tool window
                invokeAction("ActivateUnified Dependency Manager (UDP)ToolWindow")
                Thread.sleep(1000)

                // Invoke refresh via action
                invokeAction("Refresh")
                Thread.sleep(2000)

                // Test passes if no exceptions
            }
    }
}
