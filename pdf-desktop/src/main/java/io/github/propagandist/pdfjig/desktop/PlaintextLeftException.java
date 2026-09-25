package io.github.propagandist.pdfjig.desktop;

import java.nio.file.Path;

/**
 * 書き出しに失敗し、復号した中身のファイルを作業場所から消し損ねたこと（#184）。
 *
 * <p><b>★★ 利用者が読むのは「保存に失敗した」であり、平文は書かれていないと信じる。</b>
 * 消し損ねたなら、在ることと場所を言うしかない（{@code SECURITY.md}「対象範囲」2 番目）。
 *
 * <p><b>元を抱えたまま失敗した回は、こちらではなく {@link ReplacedFileKeptException} が運ぶ</b>
 * （そちらも消し損ねた平文を持てる）。こちらは<b>元を抱えていない失敗</b>のためにある——
 * 流用すると「元がここにしか無い」という名前が嘘になる。
 *
 * <p><b>作ってよいのは作業場所だけである</b>（{@link OutputWorkspace#failing}）。
 * メッセージに場所を入れない理由は {@link ReplacedFileKeptException} と同じである。
 */
final class PlaintextLeftException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 消し損ねたファイル。{@code transient} の理由は {@link ReplacedFileKeptException} と同じ。 */
    private final transient Path plaintext;

    PlaintextLeftException(Path plaintext, Throwable failed) {
        super("復号した中身を作業場所に残したまま失敗した", failed);
        this.plaintext = plaintext;
    }

    /**
     * 消し損ねたファイルを返す。
     *
     * @return 復号した中身が残っているファイルのパス
     */
    Path plaintext() {
        return plaintext;
    }
}
