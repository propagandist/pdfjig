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
 * <p><b>★★ なぜ届けさせるか、届けさせられないときにどうするか——正本は {@code docs/SPEC.md} §4.2 である</b>（#219）。
 *
 * <p><b>ここにだけある事実:</b>
 * <ul>
 *   <li><b>書いたハンドルで届けさせる。</b>名前で開き直すと、出力先の権限（既存ファイルへの書き込みを
 *       拒む継承 ACE）や、書き込みの共有を許さない他のプロセスで、保存が新たに失敗した（2026-09-25 実測。#219 の門）。
 *       <b>読み取りで開き直すと、Windows の JDK は {@code FlushFileBuffers} の失敗を黙って捨て、
 *       {@code force} は何もせずに戻る</b>（同日実測。JDK 21.0.8）
 *   <li><b>PDFBox はストリームを閉じない</b>（3.0.8。{@code COSWriter} も {@code COSStandardOutputStream} も
 *       閉じも溜めもしない）。閉じる版に変わると {@code force} が閉じたチャネルで投げ、
 *       <b>毎回「届いたか確かめられなかった」になる</b>（{@code DurableSaveTest} が赤になる）
 *   <li><b>{@code save(File)} を使わない。</b>中で自分のストリームを開いて閉じるので、届けさせる手が無い
 *   <li><b>届けさせられなくても投げない。届いたかを返す。</b>理由は {@link #write} にある
 * </ul>
 */
final class DurableSave {

    private DurableSave() {}

    /**
     * 書いて、戻る前にディスクへ届けさせる。
     *
     * <p><b>★★ 届けさせられなくても投げない。届いたかを返す</b>（#219。判断は利用者に確かめた）。
     * 吐き出しを受け付けない場所で投げると、以前は通った書き出しが必ず失敗する。
     * <b>ただし {@code false} は「本当に書けていなかった」でもありうる</b>——書き込みは OS のキャッシュまでは
     * 通り、書けなかったことが最初に分かるのが吐き出しのときである（容量不足の共有フォルダ・傷んだディスク）。
     * JDK の例外からは見分けられないので、<b>呼ぶ側は黙らずに伝えること</b>（{@link Warning#NOT_DURABLE}）。
     *
     * @param document 書く文書
     * @param output   書き出し先。無ければ作り、あれば切り詰める——既存を拒むのは呼ぶ側の仕事である
     * @return ディスクへ届いたと確かめられたなら {@code true}
     * @throws IOException 書けない（キャッシュへ書くところで既に失敗した）
     */
    static boolean write(PDDocument document, Path output) throws IOException {
        try (FileOutputStream file = new FileOutputStream(output.toFile())) {
            BufferedOutputStream buffered = new BufferedOutputStream(file);
            document.save(buffered);
            buffered.flush();
            try {
                file.getChannel().force(true);
                return true;
            } catch (IOException refused) {
                return false;
            }
        }
    }
}
