import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("kapt") version "2.2.20"
    id("com.gradleup.shadow") version "9.4.1"
    id("com.diffplug.spotless") version "8.4.0"
}

group = "gg.grounds"

version = (project.findProperty("versionOverride") as? String) ?: "local-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
}

kotlin { jvmToolchain(25) }

java {
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
}

tasks.withType<JavaCompile>().configureEach { options.release.set(24) }

tasks.withType<KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_24) }

tasks.test { useJUnitPlatform() }

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set(rootProject.name)
    archiveClassifier.set("all")
    archiveVersion.set(project.version.toString())
}

tasks.named("build") { dependsOn("shadowJar") }

tasks.named("jar") { enabled = false }

spotless {
    kotlin {
        ktfmt().googleStyle().configure {
            it.setBlockIndent(4)
            it.setContinuationIndent(4)
        }
        targetExclude("**/build/**")
    }
    kotlinGradle {
        ktfmt().googleStyle().configure {
            it.setBlockIndent(4)
            it.setContinuationIndent(4)
        }
        targetExclude("**/build/**")
    }
}
