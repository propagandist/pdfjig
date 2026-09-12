package io.github.propagandist.pdfjig.core;

/**
 * 権限フラグ。
 *
 * <p><b>★★ これは暗号学的に強制されない</b>（{@code docs/SPEC.md} §6.1）。
 * ユーザーパスワードが空なら PDF の中身は暗号化されておらず、<b>閲覧ソフトが自主的に
 * 従っているだけの申告制である。</b>PDFBox を含む任意のライブラリで無視できる。
 *
 * <p><b>pdfjig 自身も、抽出を禁じる設定を無視して抽出する</b>（§6.1.1）——
 * <b>そのことを利用者へ告げる</b>のが、この道具の態度である。
 *
 * @param print                   印刷
 * @param modify                  内容の変更
 * @param extractContent          テキストとグラフィックスの複製
 * @param modifyAnnotations       注釈の変更
 * @param fillForms               フォームへの入力
 * @param assembleDocument        ページの挿入・削除・回転
 * @param extractForAccessibility 支援技術のための複製。<b>既定で許可する</b>
 * @param printHighQuality        高品質での印刷
 */
public record AccessPermissions(
        boolean print,
        boolean modify,
        boolean extractContent,
        boolean modifyAnnotations,
        boolean fillForms,
        boolean assembleDocument,
        boolean extractForAccessibility,
        boolean printHighQuality) {

    /**
     * すべて許可する。
     *
     * <p>暗号化されていない文書が持つ権限であり、{@code inspect} はそれを返す。
     *
     * @return すべて許可した権限
     */
    public static AccessPermissions all() {
        return new AccessPermissions(true, true, true, true, true, true, true, true);
    }

    /**
     * すべて禁じる。ただし<b>支援技術のための複製だけは許可する。</b>
     *
     * <p><b>★★ {@code extractForAccessibility} を塞ぐと、視覚障害者が読めなくなる</b>
     * （{@code docs/SPEC.md} §6.2）。<b>「すべて禁じる」と言うときも、ここは開けておく</b>
     * ——閉じる理由が無く、閉じた文書は読み上げから消える。
     *
     * @return 支援技術のための複製だけを許した権限
     */
    public static AccessPermissions none() {
        return new AccessPermissions(false, false, false, false, false, false, true, false);
    }
}
