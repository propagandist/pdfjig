package io.github.propagandist.pdfjig.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * CLAUDE.md の不変条件を機械的に検証する。
 *
 * <p>このモジュールはテストのみを持ち、成果物を生成しない。
 * pdf-core の {@code build.gradle.kts} に pdf-ai を書くことは禁じられているため
 * （INV-1）、全モジュールを見渡せる検証はここに置くしかない。
 *
 * <p><b>★ 禁止のルールは、対象が 1 つも無ければ黙って成功する。</b>
 * {@code noClasses().should().dependOnClassesThat()} は依存先が存在しなければ常に真になり、
 * <b>緑のまま何も守らなくなる</b>。ルールを足すときは、<b>それが実際に何かを見ていることを
 * 確かめる対のテストも足すこと</b>（下の {@code ...RuleHasSubject}）。
 * 2026-08-22 に POI を依存から外したとき、実際にこれが起きた。
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.propagandist.pdfjig");
    }

    /**
     * その前置詞で始まるパッケージへの依存の数。
     *
     * <p>ルールが空振りしていないことを確かめるために使う。
     */
    private static long dependenciesOn(String packagePrefix) {
        return classes.stream()
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .filter(dependency ->
                        dependency.getTargetClass().getPackageName().startsWith(packagePrefix))
                .count();
    }

    @Test
    @DisplayName("検証対象のクラスが実際に読み込まれている（空集合による見せかけの成功を防ぐ）")
    void importedClassesAreNotEmpty() {
        // 空のクラス集合に対しては、どんな禁止ルールも自動的に成功する。
        // クラスパスの設定ミスで「緑だが何も検証していない」状態になるのを防ぐ番人。
        //
        // ★ ただし、これが見ているのは pdfjig 自身の取り込みが空になる場合だけである。
        //   外部ライブラリを対象にするルール（PDFBox / POI）の空振りは、ここでは捕まらない——
        //   ライブラリを 1 つ依存から外しても、pdfjig のクラスは全部読み込まれるため。
        //   そちらは各ルールの隣に置いた ...RuleHasSubject が見る。
        for (String pkg : new String[] {".core.", ".ai.", ".cli.", ".desktop."}) {
            boolean found = classes.stream().anyMatch(c -> c.getName().contains("pdfjig" + pkg));
            assertTrue(found, "パッケージ " + pkg + " のクラスが 1 つも読み込まれていない");
        }
    }

    @Test
    @DisplayName("INV-1: pdf-core は pdf-ai に依存しない")
    void coreMustNotDependOnAi() {
        // 対象も依存先も pdfjig 自身のパッケージなので、
        // importedClassesAreNotEmpty が空振りを防いでいる。
        noClasses()
                .that()
                .resideInAPackage("..pdfjig.core..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..pdfjig.ai..")
                .because("依存の向きは一方通行である。崩れると設計全体が意味を失う（CLAUDE.md INV-1）")
                .check(classes);
    }

    @Test
    @DisplayName("PDFBox のルールが空振りしていない")
    void pdfboxRuleHasSubject() {
        assertTrue(
                dependenciesOn("org.apache.pdfbox") > 0, "PDFBox への依存が 1 つも無い。pdfboxMustNotLeakOutOfCore は緑でも何も守っていない");
    }

    @Test
    @DisplayName("PDFBox の型が pdf-core の外から見えない")
    void pdfboxMustNotLeakOutOfCore() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..pdfjig.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.apache.pdfbox..")
                .because("PDFBox への依存は pdf-core に閉じる（CLAUDE.md リソース管理）")
                .check(classes);
    }

    /**
     * Apache POI を pdf-core に閉じる。
     *
     * <p>2026-08-22 に POI の宣言を pdf-core から外した（{@code b8f6654}）。1 行も使っていないのに
     * 配布物の 14MB を占め、Dependabot の警告 2 件の出所もすべて POI 経由だったため。
     * <b>クラスパスに POI が無い以上、下のルールは常に真になる</b>——緑だが何も守っていない。
     *
     * <p><b>消していないのは、M1（Excel 出力）で戻せばそのまま効くからである。</b>
     * 止めていることは {@code @Disabled} により SKIPPED としてテスト出力に残る。
     */
    @Nested
    @DisplayName("Apache POI（M1 まで依存から外してある）")
    @Disabled("POI は 2026-08-22 に依存から外した（b8f6654）。M1 で戻したら、この @Disabled も外すこと")
    class Poi {

        @Test
        @DisplayName("POI のルールが空振りしていない")
        void poiRuleHasSubject() {
            assertTrue(dependenciesOn("org.apache.poi") > 0, "POI への依存が 1 つも無い。poiMustNotLeakOutOfCore は緑でも何も守っていない");
        }

        @Test
        @DisplayName("Apache POI の型が pdf-core の外から見えない")
        void poiMustNotLeakOutOfCore() {
            noClasses()
                    .that()
                    .resideOutsideOfPackage("..pdfjig.core..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("org.apache.poi..")
                    .because("POI への依存は pdf-core に閉じる（CLAUDE.md リソース管理）")
                    .check(classes);
        }
    }

    @Test
    @DisplayName("INV-2: pdf-ai はファイルシステムに触れない")
    void aiMustNotTouchTheFileSystem() {
        // pdf-ai が Path / File を扱えないなら、ファイルを書き出す経路は構造的に存在しない。
        //
        // 依存先は JDK なので空振りしない。対象（pdf-ai）の存在は
        // importedClassesAreNotEmpty が見ている。
        noClasses()
                .that()
                .resideInAPackage("..pdfjig.ai..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.nio.file..", "java.io..")
                .because("AI はファイルを変更しない。適用は pdf-core が行う（CLAUDE.md INV-2）")
                .check(classes);
    }
}
