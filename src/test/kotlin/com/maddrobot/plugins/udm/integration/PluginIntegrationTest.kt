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
import org.junit.jupiter.api.Test
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Integration tests for the Package Finder plugin using the Starter framework.
 *
 * These tests launch a real IDE instance with the plugin installed and verify
 * plugin functionality in an actual IDE environment.
 *
 * Note: First run will download the IDE, which takes some time.
 * Subsequent runs use cached IDE installations.
 *
 * madd robot tech
 * @since 2025-02-03
 */
class PluginIntegrationTest {

    // Custom exception handler to capture IDE errors in tests
    private val di = DI {
        bindSingleton<CIServer>(overrides = true) {
            object : CIServer by NoCIServer {
                fun reportTestFailure(
                    testName: String,
                    message: String,
                    details: String
                ) {
                    throw AssertionError("$testName fails: $message.\n$details")
                }
            }
        }
    }

    /**
     * Test that the plugin loads successfully in the IDE without errors.
     * This basic smoke test verifies that the plugin initializes correctly.
     */
    @Test
    fun `plugin loads successfully`() {
        val pathToPlugin = System.getProperty("path.to.build.plugin")
            ?: error("Plugin path not provided. Run with -Dpath.to.build.plugin=<path>")

        Starter.newContext(
            testName = "pluginLoadsSuccessfully",
            testCase = TestCase(
                ideInfo = IdeProductProvider.IC,
                projectInfo = LocalProjectInfo(
                    Path("src/test/resources/test-projects/simple-gradle")
                )
            ).withVersion("2024.3")
        ).apply {
            PluginConfigurator(this).installPluginFromPath(Path(pathToPlugin))
        }.runIdeWithDriver().useDriverAndCloseIde {
            // Wait for IDE to fully initialize
            waitForIndicators(2.minutes)

            // Verify no errors occurred during plugin initialization
            // The custom CIServer will capture and report any IDE errors
        }
    }

    /**
     * Test that the Package Finder tool window can be opened.
     * Uses the ToolWindow action ID format: ActivateXxxToolWindow
     */
    @Test
    fun `tool window opens successfully`() {
        val pathToPlugin = System.getProperty("path.to.build.plugin")
            ?: error("Plugin path not provided. Run with -Dpath.to.build.plugin=<path>")

        Starter.newContext(
            testName = "toolWindowOpensSuccessfully",
            testCase = TestCase(
                ideInfo = IdeProductProvider.IC,
                projectInfo = LocalProjectInfo(
                    Path("src/test/resources/test-projects/simple-gradle")
                )
            ).withVersion("2024.3")
        ).apply {
            PluginConfigurator(this).installPluginFromPath(Path(pathToPlugin))
        }.runIdeWithDriver().useDriverAndCloseIde {
            // Wait for IDE to fully initialize
            waitForIndicators(2.minutes)

            // Tool windows have auto-generated action IDs in format: ActivateXxxToolWindow
            // For "Unified Dependency Manager (UDM)" tool window
            invokeAction("ActivateUnified Dependency Manager (UDM)ToolWindow")

            // Give UI time to update
            Thread.sleep(1000)
        }
    }
}
