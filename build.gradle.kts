plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.gradleup.shadow") version "9.0.0-beta4"
}

group = "dev.martin"
version = "0.1.0"

repositories {
    mavenCentral()
}

val kotlinVersion = "2.0.21"

dependencies {
    // Kotlin compiler for parsing and analysis
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")

    // CLI framework
    implementation("com.github.ajalt.clikt:clikt:5.0.1")

    // JSON output
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // SQLite for metrics storage
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("io.strikt:strikt-core:0.34.1")
}

application {
    mainClass.set("martin.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        resources {
            srcDir(projectDir)
            include("SKILLS.md")
        }
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}
