package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通信しない部分だけを見る。
 *
 * <p><b>{@code check()} は呼ばない。</b>実際に GitHub へ出るテストを {@code build} に載せると、
 * 手元でもネットワークの都合で赤くなる。<b>経路そのものは画面のテストが通す</b>
 * （{@code MainWindowUiTest#checksForUpdate}。あちらは遮断されていても緑になる形で書いてある）。
 */
class UpdateCheckTest {

    private static final String LOCATION = AppInfo.REPOSITORY + "/releases/tag/";

    @Test
    @DisplayName("Location から公開されている版を読む")
    void readsPublishedVersionFromLocation() {
        assertEquals(Optional.of(new ReleaseVersion(0, 1, 1, false)), UpdateCheck.published(LOCATION + "v0.1.1"));
    }

    @Test
    @DisplayName("想定していない行き先は読まない")
    void refusesUnexpectedLocation() {
        List<String> refused = Arrays.asList(
                // 302 ではなかった（Location ヘッダそのものが無い）。
                null,
                "",
                "  ",
                // 同じホストでも別の場所。
                AppInfo.REPOSITORY + "/releases/latest",
                AppInfo.REPOSITORY + "/tree/v0.1.1",
                // 別のリポジトリ・別のホスト。読むのは版数だけで追いはしないが、
                // 想定していない応答を「新しい版」として出さない（優先順位 2）。
                "https://github.com/someone/else/releases/tag/v9.9.9",
                "https://evil.example.com/releases/tag/v9.9.9",
                // 前置詞が一致していても、そこから先が版数ではない。
                LOCATION + "latest",
                LOCATION + "v0.1");

        for (String location : refused) {
            assertEquals(Optional.empty(), UpdateCheck.published(location), String.valueOf(location));
        }
    }

    @Test
    @DisplayName("接尾辞の付いたタグは読まない")
    void refusesSuffixedTag() {
        // release.yml が版数を ^\d+\.\d+\.\d+$ で縛っており、/releases/latest は prerelease を
        // 指さない。ここへ来るのは想定が崩れているときであり、そのときは黙って答えないほうが正直である。
        assertEquals(Optional.empty(), UpdateCheck.published(LOCATION + "v0.2.0-rc1"));
    }

    @Test
    @DisplayName("古ければ新しい版として出す")
    void reportsNewerVersion() {
        UpdateStatus status =
                UpdateCheck.compare(new ReleaseVersion(0, 1, 1, false), new ReleaseVersion(0, 1, 2, false));

        assertEquals(new UpdateStatus.Available(new ReleaseVersion(0, 1, 2, false)), status);
    }

    @Test
    @DisplayName("同じなら最新として出す")
    void reportsUpToDate() {
        UpdateStatus status =
                UpdateCheck.compare(new ReleaseVersion(0, 1, 2, false), new ReleaseVersion(0, 1, 2, false));

        assertInstanceOf(UpdateStatus.UpToDate.class, status);
    }

    @Test
    @DisplayName("公開されている版より先を行っていても最新として出す")
    void reportsUpToDateWhenAhead() {
        // タグを打つ前の手元のビルドがここに当たる。「古い」と言わないことだけが要る。
        UpdateStatus status =
                UpdateCheck.compare(new ReleaseVersion(0, 2, 0, false), new ReleaseVersion(0, 1, 2, false));

        assertInstanceOf(UpdateStatus.UpToDate.class, status);
    }

    @Test
    @DisplayName("開発版を「最新です」と答えない")
    void neverCallsDevelopmentBuildTheLatest() {
        UpdateStatus status =
                UpdateCheck.compare(new ReleaseVersion(0, 1, 2, true), new ReleaseVersion(0, 1, 2, false));

        assertEquals(new UpdateStatus.Development(new ReleaseVersion(0, 1, 2, false)), status);
        assertTrue(UpdateCheck.describe(status).contains("開発版"), UpdateCheck.describe(status));
    }

    @Test
    @DisplayName("古い開発版は、開発版であることより先に新しい版を出す")
    void tellsAnOldDevelopmentBuildToUpdate() {
        UpdateStatus status =
                UpdateCheck.compare(new ReleaseVersion(0, 1, 1, true), new ReleaseVersion(0, 2, 0, false));

        assertEquals(new UpdateStatus.Available(new ReleaseVersion(0, 2, 0, false)), status);
    }

    @Test
    @DisplayName("新しい版があるときは、その版数を出す")
    void namesTheAvailableVersion() {
        String message = UpdateCheck.describe(new UpdateStatus.Available(new ReleaseVersion(0, 2, 0, false)));

        assertTrue(message.contains("0.2.0"), message);
    }

    @Test
    @DisplayName("確認できなかったことは、理由を分けずに 1 行で出す")
    void describesFailureAsOneLine() {
        String message = UpdateCheck.describe(new UpdateStatus.Unavailable());

        assertTrue(message.contains("確認できませんでした"), message);
        assertEquals(1, message.lines().count(), message);
    }

    @Test
    @DisplayName("どの結果にも出す文がある")
    void describesEveryStatus() {
        List<UpdateStatus> all = List.of(
                new UpdateStatus.Available(new ReleaseVersion(0, 2, 0, false)),
                new UpdateStatus.UpToDate(),
                new UpdateStatus.Development(new ReleaseVersion(0, 1, 2, false)),
                new UpdateStatus.Unavailable());

        for (UpdateStatus status : all) {
            assertTrue(!UpdateCheck.describe(status).isBlank(), status.toString());
        }
    }

    @Test
    @DisplayName("問い合わせ先は、案内する先と同じ 1 つである")
    void asksWhereItSends() {
        // 別々に持つと、片方だけ直したときに「新しい版がある」と言いながら違う場所へ案内する。
        assertTrue(AppInfo.LATEST_RELEASE.startsWith(AppInfo.REPOSITORY), AppInfo.LATEST_RELEASE);
        assertEquals(AppInfo.REPOSITORY + "/releases/latest", AppInfo.LATEST_RELEASE);
    }

    @Test
    @DisplayName("★ 平文で出ていかない")
    void neverLeavesInPlainText() {
        // s を 1 文字消すだけで、問い合わせも「Releases を開く」も平文になる。
        // TrustManager をいじる類の変更と違い、差分は 1 文字で、他のテストは全部緑のまま通る。
        assertTrue(AppInfo.REPOSITORY.startsWith("https://"), AppInfo.REPOSITORY);
        assertTrue(AppInfo.LATEST_RELEASE.startsWith("https://"), AppInfo.LATEST_RELEASE);
    }
}
