package io.github.propagandist.pdfjig.core;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * 文書をファイルへ書き、<b>戻る前にディスクへ届けさせる。</b>pdf-core の書き出しはすべてここを通る
 * （{@code pdf-archtest} が縛る）。
 *
 * <p><b>★★ なぜ届けさせるか</b>——理由の正本は {@code docs/SPEC.md} §4.2 である（#219）。
 * 画面の保存は、書けたものを改名で置き換えてから元の控えを消す。改名はメタデータであり、
 * 中身の書き込みより先に確定しうる。
 *
 * <p><b>ここにだけある事実:</b>
 * <ul>
 *   <li><b>書いたハンドルで届けさせる。</b>名前で開き直すと、出力先の権限（既存ファイルへの書き込みを
 *       拒む継承 ACE）や、書き込みの共有を許さない他のプロセスで、保存が新たに失敗した（#219 の門で再現）。
 *       <b>読み取りで開き直すと、Windows の JDK は {@code FlushFileBuffers} の失敗を黙って捨て、
 *       {@code force} は何もせずに戻る</b>（同じく実測）
 *   <li><b>PDFBox はストリームを閉じない</b>（3.0.8。{@code COSWriter} も {@code COSStandardOutputStream} も
 *       閉じも溜めもしない）。閉じる版に変わると、{@code force} が閉じたチャネルで投げ、
 *       書き出しは目に見える形で失敗する——黙って届かなくなることはない
 *   <li><b>{@code save(File)} を使わない。</b>中で自分のストリームを開いて閉じるので、届けさせる手が無い
 *   <li><b>届けさせられなくても失敗させない。</b>理由は {@link #write} の本文にある
 * </ul>
 */
final class DurableSave {

    private DurableSave() {}

    /**
     * 書いて、戻る前にディスクへ届けさせる。
     *
     * @param document 書く文書
     * @param output   書き出し先。無ければ作り、あれば切り詰める——既存を拒むのは呼ぶ側の仕事である
     * @throws IOException 書けない。<b>届けさせられないだけなら投げない</b>（下の本文）
     */
    static void write(PDDocument document, Path output) throws IOException {
        try (FileOutputStream file = new FileOutputStream(output.toFile())) {
            BufferedOutputStream buffered = new BufferedOutputStream(file);
            document.save(buffered);
            buffered.flush();
            try {
                file.getChannel().force(true);
            } catch (IOException refused) {
                // ★★ 届けさせられなくても、書き出しは失敗させない（#219。判断は利用者に確かめた）。
                //   吐き出しを受け付けない場所（転送ドライブ・仮想マシンの共有フォルダなど。どこかは
                //   確かめていない）で失敗させると、v0.1.x では通った保存も分割も必ず失敗する。
                //   黙って進めれば、その場所では v0.1.x と同じ守りに戻るだけである。
                //   ★ 書き込みそのものの失敗はここへ来ない——save と flush が先に投げる。
            }
        }
    }
}
