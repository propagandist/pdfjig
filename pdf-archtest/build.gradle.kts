// 依存方向（CLAUDE.md INV-1）を機械的に検証するためだけのモジュール。
// pdf-core の build.gradle.kts に pdf-ai を書くことは禁じられているため、
// 全モジュールを見渡せる検証はここに置くしかない。成果物は生成しない。
dependencies {
    testImplementation(project(":pdf-core"))
    testImplementation(project(":pdf-ai"))
    testImplementation(project(":pdf-cli"))
    testImplementation(project(":pdf-desktop"))

    testImplementation(libs.archunit.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
