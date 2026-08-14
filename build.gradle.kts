import java.util.Properties

plugins {
    alias(libs.plugins.hilt) apply (false)
    alias(libs.plugins.kotlin.ksp) apply (false)
    alias(libs.plugins.aboutlibraries) apply (false)
    alias(libs.plugins.vkid.manifest.placeholders)
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.gradle)
        classpath(kotlin("gradle-plugin", libs.versions.kotlin.get()))
    }
}

val vkIdLocalProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun vkIdCredential(
    gradlePropertyName: String,
    environmentVariableName: String,
    localPropertyName: String,
): String? = providers.gradleProperty(gradlePropertyName).orNull
    ?: providers.environmentVariable(environmentVariableName).orNull
    ?: vkIdLocalProperties.getProperty(localPropertyName)

val suppliedVkIdClientId = vkIdCredential(
    gradlePropertyName = "vk.id.clientId",
    environmentVariableName = "VK_ID_CLIENT_ID",
    localPropertyName = "VKIDClientID",
)?.trim()?.takeIf(String::isNotEmpty)
val suppliedVkIdClientSecret = vkIdCredential(
    gradlePropertyName = "vk.id.clientSecret",
    environmentVariableName = "VK_ID_CLIENT_SECRET",
    localPropertyName = "VKIDClientSecret",
)?.trim()?.takeIf(String::isNotEmpty)

val vkIdConfigured = suppliedVkIdClientId?.toIntOrNull()?.let { it > 0 } == true &&
    suppliedVkIdClientSecret != null && suppliedVkIdClientSecret != "not-configured"
val effectiveVkIdClientId = suppliedVkIdClientId.takeIf { vkIdConfigured } ?: "0"
val effectiveVkIdClientSecret = suppliedVkIdClientSecret.takeIf { vkIdConfigured } ?: "not-configured"

if (!vkIdConfigured && (suppliedVkIdClientId != null || suppliedVkIdClientSecret != null)) {
    logger.warn("VK ID credentials are incomplete or invalid; VK ID authentication is disabled for this build.")
}

extra["vkIdConfigured"] = vkIdConfigured

vkidManifestPlaceholders {
    init(
        clientId = effectiveVkIdClientId,
        clientSecret = effectiveVkIdClientSecret,
    )
}

tasks.register<Delete>("Clean") {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            if (project.findProperty("enableComposeCompilerReports") == "true") {
                arrayOf("reports", "metrics").forEach {
                    freeCompilerArgs.add("-P")
                    freeCompilerArgs.add("plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=${project.layout.buildDirectory}/compose_metrics")
                }
            }
        }
    }
}
