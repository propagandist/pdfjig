import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.spotless.LineEnding

plugins {
    base
    alias(libs.plugins.spotless)
}

allprojects {
    group = "io.github.propagandist"

    // 版数はここだけを正とする。画面と CLI が出す版数もここから焼き込まれる（pdf-core の BuildInfo）。
    //
    // 無条件に代入してはならない。CI はタグから -Pversion=0.2.0 のように渡しており、
    // 代入で上書きすると、タグが何であろうとここに書いた版数のインストーラができる。
    if (version == Project.DEFAULT_VERSION) {
        version = "0.1.1-SNAPSHOT"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 整形（Spotless）
//
// 整形の判断はここが正であり、手で整えない。コミット前に ./gradlew spotlessApply を掛ける。
// spotlessCheck は check に自動で載るため、既存の ./gradlew build がそのまま検査になる。
// ワークフローを増やしていないのはそのためである。
//
// ★ 改行の判断は .gitattributes に委ねてある（下の lineEndings）。**片方だけ変えないこと。**
// ─────────────────────────────────────────────────────────────────────────────

// subprojects の中では libs アクセサが解決できないため、外で取り出す
// （pdf-desktop/build.gradle.kts の javafxVersion と同じ理由）。
val palantirVersion =
    libs.versions.palantir.java.format
        .get()
val ktlintVersion = libs.versions.ktlint.get()

/**
 * 改行は .gitattributes を正とする。
 *
 * 指定しないと git の設定へフォールバックし、Windows では CRLF 側に倒れて、
 * ubuntu ランナーと windows ランナーで判定が食い違う。
 *
 * FAST_ALLSAME は「対象が全部同じ改行」という前提で判定を省く最適化であり、Gradle で
 * 使えるのはこれだけである（GIT_ATTRIBUTES は例外になる。spotless#1274）。
 *
 * ★ この前提は「CRLF で残す *.bat / *.ps1 を Spotless の対象に入れていない」ことで
 *   成り立っている。**それらを下の target に足してはならない。** 混在を最初の 1 ファイルで
 *   代表してしまい、黙って改行を壊す。
 */
val lineEndingPolicy = LineEnding.GIT_ATTRIBUTES_FAST_ALLSAME

/** Java の整形。ソースセットを持つモジュールと、それを持たない root で共有する。 */
fun SpotlessExtension.javaFormat(configureTarget: com.diffplug.gradle.spotless.JavaExtension.() -> Unit = {}) {
    java {
        configureTarget()
        // 機械的な整形に馴染まない一角（罫線コメントや意図的な桁揃え）を
        // // spotless:off 〜 // spotless:on で退避できるようにしておく。
        toggleOffOn()
        removeUnusedImports()
        palantirJavaFormat(palantirVersion)
    }
}

spotless {
    lineEndings = lineEndingPolicy

    // ソースセットの外に居る単独の Java。sourceSets が無いので target を明示する。
    javaFormat {
        target("tools/**/*.java")
    }

    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(ktlintVersion)
    }

    // 整形はしない。行末の空白・末尾の改行・改行コードだけを見る。
    format("misc") {
        target(
            "*.md",
            "docs/**/*.md",
            ".github/**/*.yml",
            "gradle/libs.versions.toml",
            ".gitignore",
            ".gitattributes",
            ".editorconfig",
            "**/*.css",
        )
        // processResources が css を build/ へ写す。dist/ は jpackage の成果物。
        targetExclude("**/build/**", "dist/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")

    // target は書かない。既定が sourceSets の allJava であり、pdf-desktop の uiTest と
    // pdf-core の testFixtures も自動で対象に入る。
    extensions.configure<SpotlessExtension> {
        lineEndings = lineEndingPolicy
        javaFormat()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
