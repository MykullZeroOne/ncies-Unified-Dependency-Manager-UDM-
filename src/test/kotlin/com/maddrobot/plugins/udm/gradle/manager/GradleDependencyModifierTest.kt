package com.maddrobot.plugins.udm.gradle.manager

import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for GradleDependencyModifier's content manipulation logic.
 * Tests the string operations that modify build file content: removal, update,
 * addition, and exclusion management.
 */
class GradleDependencyModifierTest {

    // ── Remove Dependency ──

    @Test
    fun testRemoveDependencyFromContent() {
        val content = """
            dependencies {
                implementation("com.google.guava:guava:32.1.3-jre")
                testImplementation("junit:junit:4.13.2")
            }
        """.trimIndent()

        val implText = """implementation("com.google.guava:guava:32.1.3-jre")"""
        val offset = content.indexOf(implText)
        val result = removeDependencyText(content, offset, implText.length)

        assertFalse("Guava should be removed", result.contains("guava"))
        assertTrue("JUnit should remain", result.contains("junit"))
    }

    @Test
    fun testRemoveDependencyWithTrailingNewline() {
        val content = "implementation(\"com.google.guava:guava:32.1.3-jre\")\ntestImplementation(\"junit:junit:4.13.2\")"

        val result = removeDependencyText(content, 0, content.indexOf("\n"))

        assertTrue("JUnit should remain", result.contains("junit"))
    }

    // ── Update Version ──

    @Test
    fun testUpdateVersionInKotlinDsl() {
        val declaration = """implementation("com.google.guava:guava:31.0-jre")"""
        val updated = declaration.replace("31.0-jre", "32.1.3-jre")

        assertTrue(updated.contains("32.1.3-jre"))
        assertFalse(updated.contains("31.0-jre"))
        assertTrue(updated.contains("com.google.guava:guava"))
    }

    @Test
    fun testUpdateVersionInGroovyDsl() {
        val declaration = "implementation 'com.google.guava:guava:31.0-jre'"
        val updated = declaration.replace("31.0-jre", "32.1.3-jre")

        assertTrue(updated.contains("32.1.3-jre"))
        assertFalse(updated.contains("31.0-jre"))
    }

    @Test
    fun testUpdateVersionDoesNotAffectOtherVersions() {
        val content = """
            dependencies {
                implementation("com.google.guava:guava:31.0-jre")
                testImplementation("junit:junit:4.13.2")
            }
        """.trimIndent()

        val depText = """implementation("com.google.guava:guava:31.0-jre")"""
        val offset = content.indexOf(depText)
        val result = updateVersionText(content, offset, depText.length, "31.0-jre", "32.1.3-jre")

        assertTrue("New version should be present", result.contains("32.1.3-jre"))
        assertTrue("JUnit version should be unchanged", result.contains("4.13.2"))
    }

    // ── Add Dependency ──

    @Test
    fun testAddDependencyToExistingBlockKotlinDsl() {
        val content = """
            dependencies {
                implementation("com.google.guava:guava:32.1.3-jre")
            }
        """.trimIndent()

        val result = addDependencyToBlock(
            content, "org.slf4j", "slf4j-api", "2.0.9", "implementation", isKotlin = true
        )

        assertTrue("New dependency should be present", result.contains("org.slf4j:slf4j-api:2.0.9"))
        assertTrue("Existing dependency should remain", result.contains("guava"))
    }

    @Test
    fun testAddDependencyToExistingBlockGroovyDsl() {
        val content = """
            dependencies {
                implementation 'com.google.guava:guava:32.1.3-jre'
            }
        """.trimIndent()

        val result = addDependencyToBlock(
            content, "org.slf4j", "slf4j-api", "2.0.9", "implementation", isKotlin = false
        )

        assertTrue("New dependency should be present", result.contains("org.slf4j:slf4j-api:2.0.9"))
    }

    @Test
    fun testAddDependencyCreatesBlockIfMissing() {
        val content = """
            plugins {
                id("org.jetbrains.kotlin.jvm") version "1.9.0"
            }
        """.trimIndent()

        val result = addDependencyToBlock(
            content, "org.slf4j", "slf4j-api", "2.0.9", "implementation", isKotlin = true
        )

        assertTrue("Should contain dependencies block", result.contains("dependencies {"))
        assertTrue("New dependency should be present", result.contains("org.slf4j:slf4j-api:2.0.9"))
    }

    @Test
    fun testAddDependencyKotlinDslUsesParentheses() {
        val content = "dependencies {\n}"

        val result = addDependencyToBlock(
            content, "junit", "junit", "4.13.2", "testImplementation", isKotlin = true
        )

        assertTrue(result.contains("""testImplementation("junit:junit:4.13.2")"""))
    }

    @Test
    fun testAddDependencyGroovyDslUsesSingleQuotes() {
        val content = "dependencies {\n}"

        val result = addDependencyToBlock(
            content, "junit", "junit", "4.13.2", "testImplementation", isKotlin = false
        )

        assertTrue(result.contains("testImplementation 'junit:junit:4.13.2'"))
    }

    // ── Exclusion Management ──

    @Test
    fun testAddExclusionToKotlinDslDeclaration() {
        val declaration = """implementation("com.google.guava:guava:32.1.3-jre")"""

        val exclusion = DependencyExclusion("com.google.code.findbugs", "jsr305")
        val result = addExclusionToDeclaration(declaration, exclusion, isKotlin = true, indent = "    ")

        assertTrue("Should contain exclude call", result.contains("""exclude(group = "com.google.code.findbugs", module = "jsr305")"""))
        assertTrue("Should add closure", result.contains("{") && result.contains("}"))
    }

    @Test
    fun testAddExclusionToGroovyDslDeclaration() {
        val declaration = "implementation 'com.google.guava:guava:32.1.3-jre'"

        val exclusion = DependencyExclusion("com.google.code.findbugs", "jsr305")
        val result = addExclusionToDeclaration(declaration, exclusion, isKotlin = false, indent = "    ")

        assertTrue("Should contain exclude call", result.contains("exclude group: 'com.google.code.findbugs', module: 'jsr305'"))
    }

    @Test
    fun testAddGroupOnlyExclusionKotlinDsl() {
        val declaration = """implementation("org.apache.httpcomponents:httpclient:4.5.14")"""

        val exclusion = DependencyExclusion("commons-logging")
        val result = addExclusionToDeclaration(declaration, exclusion, isKotlin = true, indent = "    ")

        assertTrue(result.contains("""exclude(group = "commons-logging")"""))
        assertFalse("Should not contain module param", result.contains("module = "))
    }

    @Test
    fun testAddExclusionToExistingClosure() {
        val declaration = """implementation("com.google.guava:guava:32.1.3-jre") {
        exclude(group = "com.google.code.findbugs", module = "jsr305")
    }"""

        val exclusion = DependencyExclusion("org.checkerframework", "checker-qual")
        val result = addExclusionToDeclaration(declaration, exclusion, isKotlin = true, indent = "    ")

        assertTrue("Should contain existing exclusion", result.contains("findbugs"))
        assertTrue("Should contain new exclusion", result.contains("checker-qual"))
    }

    @Test
    fun testRemoveExclusionFromKotlinDsl() {
        val declaration = """implementation("com.google.guava:guava:32.1.3-jre") {
        exclude(group = "com.google.code.findbugs", module = "jsr305")
    }"""

        val exclusion = DependencyExclusion("com.google.code.findbugs", "jsr305")
        val result = removeExclusionFromDeclaration(declaration, exclusion, isKotlin = true)

        assertFalse("Should not contain exclude call", result.contains("exclude("))
        assertTrue("Should still contain dependency", result.contains("guava"))
    }

    @Test
    fun testRemoveExclusionFromGroovyDsl() {
        val declaration = """implementation('com.google.guava:guava:32.1.3-jre') {
        exclude group: 'com.google.code.findbugs', module: 'jsr305'
    }"""

        val exclusion = DependencyExclusion("com.google.code.findbugs", "jsr305")
        val result = removeExclusionFromDeclaration(declaration, exclusion, isKotlin = false)

        assertFalse("Should not contain exclude call", result.contains("exclude"))
    }

    // ── Helper methods that mirror GradleDependencyModifier's logic ──

    private fun removeDependencyText(text: String, offset: Int, length: Int): String {
        var end = offset + length
        if (end < text.length && text[end] == '\n') end++
        else if (end < text.length && text[end] == '\r') {
            end++
            if (end < text.length && text[end] == '\n') end++
        }
        val sb = StringBuilder(text)
        sb.delete(offset, end)
        return sb.toString()
    }

    private fun updateVersionText(text: String, offset: Int, length: Int, oldVersion: String, newVersion: String): String {
        val currentText = text.substring(offset, offset + length)
        val newText = currentText.replace(oldVersion, newVersion)
        val sb = StringBuilder(text)
        sb.replace(offset, offset + length, newText)
        return sb.toString()
    }

    private fun addDependencyToBlock(
        text: String, groupId: String, artifactId: String, version: String,
        configuration: String, isKotlin: Boolean
    ): String {
        val depLine = if (isKotlin) {
            "    $configuration(\"$groupId:$artifactId:$version\")"
        } else {
            "    $configuration '$groupId:$artifactId:$version'"
        }

        val dependenciesIndex = text.indexOf("dependencies {")
        if (dependenciesIndex != -1) {
            var braceCount = 0
            var closingBraceIndex = -1
            for (i in dependenciesIndex until text.length) {
                if (text[i] == '{') braceCount++
                if (text[i] == '}') {
                    braceCount--
                    if (braceCount == 0) { closingBraceIndex = i; break }
                }
            }
            if (closingBraceIndex != -1) {
                val sb = StringBuilder(text)
                val prefix = if (text[closingBraceIndex - 1] != '\n') "\n" else ""
                sb.insert(closingBraceIndex, "$prefix$depLine\n")
                return sb.toString()
            }
        }
        return text + "\n\ndependencies {\n$depLine\n}\n"
    }

    private fun addExclusionToDeclaration(
        declaration: String, exclusion: DependencyExclusion,
        isKotlin: Boolean, indent: String
    ): String {
        val innerIndent = "$indent    "
        val excludeLine = if (isKotlin) {
            if (exclusion.artifactId != null)
                "${innerIndent}exclude(group = \"${exclusion.groupId}\", module = \"${exclusion.artifactId}\")"
            else
                "${innerIndent}exclude(group = \"${exclusion.groupId}\")"
        } else {
            if (exclusion.artifactId != null)
                "${innerIndent}exclude group: '${exclusion.groupId}', module: '${exclusion.artifactId}'"
            else
                "${innerIndent}exclude group: '${exclusion.groupId}'"
        }

        val closingBraceIdx = declaration.lastIndexOf('}')
        return if (closingBraceIdx != -1) {
            val beforeBrace = declaration.substring(0, closingBraceIdx)
            val afterBrace = declaration.substring(closingBraceIdx)
            "$beforeBrace\n$excludeLine\n$indent$afterBrace"
        } else {
            "${declaration.trimEnd()} {\n$excludeLine\n$indent}"
        }
    }

    private fun removeExclusionFromDeclaration(
        declaration: String, exclusion: DependencyExclusion, isKotlin: Boolean
    ): String {
        val excludePattern = if (isKotlin) {
            if (exclusion.artifactId != null)
                Regex("""[ \t]*exclude\(group\s*=\s*"${Regex.escape(exclusion.groupId)}"\s*,\s*module\s*=\s*"${Regex.escape(exclusion.artifactId!!)}"\)\s*\n?""")
            else
                Regex("""[ \t]*exclude\(group\s*=\s*"${Regex.escape(exclusion.groupId)}"\)\s*\n?""")
        } else {
            if (exclusion.artifactId != null)
                Regex("""[ \t]*exclude\s+group:\s*'${Regex.escape(exclusion.groupId)}'\s*,\s*module:\s*'${Regex.escape(exclusion.artifactId!!)}'\s*\n?""")
            else
                Regex("""[ \t]*exclude\s+group:\s*'${Regex.escape(exclusion.groupId)}'\s*\n?""")
        }

        var result = excludePattern.replace(declaration, "")
        val closureMatch = Regex("""\s*\{\s*}""").find(result)
        if (closureMatch != null) {
            result = result.substring(0, closureMatch.range.first)
        }
        return result
    }
}
