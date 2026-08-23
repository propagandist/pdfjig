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

// ─────────────────────────────────────────────────────────────────────────────
// 画面を操作するテスト（uiTest）
//
// 通常の test とは別のソースセットにしてある。理由は 3 つある。
//
// 1. デスクトップセッションを要する。CI では windows ジョブでだけ走らせたく、
//    build に混ぜると ubuntu 側でも動いてしまう
// 2. 数秒〜十数秒かかる。数十ミリ秒で終わる既存のテストと同じタスクに置くと、
//    手元で test を回す速さが失われる
// 3. TestFX の依存を通常の test のクラスパスへ漏らさない
//
// ヘッドレス（Monocle）は使わない。org.testfx:openjfx-monocle:21.0.2 は JavaFX 21 で
// Window#_updateViewSize を実装しておらず、表示時に AbstractMethodError になる
// （TestFX/Monocle#97）。windows ランナーは対話セッションを持つのでそのまま動かせる。
// 配布対象と同じ描画経路を通る点でも、そちらのほうが検証として正直である。
//
// ★ ただしこれは「ランナーのデスクトップが、そのセッションの入力デスクトップである」ことに
//   依存している。TestFX は実入力（SendInput）を使い、それは入力デスクトップにしか届かない。
//   ここが崩れると、落ちるのではなく「クリックが 1 つも届かない」形で総崩れになる——
//   窓が見えているかどうかは関係がない（HANDOVER.md「uiTest を手元で隔離しようとして、
//   できないと分かった」に、その形で 26/29 が落ちた実測がある）。
// ─────────────────────────────────────────────────────────────────────────────

val uiTest = sourceSets.create("uiTest")

uiTest.compileClasspath += sourceSets.main.get().output
uiTest.runtimeClasspath += sourceSets.main.get().output

configurations["uiTestImplementation"].extendsFrom(configurations.implementation.get())
configurations["uiTestRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    "uiTestImplementation"(platform(libs.junit.bom))
    "uiTestImplementation"(libs.junit.jupiter)
    "uiTestImplementation"(libs.testfx.junit5) {
        // TestFX は AssertJ を runtime スコープで引くが、こちらは JUnit の assertEquals しか
        // 使っていない。持っているだけで CVE を見る対象が増える（CLAUDE.md「依存を放置しない」）。
        // 2026-08-22 時点で assertj-core 3.13.2 に XXE の警告（high）が出ていた。
        exclude(group = "org.assertj")
    }
    // TestFX は hamcrest を runtime スコープでしか宣言していない。NodeQuery の
    // シグネチャに Matcher が現れるため、コンパイルには明示的に要る。
    "uiTestImplementation"(libs.hamcrest)
    // テスト用の PDF はその場で作る（CLAUDE.md INV-6）。作法は pdf-core と共有する。
    "uiTestImplementation"(testFixtures(project(":pdf-core")))
    "uiTestRuntimeOnly"(libs.junit.platform.launcher)
}

val uiTestTask =
    tasks.register<Test>("uiTest") {
        description = "画面を操作するテスト。デスクトップセッションを要するため build には含めない。"
        group = "verification"

        testClassesDirs = uiTest.output.classesDirs
        classpath = uiTest.runtimeClasspath

        // 画面を掴むテストが 2 つ同時に動くと、片方のクリックがもう片方の窓へ行く。
        maxParallelForks = 1

        // ★ 開始も出す。完了したものしか出さないと、ハングしたときに「どのテストで止まったか」が
        //   ログに残らず、最後に PASSED した次を推測するしかない。2026-08-22 にそこで誤診した
        //   （古いブランチで ReorderUiTest が除外されずに走っていただけなのを、JUnit の版差だと
        //   読んだ）。ジョブ側に出るのは "The operation was canceled." の 1 行だけである。
        //
        //   通常の test には足さない。あちらは 121 本あって数十ミリ秒で終わる。
        //   ここは 25 本で、1 本が数分に伸びうる側である。
        testLogging {
            setEvents(listOf("started", "passed", "skipped", "failed"))
        }

        shouldRunAfter(tasks.named("test"))
    }

// ─────────────────────────────────────────────────────────────────────────────
// Windows 向けパッケージング（HANDOVER.md Phase 4）
//
// 構成は installDist → jlink → jpackage の 3 段。
//
// アプリ本体は非モジュールのままクラスパスで動かし、JavaFX だけをランタイムイメージの
// 名前付きモジュールとして解決させる。PDFBox / POI は module-info を持つが、クラスパスに
// 置く限り無視されるため、モジュール化の判断はここでは要らない。
// ─────────────────────────────────────────────────────────────────────────────

/** 表示名。CLI のコマンド名 pdfjig とは別に、画面とインストーラではこちらを使う。 */
val appName = "PDFjig"

val appVendor = "PROPAGANDIST CORPORATION"

/** ランチャー exe のバージョン情報に入る説明。エクスプローラのプロパティとタスクマネージャに出る。 */
val appDescription = "PDF を綴じ、解き、取り出すためのデスクトップユーティリティ"

/**
 * インストーラのメタデータに入る説明。
 *
 * MSI のサマリ情報ストリームはコードページ 1033（英語）で書かれるため、日本語を渡すと
 * 最初の非 ASCII 文字以降が黙って落ちて "PDF " だけが残る。切れた文字列を配るより、
 * 最初から ASCII で書いておくほうが正直である。
 * 画面に出る文言（ProductName / Manufacturer / ダイアログ）は日本語のまま影響を受けない。
 */
val installerDescription = "PDF utility - bind, split, rotate and extract pages"

/**
 * MSI の ProductVersion は数値のみ（major.minor.build）でなければならず、
 * 0.1.0-SNAPSHOT はそのままでは渡せない。CI はタグから -Pversion=0.1.0 を渡す。
 */
val appVersion = version.toString().removeSuffix("-SNAPSHOT")

/**
 * MSI の UpgradeCode。**一度決めたら二度と変えてはならない。**
 * 変えると次版のインストールがアップグレードではなく新規扱いになり、旧版が残る。
 *
 * MSI（マシン単位）と EXE（ユーザー単位）で同じ値を使う。jpackage は ProductCode を
 * 名前とバージョンから決めており、両者で同一になる。ProductCode が同じなのに UpgradeCode だけ
 * 違うと「同じ製品なのに別系統」という辻褄の合わないメタデータになるため揃えてある。
 *
 * この結果、MSI と EXE を同時に入れることはできない。片方を消してからもう片方を入れる。
 * 二重に入って両方がスタートメニューに並ぶよりは、入らないほうが説明できる。
 */
val upgradeUuid = "3210BCE4-3635-4EFC-8EC1-DC77881091BB"

/**
 * jlink に渡すルートモジュール。
 *
 * 導出は以下で行い、結果をここに固定してある。自動導出のままにすると、依存が変わったときに
 * 気づかないまま壊れる。
 *
 *   jdeps --multi-release 21 --ignore-missing-deps --print-module-deps \
 *         --module-path . -cp "<lib の jar>" <javafx 以外の jar>
 *
 * jdeps が挙げないぶんを手で足してある。
 *
 * - java.desktop / java.xml — 推移で入るが、BufferedImage と XML は直接使っているので明示する。
 *   javafx.swing 経由の推移に頼ると、依存が変わったとき黙って消える
 * - jdk.localedata — 日本語ロケール。サービス経由の読み込みなので jdeps では検出できない。
 *   外すと jlink 後に ja が消え、日付・数値の書式が英語圏のものに化ける
 *
 * ★ jdk.unsupported（POI の sun.misc.Unsafe）と jdk.xml.dom（xmlbeans）は
 *   2026-08-22 に外した。POI の宣言そのものを M1 まで外したため
 *   （理由は pdf-core/build.gradle.kts）。**POI を戻すときは、この 2 つも戻すこと。**
 */
val runtimeModules =
    listOf(
        "java.base",
        "java.desktop",
        "java.logging",
        "java.xml",
        "java.security.jgss",
        "java.xml.crypto",
        "jdk.localedata",
        "javafx.base",
        "javafx.graphics",
        "javafx.controls",
        "javafx.swing",
    )

/** 収録するロケール。UI は日本語であり、それ以外の環境では英語にフォールバックさせる。 */
val includeLocales = "en,ja"

val javafxJmods = configurations.dependencyScope("javafxJmods")

val javafxJmodsPath =
    configurations.resolvable("javafxJmodsPath") {
        extendsFrom(javafxJmods.get())
    }

dependencies {
    javafxJmods("org.openjfx.jmods:jmods:$javafxVersion@zip")
}

val javaToolchains = extensions.getByType<JavaToolchainService>()

val toolchainHome =
    javaToolchains
        .launcherFor { languageVersion = JavaLanguageVersion.of(21) }
        .map { it.metadata.installationPath }

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

fun jdkTool(name: String): String =
    toolchainHome
        .get()
        .file("bin/" + if (isWindows) "$name.exe" else name)
        .asFile.absolutePath

val packagingDir = layout.buildDirectory.dir("jpackage")

val jmodsDir = layout.buildDirectory.dir("javafx-jmods")

val inputDir = packagingDir.map { it.dir("input") }

val runtimeDir = packagingDir.map { it.dir("runtime") }

val imageDir = packagingDir.map { it.dir("image") }

val distDir = rootProject.layout.projectDirectory.dir("dist")

val licenseFile = rootProject.layout.projectDirectory.file("LICENSE")

val iconFile = layout.projectDirectory.file("packaging/pdfjig.ico")

/**
 * 出力先を空にする。
 *
 * <p>jlink も jpackage も出力先が残っていると失敗する。消せなかったことを黙って見過ごすと、
 * 前回の成果物が残ったまま「既に存在します」で落ち、原因が読めなくなる。
 * 手元では起動中のアプリがファイルを掴んでいることが実際にある。
 */
fun clearDirectory(target: File) {
    if (target.exists() && !target.deleteRecursively()) {
        throw GradleException(
            "出力先を消せませんでした: $target" +
                "（このディレクトリのファイルを掴んでいるプロセスがないか確認すること）",
        )
    }
}

val unpackJavafxJmods =
    tasks.register<Sync>("unpackJavafxJmods") {
        description = "JavaFX の jmods を展開する。"
        group = "distribution"

        from(javafxJmodsPath.flatMap { it.elements }.map { zipTree(it.single()) }) {
            // zip の中は javafx-jmods-<version>/*.jmod という 1 階層。jlink の --module-path に
            // そのまま渡したいので剥がす。
            eachFile {
                relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
            }
        }
        into(jmodsDir)
        includeEmptyDirs = false
    }

val jpackageInput =
    tasks.register<Sync>("jpackageInput") {
        description = "アプリイメージに入れる jar を集める。"
        group = "distribution"

        dependsOn(tasks.named("installDist"))
        from(layout.buildDirectory.dir("install/pdf-desktop/lib")) {
            // JavaFX の jar は入れない。ランタイムイメージ側に名前付きモジュールとして入るため、
            // クラスパスにも同じパッケージが居ると split package になって壊れる。
            exclude("javafx-*.jar")
        }
        into(inputDir)
    }

val jlinkRuntime =
    tasks.register<Exec>("jlinkRuntime") {
        description = "JavaFX を含むランタイムイメージを組む。"
        group = "distribution"

        dependsOn(unpackJavafxJmods)
        inputs.dir(jmodsDir)
        inputs.property("modules", runtimeModules)
        inputs.property("locales", includeLocales)
        inputs.property("javafxVersion", javafxVersion)
        outputs.dir(runtimeDir)

        doFirst {
            // jlink は出力先が既にあると失敗する。
            clearDirectory(runtimeDir.get().asFile)

            val modulePath =
                listOf(
                    toolchainHome
                        .get()
                        .dir("jmods")
                        .asFile.absolutePath,
                    jmodsDir.get().asFile.absolutePath,
                ).joinToString(File.pathSeparator)

            commandLine(
                jdkTool("jlink"),
                "--module-path",
                modulePath,
                "--add-modules",
                runtimeModules.joinToString(","),
                "--include-locales=$includeLocales",
                "--output",
                runtimeDir.get().asFile.absolutePath,
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress=zip-6",
                // ★ 焼き込んだ JavaFX の版を release ファイルへ残す。
                //   jlink が既定で書くのは JAVA_VERSION と MODULES だけで、JavaFX は
                //   名前付きモジュールとしてイメージの中にいるのに版が分からない。
                //   配布物は再リリースするまで利用者の PC に残るので、CVE が出たときに
                //   「配ったものが何だったか」を成果物の側から辿れないと直しようがない。
                "--release-info",
                "add:JAVAFX_VERSION=$javafxVersion",
            )
        }
    }

val jpackageAppImage =
    tasks.register<Exec>("jpackageAppImage") {
        description = "アプリイメージを作る。"
        group = "distribution"

        dependsOn(jpackageInput, jlinkRuntime)
        inputs.dir(inputDir)
        inputs.dir(runtimeDir)
        inputs.file(iconFile)
        inputs.property("appVersion", appVersion)
        outputs.dir(imageDir)

        val mainJarName = tasks.jar.flatMap { it.archiveFileName }
        val mainClassName = application.mainClass

        doFirst {
            clearDirectory(imageDir.get().asFile)

            commandLine(
                jdkTool("jpackage"),
                "--type",
                "app-image",
                "--name",
                appName,
                "--app-version",
                appVersion,
                "--vendor",
                appVendor,
                "--copyright",
                "Copyright 2026 $appVendor",
                "--description",
                appDescription,
                "--icon",
                iconFile.asFile.absolutePath,
                "--runtime-image",
                runtimeDir.get().asFile.absolutePath,
                "--input",
                inputDir.get().asFile.absolutePath,
                "--main-jar",
                mainJarName.get(),
                "--main-class",
                mainClassName.get(),
                // アプリはクラスパスに居るため、JavaFX のモジュールは明示的に解決させる必要がある。
                // これが無いと "JavaFX runtime components are missing" で起動しない。
                "--java-options",
                "--add-modules=javafx.controls,javafx.swing",
                "--dest",
                imageDir.get().asFile.absolutePath,
            )
        }
    }

/** アプリイメージから MSI / EXE を作る共通部分。 */
fun Exec.jpackageInstaller(
    type: String,
    perUser: Boolean,
) {
    dependsOn(jpackageAppImage)
    inputs.dir(imageDir)
    inputs.property("appVersion", appVersion)
    outputs.file(distDir.file("$appName-$appVersion.$type"))

    doFirst {
        val target = distDir.file("$appName-$appVersion.$type").asFile
        target.parentFile.mkdirs()
        target.delete()

        val arguments =
            mutableListOf(
                jdkTool("jpackage"),
                "--type",
                type,
                "--app-image",
                imageDir
                    .get()
                    .dir(appName)
                    .asFile.absolutePath,
                "--name",
                appName,
                "--app-version",
                appVersion,
                "--vendor",
                appVendor,
                "--copyright",
                "Copyright 2026 $appVendor",
                "--description",
                installerDescription,
                "--license-file",
                licenseFile.asFile.absolutePath,
                "--win-menu",
                "--win-menu-group",
                appName,
                "--win-shortcut",
                "--win-upgrade-uuid",
                upgradeUuid,
                "--dest",
                distDir.asFile.absolutePath,
            )
        if (perUser) {
            arguments += "--win-per-user-install"
        } else {
            // マシン単位のときだけ入れ先を選ばせる。ユーザー単位は %LOCALAPPDATA% 固定でよい。
            arguments += "--win-dir-chooser"
        }

        commandLine(arguments)
    }
}

val packageMsi =
    tasks.register<Exec>("packageMsi") {
        description = "マシン単位でインストールする MSI を作る。情報システム部門による一括配布向け。"
        group = "distribution"

        jpackageInstaller("msi", perUser = false)
    }

val packageExe =
    tasks.register<Exec>("packageExe") {
        description = "ユーザー単位でインストールする EXE を作る。管理者権限が要らない。"
        group = "distribution"

        jpackageInstaller("exe", perUser = true)
    }

val packageZip =
    tasks.register<Zip>("packageZip") {
        description = "インストール不要で動く ZIP を作る。インストーラを使えない環境向け。"
        group = "distribution"

        dependsOn(jpackageAppImage)
        from(imageDir)
        destinationDirectory = distDir
        archiveFileName = "$appName-$appVersion-win-x64.zip"
    }

tasks.register("packageAll") {
    description = "MSI / EXE / ZIP をまとめて作る。"
    group = "distribution"

    dependsOn(packageMsi, packageExe, packageZip)
}
