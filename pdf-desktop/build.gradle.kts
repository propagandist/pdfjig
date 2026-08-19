plugins {
    alias(libs.plugins.javafx)
    application
}

dependencies {
    implementation(project(":pdf-core"))
    implementation(project(":pdf-ai"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// javafx {} ブロックの中では libs アクセサが解決できないため、外で取り出す。
val javafxVersion = libs.versions.javafx.get()

javafx {
    version = javafxVersion
    modules = listOf("javafx.controls", "javafx.graphics")
}

application {
    mainClass = "io.github.propagandist.pdfjig.desktop.PdfjigApplication"
}
