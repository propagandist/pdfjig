package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.propagandist.pdfjig.core.PageSelection;
import io.github.propagandist.pdfjig.core.TestPdfs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 控えを抱えたまま失敗したとき、その在り処が利用者へ届くこと（#124）。
 *
 * <p><b>★★ この状態はテストから作れないと書いていたが、誤りだった。</b>「入れ替えと巻き戻しは
 * 同じ 2 つのパスの間の逆向きの改名であり、片方を塞ぐ条件はもう片方も塞ぐ」——
 * <b>塞ぐ場所を「パス」ではなく「権利」で考えると非対称がある。</b>
 *
 * <p><b>Windows の書き込み権は、ファイルを足す権利（{@code FILE_ADD_FILE}）と、
 * フォルダを足す権利（{@code FILE_ADD_SUBDIRECTORY}）が別である。</b>
 * 出力先フォルダで<b>前者だけを拒む</b>と、こうなる。
 *
 * <ul>
 *   <li><b>作業場所は作れる</b>（フォルダなので後者）——{@code OutputWorkspace#nextTo} は通る
 *   <li><b>退避は通る</b>——出力先から<b>出す</b>のは削除の権利で、<b>入れる</b>先は作業場所の中である
 *   <li><b>入れ替えも巻き戻しも落ちる</b>——どちらも<b>出力先へファイルを足す</b>
 * </ul>
 *
 * <p><b>だから「控えを抱えたまま、入れ替えにも巻き戻しにも失敗した」状態がそのまま作れる。</b>
 * ★ <b>この筋が無いと、#124 の配線（{@code DocumentWriter#assemble} の {@code catch}）を
 * 消しても何も赤くならない</b>——他のテストはどれも {@code assemble} を通らない。
 *
 * <p><b>Windows でだけ走る。</b>POSIX の書き込み権はファイルとディレクトリを分けないので、
 * 同じ非対称を作れない（拒めば退避も落ちる）。
 */
@EnabledOnOs(OS.WINDOWS)
class KeptCopyReportTest {

    /**
     * 控えを抱えたまま失敗したら、その在り処を載せて投げる。
     *
     * <p><b>★★ 端から端まで通す。</b>{@code assemble} が作業場所を開き、書き、退避し、
     * 入れ替えに失敗し、巻き戻しにも失敗し、包んで投げるところまでを 1 本で見る。
     * <b>そのうえで、載っているパスに元の中身が本当にあることまで確かめる</b>——
     * 在り処を言うだけなら、間違った在り処でも言える。
     */
    @Test
    @DisplayName("控えを抱えたまま失敗したら、その在り処を載せて投げる")
    void reportsWhereTheOriginalIsKept(@TempDir Path directory) throws IOException {
        Path output = directory.resolve("out.pdf");
        TestPdfs.plain(output, 3);
        long originalSize = Files.size(output);
        Path source = TestPdfs.plain(directory.resolve("source.pdf"), 2);

        AclEntry denial = denyAddingFilesTo(directory);
        try {
            ReplacedFileKeptException kept = assertThrows(
                    ReplacedFileKeptException.class,
                    () -> DocumentWriter.assemble(List.of(source), List.of(PageSelection.of(1)), output));

            assertTrue(Files.notExists(output), "入れ替えに失敗したのに出力先に何かある。前提が変わっている");
            assertTrue(Files.exists(kept.kept()), "在り処として載せたパスに何も無い。利用者は探しに行って見つけられない（#124）");
            assertEquals(originalSize, Files.size(kept.kept()), "載せたパスにあるのが元のファイルではない");
            assertTrue(Messages.describe(kept).contains(kept.kept().toString()), "画面に出る文言に在り処が入っていない");
        } finally {
            // @TempDir が片づけられるように戻す。残すと後続のテストの一時領域も壊れる。
            allowAgain(directory, denial);
        }
    }

    /**
     * 出力先フォルダに<b>ファイルを</b>足せなくする。フォルダを足すことは拒まない。
     *
     * <p><b>★ 継承の旗を立てない。</b>立てると作業場所の中にも降りてしまい、
     * <b>退避そのものが落ちて</b>作りたい状態にならない。
     */
    private static AclEntry denyAddingFilesTo(Path directory) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        UserPrincipal owner = view.getOwner();
        AclEntry denial = AclEntry.newBuilder()
                .setType(AclEntryType.DENY)
                .setPrincipal(owner)
                // ADD_FILE と WRITE_DATA は同じ定数である（どちらも FILE_ADD_FILE）。
                .setPermissions(Set.of(AclEntryPermission.ADD_FILE))
                .build();

        List<AclEntry> acl = new ArrayList<>(view.getAcl());
        // 拒否は先頭に置く。Windows は前から順に評価するので、後ろに置くと許可のほうが先に当たる。
        acl.add(0, denial);
        view.setAcl(acl);
        return denial;
    }

    private static void allowAgain(Path directory, AclEntry denial) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        List<AclEntry> acl = new ArrayList<>(view.getAcl());
        acl.remove(denial);
        view.setAcl(acl);
    }
}
