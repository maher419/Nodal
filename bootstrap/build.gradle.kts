plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("nodal.bootstrap.MainKt")
}
