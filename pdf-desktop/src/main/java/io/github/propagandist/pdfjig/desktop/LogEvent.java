package io.github.propagandist.pdfjig.desktop;

/**
 * ログに出す事象。
 *
 * <p><b>★ ログの口が受け取れるのはこの列挙だけである</b>（{@link Logs}）。自由な文字列を
 * 受け取る口を作らないのは、<b>そこがパスとファイル名の入口になるから</b>である——
 * {@code docs/SPEC.md} §10.4 が「書かない」と決めたものは、機械で守られていなければ
 * いつか書かれる（CLAUDE.md INV-5 が {@code String} のパスワードを禁じているのと同じ形）。
 *
 * <p>足すときは <b>{@code docs/SPEC.md} §10.4 の表を先に見ること</b>。
 * 事象の名前そのものが文書の特定につながらないかを、足す側が確かめる。
 */
enum LogEvent {

    /** 設定ファイルが読めない・壊れているので捨てた。 */
    SETTINGS_UNREADABLE("設定を読めなかったので既定に戻した"),

    /** 設定ファイルの値 1 つがパスとして読めない。 */
    SETTINGS_VALUE_UNREADABLE("設定の値をパスとして読めなかった"),

    /** 設定ファイルを書けなかった。覚えたフォルダは次の起動に残らない。 */
    SETTINGS_UNWRITABLE("設定を書けなかった"),

    /** 書き出しの作業場所を片づけられなかった。出力先に {@code .pdfjig-*} が残る。 */
    WORKSPACE_NOT_DISCARDED("書き出しの作業場所を片づけられなかった"),

    /** 画面に失敗として出した操作。何が起きたかは利用者にも見えている。 */
    OPERATION_FAILED("操作が失敗した"),

    /** どこも捕まえなかった例外。ここに出るものは想定していない経路である。 */
    UNCAUGHT("捕まえられなかった例外");

    private final String description;

    LogEvent(String description) {
        this.description = description;
    }

    /** 人が読むための説明。 */
    String description() {
        return description;
    }
}
