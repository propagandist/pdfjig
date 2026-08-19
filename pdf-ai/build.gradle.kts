// LLM プロバイダの抽象化。すべての公開メソッドは Proposal<T> を返し、ファイルを変更しない
// （CLAUDE.md INV-2）。
dependencies {
    api(project(":pdf-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
