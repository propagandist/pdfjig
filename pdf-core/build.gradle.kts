// 確定的処理のみ。外部ネットワーク通信を一切行わない。
//
// CLAUDE.md INV-1: このファイルに pdf-ai が現れることは絶対にない。
// PDFBox / POI / tabula への依存はこのモジュールに閉じるため、すべて implementation で宣言する
// （api にすると PDDocument 等の型が下流モジュールから見えてしまう）。
dependencies {
    implementation(libs.pdfbox)
    implementation(libs.poi.ooxml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
