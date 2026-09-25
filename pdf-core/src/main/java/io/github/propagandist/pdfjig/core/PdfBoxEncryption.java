package io.github.propagandist.pdfjig.core;

import static io.github.propagandist.pdfjig.core.PdfBoxGuard.guarded;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * PDFBox による {@link Encryption} の実装。
 *
 * <p>状態を持たない。複数スレッドから同時に呼び出してよい。
 *
 * <p><b>★★ 開くのは {@link PdfDocument} を通す。</b>{@code Loader} を直に呼ばない——
 * <b>INV-5 の {@code String} 化も、PDFBox の例外の分類も、あちらが 1 か所で持っている。</b>
 * ここが自前で開くと、<b>同じ判断の写しが 2 つになり、片方が腐る。</b>
 */
public final class PdfBoxEncryption implements Encryption {

    @Override
    public EncryptionInfo inspect(Path input) {
        return guarded(ErrorCode.NOT_A_PDF, () -> inspectAt(input));
    }

    /**
     * 暗号化の状態を読む実体。
     *
     * <p><b>★ 公開の {@code inspect} どうしで呼び合わない。</b>包みが二重になるだけなら
     * 害は無いが、<b>{@code pdf-archtest} が「公開の入口はどれも包みを通る」を見るとき、
     * 呼び合いは入口の形を読みにくくする</b>（#178）。
     */
    private static EncryptionInfo inspectAt(Path input) {
        try (PdfDocument document = PdfDocument.open(input)) {
            return document.encryption();
        } catch (PdfjigException e) {
            if (e.errorCode() == ErrorCode.PASSWORD_REQUIRED) {
                // ★★ 開けなかった。暗号化されていることは分かるが、方式も権限も読めていない
                //   ——PDFBox は文書を返さないので、暗号化辞書そのものが手に入らない
                //   （2026-09-12 実測）。NONE ではなく UNKNOWN を返す（EncryptionAlgorithm）。
                //   ★ 権限に all() が入るのは置き値であって、読めた値ではない（#187）。
                return new EncryptionInfo(true, EncryptionAlgorithm.UNKNOWN, true, AccessPermissions.all());
            }
            throw e;
        }
    }

    @Override
    public EncryptionInfo inspect(Path input, Password password) {
        return guarded(ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE, () -> {
            // ★ パスワードが要ったかどうかは、渡して開いただけでは分からない——オーナー
            //   パスワードだけの文書も同じ道で開ける。先に訊いてから、その答えを載せる。
            boolean required = inspectAt(input).userPasswordRequired();
            try (PdfDocument document = PdfDocument.open(input, password)) {
                return document.encryptionWith(required);
            }
        });
    }

    @Override
    public Path protect(
            Path input,
            Password userPassword,
            Password ownerPassword,
            AccessPermissions permissions,
            EncryptionAlgorithm algorithm,
            Path output) {
        requireAbsent(output);

        // ★★ この包みは消さない。入力を閉じ損ねただけで書けた出力が消えるのを避けるため、
        //   消す判断は protectInto の中だけに置いてある（下）。ここは符号を畳むだけである。
        // ★ 値をまとめるのは包みの外である。Protection の検めは引数だけで決まるので、
        //   中でやると IllegalArgumentException が PdfjigException に畳まれる
        //   （PdfBoxPageOperations の冒頭の枠と同じ規律）。
        Protection protection = new Protection(userPassword, ownerPassword, permissions, algorithm);
        return guarded(ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE, () -> {
            try (PdfDocument source = PdfDocument.open(input)) {
                protectInto(source.delegate(), protection, output);
            }
            return output;
        });
    }

    /**
     * 方針を当てて書き出す。
     *
     * <p><b>★★ 閉じる失敗をここへ入れない。</b>呼ぶ側の try-with-resources が入力を閉じるのは
     * この後であり、<b>そこを同じ {@code catch} で覆うと、書けた出力を「失敗した」として消す</b>
     * ——細工 PDF は閉じるときに投げる（#150）。<b>完全に書けた保護付きの出力が、
     * 入力を閉じ損ねただけで消えることになる。</b>
     */
    private static void protectInto(PDDocument document, Protection protection, Path output) {
        try {
            // ★ 方針の組み立ては StandardProtection が持つ。組み立てながら掛ける側（#199）と
            //   同じものを使う——写すと、片方だけ直した設定で書けるようになる。
            StandardProtection.apply(document, protection);

            // ★★ ここが漏えいの関門である（#28 の申し送り）。SASLprep が走るのは protect ではなく
            //   save のほうであり、禁じられた文字に当たると PDFBox は本物のパスワードの文字と
            //   位置をメッセージに載せた IllegalArgumentException を投げる——IOException ではない。
            //   wrapping は型名しか残さないので、ここで包めば漏れない（#144 / INV-5）。
            if (!DurableSave.write(document, output)) {
                // ★ 暗号化には警告を返す口が無い。届いたか確かめられないなら失敗にする——
                //   呼ぶ側が置き換えに使うなら、置き換えを止めるのが正しい（#219）。
                deleteQuietly(output);
                throw new PdfjigException(ErrorCode.OUTPUT_NOT_DURABLE);
            }
        } catch (IOException e) {
            // ★★ 書けなかったことを、パスワードのせいにしない。出力先が無い・ディスクが満杯
            //   といった失敗まで PASSWORD_OR_DOCUMENT_FAILURE に畳むと、利用者は打ち直す
            //   ——ErrorCode 自身が「絞れないときにだけ使う」と書いている符号である。
            deleteQuietly(output);
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        } catch (RuntimeException e) {
            // 書けたところまでを残さない。保護が掛かっていない半端な出力は、
            // 保護されているつもりで配られる元になる（優先順位 1・2）。
            deleteQuietly(output);
            throw PdfjigException.wrapping(ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE, e);
        }
    }

    @Override
    public Path unprotect(Path input, Password password, Path output) {
        requireAbsent(output);

        return guarded(ErrorCode.IO_FAILURE, () -> {
            try (PdfDocument source = PdfDocument.open(input, password)) {
                unprotectInto(source.delegate(), output);
            }
            return output;
        });
    }

    /** 保護を外して書き出す。<b>閉じる失敗をここへ入れない</b>（{@link #protectInto} と同じ理由）。 */
    private static void unprotectInto(PDDocument document, Path output) {
        try {
            document.setAllSecurityToBeRemoved(true);
            if (!DurableSave.write(document, output)) {
                // ★ 暗号化には警告を返す口が無い。届いたか確かめられないなら失敗にする——
                //   呼ぶ側が置き換えに使うなら、置き換えを止めるのが正しい（#219）。
                deleteQuietly(output);
                throw new PdfjigException(ErrorCode.OUTPUT_NOT_DURABLE);
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(output);
            // ★ 自分で分類した失敗は塗り替えない。PdfDocument#open が INVALID_PASSWORD を
            //   付けていれば、wrapping がそれを素通しする。
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    /**
     * 出力が既にあれば拒む。
     *
     * <p>{@code pdf-core} 全体の契約である（{@code docs/SPEC.md} §4.2）。
     * <b>★ 同じ検査が {@code PdfBoxPageOperations} にもある</b>——
     * <b>寄せ先を作るのは差分の外へ広がるので、#187 が持つ。</b>
     */
    private static void requireAbsent(Path output) {
        if (output == null) {
            throw new IllegalArgumentException("output は null にできません。");
        }
        if (Files.exists(output)) {
            throw new PdfjigException(ErrorCode.OUTPUT_ALREADY_EXISTS);
        }
    }

    /** 書きかけを消す。消せなくても、伝えるべきは元の失敗である。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // 握りつぶす。ここで投げると本当の失敗が消える。
        }
    }
}
