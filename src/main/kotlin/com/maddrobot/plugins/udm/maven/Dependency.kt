package com.maddrobot.plugins.udm.maven

/**
 * Maven dependency core information (GAV)
 *
 * madd robot tech
 * @LastModified: 2025-01-20
 * @since 2025-01-20
 */
abstract class Dependency(
    open val groupId: String,
    open val artifactId: String,
    open val version: String
) {
    /**
     * Constructor for the abstract Dependency class with default values.
     *
     * This constructor is mandatory because a non-serializable parent class must have a parameterless
     * constructor to allow its subclasses to implement serialization. Providing this constructor ensures
     * compatibility with serialization frameworks that require this constraint.
     *
     * Note:
     * This constructor initializes the `groupId`, `artifactId`, and `version` properties to empty strings.
     */

    constructor() : this("", "", "")

    fun getGradleGroovyDeclaration(dependencyScope: DependencyScope): String =
        "${dependencyScope.gradleScopeConfig} '$groupId:$artifactId:$version'"

    fun getGradleKotlinDeclaration(dependencyScope: DependencyScope): String =
        """${dependencyScope.gradleScopeConfig}("$groupId:$artifactId:$version")"""

    fun getMavenDeclaration(dependencyScope: DependencyScope): String {
        val base = """
            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>$artifactId</artifactId>
                <version>$version</version>
        """.trimIndent()

        val scopePart = dependencyScope.mavenScopeConfig?.let {
            "    <scope>$it</scope>\n"
        } ?: ""

        return if (scopePart.isNotEmpty()) {
            "$base\n$scopePart</dependency>"
        } else {
            "$base\n</dependency>"
        }
    }
}
