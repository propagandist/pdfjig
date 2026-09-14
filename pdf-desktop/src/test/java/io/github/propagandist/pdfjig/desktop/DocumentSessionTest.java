package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.Password;
import io.github.propagandist.pdfjig.core.TestPdfs;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 書き出す前に問う相手を決めるところ（#29 / #192）。
 *
 * <p><b>★ 画面を出さないので、素の {@code test} から縛れる。</b>
 * ここが答える規則は<b>窓の中身よりも間違えやすい</b>——
 * 「開いているものが保護されているか」と「出力に寄与する入力が保護されているか」は
 * <b>ふつうの操作で食い違う</b>（足した文書のページを全部消して保存する）。
 */
class DocumentSessionTest {

    @Test
    @DisplayName("鍵を渡して開いた出どころだけを挙げる")
    void listsOnlyKeyedSources(@TempDir Path dir) throws Exception {
        Path plain = TestPdfs.plain(dir.resolve("plain.pdf"), 1);
        Path locked = TestPdfs.encrypted(dir.resolve("locked.pdf"), "key");

        try (Password key = Password.copyOf("key");
                DocumentSession session = DocumentSession.open(plain)) {
            session.add(locked, key);

            assertEquals(
                    List.of("locked.pdf"),
                    session.keyedContributors(List.of(PageSelection.of(0, 1), PageSelection.of(1, 1))));
        }
    }

    @Test
    @DisplayName("★★ 足しただけでページを 1 枚も使わない出どころは挙げない")
    void staysSilentAboutSourcesThatDoNotReachTheOutput(@TempDir Path dir) throws Exception {
        // ★★ 画面で「PDF を追加」したあと、足したほうのページを 1 枚残らず消してから保存すると
        //   この形になる。出力に 1 バイトも入らないのに問えば、中身と食い違う窓になり、
        //   次に本物を扱ったときに読まずに押される（docs/SPEC.md §4.3.1。優先順位 2）。
        Path plain = TestPdfs.plain(dir.resolve("plain.pdf"), 1);
        Path locked = TestPdfs.encrypted(dir.resolve("locked.pdf"), "key");

        try (Password key = Password.copyOf("key");
                DocumentSession session = DocumentSession.open(plain)) {
            session.add(locked, key);

            assertEquals(List.of(), session.keyedContributors(List.of(PageSelection.of(0, 1))));
        }
    }

    @Test
    @DisplayName("★★ オーナーパスワードだけの文書は挙げない（鍵を打たずに開けている）")
    void staysSilentAboutOwnerOnlyProtection(@TempDir Path dir) throws Exception {
        // ★★ ここで止める窓を出すと、本物の機密文書に当たる前に「読まずに続行を押す」習慣ができる。
        //   暗号化はされているので encrypted() は真である——見ているのはそちらではない。
        Path owner = TestPdfs.ownerProtected(dir.resolve("owner.pdf"), "owner", 1);

        try (DocumentSession session = DocumentSession.open(owner)) {
            assertEquals(List.of(), session.keyedContributors(List.of(PageSelection.of(0, 1))));
        }
    }

    @Test
    @DisplayName("同じ出どころを何ページ使っても 1 度しか挙げない")
    void namesEachSourceOnce(@TempDir Path dir) throws Exception {
        Path locked = TestPdfs.encrypted(dir.resolve("locked.pdf"), "key", 2);

        try (Password key = Password.copyOf("key");
                DocumentSession session = DocumentSession.open(locked, key)) {
            assertEquals(
                    List.of("locked.pdf"),
                    session.keyedContributors(List.of(PageSelection.of(0, 1), PageSelection.of(0, 2))));
        }
    }
}
