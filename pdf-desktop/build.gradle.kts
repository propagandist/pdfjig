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
 * - jdk.unsupported — POI が sun.misc.Unsafe を使う
 * - jdk.xml.dom — xmlbeans が要求する
 * - jdk.localedata — 日本語ロケール。サービス経由の読み込みなので jdeps では検出できない。
 *   外すと jlink 後に ja が消え、日付・数値の書式が英語圏のものに化ける
 */
val runtimeModules = listOf(
    "java.base",
    "java.desktop",
    "java.logging",
    "java.xml",
    "java.security.jgss",
    "java.xml.crypto",
    "jdk.unsupported",
    "jdk.xml.dom",
    "jdk.localedata",
    "javafx.base",
    "javafx.graphics",
    "javafx.controls",
    "javafx.swing",
)

/** 収録するロケール。UI は日本語であり、それ以外の環境では英語にフォールバックさせる。 */
val includeLocales = "en,ja"

val javafxJmods = configurations.dependencyScope("javafxJmods")

val javafxJmodsPath = configurations.resolvable("javafxJmodsPath") {
    extendsFrom(javafxJmods.get())
}

dependencies {
    javafxJmods("org.openjfx.jmods:jmods:$javafxVersion@zip")
}

val javaToolchains = extensions.getByType<JavaToolchainService>()

val toolchainHome = javaToolchains
    .launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    .map { it.metadata.installationPath }

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

fun jdkTool(name: String): String =
    toolchainHome.get().file("bin/" + if (isWindows) "$name.exe" else name).asFile.absolutePath

val packagingDir = layout.buildDirectory.dir("jpackage")

val jmodsDir = layout.buildDirectory.dir("javafx-jmods")

val inputDir = packagingDir.map { it.dir("input") }

val runtimeDir = packagingDir.map { it.dir("runtime") }

val imageDir = packagingDir.map { it.dir("image") }

val distDir = rootProject.layout.projectDirectory.dir("dist")

val licenseFile = rootProject.layout.projectDirectory.file("LICENSE")

val iconFile = layout.projectDirectory.file("packaging/pdfjig.ico")

val unpackJavafxJmods = tasks.register<Sync>("unpackJavafxJmods") {
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

val jpackageInput = tasks.register<Sync>("jpackageInput") {
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

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    description = "JavaFX を含むランタイムイメージを組む。"
    group = "distribution"

    dependsOn(unpackJavafxJmods)
    inputs.dir(jmodsDir)
    inputs.property("modules", runtimeModules)
    inputs.property("locales", includeLocales)
    outputs.dir(runtimeDir)

    doFirst {
        // jlink は出力先が既にあると失敗する。
        runtimeDir.get().asFile.deleteRecursively()

        val modulePath = listOf(
            toolchainHome.get().dir("jmods").asFile.absolutePath,
            jmodsDir.get().asFile.absolutePath,
        ).joinToString(File.pathSeparator)

        commandLine(
            jdkTool("jlink"),
            "--module-path", modulePath,
            "--add-modules", runtimeModules.joinToString(","),
            "--include-locales=$includeLocales",
            "--output", runtimeDir.get().asFile.absolutePath,
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress=zip-6",
        )
    }
}

val jpackageAppImage = tasks.register<Exec>("jpackageAppImage") {
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
        imageDir.get().asFile.deleteRecursively()

        commandLine(
            jdkTool("jpackage"),
            "--type", "app-image",
            "--name", appName,
            "--app-version", appVersion,
            "--vendor", appVendor,
            "--copyright", "Copyright 2026 $appVendor",
            "--description", appDescription,
            "--icon", iconFile.asFile.absolutePath,
            "--runtime-image", runtimeDir.get().asFile.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", mainJarName.get(),
            "--main-class", mainClassName.get(),
            // アプリはクラスパスに居るため、JavaFX のモジュールは明示的に解決させる必要がある。
            // これが無いと "JavaFX runtime components are missing" で起動しない。
            "--java-options", "--add-modules=javafx.controls,javafx.swing",
            "--dest", imageDir.get().asFile.absolutePath,
        )
    }
}

/** アプリイメージから MSI / EXE を作る共通部分。 */
fun Exec.jpackageInstaller(type: String, perUser: Boolean) {
    dependsOn(jpackageAppImage)
    inputs.dir(imageDir)
    inputs.property("appVersion", appVersion)
    outputs.file(distDir.file("$appName-$appVersion.$type"))

    doFirst {
        val target = distDir.file("$appName-$appVersion.$type").asFile
        target.parentFile.mkdirs()
        target.delete()

        val arguments = mutableListOf(
            jdkTool("jpackage"),
            "--type", type,
            "--app-image", imageDir.get().dir(appName).asFile.absolutePath,
            "--name", appName,
            "--app-version", appVersion,
            "--vendor", appVendor,
            "--copyright", "Copyright 2026 $appVendor",
            "--description", installerDescription,
            "--license-file", licenseFile.asFile.absolutePath,
            "--win-menu",
            "--win-menu-group", appName,
            "--win-shortcut",
            "--win-upgrade-uuid", upgradeUuid,
            "--dest", distDir.asFile.absolutePath,
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

val packageMsi = tasks.register<Exec>("packageMsi") {
    description = "マシン単位でインストールする MSI を作る。情報システム部門による一括配布向け。"
    group = "distribution"

    jpackageInstaller("msi", perUser = false)
}

val packageExe = tasks.register<Exec>("packageExe") {
    description = "ユーザー単位でインストールする EXE を作る。管理者権限が要らない。"
    group = "distribution"

    jpackageInstaller("exe", perUser = true)
}

val packageZip = tasks.register<Zip>("packageZip") {
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
