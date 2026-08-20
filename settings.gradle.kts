rootProject.name = "pdfjig"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()

        // JavaFX の jmods は Maven Central に無く（あるのは jar だけ）、Gluon が zip で配っている。
        // jpackage に渡すランタイムイメージを jlink で組むには jmods が要るため、ここから取る。
        //
        // JavaFX 同梱の JDK（Liberica Full 等）を使えば jmods は手に入るが、それだと jpackage を
        // 回す全員にその JDK の導入を強いることになる。バージョンを libs.versions.toml の javafx に
        // 固定して取るこの形なら、手元と CI が同じ JDK でなくても同じ成果物になる。
        ivy("https://download2.gluonhq.com/openjfx/") {
            patternLayout {
                artifact("[revision]/openjfx-[revision]_windows-x64_bin-[module].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("org.openjfx.jmods", "jmods") }
        }
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
