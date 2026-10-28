package io.github.propagandist.pdfjig.core;

import java.util.List;

/**
 * pdfjig の唯一の非チェック例外。
 *
 * <p><b>原因例外を連結しない。</b> PDFBox をはじめとする依存ライブラリの例外メッセージには
 * 入力値が埋め込まれている可能性があり、{@code printStackTrace} やログ出力を経由して
 * パスワードが漏れる経路になりうる（CLAUDE.md INV-5）。
 * 診断に必要な情報は原因例外の <b>型名と、投げられた位置のフレームだけ</b> を保持する。
 *
 * <p><b>★ フレームは持つが、繋がない</b>（#152）。{@link StackTraceElement} が持つのはクラス名・メソッド名・
 * ソースファイル名・行番号だけで、利用者の入力は入らない。<b>持たないと、包んだ失敗がどこで起きたのかが
 * 診断ファイルから読めなくなる。</b>連結すると {@code printStackTrace} が原因のメッセージまで出すので、
 * 別に持って、書く側（診断ログ）が読む。
 */
public final class PdfjigException extends RuntimeException {

    /** 原因のフレームを持たせたときに上げた（#152）。古い形は {@code InvalidClassException} で読めない。 */
    private static final long serialVersionUID = 2L;

    private static final StackTraceElement[] NO_FRAMES = new StackTraceElement[0];

    private final ErrorCode errorCode;

    /** 原因例外の型名。原因を持たない場合は {@code null}。 */
    private final String causeType;

    /**
     * 原因例外が投げられた位置のフレーム。原因を持たない場合は空。
     *
     * <p>配列で持つのは直列化のためである（{@link List} の実装は直列化できるとは限らない）。外へは写しを返す。
     */
    private final StackTraceElement[] causeFrames;

    public PdfjigException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.causeType = null;
        this.causeFrames = NO_FRAMES;
    }

    private PdfjigException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.causeType = cause.getClass().getName();
        this.causeFrames = cause.getStackTrace();
    }

    /**
     * 依存ライブラリの例外を包む。
     *
     * <p>{@code cause} は {@link Throwable#initCause} で連結せず、型名とフレームだけを取り出す。
     * メッセージは引き継がない。フレームは {@link #causeFrames} で別に持ち、自分のスタックトレースには混ぜない。
     *
     * <p><b>★★ 既に包んであるものは、そのまま返す。</b>未検査例外まで捕まえる
     * {@code catch} は<b>自分で分類した失敗もろとも拾う</b>ので、ここで通さないと
     * <b>{@code PAGE_OUT_OF_RANGE} が「抽出できませんでした」に化ける</b>——
     * <b>利用者は次に何をすればよいか分からなくなる</b>（CLAUDE.md 優先順位 2）。
     * <b>その判断をここに置くのは、境界を足すたびに写されるのを止めるためである</b>
     * ——#150 の前は、同じ 1 行が 7 か所に別々の綴りで在った。
     *
     * @param errorCode 失敗の分類。<b>{@code cause} が既に {@link PdfjigException} なら使われない</b>
     * @param cause     元の例外。メッセージは読まれない
     * @return 原因の型名とフレームだけを保持する例外
     */
    public static PdfjigException wrapping(ErrorCode errorCode, Throwable cause) {
        if (cause instanceof PdfjigException already) {
            return already;
        }
        return new PdfjigException(errorCode, cause);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 原因例外の型名を返す。
     *
     * @return 完全修飾クラス名。原因を持たない場合は {@code null}
     */
    public String causeType() {
        return causeType;
    }

    /**
     * 原因例外が投げられた位置のフレームを返す（#152）。
     *
     * @return 手前から並べたフレーム。原因を持たない場合は空
     */
    public List<StackTraceElement> causeFrames() {
        return List.of(causeFrames);
    }
}
