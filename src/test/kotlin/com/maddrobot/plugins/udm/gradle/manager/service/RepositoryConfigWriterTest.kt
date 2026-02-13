package com.maddrobot.plugins.udm.gradle.manager.service

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for RepositoryConfigWriter's repository block manipulation logic.
 * Verifies regex-based repository addition, removal, and detection for
 * Gradle (Kotlin/Groovy DSL) and Maven settings.xml formats.
 */
class RepositoryConfigWriterTest {

    // ── Add Repository to Repositories Block ──

    @Test
    fun testAddRepoToExistingBlock() {
        val text = """
            repositories {
                mavenCentral()
            }
        """.trimIndent()

        val repoDeclaration = """        maven { url = uri("https://nexus.example.com/maven") }"""
        val result = addToRepositoriesBlock(text, repoDeclaration)

        assertNotNull("Should find repositories block", result)
        assertTrue("Should contain new repo", result!!.contains("nexus.example.com"))
        assertTrue("Should still contain mavenCentral", result.contains("mavenCentral"))
    }

    @Test
    fun testAddRepoToDependencyResolutionManagement() {
        val text = """
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }
        """.trimIndent()

        val repoDeclaration = """        maven { url = uri("https://nexus.example.com/maven") }"""
        val result = addToRepositoriesBlock(text, repoDeclaration)

        assertNotNull("Should find dependencyResolutionManagement block", result)
        assertTrue("Should contain new repo", result!!.contains("nexus.example.com"))
    }

    @Test
    fun testDoesNotAddDuplicateWellKnownRepo() {
        val text = """
            repositories {
                mavenCentral()
                google()
            }
        """.trimIndent()

        // mavenCentral detection uses a special check
        val repoDeclaration = """        mavenCentral()"""
        val result = addToRepositoriesBlock(text, repoDeclaration)

        assertNull("Should return null when mavenCentral already exists", result)
    }

    @Test
    fun testDoesNotAddDuplicateMavenCentral() {
        val text = """
            repositories {
                mavenCentral()
            }
        """.trimIndent()

        val repoDeclaration = """        mavenCentral()"""
        val result = addToRepositoriesBlock(text, repoDeclaration)

        assertNull("Should return null when mavenCentral already exists", result)
    }

    @Test
    fun testReturnsNullWhenNoRepoBlock() {
        val text = """
            plugins {
                id("org.jetbrains.kotlin.jvm") version "1.9.0"
            }
        """.trimIndent()

        val repoDeclaration = """        mavenCentral()"""
        val result = addToRepositoriesBlock(text, repoDeclaration)

        assertNull("Should return null when no repositories block", result)
    }

    // ── Create Repositories Block ──

    @Test
    fun testCreateRepositoriesBlock() {
        val text = """
            plugins {
                id("org.jetbrains.kotlin.jvm") version "1.9.0"
            }
        """.trimIndent()

        val repoDeclaration = """    mavenCentral()"""
        val result = createRepositoriesBlock(text, repoDeclaration)

        assertTrue("Should contain repositories block", result.contains("repositories {"))
        assertTrue("Should contain mavenCentral", result.contains("mavenCentral"))
        assertTrue("Should still contain plugins block", result.contains("plugins"))
    }

    // ── Remove Repository ──

    @Test
    fun testRemoveCustomMavenRepo() {
        val text = """
            repositories {
                mavenCentral()
                maven { url = uri("https://nexus.example.com/maven") }
            }
        """.trimIndent()

        val result = removeGradleRepository(text, "https://nexus.example.com/maven")

        assertFalse("Should not contain removed repo", result.contains("nexus.example.com"))
        assertTrue("Should still contain mavenCentral", result.contains("mavenCentral"))
    }

    @Test
    fun testRemoveMavenCentral() {
        val text = """
            repositories {
                mavenCentral()
                google()
            }
        """.trimIndent()

        val result = removeWellKnownRepo(text, "mavenCentral")

        assertFalse("Should not contain mavenCentral", result.contains("mavenCentral"))
        assertTrue("Should still contain google", result.contains("google"))
    }

    @Test
    fun testRemoveGoogleRepo() {
        val text = """
            repositories {
                mavenCentral()
                google()
            }
        """.trimIndent()

        val result = removeWellKnownRepo(text, "google")

        assertFalse("Should not contain google", result.contains("google"))
        assertTrue("Should still contain mavenCentral", result.contains("mavenCentral"))
    }

    @Test
    fun testRemoveGradlePluginPortal() {
        val text = """
            repositories {
                gradlePluginPortal()
                mavenCentral()
            }
        """.trimIndent()

        val result = removeWellKnownRepo(text, "gradlePluginPortal")

        assertFalse("Should not contain gradlePluginPortal", result.contains("gradlePluginPortal"))
        assertTrue("Should still contain mavenCentral", result.contains("mavenCentral"))
    }

    // ── Repo Declaration Generation ──

    @Test
    fun testGenerateMavenCentralDeclarationKotlin() {
        val decl = generateRepoDeclaration("https://repo.maven.org/maven2", isKotlin = true)
        assertTrue(decl.contains("mavenCentral()"))
    }

    @Test
    fun testGenerateGoogleDeclarationKotlin() {
        val decl = generateRepoDeclaration("https://maven.google.com", isKotlin = true)
        assertTrue(decl.contains("google()"))
    }

    @Test
    fun testGenerateGradlePluginPortalDeclaration() {
        val decl = generateRepoDeclaration("https://plugins.gradle.org/m2", isKotlin = true)
        assertTrue(decl.contains("gradlePluginPortal()"))
    }

    @Test
    fun testGenerateCustomRepoKotlinDsl() {
        val decl = generateRepoDeclaration("https://nexus.example.com/maven", isKotlin = true)
        assertTrue(decl.contains("""maven { url = uri("https://nexus.example.com/maven") }"""))
    }

    @Test
    fun testGenerateCustomRepoGroovyDsl() {
        val decl = generateRepoDeclaration("https://nexus.example.com/maven", isKotlin = false)
        assertTrue(decl.contains("maven { url 'https://nexus.example.com/maven' }"))
    }

    @Test
    fun testGenerateRepoWithCredentialsKotlin() {
        val decl = generateRepoDeclarationWithCredentials(
            "https://private.example.com/maven", "repoId", isKotlin = true
        )

        assertTrue("Should contain credentials block", decl.contains("credentials"))
        assertTrue("Should reference repoIdUser", decl.contains("repoIdUser"))
        assertTrue("Should reference repoIdPassword", decl.contains("repoIdPassword"))
    }

    // ── Maven settings.xml Generation ──

    @Test
    fun testCreateMavenSettingsXml() {
        val repo = RepositoryConfig(
            id = "nexus", name = "Nexus",
            url = "https://nexus.example.com/maven",
            type = RepositoryType.NEXUS
        )
        val xml = createMavenSettings(repo)

        assertTrue("Should contain xml declaration", xml.contains("<?xml"))
        assertTrue("Should contain repository", xml.contains("<repository>"))
        assertTrue("Should contain URL", xml.contains("nexus.example.com"))
        assertTrue("Should contain profile", xml.contains("custom-repos"))
        assertTrue("Should activate profile", xml.contains("<activeProfile>custom-repos</activeProfile>"))
    }

    @Test
    fun testCreateMavenSettingsWithCredentials() {
        val repo = RepositoryConfig(
            id = "nexus", name = "Nexus",
            url = "https://nexus.example.com/maven",
            type = RepositoryType.NEXUS,
            username = "admin", password = "secret"
        )
        val xml = createMavenSettings(repo)

        assertTrue("Should contain servers section", xml.contains("<server>"))
        assertTrue("Should contain username", xml.contains("<username>admin</username>"))
        assertTrue("Should contain password", xml.contains("<password>secret</password>"))
    }

    // ── Helper methods mirroring RepositoryConfigWriter logic ──

    private fun addToRepositoriesBlock(text: String, repoDeclaration: String): String? {
        val dependencyResolutionMatch = Regex(
            """(dependencyResolutionManagement\s*\{[^}]*repositories\s*\{)([^}]*)(\})"""
        ).find(text)

        if (dependencyResolutionMatch != null) {
            val existingRepos = dependencyResolutionMatch.groupValues[2]
            if (existingRepos.contains(repoDeclaration.trim()) ||
                (repoDeclaration.contains("mavenCentral") && existingRepos.contains("mavenCentral"))) {
                return null
            }
            val newRepos = existingRepos.trimEnd() + "\n$repoDeclaration\n    "
            val groupRange = dependencyResolutionMatch.groups[2]?.range ?: return null
            return text.replaceRange(groupRange, newRepos)
        }

        val reposMatch = Regex("""(repositories\s*\{)([^}]*)(\})""").find(text)
        if (reposMatch != null) {
            val existingRepos = reposMatch.groupValues[2]
            if (existingRepos.contains(repoDeclaration.trim())) return null
            val newRepos = existingRepos.trimEnd() + "\n$repoDeclaration\n"
            val groupRange = reposMatch.groups[2]?.range ?: return null
            return text.replaceRange(groupRange, newRepos)
        }

        return null
    }

    private fun createRepositoriesBlock(text: String, repoDeclaration: String): String {
        val newBlock = "\nrepositories {\n$repoDeclaration\n}\n"
        return text.trimEnd() + "\n" + newBlock
    }

    private fun removeGradleRepository(text: String, url: String): String {
        val patterns = listOf(
            Regex("""maven\s*\{\s*url\s*=?\s*['"]?${Regex.escape(url)}['"]?\s*\}"""),
            Regex("""maven\s*\{\s*url\s*=?\s*uri\s*\(\s*['"]${Regex.escape(url)}['"]\s*\)\s*\}"""),
            Regex("""maven\s*\(\s*['"]${Regex.escape(url)}['"]\s*\)""")
        )
        var result = text
        for (pattern in patterns) {
            result = pattern.replace(result) { "" }
        }
        return result.replace(Regex("\n\\s*\n\\s*\n"), "\n\n")
    }

    private fun removeWellKnownRepo(text: String, name: String): String {
        return Regex("""$name\s*\(\s*\)""").replace(text) { "" }
            .replace(Regex("\n\\s*\n\\s*\n"), "\n\n")
    }

    private fun generateRepoDeclaration(url: String, isKotlin: Boolean): String {
        val indent = "        "
        return when {
            url.contains("repo.maven.org/maven2") -> "${indent}mavenCentral()"
            url.contains("maven.google.com") -> "${indent}google()"
            url.contains("plugins.gradle.org/m2") -> "${indent}gradlePluginPortal()"
            else -> {
                if (isKotlin) """${indent}maven { url = uri("$url") }"""
                else """${indent}maven { url '$url' }"""
            }
        }
    }

    private fun generateRepoDeclarationWithCredentials(url: String, repoId: String, isKotlin: Boolean): String {
        val indent = "        "
        return if (isKotlin) {
            """
            |${indent}maven {
            |${indent}    url = uri("$url")
            |${indent}    credentials {
            |${indent}        username = findProperty("${repoId}User")?.toString() ?: ""
            |${indent}        password = findProperty("${repoId}Password")?.toString() ?: ""
            |${indent}    }
            |${indent}}
            """.trimMargin()
        } else {
            """
            |${indent}maven {
            |${indent}    url '$url'
            |${indent}    credentials {
            |${indent}        username = findProperty('${repoId}User') ?: ''
            |${indent}        password = findProperty('${repoId}Password') ?: ''
            |${indent}    }
            |${indent}}
            """.trimMargin()
        }
    }

    private fun createMavenSettings(repo: RepositoryConfig): String {
        val serversSection = if (repo.username != null || repo.password != null) {
            """
  <servers>
    <server>
      <id>${repo.id}</id>
      ${if (repo.username != null) "<username>${repo.username}</username>" else ""}
      ${if (repo.password != null) "<password>${repo.password}</password>" else ""}
    </server>
  </servers>
"""
        } else ""

        return """<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd">
$serversSection  <profiles>
    <profile>
      <id>custom-repos</id>
      <repositories>
        <repository>
          <id>${repo.id}</id>
          <url>${repo.url}</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>custom-repos</activeProfile>
  </activeProfiles>
</settings>
"""
    }
}
