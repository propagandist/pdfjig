import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * アイキャッチ撮影用のサンプル PDF を生成する。
 *
 * <pre>
 *   java tools/sample/GenerateSamplePdf.java [出力先]
 * </pre>
 *
 * <p>出力先を省くと {@code tmp/sample-scan-bundle-12p.pdf} に書く。
 *
 * <p>作るのは「1 件あたりの枚数がまちまちな書類が 3 件混ざった束」である
 * （DELIVERY NOTE 3 枚 / INSPECTION LOG 5 枚 / WORK ORDER 4 枚 の計 12 ページ）。
 * 区切り → 分割のデモに使える構成にしてある。中身はダミー文字列だけで機密は含まない。
 * 標準 14 フォント（Helvetica）しか使わないのでフォントの埋め込みも要らない。
 *
 * <p><b>生成物はリポジトリにコミットしない</b>（CLAUDE.md INV-6）。このツールは乱数も日付も
 * 使わないため、何度回しても同じバイト列が出る。PDF を追跡しなくても同じ画を撮り直せる、
 * というのが {@code .gitignore} の {@code *.pdf} に穴を開けずに済ませている根拠である。
 * <b>乱数や日付を持ち込むと、その根拠が壊れる。</b>
 *
 * <p>PDFBox は使わず、バイト列を手で組んでいる。{@code tools/} は Gradle のビルドグラフの外に
 * あり、単一ファイル起動では外部 jar を引けないためである（{@code tools/icon/GenerateIcon.java}
 * も JDK 標準だけで書かれている）。
 *
 * <p>既定のファイル名はページ数を含む。{@link #DOCUMENTS} を変えたら名前のほうも変えること。
 */
public final class GenerateSamplePdf {

    /** A4 の幅（pt）。 */
    private static final double PAGE_WIDTH = 595.28;

    /** A4 の高さ（pt）。 */
    private static final double PAGE_HEIGHT = 841.89;

    /** 最初のページオブジェクトの番号。1=Catalog / 2=Pages / 3=F1 / 4=F2 と埋まっている。 */
    private static final int FIRST_PAGE = 5;

    /** 出力先を省いたときの書き出し先。 */
    private static final String DEFAULT_TARGET = "tmp/sample-scan-bundle-12p.pdf";

    /** 束に混ぜる書類。枚数をわざと不揃いにしてある。 */
    private static final List<Document> DOCUMENTS = List.of(
            new Document("DELIVERY NOTE", 3, 0.85, 0.45, 0.05),
            new Document("INSPECTION LOG", 5, 0.15, 0.45, 0.75),
            new Document("WORK ORDER", 4, 0.25, 0.60, 0.35));

    /** 1 件の書類。ラベル・枚数・ヘッダ帯の色（RGB 各 0〜1）。 */
    private record Document(String label, int pages, double red, double green, double blue) {}

    /** 束の中の 1 枚。どの書類の何枚目か。 */
    private record Sheet(Document document, int indexInDocument) {}

    private GenerateSamplePdf() {}

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : DEFAULT_TARGET);

        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<Sheet> sheets = sheets();
        byte[] pdf = serialize(objects(sheets));
        Files.write(target, pdf);

        System.out.println("wrote " + target + " (" + sheets.size() + " pages, " + pdf.length + " bytes)");
    }

    /** 束のページの並びを作る。書類ごとに 1 枚目から順に並べる。 */
    private static List<Sheet> sheets() {
        List<Sheet> sheets = new ArrayList<>();
        for (Document document : DOCUMENTS) {
            for (int i = 1; i <= document.pages(); i++) {
                sheets.add(new Sheet(document, i));
            }
        }
        return sheets;
    }

    /**
     * 間接オブジェクトを番号順に組み立てる。
     *
     * <p>オブジェクト番号を配列の添字にそのまま使うため、添字 0 は空けてある。
     */
    private static String[] objects(List<Sheet> sheets) {
        int total = sheets.size();
        int firstContent = FIRST_PAGE + total;

        String[] objects = new String[firstContent + total];
        objects[1] = "<< /Type /Catalog /Pages 2 0 R >>";

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                kids.append(' ');
            }
            kids.append(FIRST_PAGE + i).append(" 0 R");
        }
        objects[2] = "<< /Type /Pages /Count " + total + " /Kids [" + kids + "] >>";
        objects[3] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>";
        objects[4] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>";

        for (int i = 0; i < total; i++) {
            objects[FIRST_PAGE + i] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(PAGE_WIDTH) + " "
                    + num(PAGE_HEIGHT) + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
                    + (firstContent + i) + " 0 R >>";

            String stream = content(sheets.get(i), i + 1, total);
            objects[firstContent + i] =
                    "<< /Length " + latin1(stream).length + " >>\nstream\n" + stream + "\nendstream";
        }

        return objects;
    }

    /**
     * 組み上がったオブジェクトを 1 つのファイルに並べる。
     *
     * <p>xref に書くオフセットは、書きながら実測する。
     */
    private static byte[] serialize(String[] objects) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 2 行目のバイナリマーカーは「このファイルはテキストではない」の合図。中身に意味はない。
        push(out, "%PDF-1.7\n%âãÏÓ\n");

        int size = objects.length;
        int[] offsets = new int[size];
        for (int i = 1; i < size; i++) {
            offsets[i] = out.size();
            push(out, i + " 0 obj\n" + objects[i] + "\nendobj\n");
        }

        int startxref = out.size();
        StringBuilder xref = new StringBuilder("xref\n0 " + size + "\n0000000000 65535 f \n");
        for (int i = 1; i < size; i++) {
            xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]));
        }
        push(out, xref.toString());

        // /ID は暗号化なしでも付けておくのが慣例（一部のリーダーが期待する）。
        String id = "<0123456789ABCDEF0123456789ABCDEF>";
        push(
                out,
                "trailer\n<< /Size " + size + " /Root 1 0 R /ID [" + id + " " + id + "] >>\nstartxref\n" + startxref
                        + "\n%%EOF\n");

        return out.toByteArray();
    }

    /** 1 ページ分の描画命令を作る。 */
    private static String content(Sheet sheet, int pageNumber, int total) {
        Document document = sheet.document();
        List<String> lines = new ArrayList<>();

        // ヘッダ帯
        lines.add(num(document.red()) + " " + num(document.green()) + " " + num(document.blue()) + " rg");
        lines.add("0 " + num(PAGE_HEIGHT - 96) + " " + num(PAGE_WIDTH) + " 96 re f");
        lines.add("1 1 1 rg");
        lines.add("BT /F2 22 Tf 48 " + num(PAGE_HEIGHT - 56) + " Td (" + escape(document.label()) + ") Tj ET");
        lines.add("BT /F1 12 Tf 48 " + num(PAGE_HEIGHT - 78) + " Td ("
                + escape("Sheet " + sheet.indexInDocument() + " of " + document.pages()
                        + "  --  sample document, no real data")
                + ") Tj ET");

        // 大きな通しページ番号（サムネイルでも読める）
        lines.add("0.88 0.88 0.90 rg");
        lines.add("BT /F2 150 Tf " + num(PAGE_WIDTH / 2 - 45) + " " + num(PAGE_HEIGHT / 2 - 40) + " Td ("
                + escape(String.valueOf(pageNumber)) + ") Tj ET");

        // 書類風のダミー罫線
        lines.add("0.80 0.80 0.82 rg");
        for (int i = 0; i < 14; i++) {
            double y = PAGE_HEIGHT - 170 - i * 34;
            int width = (i % 4 == 0) ? 300 : (i % 3 == 0) ? 460 : 420;
            lines.add("64 " + num(y) + " " + width + " 7 re f");
        }

        // 表を模したブロック
        lines.add("0.86 0.86 0.88 rg");
        lines.add("64 150 468 2 re f");
        lines.add("64 118 468 2 re f");
        lines.add("64 86 468 2 re f");

        // フッタ
        lines.add("0.45 0.45 0.48 rg");
        lines.add("BT /F1 10 Tf 64 56 Td (" + escape("PDFjig sample bundle  |  page " + pageNumber + " / " + total)
                + ") Tj ET");

        return String.join("\n", lines);
    }

    /**
     * PDF に書く数値を文字列にする。
     *
     * <p>整数値は小数点を落とす。移植元の JS には数値型が 1 つしかなく {@code 300} は
     * {@code "300"} になるが、Java の {@code double} をそのまま文字列にすると {@code "300.0"} に
     * なって出力がずれる。ここで JS 側の表記に合わせている。
     *
     * <p>整数として扱うのは {@code long} で表せる範囲だけにしてある。範囲外は {@code (long)} の
     * キャストが飽和して別の数になるためで、ページの座標でそこへ届くことはない。
     */
    private static String num(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /** PDF の文字列リテラルの中で特別扱いされる丸括弧とバックスラッシュを退避する。 */
    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** ISO-8859-1（移植元の JS でいう latin1）で 1 文字 1 バイトに落とす。 */
    private static byte[] latin1(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void push(ByteArrayOutputStream out, String text) {
        out.writeBytes(latin1(text));
    }
}
