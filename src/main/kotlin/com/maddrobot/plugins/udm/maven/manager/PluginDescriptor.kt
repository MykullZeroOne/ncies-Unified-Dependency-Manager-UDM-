package com.maddrobot.plugins.udm.maven.manager

/**
 * Represents a parsed Maven plugin descriptor from META-INF/maven/plugin.xml inside a plugin JAR.
 * Contains mojo (goal) definitions and their configurable parameters.
 */
data class PluginDescriptor(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val goalPrefix: String?,
    val description: String?,
    val url: String?,
    val mojos: List<MojoDescriptor>
)

/**
 * Represents a single mojo (goal) within a Maven plugin.
 */
data class MojoDescriptor(
    val goal: String,
    val description: String?,
    val defaultPhase: String?,
    val parameters: List<MojoParameter>
)

/**
 * Represents a configurable parameter of a Maven plugin mojo.
 */
data class MojoParameter(
    val name: String,
    val type: String,
    val required: Boolean,
    val defaultValue: String?,
    val description: String?,
    val expression: String?,
    val editable: Boolean
)
