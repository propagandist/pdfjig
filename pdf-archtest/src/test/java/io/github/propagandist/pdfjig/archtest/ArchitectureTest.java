package io.github.propagandist.pdfjig.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CLAUDE.md の不変条件を機械的に検証する。
 *
 * <p>このモジュールはテストのみを持ち、成果物を生成しない。
 * pdf-core の {@code build.gradle.kts} に pdf-ai を書くことは禁じられているため
 * （INV-1）、全モジュールを見渡せる検証はここに置くしかない。
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.propagandist.pdfjig");
    }

    @Test
    @DisplayName("検証対象のクラスが実際に読み込まれている（空集合による見せかけの成功を防ぐ）")
    void importedClassesAreNotEmpty() {
        // 空のクラス集合に対しては、どんな禁止ルールも自動的に成功する。
        // クラスパスの設定ミスで「緑だが何も検証していない」状態になるのを防ぐ番人。
        for (String pkg : new String[] {".core.", ".ai.", ".cli.", ".desktop."}) {
            boolean found = classes.stream().anyMatch(c -> c.getName().contains("pdfjig" + pkg));
            assertTrue(found, "パッケージ " + pkg + " のクラスが 1 つも読み込まれていない");
        }
    }

    @Test
    @DisplayName("INV-1: pdf-core は pdf-ai に依存しない")
    void coreMustNotDependOnAi() {
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

    @Test
    @DisplayName("INV-2: pdf-ai はファイルシステムに触れない")
    void aiMustNotTouchTheFileSystem() {
        // pdf-ai が Path / File を扱えないなら、ファイルを書き出す経路は構造的に存在しない。
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
