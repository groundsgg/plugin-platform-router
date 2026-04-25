import com.github.gmazzo.buildconfig.BuildConfigExtension
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("kapt") version "2.2.20"
    id("com.gradleup.shadow") version "9.4.1"
    id("com.diffplug.spotless") version "8.4.0"
    id("com.github.gmazzo.buildconfig") version "6.0.9"
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
    testImplementation("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    implementation("org.tomlj:tomlj:1.1.1")
    implementation("io.fabric8:kubernetes-client:7.4.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("io.fabric8:kubernetes-server-mock:7.4.0")
    testImplementation("io.fabric8:mockwebserver:0.2.18")
    testImplementation("org.awaitility:awaitility:4.2.2")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
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

configure<BuildConfigExtension> {
    className("BuildInfo")
    packageName("gg.grounds.router")
    useKotlinOutput()
    buildConfigField("String", "VERSION", "\"${project.version}\"")
}

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
