package io.github.propagandist.pdfjig.archtest;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    /** ログの書き手。{@code java.util.logging} を触ってよい唯一のクラスである。 */
    private static final String LOGS = "io.github.propagandist.pdfjig.desktop.Logs";

    /** 外へ出る唯一の経路。pdf-desktop で {@code java.net} の通信 API を触ってよい唯一のクラスである。 */
    private static final String UPDATE_CHECK = "io.github.propagandist.pdfjig.desktop.UpdateCheck";

    /** 書き出しの実体。JavaFX の型を持たず、画面を持たずに読める側である。 */
    private static final String DOCUMENT_WRITER = "io.github.propagandist.pdfjig.desktop.DocumentWriter";

    /** 既にあるファイルを置き換える書き出しを頼んでよい唯一のクラス。 */
    private static final String MAIN_WINDOW = "io.github.propagandist.pdfjig.desktop.MainWindow";

    /** 書き出しの作業場所。控えを抱えたまま失敗したことを利用者へ伝える関門を持つ。 */
    private static final String OUTPUT_WORKSPACE = "io.github.propagandist.pdfjig.desktop.OutputWorkspace";

    /** 控えの在り処を運ぶ型。作ってよいのは、抱えているかどうかを知っている作業場所だけである。 */
    private static final String KEPT_EXCEPTION = "io.github.propagandist.pdfjig.desktop.ReplacedFileKeptException";

    /** 消し損ねた平文の在り処を運ぶ型（#184）。作ってよいのは作業場所だけである。 */
    private static final String PLAINTEXT_EXCEPTION = "io.github.propagandist.pdfjig.desktop.PlaintextLeftException";

    /** PDFBox のパッケージ。この前置詞で始まる型を呼ぶことが、向こうのコードを走らせることである。 */
    private static final String PDFBOX = "org.apache.pdfbox";

    /** パスワードの持ち主。素の {@code char[]} を持ってよい唯一の型である。 */
    private static final String PASSWORD = "io.github.propagandist.pdfjig.core.Password";

    /** 開くときの {@code String} 境界。PDFBox の {@code Loader} が {@code String} しか受けない。 */
    private static final String PDF_DOCUMENT = "io.github.propagandist.pdfjig.core.PdfDocument";

    /** 掛けるときの {@code String} 境界。PDFBox の {@code StandardProtectionPolicy} も同じである。 */
    private static final String STANDARD_PROTECTION = "io.github.propagandist.pdfjig.core.StandardProtection";

    /** PDFBox を走らせる仕事を包む、ただ 1 つの場所。 */
    private static final String PDFBOX_GUARD = "io.github.propagandist.pdfjig.core.PdfBoxGuard";

    /** pdf-core の書き出しがすべて通る。#219 の規則が見る。 */
    private static final String DURABLE_SAVE = "io.github.propagandist.pdfjig.core.DurableSave";

    /** PDFBox の文書。書き出しの入口 {@code save} を持つ。 */
    private static final String PD_DOCUMENT = "org.apache.pdfbox.pdmodel.PDDocument";

    /** 警告の受け口。動かしてよいのは {@code Warnings} だけである。 */
    private static final String WARNING_LISTENER = "io.github.propagandist.pdfjig.core.WarningListener";

    /** 警告を溜めて、包みを抜けてから流す型。 */
    private static final String WARNINGS = "io.github.propagandist.pdfjig.core.Warnings";

    /** 包みと受け口の規律が掛かる範囲。{@code pdf-desktop} の受け手は対象外である。 */
    private static final String CORE_PACKAGE = "io.github.propagandist.pdfjig.core";

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
        assertTrue(dependenciesOn(PDFBOX) > 0, "PDFBox への依存が 1 つも無い。pdfboxMustNotLeakOutOfCore は緑でも何も守っていない");
    }

    @Test
    @DisplayName("PDFBox の型が pdf-core の外から見えない")
    void pdfboxMustNotLeakOutOfCore() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..pdfjig.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(PDFBOX + "..")
                .because("PDFBox への依存は pdf-core に閉じる（.claude/rules/modules-and-invariants.md「モジュール別の責務」）")
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
                    .because("POI への依存は pdf-core に閉じる（.claude/rules/modules-and-invariants.md「モジュール別の責務」）")
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

    /**
     * pdf-core から外へ出る経路を塞ぐ。
     *
     * <p><b>対の {@code ...RuleHasSubject} は足さない。</b> あれが要るのは、依存から外せる
     * ライブラリを対象にするときである（PDFBox / POI）。ここでの依存先は JDK の
     * {@code java.net} / {@code javax.net} であり、<b>クラスパスから外れることがない。</b>
     * 対象（pdf-core）が読み込まれていることは {@link #importedClassesAreNotEmpty} が見ている。
     * {@link #aiMustNotTouchTheFileSystem} が対を持たないのと同じ理由である。
     *
     * <p><b>★ 縛るのは pdf-core だけである。</b> pdf-ai は通信するのが仕事であり
     * （{@code AnthropicProvider}）、pdf-cli はそれを呼ぶ。
     * {@code .claude/rules/modules-and-invariants.md}「モジュール別の責務」が「一切行わない」と書いているのは pdf-core だけなので、
     * <b>規約が言っている以上のことを機械に守らせない。</b>
     *
     * <p><b>★ 訂正（#72、2026-08-30）——外への通信が初めて入るのは pdf-ai ではなかった。</b>
     * pdf-desktop の {@code UpdateCheck} が先に入り、pdf-ai はまだ実装が無い。
     * <b>そちらは経路を 1 つに絞る形で別に縛ってある</b>（{@link
     * #desktopReachesTheNetworkOnlyThroughUpdateCheck}）——このルールは何も変わっていない。
     *
     * <p><b>★ {@code java.net} を丸ごと禁じない。</b> {@link java.net.URI} と符号化の道具は
     * <b>識別子を扱うだけで通信しない</b>。{@code Path#toUri} を呼ぶような真っ当な変更まで
     * 赤にすると、直す方法がルールを緩めることしか無くなり、上の「規約が言っている以上のことを
     * 機械に守らせない」と衝突する。<b>除外する側を列挙する</b>ので、{@code java.net} に
     * 新しい通信の入口が増えたときは自動的に禁止側へ入る。
     * {@link java.net.URL} は除外しない——{@code openStream} を持つ、それ自体が入口である。
     *
     * <p><b>★ 見えない経路が 1 つある。</b> pdf-core にサードパーティの HTTP クライアントを
     * 足すと、{@code java.net} を直接参照しないまま通信できてしまい、<b>このルールは緑のままになる。</b>
     * 塞ぐには pdf-core の宣言済み依存そのものを見る必要があり、それはこのルールの範囲外である
     * （#90）。<b>ここが見ているのは「JDK の通信 API を直に使うこと」だけである。</b>
     */
    @Test
    @DisplayName("pdf-core は外部ネットワーク通信を行わない")
    void coreMustNotReachTheNetwork() {
        noClasses()
                .that()
                .resideInAPackage("..pdfjig.core..")
                .should()
                .dependOnClassesThat(resideInAnyPackage("java.net..", "javax.net..")
                        .and(not(nameMatching("java\\.net\\.(URI|URISyntaxException|URLEncoder|URLDecoder)"))))
                .because("pdf-core は確定的処理のみを行う。"
                        + "外へ出る経路は pdf-desktop の UpdateCheck にあり、pdf-ai にも入る。"
                        + "その都合がこちらへ滲むのを止める（.claude/rules/modules-and-invariants.md「モジュール別の責務」）")
                .check(classes);
    }

    /**
     * 画面から外へ出る経路を 1 つに絞る。
     *
     * <p>{@code README.md} が「<b>外への通信は、更新を確認したときだけである</b>」と公開している
     * （#72）。<b>その約束は、誰かが別の場所で通信を始めた瞬間に嘘になる</b>——
     * 想定利用者にはクラウド送信が社内規程で禁じられている現場が含まれており、
     * <b>破れたことに気づけないまま配ることがいちばん重い</b>（{@code CLAUDE.md} 優先順位 2）。
     *
     * <p><b>縛るのは pdf-desktop だけである。</b>pdf-ai は通信するのが仕事であり、pdf-cli は
     * それを呼ぶ。pdf-core は上の {@link #coreMustNotReachTheNetwork} が別に見ている。
     * <b>規約が言っている以上のことを機械に守らせない</b>のは、あちらと同じ判断である。
     *
     * <p><b>★ {@code java.net.URL} を除外する。上（pdf-core）とはそこだけ違う。</b>
     * JavaFX はスタイルシートを URL の文字列で受け取るため、{@code PdfjigApplication} が
     * {@code getResource("pdfjig.css").toExternalForm()} を呼ぶ——<b>クラスパスの資源を
     * 名指しているだけで、通信はしない</b>（実際にこのルールが最初に掴んだのがそれである）。
     * pdf-core には URL を使う理由が 1 つも無いので、あちらは除外しない。
     *
     * <p><b>そのぶんの穴は下の {@link #urlMustNotBeOpenedDirectly} が塞ぐ。</b>
     * {@code URL#openConnection} は戻り値の {@code URLConnection} でこのルールに掛かるが、
     * {@code URL#openStream} は {@code InputStream} を返すので<b>型では掛からない</b>。
     *
     * <p><b>見落とす経路がもう 1 つある</b>——サードパーティの HTTP クライアントを足せば
     * {@code java.net} を直接参照しないまま通信でき、このルールは緑のままになる（#90）。
     * pdf-core と同じ限界であり、ここが見ているのは「JDK の通信 API を直に使うこと」だけである。
     *
     * <p><b>下の {@code ...RuleHasSubject} は、除外した側が本当に通信していることを見る。</b>
     * {@code UpdateCheck} を消せば、このルールは緑のまま何も守らなくなる。
     */
    @Test
    @DisplayName("pdf-desktop で外へ出るのは UpdateCheck だけである")
    void desktopReachesTheNetworkOnlyThroughUpdateCheck() {
        noClasses()
                .that()
                .resideInAPackage("..pdfjig.desktop..")
                .and(not(isUpdateCheck()))
                .should()
                .dependOnClassesThat(resideInAnyPackage("java.net..", "javax.net..")
                        .and(not(nameMatching("java\\.net\\.(URI|URISyntaxException|URL|URLEncoder|URLDecoder)"))))
                .because("外への通信は、利用者が「更新を確認」を押したときだけである。"
                        + "起動しただけで外へ出ないことを README が公開しており、"
                        + "それを守れているかは経路の数でしか確かめられない（#72）")
                .check(classes);
    }

    /**
     * {@code URL} から直に開く経路を塞ぐ。
     *
     * <p>上のルールが {@code java.net.URL} を除外したぶんの穴である。<b>型では縛れないので、
     * 入口のメソッドを名指しする。</b>{@code new URL("https://…").openStream()} は
     * <b>1 行で外へ出られる</b>——それが資源の読み出しと同じ形をしているのが厄介なところで、
     * {@code getResource(…).openStream()} との違いは URL の中身にしかない。
     *
     * <p><b>誰も呼んでよくない。</b>{@code UpdateCheck} も除外しない——あちらは
     * {@link java.net.HttpURLConnection} を使っており、これを呼ぶ理由が無い。
     * <b>対の {@code ...RuleHasSubject} を持たないのは、依存先が JDK であり、
     * クラスパスから外れることがないためである</b>（{@link #coreMustNotReachTheNetwork} と同じ）。
     *
     * <p><b>★ 名指しは 2 つある。{@code openStream} は {@code InputStream} を、
     * {@code getContent} は {@code Object} を返す</b>——どちらも<b>戻り値の型に
     * {@code java.net} が現れない</b>。{@code openConnection} は {@code URLConnection} を返すので
     * 上のルールに掛かる。<b>ここへ挙げるのは「型に現れないもの」だけである</b>ので、
     * 増やすときは戻り値の型を先に見ること。
     *
     * <p><b>★ {@code callMethod} は多重定義を 1 つずつ指す。</b>引数まで照合するため、
     * {@code getContent()} を挙げても {@code getContent(Class[])} は素通りする。
     * <b>名指しするメソッドに多重定義があるなら、全部を挙げること。</b>
     */
    @Test
    @DisplayName("URL から直に開かない（java.net.URL は識別子として使うので型では縛れない）")
    void urlMustNotBeOpenedDirectly() {
        noClasses()
                .that()
                .resideInAPackage("..pdfjig..")
                .should()
                .callMethod(java.net.URL.class, "openStream")
                .orShould()
                .callMethod(java.net.URL.class, "getContent")
                .orShould()
                .callMethod(java.net.URL.class, "getContent", Class[].class)
                .because("URL#openStream と URL#getContent は型に現れないまま外へ出る。" + "資源の読み出しと同じ形をしており、差分を読んだだけでは見分けがつかない（#72）")
                .check(classes);
    }

    /**
     * 既にあるファイルを置き換える書き出しを、確認を取れる経路だけに残す。
     *
     * <p>{@code DocumentWriter#assemble} は {@code OutputWorkspace} に書いてから
     * <b>{@code REPLACE_EXISTING} で置き換える</b>——pdf-core が「既存の出力を拒む」約束の上に、
     * <b>この経路だけが層を重ねている</b>（{@code docs/SPEC.md} §4.2）。
     * 重ねてよい根拠は<b>保存ダイアログが上書きの確認を取っていること</b>だけであり、
     * <b>それを持っているのは {@code MainWindow#saveAs} である。</b>
     *
     * <p><b>★ #57 まではコンパイラが縛っていた。</b>{@code MainWindow} の中に private で
     * 置いてあったので、呼べる相手が 1 つしか無かった。<b>外へ出した以上、同じ強さを
     * ここで作り直す</b>——{@code CLAUDE.md}「配る差分の門」3 と、優先順位 1 である。
     *
     * <p><b>対の {@code ...RuleHasSubject} は足さない。</b>依存先は pdfjig 自身の型であり、
     * クラスパスから外れることがない（{@link #coreMustNotReachTheNetwork} と同じ理由）。
     */
    @Test
    @DisplayName("既にあるファイルを置き換える書き出しを頼めるのは MainWindow だけである")
    void assembleIsCalledOnlyByMainWindow() {
        noClasses()
                .that(not(named(MAIN_WINDOW)))
                .should()
                .callMethodWhere(target(owner(name(DOCUMENT_WRITER))).and(target(name("assemble"))))
                .because("既にあるファイルを黙って置き換えてよいのは、保存ダイアログが上書きの確認を"
                        + "取ったときだけである。確認を持っているのは MainWindow#saveAs だけであり、"
                        + "#57 まではそれを private が縛っていた（CLAUDE.md 優先順位 1）")
                .check(classes);
    }

    /**
     * 置き換えそのものも、確認を取れる経路の内側に閉じる。
     *
     * <p>{@code DocumentWriter#move} は<b>既にあるファイルを問答無用で置き換える</b>。
     * #113 で {@code private} から package-private になった——<b>テストから呼ぶためであり、
     * 他から呼んでよくなったわけではない。</b>
     *
     * <p><b>★ 上の {@link #assembleIsCalledOnlyByMainWindow} だけでは足りない。</b>
     * あちらが縛るのは {@code assemble} で、<b>{@code move} は 2 つ目の入口になっている</b>
     * ——確認を取っていないパスを渡せば、そこから同じことが起きる（優先順位 1）。
     *
     * <p>呼んでよいのは {@code DocumentWriter} 自身だけである（{@code assemble} の中から）。
     * <b>テストは対象外である</b>——{@code DO_NOT_INCLUDE_TESTS} で取り込んでいない。
     */
    @Test
    @DisplayName("置き換えを頼めるのは DocumentWriter の中だけである")
    void replaceIsCalledOnlyByDocumentWriter() {
        noClasses()
                .that(not(named(DOCUMENT_WRITER)))
                .should()
                .callMethodWhere(target(owner(name(DOCUMENT_WRITER))).and(target(name("move"))))
                .because("move は既にあるファイルを問答無用で置き換える。#113 で private を外したのは"
                        + "テストから呼ぶためであり、他から呼んでよくなったわけではない"
                        + "（CLAUDE.md 優先順位 1）")
                .check(classes);
    }

    /**
     * 作業場所を開ける場所と、控えの在り処を伝える場所は、同じ 1 か所である。
     *
     * <p><b>★★ 控えを抱えたまま失敗したことを利用者へ伝える関門は、作業場所の側にある</b>
     * （{@code OutputWorkspace#failing}。#124）。<b>それでも、呼ぶ側がそれを通すかどうかには懸かっている</b>
     * ——{@code nextTo} で作業場所を開いておきながら {@code failing} を通さない道が増えると、
     * <b>出力先には何も無く、元は作業場所の中にしか無いのに、画面には汎用の失敗しか出ない。</b>
     *
     * <p><b>★★ クラスの粒度では足りない。</b>いちばん増えそうな場所は
     * {@code DocumentWriter} の<b>中</b>である（{@code splitInto} はもう 1 つの書き出しであり、
     * いまは作業場所を使っていない）。<b>「DocumentWriter 以外は呼ばない」で縛ると、そこが素通りする</b>
     * ——{@code everyRenameAsksForAnAtomicMove} が #119 で学んだのと同じ形である。
     *
     * <p><b>★ 禁止の形では書かない。</b>{@code noClasses().should()} は<b>対象が 1 つも無ければ
     * 黙って成功する</b>ので、{@code nextTo} を改名した日に何も見なくなる。
     * <b>呼ぶメソッドの集合そのものを突き合わせる</b>——空になれば落ちる。
     */
    @Test
    @DisplayName("作業場所を開ける場所と、在り処を伝える場所は同じ 1 か所である")
    void theWorkspaceIsOpenedAndReportedInOnePlace() {
        Set<String> opens = methodsCalling("nextTo");
        assertEquals(
                Set.of("assemble"),
                opens,
                "作業場所を開ける場所が増減している。増えたなら、そこも OutputWorkspace#failing を"
                        + "通しているかをここで見ること——通っていないと、控えを抱えたまま失敗しても"
                        + "画面に在り処が出ない（#124）");
        assertEquals(
                opens,
                methodsCalling("failing"),
                "作業場所を開いておきながら、控えの在り処を伝えない道がある。"
                        + "その状態は KeptCopyReportTest が windows でだけ作れるので、"
                        + "別の道を足しても赤くならない（#124）");
    }

    /**
     * 作業場所を開けるのも、控えの在り処を伝える型を作るのも、{@code DocumentWriter} の中だけである。
     *
     * <p><b>★ 上の {@link #theWorkspaceIsOpenedAndReportedInOnePlace} だけでは足りない。</b>
     * あちらが見るのは<b>{@code DocumentWriter} の中</b>だけなので、
     * <b>別のクラスが作業場所を開いても気づかない。</b>クラスをまたぐ側はここが縛る。
     *
     * <p><b>★★ 例外を直に作る道も塞ぐ。</b>{@code ReplacedFileKeptException} を
     * <b>自分で組み立てられると、印も控えも見ずに「ここに残っています」と言える</b>——
     * <b>作ってよいのは、抱えているかどうかを知っている作業場所だけである。</b>
     */
    @Test
    @DisplayName("作業場所を開けるのも、在り処を運ぶ型を作るのも、決まった場所だけである")
    void nobodyElseOpensAWorkspaceOrBuildsTheReport() {
        noClasses()
                .that(not(named(DOCUMENT_WRITER)))
                .should()
                .callMethodWhere(target(owner(name(OUTPUT_WORKSPACE))).and(target(name("nextTo"))))
                .because("作業場所を開いた側は、控えを抱えたまま失敗したことを OutputWorkspace#failing で" + "伝えなければならない。開ける場所が増えると、そこだけ黙る（#124）")
                .check(classes);

        noClasses()
                .that(not(named(OUTPUT_WORKSPACE)))
                .should()
                .callConstructorWhere(target(owner(name(KEPT_EXCEPTION))))
                .because("印も控えも見ずに在り処を名乗れると、無事なファイルを「作業場所にしか無い」と" + "伝えることになる。作ってよいのは抱えているかどうかを知っている側だけである（#124）")
                .check(classes);

        noClasses()
                .that(not(named(OUTPUT_WORKSPACE)))
                .should()
                .callConstructorWhere(target(owner(name(PLAINTEXT_EXCEPTION))))
                .because("消せたかどうかを見ずに「平文が残っています」と名乗れると、無いものを探させることになる。" + "作ってよいのは消そうとした作業場所だけである（#184）")
                .check(classes);
    }

    /**
     * {@code DocumentWriter} の中で、{@link OutputWorkspace} のそのメソッドを呼んでいるものの名前。
     *
     * <p><b>探す相手が見つからなければ落とす。</b>取り込みが空でも、改名で当たらなくなっても、
     * <b>集合が空になって突き合わせが落ちる</b>——黙って緑になる形を作らない。
     */
    private static Set<String> methodsCalling(String workspaceMethod) {
        String target = OUTPUT_WORKSPACE + "." + workspaceMethod;
        return classes.stream()
                .filter(javaClass -> javaClass.getName().equals(DOCUMENT_WRITER))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.getCallsFromSelf().stream()
                        .anyMatch(call -> call.getTarget().getFullName().startsWith(target)))
                .map(method -> method.getName())
                .collect(Collectors.toSet());
    }

    /**
     * 出力先へ改名するところは、どこも原子的な移動を頼む。
     *
     * <p><b>★★ これは実装の中身を縛るルールである。</b>ふつうはやらない——
     * だが<b>ここは「頼んでいること」そのものが直しであり、それを見るテストが 1 本も書けなかった</b>
     * （#113）。頼まなければ断られようがないので、フォールバックを見るテストは素通りし、
     * 置き換えを見るテストは 2 段でも成功する。<b>消しても何も鳴らない。</b>
     *
     * <p><b>★★ 呼ぶ場所ごとに見る</b>（#119）。<b>「クラスのどこかで 1 度は触る」では足りない</b>——
     * #119 で改名が 3 か所（退避・入れ替え・巻き戻し）に増えたので、
     * <b>1 か所から外しても他が残っていれば緑になる。</b>そして<b>いちばん外されやすいのは、
     * ふつうは通らない巻き戻しである。</b>
     *
     * <p><b>★ ArchRule の形では書けない。</b>{@code should().accessField} は
     * 「触るフィールドはすべてそれであること」を意味し、<b>入れ子の {@code SplitResult} が
     * 自分のフィールドを触るだけで違反になる</b>（実測）。<b>「1 度は触ること」を言う形が無い</b>ので、
     * 取り込んだクラスを直に見る。<b>空振りは、探す相手が見つからなければ落ちることで防いでいる。</b>
     */
    @Test
    @DisplayName("出力先へ改名するところは、どこも原子的な移動を頼む")
    void everyRenameAsksForAnAtomicMove() {
        String atomicMove = StandardCopyOption.class.getName() + ".ATOMIC_MOVE";
        assertTrue(
                classes.stream().anyMatch(javaClass -> javaClass.getName().equals(DOCUMENT_WRITER)),
                DOCUMENT_WRITER + " が取り込まれていない。このテストは何も見ていない");

        Set<String> renames = classes.stream()
                .filter(javaClass -> javaClass.getName().equals(DOCUMENT_WRITER))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.getCallsFromSelf().stream()
                        .anyMatch(call -> call.getTarget().getFullName().startsWith(Files.class.getName() + ".move")))
                .map(method -> method.getName())
                .collect(Collectors.toSet());
        assertEquals(Set.of("setAside", "replaceWith"), renames, "改名する場所が増減している。増えたなら、そこも原子的を頼んでいるかをここで見ること（#119）");

        Set<String> atomic = classes.stream()
                .filter(javaClass -> javaClass.getName().equals(DOCUMENT_WRITER))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.getFieldAccesses().stream()
                        .anyMatch(access -> access.getTarget().getFullName().equals(atomicMove)))
                .map(method -> method.getName())
                .collect(Collectors.toSet());
        assertEquals(
                renames,
                atomic,
                "頼まないと Windows では DeleteFile → MoveFileEx の 2 段になり、その間に割り込まれると"
                        + "元のファイルも置き換えるはずのものも残らない（#113）。"
                        + "1 か所だけ外しても DocumentWriterTest は全部緑になるので、縛れるのはここだけである");
    }

    /**
     * {@code pdf-core} の書き出しは、すべて閉じる前に書いたハンドルでディスクへ届けさせる（#219）。
     *
     * <p><b>★★ これも「頼んでいること」そのものが直しである</b>——上の原子的な移動と同じく、
     * 届いたかどうかは電源を落とさないと見えず、<b>消しても何も鳴らない。</b>理由の正本は
     * {@code docs/SPEC.md} §4.2 と {@code DurableSave} である。
     *
     * <p><b>書き出しを 1 か所に集め、そこを縛る。</b>{@code PDDocument#save} を呼ぶのは
     * {@code DurableSave.write} だけで、しかも {@code save(OutputStream)} であること。
     * 届けさせる呼び出し・チャネルの取り出し・出力を開くことも、そこだけで、開くのは 1 度だけであること。
     * <b>{@code save(File)} へ戻す形、名前で開き直して届けさせる形（読み取りでも書き込みでも）、
     * 暗号化を {@code save(File)} へ戻す形は、どれも赤になる</b>——6 通りに壊して確かめた（2026-09-25）。
     *
     * <p><b>★ 見えないものがある。</b>{@code PDDocument#save} を通らない書き方（{@code COSWriter} を直に使う、
     * {@code PDFMergerUtility} に書き出し先を渡す等）、書く順序（{@code force} が閉じた後か）、
     * {@code core} の下のパッケージ。<b>書き出しを足すときは {@code DurableSave} を通すこと。</b>
     */
    @Test
    @DisplayName("pdf-core の書き出しは、すべて閉じる前に書いたハンドルでディスクへ届けさせる")
    void everyWriteFlushesThroughTheHandleItWrote() {
        String write = DURABLE_SAVE + ".write(PDDocument, Path)";
        assertEquals(Set.of(write), unitsAccessing(PD_DOCUMENT, "save"), "DurableSave を通らずに書いている。届く前に置き換えられうる（#219）");
        assertEquals(Set.of(write), unitsAccessing(FileChannel.class.getName(), "force"), "届けさせる場所が増減している（#219）");
        assertEquals(
                Set.of(write),
                unitsAccessing(FileOutputStream.class.getName(), "getChannel"),
                "書いたハンドルから届けさせていない（#219）");

        List<JavaCodeUnitAccess<?>> saves = coreClasses()
                .flatMap(javaClass -> javaClass.getCodeUnitAccessesFromSelf().stream())
                .filter(access -> access.getTargetOwner().getName().equals(PD_DOCUMENT))
                .filter(access -> access.getTarget().getName().equals("save"))
                .toList();
        assertTrue(
                saves.stream()
                        .allMatch(access -> access.getTarget().getRawParameterTypes().stream()
                                .map(JavaClass::getName)
                                .toList()
                                .equals(List.of(OutputStream.class.getName()))),
                "save(OutputStream) 以外で書いている。save(File) は中で閉じるので、届けさせる手が無い（#219）");

        long opened = coreClasses()
                .flatMap(javaClass -> javaClass.getCodeUnitAccessesFromSelf().stream())
                .filter(access -> access.getTargetOwner().getName().equals(FileOutputStream.class.getName()))
                .filter(access -> access.getTarget().getName().equals(JavaConstructor.CONSTRUCTOR_NAME))
                .count();
        assertEquals(1, opened, "出力を開き直している。書いたハンドルで届けさせること（#219）");

        // ★★ 届いたか確かめられなかったとき、画面の保存は置き換えを止める。止める判定を外しても
        //   単体テスト（DocumentWriterTest）は判定そのものしか見ないので、呼んでいることはここで縛る。
        // ★ 順序も見る。move の後で止めても、元は既に置き換わり、控えも手放している。
        List<JavaMethodCall> calls = classes.get(DOCUMENT_WRITER).getMethods().stream()
                .filter(method -> method.getName().equals("assemble"))
                .flatMap(method -> method.getMethodCallsFromSelf().stream())
                .toList();
        int refuse = calls.stream()
                .filter(call -> call.getTarget().getName().equals("refuseToReplaceUnlessDurable"))
                .mapToInt(JavaMethodCall::getLineNumber)
                .min()
                .orElse(Integer.MAX_VALUE);
        int move = calls.stream()
                .filter(call -> call.getTarget().getName().equals("move"))
                .mapToInt(JavaMethodCall::getLineNumber)
                .min()
                .orElse(-1);
        assertTrue(refuse < move, "画面の保存が、届いたか確かめる前に置き換えうる（#219）");
    }

    /**
     * 書き出しを画面から切り離したままにする。
     *
     * <p>{@code DocumentWriter} は<b>バックグラウンドスレッドから呼ばれる</b>
     * （{@code BackgroundTasks}）。<b>安全に呼べる根拠は「JavaFX の型を 1 つも持たない」ことだけ</b>で、
     * あちらの Javadoc はそれを断言している。<b>断言を機械で支える。</b>
     *
     * <p>#57 が「画面を持たずに読める」と書いた効き目は、ここが赤くなることで保たれる
     * ——{@code Platform.runLater} を 1 つ足せば、その文は嘘になる。
     */
    @Test
    @DisplayName("DocumentWriter は JavaFX の型を持たない")
    void documentWriterHasNoScreen() {
        noClasses()
                .that(named(DOCUMENT_WRITER))
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("javafx..")
                .because("バックグラウンドスレッドから呼ぶ。画面の型が入った瞬間に、" + "その約束が読む側から確かめられなくなる（#57）")
                .check(classes);
    }

    /**
     * ログの口を迂回させない。
     *
     * <p>{@code Logs} は {@code LogEvent} しか受け取らない——<b>自由な文字列を渡せないので、
     * 文書のパスもファイル名も載せられない</b>（{@code docs/SPEC.md} §10.4）。
     * <b>その線は、誰かが {@code Logger.getLogger} を直に取った瞬間に消える。</b>
     * 型で守っているものを、型を迂回して破れないようにするのがここである。
     *
     * <p><b>下の {@code ...RuleHasSubject} は、除外した側が本当に書き手であることを見る。</b>
     * {@code Logs} を消したり別の仕組みへ移したりすると、このルールは緑のまま何も守らなくなる
     * ——依存先が JDK なので、この形の空振りは上の 2 例（PDFBox / POI）とは別の起こり方をする。
     */
    @Test
    @DisplayName("java.util.logging を触るのは Logs だけである")
    void loggingMustGoThroughLogs() {
        noClasses()
                .that(not(isLogs()))
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.util.logging..")
                .because("ログの口は LogEvent しか受け取らない。"
                        + "直に Logger を取ると、書かないと決めたパスとファイル名がそこから入る"
                        + "（docs/SPEC.md §10.4、CLAUDE.md INV-5）")
                .check(classes);
    }

    @Test
    @DisplayName("ログのルールが空振りしていない（除外した Logs が実際の書き手である）")
    void loggingRuleHasSubject() {
        boolean writes = classes.stream()
                .filter(javaClass -> isLogs().test(javaClass))
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .anyMatch(dependency ->
                        dependency.getTargetClass().getPackageName().startsWith("java.util.logging"));

        assertTrue(writes, "Logs が java.util.logging を使っていない。loggingMustGoThroughLogs は緑でも何も守っていない");
    }

    @Test
    @DisplayName("外へ出る経路のルールが空振りしていない（除外した UpdateCheck が実際に通信する）")
    void desktopNetworkRuleHasSubject() {
        boolean reaches = classes.stream()
                .filter(javaClass -> isUpdateCheck().test(javaClass))
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .anyMatch(dependency -> dependency.getTargetClass().getName().equals("java.net.HttpURLConnection"));

        assertTrue(
                reaches,
                "UpdateCheck が java.net.HttpURLConnection を使っていない。"
                        + "desktopReachesTheNetworkOnlyThroughUpdateCheck は緑でも何も守っていない");
    }

    /**
     * 秘密を素の配列でやり取りする口を、持ち主の型の中だけに閉じる。
     *
     * <p><b>★★ これ単独では #135 / #144 / #145 を捕まえられない</b>——
     * <b>{@code Arrays.fill} は最初から在ったからである。</b>ArchUnit は呼び出しを見るが、
     * <b>文の順序も例外表の範囲も見ない。</b>捕まえるのは<b>新しい入口が増えたとき</b>である
     * ——{@code Encryption#protect} は秘密を 2 本取り、{@code pdf-cli} の
     * {@code --password-stdin} は読み手をもう 1 つ増やす（#146 / #28）。
     *
     * <p><b>★ 禁止の形では書かない。</b>{@code noMethods().should()} は<b>対象が 1 つも無ければ
     * 黙って成功する</b>ので、{@code Password} を改名した日に何も見なくなる。
     * <b>口の集合そのものを突き合わせる</b>——空になれば落ちる。
     *
     * <p><b>★ 期待値に {@code copyOf} は入らない。</b>あちらが作る配列は局所変数であり、
     * <b>外から見える口ではない</b>——そこが「持ち主の無い配列を作れない」形の要である。
     */
    @Test
    @DisplayName("素の char[] でパスワードを受け渡す口は Password の中だけである")
    void rawCharArraysStayInsidePassword() {
        assertEquals(
                Set.of(
                        PASSWORD + ".<init>(char[])",
                        PASSWORD + ".of(char[])",
                        PASSWORD + ".value()",
                        PASSWORD + ".value"),
                membersTouchingCharArrays(),
                "素の char[] が現れる口が増減している。増えたなら、そこは持ち主の無い配列を"
                        + "作れる場所である——ゼロ埋めを守るものが、また註だけに戻る"
                        + "（#146。CLAUDE.md INV-5）");
    }

    /**
     * 秘密が {@code String} になる場所を数え上げる。
     *
     * <p><b>★★ ここは INV-5 が「回避できない」と認めた限界である。</b>PDFBox の
     * {@code Loader.loadPDF} も {@code StandardProtectionPolicy} も <b>{@code String} しか
     * 受け付けない</b>ので、<b>境界で一度 {@code String} が生まれ、GC されるまでヒープに残る</b>
     * ——<b>明示的なゼロ埋めができない。</b>
     *
     * <p><b>★★ だから「何か所あるか」を註で書いていた。それが腐った</b>
     * （<b>2026-09-14 実測</b>。#199 で方針の組み立てを {@code PdfBoxEncryption} から
     * {@code StandardProtection} へ移したとき、<b>{@code PdfDocument} の Javadoc が
     * 移る前の場所を指したまま残った</b>）。<b>数えるのは機械の仕事である。</b>
     *
     * <p><b>★★ 綴りを 1 つに絞らない。</b>{@code new String(char[])} だけを見ると、
     * <b>{@code String.valueOf(char[])} と書いた日に素通りする</b>——
     * <b>同じ「消せない写し」ができるのに、規則も INV-5 の口の規則も緑のままになる</b>
     * （後者は<b>成員の型</b>を見るので、局所の式は増やさない）。
     * <b>{@code CharBuffer#wrap} と {@code StringBuilder#append} も同じ入口である。</b>
     */
    @Test
    @DisplayName("秘密が String になるのは、PDFBox の口が String しか受けない 2 か所だけである")
    void secretsBecomeStringsOnlyAtPdfBoxBoundaries() {
        assertEquals(
                Set.of(PDF_DOCUMENT + ".open(Path, Password)", STANDARD_PROTECTION + ".apply(PDDocument, Protection)"),
                unitsBuildingStringsFromCharArrays(),
                "秘密を String にする場所が増減している。増えたなら、そこは消せない写しが"
                        + "ヒープに残る場所である——PDFBox の口が String しか受けないとき以外に作らない"
                        + "（#199。CLAUDE.md INV-5）");
    }

    /**
     * ゼロ埋めそのものも 1 か所に閉じる。
     *
     * <p><b>★★ 上のルールだけでは足りない。</b>{@code char[]} を引数に取らなくても、
     * <b>自分で配列を作って自分で消す形</b>は書ける——そこは持ち主の型を通らないので、
     * <b>作った場所からは片づけが見えない。</b>
     *
     * <p>対象は {@code Arrays.fill(char[], char)} だけである。
     * <b>他の型の {@code fill} まで縛ると、パスワードと関係のない差分で赤くなる</b>
     * ——守れているのに赤が出る検査は、無視する習慣を作る。
     */
    @Test
    @DisplayName("ゼロ埋めするのは Password の中だけである")
    void zeroingHappensOnlyInsidePassword() {
        assertEquals(
                Set.of(PASSWORD + ".close()"),
                unitsZeroingCharArrays(),
                "char[] のゼロ埋めが Password の外で起きている。持ち主の型を通らない片づけは、" + "作った場所からは見えないので、二重に消すか、一度も消さないかになる（#146）");
    }

    /**
     * PDFBox に触れる公開クラスの公開メソッドは、自分たちの包みを通る。
     *
     * <p><b>★★ pdf-core の外へ出るのは {@code PdfjigException} だけである</b>——
     * それを機械で見る（#150 / #178）。{@code pdfboxMustNotLeakOutOfCore} は<b>型の見え方</b>を
     * 見ており、軸が違う——<b>PDFBox の例外が {@code Throwable} として飛び抜けるのは止められない。</b>
     *
     * <p><b>★★ 以前は「{@code try} の形」を見ていた。2 通りに外れた</b>（#178）。
     *
     * <ul>
     *   <li><b>開く側（偽の緑）</b>——<b>javac は try-with-resources のために
     *       {@code catch java/lang/Throwable} を自分で吐く</b>ので、資源を開ける公開メソッドは
     *       <b>人が 1 行も書かなくても通った</b>（<b>2026-09-12 実測</b>）。
     *       <b>深さを 1 段下げても直らない</b>——「本体全体が {@code try} の中にある」に替えても、
     *       合成ハンドラが本体をほぼ覆うので無改修で緑になった</li>
     *   <li><b>閉じる側（偽の赤）</b>——ArchUnit は<b>ラムダの本体を、囲む {@code try} の中とは
     *       見ない</b>。そのために {@code rotate} は {@code forEach} を {@code for} へ
     *       書き換えていた</li>
     * </ul>
     *
     * <p><b>いまは「{@link #PDFBOX_GUARD} を呼んだか」を見る。</b>
     * <b>javac は自分たちのヘルパへの呼び出しを合成しない</b>ので、<b>偽の緑が原理的に出ない。</b>
     * <b>ラムダも数えられる</b>——ArchUnit は<b>呼び出しについては</b>ラムダの本体を
     * 囲むコード単位のものとして返す（<b>2026-09-13 実測</b>。
     * 見ないのは {@code try} との対応だけである）。
     *
     * <p><b>★★ これが #150 の ① を守る。</b>{@code PdfBoxPageRendering#renderScaled} は
     * <b>private なので、公開の入口を見る旧規則の対象外だった</b>——
     * <b>あそこの {@code catch} を {@code IOException} だけに戻しても、旧規則は 0 件のまま緑だった</b>
     * （同日実測）。<b>包みが入口に在れば、そこから先は誰が投げても外へ出ない</b>ので、
     * <b>あの {@code catch} は不要になり、いまは {@code render} から {@code guarded} を外せば
     * ここが赤くなる。</b>同じ理由で、規則から見えなかった直の呼び出し 109 件
     * （{@code PageReferences} の 60 件を含む）も<b>包みの内側に入った。</b>
     *
     * <p><b>★★ 対象はメソッド単位である。</b>以前は「PDFBox に触れる<b>クラス</b>の公開メソッド」
     * を見ていたが、<b>PDFBox へ 1 歩も降りない公開メソッドを足した日に赤くなる</b>
     * ——{@code PdfDocument#openedWithPassword()} は<b>控えた真偽を返すだけ</b>である。
     * <b>意味のない {@code guarded} を書かせるか、除外を 1 つ増やすかになる</b>
     * （#189 の門が予告し、#193 で現に踏んだ）。<b>いまは自分のクラスの中をたどって、
     * ほんとうに PDFBox へ届くものだけを対象にする。</b>
     *
     * <p><b>★★ たどるのは「自分で包む責任がまだ移っていない先」だけである。</b>
     * <b>別の公開メソッドに当たったらそこで止める</b>——あちらはこの規則の対象であり、
     * <b>自分で包む。</b>それ以外（private・パッケージプライベート・同じクラスの中）は<b>たどる。</b>
     *
     * <p><b>★★ 「同じクラスの中だけ」では弱かった</b>（2026-09-13 実測。#196 の門の 2 段目）。
     * {@code PdfBoxEncryption#inspect} は {@code PdfDocument#encryption()} を経由して
     * PDFBox へ届くが、<b>あれはパッケージプライベートなので入口ではない</b>
     * ——<b>対象が 24 から 21 へ減り、あの 2 本から包みを外しても緑になる状態だった。</b>
     *
     * <p><b>★ ここが見ないもの。</b>「その入口の PDFBox の仕事が<b>すべて</b>包みの中にあるか」は
     * <b>見ていない</b>——包みを呼びつつ、その外でも PDFBox を呼ぶ形は通る。
     * <b>ラムダの中と外を、呼び出しの一覧からは区別できない</b>（上の「閉じる側」と同じ限界である）。
     * <b>書き方の側で揃えてある</b>（{@code PdfBoxPageOperations} の冒頭の枠）。
     *
     * <p><b>★ 禁止の形では書かない。</b>違反の集合そのものを突き合わせる——
     * <b>対象が 1 つも無くなれば、下の対の検査が落ちる。</b>
     */
    @Test
    @DisplayName("PDFBox に触れる公開クラスの公開メソッドは、自分たちの包みを通る")
    void publicEntryPointsGoThroughTheGuard() {
        assertEquals(
                Set.of(),
                publicEntryPointsTouchingPdfBox()
                        .filter(method -> !callsGuard(method))
                        .map(ArchitectureTest::describe)
                        .collect(Collectors.toSet()),
                "公開の入口が包みを通っていない。細工 PDF は IOException ではない例外を投げるので、"
                        + "そこから素の未検査例外が pdf-core の外へ出る——呼ぶ側の分岐はどれも当たらない"
                        + "（#144 / #150 / #178）");
    }

    @Test
    @DisplayName("包みの規則が空振りしていない（PDFBox に触れる公開クラスが実際にある）")
    void guardRuleHasSubject() {
        assertTrue(
                publicEntryPointsTouchingPdfBox().findAny().isPresent(),
                "PDFBox に触れる公開クラスの公開メソッドが 1 つも無い。" + "publicEntryPointsGoThroughTheGuard は緑でも何も守っていない");
    }

    /**
     * 警告の受け口を動かすのは {@code Warnings} だけである。
     *
     * <p><b>★★ {@code WarningListener} を動かすのは呼ぶ側のコードである。</b>
     * 包みの中で走らせると、<b>そこで投げた失敗を包みが飲む</b>——
     * 「ファイルの読み書きに失敗しました」に化け、<b>呼ぶ側の失敗が入力のせいにされる</b>
     * （#178。{@code CLAUDE.md} 優先順位 2）。<b>型では区別が付かない</b>ので、
     * <b>走らせる場所で分けるしかない。</b>
     *
     * <p><b>★★ 呼び出し場所の集合では縛れなかった</b>（<b>2026-09-13 実測</b>）。
     * 「{@code onWarning} を呼ぶのは {@code PdfBoxPageOperations.report} だけ」を置いたところ、
     * <b>違反 5 件のうち 4 件は直してはならないものだった</b>——
     * <b>受け口を引数で受け取って流すメソッドは、包みの外から呼ばれるときは正しく動く。</b>
     * <b>問われているのは「どこで呼ぶか」ではなく「どの受け口へ流すか」である。</b>
     * <b>受け口を握る型を 1 つにすれば、そのまま集合になる。</b>
     *
     * <p><b>★ クラスを数え上げない。</b>境界が増えても規則を直さずに効く——
     * {@code PdfBoxTextExtraction} に警告の口が付く日（#180）も、{@code pdf-mcp} が来る日（#103）も、
     * <b>受け口を握るのが {@code Warnings} である限りここが見る。</b>
     *
     * <p><b>★ 見るのは {@code pdf-core} だけである。</b>守っている性質は
     * <b>「{@code pdf-core} が包みの中で呼ぶ側のコードを走らせない」</b>であり、
     * {@code WarningListener} は {@code pdf-desktop} が実装する公開の口でもある——
     * <b>絞らないと、画面側の受け手が別の受け手へ流した日に、包みと無関係な理由で赤くなる。</b>
     * <b>そのとき打てる手は期待値へ名前を足すことだけで、避けたはずの数え上げが戻る。</b>
     */
    @Test
    @DisplayName("警告の受け口を動かすのは Warnings だけである")
    void onlyWarningsNotifiesTheListener() {
        assertEquals(
                Set.of(WARNINGS + ".report()"),
                unitsNotifyingWarningListener(),
                "警告の受け口が Warnings の外から動かされている。包みの中でそれが走ると、" + "呼ぶ側が投げた失敗を包みが飲み、入力のせいに化ける（#178）");
    }

    /**
     * 溜めた警告は、溜め始めたところが流す。
     *
     * <p><b>★★ 上の 2 本が見ない失敗が 1 つある</b>——<b>{@code Warnings} を作ったのに
     * {@code report()} を呼び忘れる</b>形である。<b>包みも受け口も規律どおりなのに、
     * 警告が 1 件も届かない</b>——「保護は引き継がれません」が黙って消える（優先順位 2）。
     * <b>公開の入口が 9 本あり、同じ枠を手で書き写している</b>ので、
     * <b>次に 1 本足す日がいちばん危ない。</b>
     *
     * <p>作るコード単位と流すコード単位が一致していれば、書き忘れはここで落ちる。
     *
     * <p><b>★ 置く場所までは見ない。</b>{@code report()} が包みの<b>中</b>で呼ばれていても
     * ここは緑である——<b>ラムダの中と外を、呼び出しの一覧からは区別できない</b>
     * （{@link #publicEntryPointsGoThroughTheGuard} と同じ限界）。
     * <b>そちらは挙動のテストが縛っている</b>（{@code PdfBoxPageOperationsTest} の
     * 「包みが飲まない」3 本）。
     */
    @Test
    @DisplayName("Warnings を作ったところが、溜めたものを流す")
    void whoeverCollectsAlsoReports() {
        assertEquals(
                unitsAccessing(WARNINGS, JavaConstructor.CONSTRUCTOR_NAME),
                unitsAccessing(WARNINGS, "report"),
                "溜め始めたところと流すところが食い違っている。作って流さなければ、" + "規律どおりに見えるまま警告が 1 件も届かない（#178）");
    }

    /**
     * PDFBox へ届く公開メソッド。
     *
     * <p><b>★ コンストラクタは数えない。</b>{@code PdfBoxPageOperations} の公開コンストラクタは
     * 受け口を控えるだけであり、<b>PDFBox へは降りない</b>——数えると、
     * <b>守れているのに赤が出る検査になる。</b>
     */
    private static Stream<JavaMethod> publicEntryPointsTouchingPdfBox() {
        return classes.stream()
                .filter(javaClass -> javaClass.getModifiers().contains(JavaModifier.PUBLIC))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(ArchitectureTest::reachesPdfBox);
    }

    /**
     * そのコード単位から、PDFBox へ届くか。
     *
     * <p><b>★ 型の持ち方では見ない。</b>{@code EncryptionInfo} のような値の型は
     * PDFBox から読んだ値を運ぶが、<b>向こうのコードは走らせない。</b>
     *
     * <p><b>★ 別の公開メソッドで止める理由は {@link #publicEntryPointsGoThroughTheGuard()}
     * にある。</b>
     */
    private static boolean reachesPdfBox(JavaCodeUnit from) {
        Set<JavaCodeUnit> seen = new HashSet<>();
        Deque<JavaCodeUnit> pending = new ArrayDeque<>();
        pending.add(from);
        while (!pending.isEmpty()) {
            JavaCodeUnit unit = pending.poll();
            if (!seen.add(unit)) {
                continue;
            }
            for (JavaCodeUnitAccess<?> access : accessesFromSelf(unit).toList()) {
                if (isPdfBoxOwned(access.getTargetOwner())) {
                    return true;
                }
                access.getTarget()
                        .resolveMember()
                        .filter(target -> isInternalTo(from, target))
                        .ifPresent(pending::add);
            }
        }
        return false;
    }

    /**
     * その先を、入口の責任のうちと見るか。
     *
     * <p><b>止めるのは 2 つ。</b>
     *
     * <ul>
     *   <li><b>別の公開メソッド</b>——あちらはこの規則の対象であり、<b>自分で包む</b></li>
     *   <li><b>別のパッケージ</b>——{@code pdf-desktop} から {@code pdf-core} を呼ぶ類である。
     *       <b>あそこは口の向こう側であり、包むのはあちらの仕事である</b>
     *       （止めないと {@code MainWindow#build} まで対象になる。<b>2026-09-13 実測</b>）</li>
     * </ul>
     */
    private static boolean isInternalTo(JavaCodeUnit from, JavaCodeUnit target) {
        if (!target.getOwner().getPackageName().equals(from.getOwner().getPackageName())) {
            return false;
        }
        return !(target instanceof JavaMethod
                && target.getModifiers().contains(JavaModifier.PUBLIC)
                && target.getOwner().getModifiers().contains(JavaModifier.PUBLIC));
    }

    /** そのコード単位が、自分たちの包みを呼ぶか。 */
    private static boolean callsGuard(JavaCodeUnit unit) {
        return accessesFromSelf(unit)
                .anyMatch(access -> access.getTargetOwner().getName().equals(PDFBOX_GUARD));
    }

    /**
     * そのコード単位が届く先。<b>呼び出しとメソッド参照の両方を数える。</b>
     *
     * <p><b>★★ {@code getCallsFromSelf} だけでは足りない</b>（<b>2026-09-13 実測</b>）。
     * {@code guarded(code, this::work)} と書くと<b>呼び出しとしては数えられず、
     * 包みの規則が素通りする</b>——<b>同じ形で受け口の規則も素通りした</b>
     * （{@code forEach(listener::onWarning)} を包みの中へ入れても緑だった）。
     * <b>物差しを「自分たちの包みを通ったか」へ替えても、読み取りの側が片目なら同じ穴が開く。</b>
     */
    private static Stream<JavaCodeUnitAccess<?>> accessesFromSelf(JavaCodeUnit unit) {
        return Stream.concat(unit.getCallsFromSelf().stream(), unit.getCodeUnitReferencesFromSelf().stream());
    }

    /**
     * 表記は {@code 型.名前(引数の型…)}。
     *
     * <p><b>★★ 引数の型まで書く。</b>落とすと<b>多重定義が 1 つに潰れる</b>——
     * {@code assemble(Path, …)} と {@code assemble(List, …)} が同じ綴りになり、
     * <b>集合を突き合わせる規則が、片方の書き忘れを見逃す</b>（2026-09-13 実測。
     * 片方から {@code report()} を落としても緑だった）。
     * <b>多重定義は「次に 1 本足す」いちばんありふれた形である。</b>
     */
    private static String describe(JavaCodeUnit unit) {
        return unit.getOwner().getName() + "." + unit.getName() + "("
                + unit.getRawParameterTypes().stream()
                        .map(JavaClass::getSimpleName)
                        .collect(Collectors.joining(", "))
                + ")";
    }

    /**
     * その型を呼ぶことが、PDFBox を呼ぶことになるか。
     *
     * <p><b>★ 継承した側も数える。</b>{@code PdfBoxTextExtraction} の
     * {@code PositionCollector} は {@code PDFTextStripper} を継承しており、
     * <b>そこへの呼び出しは静的型が pdfjig 側なので、パッケージだけで見ると素通りする</b>
     * ——<b>走るのは向こうのコードである。</b>
     */
    private static boolean isPdfBoxOwned(JavaClass type) {
        return isPdfBoxPackage(type)
                || type.getAllRawSuperclasses().stream().anyMatch(ArchitectureTest::isPdfBoxPackage);
    }

    private static boolean isPdfBoxPackage(JavaClass type) {
        return type.getPackageName().startsWith(PDFBOX);
    }

    /**
     * {@code WarningListener#onWarning} を呼んでいる {@code pdf-core} のコード単位。
     *
     * <p>絞る理由は {@link #onlyWarningsNotifiesTheListener()} にある。
     */
    private static Set<String> unitsNotifyingWarningListener() {
        return unitsAccessing(WARNING_LISTENER, "onWarning");
    }

    /**
     * その型のその成員へ届いている、{@code pdf-core} のコード単位。
     *
     * <p>コンストラクタは {@link JavaConstructor#CONSTRUCTOR_NAME} で指す。
     * 呼び出しとメソッド参照の両方を数える理由は {@link #accessesFromSelf} にある。
     */
    private static Set<String> unitsAccessing(String owner, String member) {
        return coreClasses()
                .flatMap(javaClass -> javaClass.getCodeUnitAccessesFromSelf().stream())
                .filter(access -> access.getTargetOwner().getName().equals(owner))
                .filter(access -> access.getTarget().getName().equals(member))
                .map(access -> describe(access.getOrigin()))
                .collect(Collectors.toSet());
    }

    private static Stream<JavaClass> coreClasses() {
        return classes.stream().filter(javaClass -> javaClass.getPackageName().equals(CORE_PACKAGE));
    }

    /**
     * 素の {@code char[]} が現れる本番のフィールド・メソッド・コンストラクタ。
     *
     * <p><b>名前は完全修飾で組む。</b>単純名で突き合わせると、<b>別のパッケージに同名の型が
     * 生まれた日に、そちらの口が期待値へ黙って吸われる</b>——{@code pdf-cli} の
     * {@code --password-stdin} は、まさに口を増やす差分である（#146 / #28）。
     *
     * <p>表記は、コード単位が {@code 型.名前(引数の型…)}、フィールドが {@code 型.名前} である
     * （{@link #describe}）。
     */
    private static Set<String> membersTouchingCharArrays() {
        return classes.stream()
                .flatMap(javaClass -> Stream.concat(
                        javaClass.getCodeUnits().stream()
                                .filter(ArchitectureTest::touchesCharArray)
                                .map(ArchitectureTest::describe),
                        javaClass.getFields().stream()
                                .filter(field -> isCharArray(field.getRawType()))
                                .map(field -> field.getOwner().getName() + "." + field.getName())))
                .collect(Collectors.toSet());
    }

    /** 引数か戻り値に素の {@code char[]} を持つか。 */
    private static boolean touchesCharArray(JavaCodeUnit unit) {
        return unit.getRawParameterTypes().stream().anyMatch(ArchitectureTest::isCharArray)
                || isCharArray(unit.getRawReturnType());
    }

    /**
     * {@code char[]} そのものか。
     *
     * <p>型の名前で見ない。<b>配列の表し方は版で変わりうる</b>ので、
     * 比較する相手を {@code char[].class} から取る。
     */
    private static boolean isCharArray(JavaClass type) {
        return type.isEquivalentTo(char[].class);
    }

    /** {@code char[]} から文字列を作っている本番のコード単位。 */
    private static Set<String> unitsBuildingStringsFromCharArrays() {
        return classes.stream()
                .flatMap(javaClass -> javaClass.getCodeUnitAccessesFromSelf().stream())
                .filter(ArchitectureTest::materializesText)
                .filter(access ->
                        access.getTarget().getRawParameterTypes().stream().anyMatch(ArchitectureTest::isCharArray))
                .map(access -> describe(access.getOrigin()))
                .collect(Collectors.toSet());
    }

    /**
     * その呼び出しは、{@code char[]} を文字の入れ物へ写すか。
     *
     * <p><b>★ 型で挙げる。</b>{@code String} を作る綴りは 1 つではなく、
     * <b>{@code StringBuilder} と {@code CharBuffer} を経由すれば同じものができる。</b>
     */
    private static boolean materializesText(JavaCodeUnitAccess<?> access) {
        JavaClass owner = access.getTargetOwner();
        return owner.isEquivalentTo(String.class)
                || owner.isEquivalentTo(StringBuilder.class)
                || owner.isEquivalentTo(StringBuffer.class)
                || owner.isEquivalentTo(java.nio.CharBuffer.class);
    }

    /** {@code Arrays.fill(char[], char)} を呼んでいる本番のコード単位。 */
    private static Set<String> unitsZeroingCharArrays() {
        return classes.stream()
                .flatMap(javaClass -> javaClass.getCodeUnitAccessesFromSelf().stream())
                .filter(access -> access.getTargetOwner().getName().equals(Arrays.class.getName()))
                .filter(access -> access.getTarget().getName().equals("fill"))
                .filter(access ->
                        access.getTarget().getRawParameterTypes().stream().anyMatch(ArchitectureTest::isCharArray))
                .map(access -> describe(access.getOrigin()))
                .collect(Collectors.toSet());
    }

    /**
     * {@code UpdateCheck} とその入れ子クラス。
     *
     * <p>照合の作法は {@link #isLogs()} と同じ理由で名前による。前置詞にすると
     * {@code UpdateCheckScheduler} のような別のクラスまで通してしまう。
     */
    private static DescribedPredicate<JavaClass> isUpdateCheck() {
        return DescribedPredicate.describe(
                "UpdateCheck とその入れ子クラス",
                javaClass -> javaClass.getName().equals(UPDATE_CHECK)
                        || javaClass.getName().startsWith(UPDATE_CHECK + "$"));
    }

    /**
     * その名前のクラスとその入れ子クラス。
     *
     * <p>照合の作法は {@link #isLogs()} と同じ理由で名前による。前置詞にすると
     * {@code MainWindowHelper} のような別のクラスまで通してしまうので、
     * <b>厳密一致か、入れ子を表す {@code $} で始まるものだけ</b>を通す。
     */
    private static DescribedPredicate<JavaClass> named(String className) {
        return DescribedPredicate.describe(
                className + " とその入れ子クラス",
                javaClass -> javaClass.getName().equals(className)
                        || javaClass.getName().startsWith(className + "$"));
    }

    /**
     * {@code Logs} とその入れ子クラス。
     *
     * <p>正規表現ではなく名前で照合する。{@code Logs} を前置詞にすると
     * {@code LogsHelper} のような別のクラスまで通してしまうので、
     * <b>厳密一致か、入れ子を表す {@code $} で始まるものだけ</b>を通す。
     */
    private static DescribedPredicate<JavaClass> isLogs() {
        return DescribedPredicate.describe(
                "Logs とその入れ子クラス",
                javaClass ->
                        javaClass.getName().equals(LOGS) || javaClass.getName().startsWith(LOGS + "$"));
    }
}
