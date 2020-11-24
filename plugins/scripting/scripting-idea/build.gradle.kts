
description = "Kotlin Scripting IDEA Plugin"

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(project(":kotlin-scripting-intellij"))
    compileOnly(project(":idea:idea-gradle"))
    compileOnly(project(":idea:idea-core"))
    compileOnly(intellijDep())
    compileOnly(intellijDep("gradle"))
    compileOnly(project(":idea:kotlin-gradle-tooling"))
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}

runtimeJar()

