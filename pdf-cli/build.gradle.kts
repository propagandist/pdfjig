plugins {
    application
}

dependencies {
    implementation(project(":pdf-core"))
    implementation(project(":pdf-ai"))
    implementation(libs.picocli)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "io.github.propagandist.pdfjig.cli.PdfjigCommand"
}
