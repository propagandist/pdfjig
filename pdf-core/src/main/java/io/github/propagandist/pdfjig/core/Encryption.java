package io.github.propagandist.pdfjig.core;

import java.nio.file.Path;

/**
 * 暗号化の設定・解除・判定。
 *
 * <p><b>★★ 受け取った {@link Password} は読むだけである。</b>消すのは作った場所であり
 * （{@link Password}）、<b>{@code protect} が秘密を 2 本取っても、呼ぶ側が並べて宣言すれば
 * 1 本目で投げたときに 2 本目が残らない</b>——#146 が「手で書いた {@code finally} が
 * いちばん間違えるところ」と呼んだ経路が、構文で片づく。
 *
 * <p><b>既存の出力は拒む</b>（{@link ErrorCode#OUTPUT_ALREADY_EXISTS}）。
 * {@code pdf-core} 全体の契約である（{@code docs/SPEC.md} §4.2）。
 */
public interface Encryption {

    /**
     * 暗号化の状態を調べる。
     *
     * <p><b>パスワードは要らない。</b>ただし<b>ユーザーパスワードが要る文書では、
     * 方式と権限を読めない</b>——PDFBox はそのとき文書を返さないので、
     * <b>暗号化辞書そのものが手に入らない</b>（2026-09-12 実測）。
     * そのときは {@link EncryptionAlgorithm#UNKNOWN} と、
     * <b>すべて許可</b>（{@link AccessPermissions#all()}）が返る
     * ——<b>権限を読めたと誤解しないこと。</b>読むなら {@link #inspect(Path, Password)} を使う。
     *
     * <p><b>オーナーパスワードだけが掛かった文書は、パスワードなしでも全部読める。</b>
     *
     * @param input 入力ファイル
     * @return 暗号化の状態
     * @throws PdfjigException 読めない場合は {@link ErrorCode#FILE_NOT_FOUND}、
     *                         PDF として読めない場合は {@link ErrorCode#NOT_A_PDF}
     */
    EncryptionInfo inspect(Path input);

    /**
     * パスワードを使って暗号化の状態を調べる。
     *
     * <p><b>ユーザーパスワードが要る文書の方式と権限を読むには、こちらを使う。</b>
     *
     * <p><b>★ 読むのは文書に書かれた権限であって、認証後の実効権限ではない</b>
     * （{@code docs/SPEC.md} §4.3.1）——<b>オーナーパスワードで開くと、PDFBox は
     * すべてを許可した値を返す。</b>同じ文書でも<b>誰が開いたかで答えが変わってはならない。</b>
     *
     * @param input    入力ファイル
     * @param password 開くためのパスワード
     * @return 暗号化の状態
     * @throws PdfjigException パスワードが違う場合は {@link ErrorCode#INVALID_PASSWORD}
     */
    EncryptionInfo inspect(Path input, Password password);

    /**
     * 暗号化して書き出す。
     *
     * <p><b>★ 権限フラグは暗号学的に強制されない</b>（{@link AccessPermissions}）。
     * <b>ユーザーパスワードを空にすると、中身は暗号化されず、権限は申告制になる。</b>
     *
     * @param input          入力ファイル
     * @param userPassword   開くためのパスワード。<b>空なら中身は暗号化されない</b>
     * @param ownerPassword  権限を変えるためのパスワード
     * @param permissions    権限フラグ
     * @param algorithm      方式。<b>{@link EncryptionAlgorithm#NONE} は渡せない</b>
     * @param output         出力ファイル
     * @return 書き出したファイル
     * @throws PdfjigException 出力が既にある場合は {@link ErrorCode#OUTPUT_ALREADY_EXISTS}、
     *                         方式が {@code NONE} の場合は {@link ErrorCode#UNSUPPORTED_ENCRYPTION}
     */
    Path protect(
            Path input,
            Password userPassword,
            Password ownerPassword,
            AccessPermissions permissions,
            EncryptionAlgorithm algorithm,
            Path output);

    /**
     * 保護を外して書き出す。
     *
     * <p><b>出力は平文になる。</b>
     *
     * @param input    入力ファイル
     * @param password 開くためのパスワード
     * @param output   出力ファイル
     * @return 書き出したファイル
     * @throws PdfjigException 出力が既にある場合は {@link ErrorCode#OUTPUT_ALREADY_EXISTS}、
     *                         パスワードが違う場合は {@link ErrorCode#INVALID_PASSWORD}
     */
    Path unprotect(Path input, Password password, Path output);
}
