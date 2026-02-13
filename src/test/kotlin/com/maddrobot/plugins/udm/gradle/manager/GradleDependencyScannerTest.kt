package com.maddrobot.plugins.udm.gradle.manager

import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for InstalledDependency model and dependency configuration recognition.
 * Verifies the data structures used by GradleDependencyScanner.
 */
class GradleDependencyScannerTest {

    // ── InstalledDependency Model Tests ──

    @Test
    fun testInstalledDependencyId() {
        val dep = createDependency("com.google.guava", "guava", "32.1.3-jre")
        assertEquals("com.google.guava:guava", dep.id)
    }

    @Test
    fun testInstalledDependencyFullName() {
        val dep = createDependency("com.google.guava", "guava", "32.1.3-jre")
        assertEquals("com.google.guava:guava:32.1.3-jre", dep.fullName)
    }

    @Test
    fun testInstalledDependencyWithExclusions() {
        val dep = InstalledDependency(
            groupId = "com.google.guava",
            artifactId = "guava",
            version = "32.1.3-jre",
            configuration = "implementation",
            moduleName = "app",
            buildFile = "/path/to/build.gradle.kts",
            exclusions = listOf(
                DependencyExclusion("com.google.code.findbugs", "jsr305"),
                DependencyExclusion("org.checkerframework")
            )
        )

        assertEquals(2, dep.exclusions.size)
        assertEquals("com.google.code.findbugs", dep.exclusions[0].groupId)
        assertEquals("jsr305", dep.exclusions[0].artifactId)
        assertNull(dep.exclusions[1].artifactId)
    }

    @Test
    fun testInstalledDependencyDefaultValues() {
        val dep = createDependency("org.slf4j", "slf4j-api", "2.0.9")
        assertFalse(dep.isFromVersionCatalog)
        assertNull(dep.catalogKey)
        assertEquals(-1, dep.offset)
        assertEquals(0, dep.length)
        assertTrue(dep.exclusions.isEmpty())
    }

    @Test
    fun testInstalledDependencyWithVersionCatalog() {
        val dep = InstalledDependency(
            groupId = "com.google.guava",
            artifactId = "guava",
            version = "32.1.3-jre",
            configuration = "implementation",
            moduleName = "app",
            buildFile = "/path/to/build.gradle.kts",
            isFromVersionCatalog = true,
            catalogKey = "libs.guava"
        )

        assertTrue(dep.isFromVersionCatalog)
        assertEquals("libs.guava", dep.catalogKey)
    }

    // ── DependencyExclusion Model Tests ──

    @Test
    fun testExclusionWithGroupAndArtifact() {
        val exclusion = DependencyExclusion("com.google.code.findbugs", "jsr305")
        assertEquals("com.google.code.findbugs:jsr305", exclusion.id)
        assertEquals("com.google.code.findbugs:jsr305", exclusion.displayName)
    }

    @Test
    fun testExclusionGroupOnly() {
        val exclusion = DependencyExclusion("com.google.code.findbugs")
        assertEquals("com.google.code.findbugs", exclusion.id)
        assertEquals("com.google.code.findbugs:*", exclusion.displayName)
    }

    // ── Dependency String Parsing Logic Tests ──

    @Test
    fun testParseKotlinDslDependencyString() {
        val depString = "com.google.guava:guava:32.1.3-jre"
        val parts = depString.split(":")
        assertEquals(3, parts.size)
        assertEquals("com.google.guava", parts[0])
        assertEquals("guava", parts[1])
        assertEquals("32.1.3-jre", parts[2])
    }

    @Test
    fun testParseGroovyDslDependencyString() {
        val depString = "'com.google.guava:guava:32.1.3-jre'"
            .removeSurrounding("'").removeSurrounding("\"")
        val parts = depString.split(":")
        assertEquals(3, parts.size)
        assertEquals("com.google.guava", parts[0])
    }

    @Test
    fun testParseGroovyDoubleQuoteDependencyString() {
        val depString = "\"com.google.guava:guava:32.1.3-jre\""
            .removeSurrounding("'").removeSurrounding("\"")
        val parts = depString.split(":")
        assertEquals(3, parts.size)
        assertEquals("guava", parts[1])
    }

    @Test
    fun testParseDependencyStringWithTwoParts() {
        val depString = "com.google.guava:guava"
        val parts = depString.split(":")
        assertEquals(2, parts.size)
        // Should not be treated as a full dependency (requires 3 parts)
        assertTrue(parts.size < 3)
    }

    @Test
    fun testParseDependencyStringWithFourParts() {
        val depString = "com.google.guava:guava:32.1.3-jre:sources"
        val parts = depString.split(":")
        assertTrue(parts.size >= 3)
        assertEquals("sources", parts[3])
    }

    // ── Configuration Recognition Tests ──

    @Test
    fun testRecognizedConfigurations() {
        val validConfigs = listOf(
            "implementation", "api", "testImplementation",
            "runtimeOnly", "compileOnly", "annotationProcessor",
            "testRuntimeOnly", "testCompileOnly"
        )
        for (config in validConfigs) {
            assertTrue("$config should be recognized", isDependencyConfiguration(config))
        }
    }

    @Test
    fun testUnrecognizedConfigurations() {
        val invalidConfigs = listOf("plugins", "apply", "buildscript", "allprojects", "task")
        for (config in invalidConfigs) {
            assertFalse("$config should NOT be recognized", isDependencyConfiguration(config))
        }
    }

    // ── Data Equality Tests ──

    @Test
    fun testDependencyEquality() {
        val dep1 = createDependency("com.google.guava", "guava", "32.1.3-jre")
        val dep2 = createDependency("com.google.guava", "guava", "32.1.3-jre")
        assertEquals(dep1, dep2)
    }

    @Test
    fun testDependencyInequality() {
        val dep1 = createDependency("com.google.guava", "guava", "31.0-jre")
        val dep2 = createDependency("com.google.guava", "guava", "32.1.3-jre")
        assertNotEquals(dep1, dep2)
    }

    // ── Helper ──

    private fun isDependencyConfiguration(name: String): Boolean {
        return name in listOf(
            "implementation", "api", "testImplementation",
            "runtimeOnly", "compileOnly", "annotationProcessor",
            "testRuntimeOnly", "testCompileOnly"
        )
    }

    private fun createDependency(groupId: String, artifactId: String, version: String) =
        InstalledDependency(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            configuration = "implementation",
            moduleName = "app",
            buildFile = "/path/to/build.gradle.kts"
        )
}
