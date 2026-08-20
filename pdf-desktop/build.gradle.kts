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
    // javafx.swing は SwingFXUtils のためだけに要る。pdf-core は PDFBox の型を外に
    // 出せないため、描画結果を JDK 標準の BufferedImage で受け取り、ここで JavaFX の
    // Image に変換する。
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.swing")
}

application {
    mainClass = "io.github.propagandist.pdfjig.desktop.PdfjigApplication"
}
