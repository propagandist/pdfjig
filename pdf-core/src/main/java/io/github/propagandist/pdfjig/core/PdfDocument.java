package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.PDCryptFilterDictionary;
import org.apache.pdfbox.pdmodel.encryption.PDEncryption;

/**
 * 開かれた PDF 文書のハンドル。
 *
 * <p>必ず try-with-resources で扱うこと。
 *
 * <p>PDFBox の {@link PDDocument} はこのクラスの外に漏らさない。
 * 取得手段はパッケージプライベートの {@link #delegate()} のみであり、
 * 他モジュールから PDFBox の型に触れる経路は存在しない。
 */
public final class PdfDocument implements AutoCloseable {

    private final PDDocument delegate;

    /**
     * パスワードなしでは開けなかったか。
     *
     * <p><b>★★ 開いたときにしか分からない。</b>暗号化辞書からは読めない——
     * <b>ユーザーパスワードが空かどうかはハッシュ化されており、実際に試すしかない。</b>
     * だから<b>開いた側が覚えておく。</b>
     */
    private final boolean userPasswordRequired;

    private PdfDocument(PDDocument delegate, boolean userPasswordRequired) {
        this.delegate = delegate;
        this.userPasswordRequired = userPasswordRequired;
    }

    /**
     * パスワードなしで開く。
     *
     * @param path 入力ファイル
     * @return 開かれた文書
     * @throws PdfjigException 開けない場合。暗号化されている場合は
     *                         {@link ErrorCode#PASSWORD_REQUIRED}、
     *                         読めない場合は {@link ErrorCode#FILE_NOT_FOUND}。
     *                         それ以外はすべて {@link ErrorCode#NOT_A_PDF} になる——
     *                         <b>PDF でないこととは限らない。</b>原因を絞る材料が無い
     */
    public static PdfDocument open(Path path) {
        requireReadable(path);
        try {
            return new PdfDocument(Loader.loadPDF(path.toFile()), false);
        } catch (InvalidPasswordException e) {
            throw PdfjigException.wrapping(ErrorCode.PASSWORD_REQUIRED, e);
        } catch (IOException | RuntimeException e) {
            // ★ PDFBox は IOException ではない例外も投げる（#144）。ここでは秘密を持たないので
            //   INV-5 には当たらないが、包むのは「外へ出るのは PdfjigException だけ」という
            //   契約のためである——包まないと、呼ぶ側の分岐がどれも当たらない。
            //   ★ 分類を細かくする材料がここには無い。開こうとして駄目だった、しか分からない。
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    /**
     * パスワード付きで開く。
     *
     * <p><b>★★ 渡された {@code password} は読むだけである。</b>ゼロ埋めはしない——
     * <b>消すのは作った場所である</b>（{@link Password}）。ここで消すと、
     * <b>片づけが 2 か所になり、どちらが持ち主かを註でしか書けなくなる。</b>
     *
     * <p><b>既知の限界:</b> PDFBox 3 の {@code Loader.loadPDF} は {@code String} しか受け付けない。
     * そのため境界で一度だけ {@code String} が生成され、これは GC されるまでヒープに残り、
     * 明示的なゼロ埋めができない。pdfjig 側でこれを回避する手段はない。
     * 生成箇所をこの 1 か所に限定することで影響範囲を最小化している。
     *
     * @param path     入力ファイル
     * @param password パスワード。ここでは消さない
     * @return 開かれた文書
     * @throws PdfjigException 開けない場合。パスワード誤りは
     *                         {@link ErrorCode#INVALID_PASSWORD}、
     *                         読めない場合は {@link ErrorCode#FILE_NOT_FOUND}、
     *                         PDF として読めない場合は {@link ErrorCode#NOT_A_PDF}、
     *                         原因を絞れない場合は {@link ErrorCode#PASSWORD_OR_DOCUMENT_FAILURE}
     */
    public static PdfDocument open(Path path, Password password) {
        try {
            requireReadable(path);
            // INV-5 の境界。PDFBox の API 制約により String 化は避けられない。
            String boundaryPassword = new String(password.value());
            // ★ パスワードを渡して開けたが、それが要ったかどうかはここからは分からない
            //   ——オーナーパスワードだけの文書も、同じ道で開ける。requiredWhenOpened が
            //   知っている呼ぶ側だけが、正しい値を載せられる（Encryption#inspect）。
            return new PdfDocument(Loader.loadPDF(path.toFile(), boundaryPassword), true);
        } catch (InvalidPasswordException e) {
            throw PdfjigException.wrapping(ErrorCode.INVALID_PASSWORD, e);
        } catch (IOException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        } catch (RuntimeException e) {
            // ★★ INV-5。PDFBox は AES-256 のとき、照合する前に SASLprep を通す。禁止文字に
            //   当たると、本物のパスワードの文字と位置をメッセージに載せた
            //   IllegalArgumentException を投げる——IOException ではないので、上の 2 つに
            //   当たらない。wrapping は型名しか残さないので、ここで包めば漏れない（#144）。
            //   ★ どちらが原因かは、呼んだ側からは区別が付かない。文書かもしれず、
            //   パスワードかもしれない。分からないことを分からないまま伝える。
            throw PdfjigException.wrapping(ErrorCode.PASSWORD_OR_DOCUMENT_FAILURE, e);
        }
    }

    /**
     * 総ページ数。
     *
     * <p><b>★ 符号は {@link ErrorCode#NOT_A_PDF} である</b>——<b>開けたのだから PDF では
     * あったが、読もうとしたところが壊れていた。</b>{@link #open(Path)} の
     * 「PDF でないこととは限らない」と同じ扱いであり、<b>原因を絞る材料がここにも無い。</b>
     * 下の {@link #encrypted()} と {@link #signed()} も同じである。
     *
     * @throws PdfjigException 数えられない場合は {@link ErrorCode#NOT_A_PDF}
     */
    public int pageCount() {
        try {
            return delegate.getNumberOfPages();
        } catch (RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    /**
     * 暗号化の状態。
     *
     * <p><b>★★ 読む手段はここである</b>（{@code docs/SPEC.md} §4.1 / §6.1.1）——
     * {@code Encryption#inspect} は {@code Path} を取るので、<b>既に開いた文書からは呼べず、
     * パスワードの要る文書では開き直せない。</b>
     *
     * <p><b>★ 権限は素の {@code /P} を読む。</b>{@code getCurrentAccessPermission} は
     * 認証の結果であり、<b>オーナーパスワードで開くとすべてを許可した値になる</b>
     * ——<b>同じ文書でも誰が開いたかで答えが変わってはならない</b>（§4.3.1）。
     *
     * <p><b>★★ まだ公開しない。</b>{@code open(Path, Password)} で開いたときの
     * {@code userPasswordRequired} は<b>「パスワードを渡した」しか意味せず、
     * オーナーパスワードだけの文書でも真になる</b>——<b>公開の口が嘘を返す。</b>
     * <b>言い直せる呼ぶ側</b>（{@link #encryptionWith}）<b>を通す。</b>
     * 公開するのは #180 が正しい形を決めてからである。
     *
     * @return 暗号化の状態
     * @throws PdfjigException 読めない場合は {@link ErrorCode#NOT_A_PDF}
     */
    EncryptionInfo encryption() {
        try {
            if (!delegate.isEncrypted()) {
                return EncryptionInfo.none();
            }
            PDEncryption encryption = delegate.getEncryption();
            return new EncryptionInfo(
                    true,
                    algorithmOf(encryption),
                    userPasswordRequired,
                    permissionsOf(new AccessPermission(encryption.getPermissions())));
        } catch (RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    /**
     * 同じ状態を、パスワードが要ったかどうかを言い直して返す。
     *
     * <p><b>開いた側は「パスワードを渡した」ことしか知らない</b>——
     * <b>それが要ったのか、オーナーパスワードだっただけなのかは、試さないと分からない。</b>
     * 知っている呼ぶ側（{@code Encryption#inspect(Path, Password)}）が言い直す。
     *
     * @param required パスワードなしでは開けなかったか
     * @return その値を載せた状態
     */
    EncryptionInfo encryptionWith(boolean required) {
        EncryptionInfo info = encryption();
        return new EncryptionInfo(info.encrypted(), info.algorithm(), required, info.permissions());
    }

    /**
     * 方式を読む。
     *
     * <p><b>鍵の長さだけでは足りない</b>——128 ビットは RC4 と AES の両方にある。
     *
     * <p><b>★★ 版（{@code /R}）だけでも足りない。</b>{@code /V 4 /R 4} は
     * <b>暗号フィルタの方式（{@code /CFM}）で AES と RC4 に分かれる</b>——
     * Acrobat が「Acrobat 6.0 以降」で書くのは {@code /CFM /V2}（RC4-128）である。
     * <b>そこを見ずに版だけで決めると、RC4 の文書を AES-128 と答える。</b>
     * ★ <b>往復のテストでは捕まらない</b>——PDFBox 自身が書く RC4-128 は {@code /V 2 /R 3}
     * であり、{@code /R 4} の枝を通らない。
     */
    private static EncryptionAlgorithm algorithmOf(PDEncryption encryption) {
        int revision = encryption.getRevision();
        if (revision >= 5) {
            return EncryptionAlgorithm.AES_256;
        }
        if (revision == 4) {
            return filterAlgorithm(encryption);
        }
        return encryption.getLength() > 40 ? EncryptionAlgorithm.RC4_128 : EncryptionAlgorithm.RC4_40;
    }

    /**
     * {@code /V 4} の暗号フィルタが指す方式。
     *
     * <p><b>★★ 読めなければ {@link EncryptionAlgorithm#UNKNOWN} である。AES ではない。</b>
     * <b>{@code /CF} が無いときの既定は {@code /Identity}——本文も文字列も暗号化されない。</b>
     * <b>そこを AES と答えると、中身が生のままの文書を「AES-128 で保護済み」と報告することになる</b>
     * ——<b>入力の側が完全に決められる値であり、保護の状態を偽装できる</b>
     * （{@code SECURITY.md}「対象範囲」／優先順位 2）。{@code /CFM /None} も同じである。
     *
     * <p><b>★★ この入力をテストで作れていない</b>（2026-09-12 実測）。PDFBox は
     * <b>{@code /Encrypt} を持つ文書の保存を拒む</b>（{@code COSWriter}。平文が落ちる経路を
     * 塞ぐ仕組みであり、それ自体は正しい）ので、<b>{@code TestPdfs} の作法では組めない。</b>
     * <b>作れないことは、穴が無いことを意味しない</b>——<b>攻撃者は PDFBox を使わない。</b>
     * 縛る形は #187 が持つ。
     */
    private static EncryptionAlgorithm filterAlgorithm(PDEncryption encryption) {
        PDCryptFilterDictionary filter = encryption.getStdCryptFilterDictionary();
        if (filter == null) {
            return EncryptionAlgorithm.UNKNOWN;
        }
        COSName method = filter.getCryptFilterMethod();
        if (method == null) {
            return EncryptionAlgorithm.UNKNOWN;
        }
        String name = method.getName();
        if (name.startsWith("AESV")) {
            return EncryptionAlgorithm.AES_128;
        }
        return "V2".equals(name) ? EncryptionAlgorithm.RC4_128 : EncryptionAlgorithm.UNKNOWN;
    }

    private static AccessPermissions permissionsOf(AccessPermission permission) {
        return new AccessPermissions(
                permission.canPrint(),
                permission.canModify(),
                permission.canExtractContent(),
                permission.canModifyAnnotations(),
                permission.canFillInForm(),
                permission.canAssembleDocument(),
                permission.canExtractForAccessibility(),
                permission.canPrintFaithful());
    }

    /**
     * 暗号化されているか。
     *
     * @throws PdfjigException 読み取れない場合は {@link ErrorCode#NOT_A_PDF}
     */
    public boolean encrypted() {
        try {
            return delegate.isEncrypted();
        } catch (RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    /**
     * 電子署名が付いているか。
     *
     * <p>署名が <b>在るか</b> だけを見る。正当性・証明書・失効は一切確かめない。
     * pdfjig は署名を作らず、検証もしない（SPEC.md 2.2 の Non-goals）。
     * それでも在ることを知る必要があるのは、ページを並べ替えて書き出せば
     * 署名が無効になるためである。黙って壊すと、利用者は署名済みのつもりで
     * 検証に落ちる文書を配ることになる。
     *
     * <p><b>★★ 壊れた {@code /AcroForm} で投げる。</b>{@code getSignatureDictionaries} は
     * フォームと欄の木を辿るので、そこは細工 PDF が決められるところである（#150）。
     *
     * @throws PdfjigException 読み取れない場合は {@link ErrorCode#NOT_A_PDF}
     */
    public boolean signed() {
        try {
            return !delegate.getSignatureDictionaries().isEmpty();
        } catch (RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.NOT_A_PDF, e);
        }
    }

    /**
     * PDFBox の文書オブジェクト。
     *
     * <p>パッケージプライベート。pdf-core の内部実装のみが使う。
     */
    PDDocument delegate() {
        return delegate;
    }

    /**
     * 閉じる。
     *
     * <p><b>★★ 閉じるときに初めて投げることがある。</b>{@code COSDocument#close} は
     * オブジェクトの溜まりを辿って参照を解決するので、<b>そこで初めて壊れた参照に当たる</b>
     * 細工 PDF がある。{@code PDDocument#close} が通す {@code IOUtils} は
     * {@code IOException} しか握らないため、<b>未検査例外はそのまま抜ける</b>（#150）。
     *
     * @throws PdfjigException 閉じられない場合は {@link ErrorCode#IO_FAILURE}
     */
    @Override
    public void close() {
        try {
            delegate.close();
        } catch (IOException | RuntimeException e) {
            throw PdfjigException.wrapping(ErrorCode.IO_FAILURE, e);
        }
    }

    private static void requireReadable(Path path) {
        if (!Files.isReadable(path)) {
            throw new PdfjigException(ErrorCode.FILE_NOT_FOUND);
        }
    }
}
