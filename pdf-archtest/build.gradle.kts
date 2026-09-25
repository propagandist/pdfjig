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

// AgentRulesTest は .claude/rules/ と、それを指す参照と、glob が当たるファイルを読む（#168）。
// どれもクラスパスの外なので、入力に宣言しないとルールだけを直したときに UP-TO-DATE で飛ばされる。
// 入力は追跡しうるファイル全体になるので、ArchUnit を巻き込まないよう別のタスクに分けた。
val agentRulesTest by tasks.registering(Test::class) {
    description = "Checks .claude/rules/ frontmatter and references to rule sections."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*.AgentRulesTest") }
    inputs
        .files(
            rootProject.fileTree(rootDir) {
                // AgentRulesTest は git ls-files しか見ない。ここは追跡しないものの代表だけを外す。
                exclude("**/build/**", "**/bin/**", "**/.gradle/**", ".git/**", ".claude/worktrees/**", "tmp/**", "dist/**")
            },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("repositoryFiles")
    // 見ているのは git ls-files なので、add / rm --cached だけの変更でも走り直させる。
    inputs.files(rootProject.file(".git/index")).withPropertyName("gitIndex")
}

tasks.test {
    filter { excludeTestsMatching("*.AgentRulesTest") }
}

tasks.check {
    dependsOn(agentRulesTest)
}
