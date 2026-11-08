package io.github.propagandist.pdfjig.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 確認の窓が、保護して書き出す道をどう案内するか（#302）。
 *
 * <p><b>★★ 案内する名前は書き写さず、渡された道から組む。</b>書き写すと、ボタンやメニューを動かしたときに
 * <b>案内どおりに辿れない窓が残る</b>（{@code CLAUDE.md} 優先順位 2）。画面に実際に出ている名前と
 * 突き合わせるのは {@code ProtectionPromptUiTest} だが、<b>あれは {@code build} に入らない。</b>
 * ここは素の {@code test} なので、手元の {@code ./gradlew build} でも落ちる。
 *
 * <p>道の名前は、どれも画面に出ないものにしてある。<b>組まずに書き写していれば、ここで落ちる。</b>
 */
class ProtectionPromptTest {

    private static final ProtectionPrompt.Route ROUTE = new ProtectionPrompt.Route("鍵ボタン", "書類", "鍵を掛けて書き出す…");

    @Test
    @DisplayName("保存の確認は、渡された道を 2 つとも案内する")
    void plainNamesBothRoutes() {
        String advice = ProtectionPrompt.Outcome.PLAIN.advice(ROUTE);

        assertTrue(advice.contains("ツールバーの「鍵ボタン」"), "ツールバーの道を組んでいない: " + advice);
        assertTrue(advice.contains("メニューの「書類」→「鍵を掛けて書き出す…」"), "メニューの道を組んでいない: " + advice);
    }

    @Test
    @DisplayName("分割の確認は、保存へ案内しない")
    void splitDoesNotPointToSaving() {
        // ★★ 「パスワードで保護して保存」は分割しない 1 ファイルを書くので、分割の代わりにならない（#172 の門）。
        String advice = ProtectionPrompt.Outcome.PLAIN_SPLIT.advice(ROUTE);

        assertFalse(advice.contains("鍵ボタン"), "分割の代わりにならない保存へ案内している: " + advice);
        assertFalse(advice.contains("鍵を掛けて書き出す"), "分割の代わりにならない保存へ案内している: " + advice);
    }

    @Test
    @DisplayName("保護を掛けている最中の確認は、保護して書き出す道へ案内しない")
    void opensWithoutAKeyDoesNotPointToProtecting() {
        // いま保護して書き出している最中である。案内すべきはユーザーパスワードの設定であって、別の道ではない。
        String advice = ProtectionPrompt.Outcome.OPENS_WITHOUT_A_KEY.advice(ROUTE);

        assertFalse(advice.contains("鍵ボタン"), "保護を掛けている最中に、同じ操作へ案内している: " + advice);
        assertFalse(advice.contains("鍵を掛けて書き出す"), "保護を掛けている最中に、同じ操作へ案内している: " + advice);
    }

    @Test
    @DisplayName("ツールバーに出していなければ、メニューの道だけを案内する")
    void plainWithoutToolbarNamesOnlyTheMenu() {
        // ★ ツールバーから外した日に「ツールバーの「null」」と出さない。無い道を案内しない（優先順位 2）。
        String advice = ProtectionPrompt.Outcome.PLAIN.advice(new ProtectionPrompt.Route(null, "書類", "鍵を掛けて書き出す…"));

        assertFalse(advice.contains("ツールバー"), "出していないツールバーへ案内している: " + advice);
        assertFalse(advice.contains("null"), "無い名前をそのまま出している: " + advice);
        assertTrue(advice.contains("中止して、メニューの「書類」→「鍵を掛けて書き出す…」を使ってください。"), "メニューの道を組んでいない: " + advice);
    }
}
