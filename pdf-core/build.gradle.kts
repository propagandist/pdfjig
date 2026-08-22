plugins {
    // テスト用の PDF を作る TestPdfs を pdf-desktop の画面テストからも使う。
    // リポジトリに PDF を置けない（CLAUDE.md INV-6）以上、フィクスチャは常に生成する必要があり、
    // その作法をモジュールごとに書くと必ず食い違う。
    `java-test-fixtures`
}

// 確定的処理のみ。外部ネットワーク通信を一切行わない。
//
// CLAUDE.md INV-1: このファイルに pdf-ai が現れることは絶対にない。
// PDFBox / POI / tabula への依存はこのモジュールに閉じるため、すべて implementation で宣言する
// （api にすると PDDocument 等の型が下流モジュールから見えてしまう）。
dependencies {
    implementation(libs.pdfbox)
    implementation(libs.poi.ooxml)

    // api にしない。TestPdfs が返すのは Path だけであり、PDDocument を下流へ見せる必要はない。
    testFixturesImplementation(libs.pdfbox)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// 版数はルートの build.gradle.kts の version が唯一の正であり、それを build.properties に焼き込む。
// 読み出しは BuildInfo が行う。
val projectVersion = version.toString()

tasks.processResources {
    // 版数が変わったら作り直させる。入力に挙げないと up-to-date と判定され、古い値が残る。
    inputs.property("version", projectVersion)

    // expand をリソース全体に掛けない。$ を含む他のリソースやバイナリを壊す。
    filesMatching("io/github/propagandist/pdfjig/core/build.properties") {
        expand("version" to projectVersion)
    }
}
