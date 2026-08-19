rootProject.name = "pdfjig"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(
    "pdf-core",
    "pdf-ai",
    "pdf-cli",
    "pdf-desktop",
    // 依存方向（CLAUDE.md INV-1）の検証専用。テストのみを持ち、成果物を生成しない。
    // pdf-core の build.gradle.kts に pdf-ai を書けないため、検証は独立モジュールで行う。
    "pdf-archtest",
)
