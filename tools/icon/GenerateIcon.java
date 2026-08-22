import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * PDFjig の仮アイコンを生成する。
 *
 * <pre>
 *   java tools/icon/GenerateIcon.java
 * </pre>
 *
 * <p>リポジトリのルートで実行すると、以下の 2 つを上書きする。
 *
 * <ul>
 *   <li>{@code pdf-desktop/packaging/pdfjig.ico} — jpackage の {@code --icon} に渡す
 *   <li>{@code pdf-desktop/src/main/resources/io/github/propagandist/pdfjig/desktop/pdfjig-256.png}
 *       — JavaFX の {@code Stage#getIcons} に積む
 * </ul>
 *
 * <p>ビルドはこのツールに依存しない。生成物はリポジトリにコミットしてあり、
 * 意匠を差し替えたくなったときだけ手で回せばよい。
 *
 * <p>意匠は治具（クランプ）が書類を挟んでいる図である。16px でも潰れないよう、
 * 要素はクランプと書類の 2 つに限り、両者を別の色にして輪郭線に頼らずに分離している。
 * 白同士だと小さいサイズで融合して一塊に見えるため。
 */
public final class GenerateIcon {

    /** 背景。濃紺。 */
    private static final Color BACKGROUND = new Color(0x1F, 0x2A, 0x37);

    /** 書類。 */
    private static final Color PAPER = new Color(0xFF, 0xFF, 0xFF);

    /** 書類の角折れ。16px では描かない。 */
    private static final Color PAPER_FOLD = new Color(0xC9, 0xD1, 0xD9);

    /** クランプ。工具を思わせる琥珀色。 */
    private static final Color CLAMP = new Color(0xE8, 0xA3, 0x3D);

    /** ICO に収めるサイズ。Windows がどれを選んでも破綻しないよう一通り入れる。 */
    private static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};

    /** ICONDIRENTRY 1 件の長さ。 */
    private static final int ENTRY_SIZE = 16;

    /** ICONDIR の長さ。 */
    private static final int HEADER_SIZE = 6;

    private GenerateIcon() {}

    public static void main(String[] args) throws IOException {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of(".");

        Path ico = root.resolve("pdf-desktop/packaging/pdfjig.ico");
        Path png = root.resolve("pdf-desktop/src/main/resources/io/github/propagandist/pdfjig/desktop/pdfjig-256.png");

        Files.createDirectories(ico.getParent());
        Files.createDirectories(png.getParent());

        writeIco(ico);
        ImageIO.write(draw(256), "png", png.toFile());

        System.out.println("wrote " + ico);
        System.out.println("wrote " + png);
    }

    /**
     * ICO を書き出す。
     *
     * <p>ImageIO に ICO のライタがないため、コンテナを自前で組む。
     * 各エントリの中身は PNG のまま埋め込む（Vista 以降が受け付ける形式であり、
     * 本アプリの動作対象は Windows 10 21H2 以降）。
     */
    private static void writeIco(Path target) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int size : ICO_SIZES) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(draw(size), "png", buffer);
            images.add(buffer.toByteArray());
        }

        try (OutputStream out = Files.newOutputStream(target)) {
            out.write(header(images.size()));

            int offset = HEADER_SIZE + ENTRY_SIZE * images.size();
            for (int i = 0; i < images.size(); i++) {
                byte[] image = images.get(i);
                out.write(entry(ICO_SIZES[i], image.length, offset));
                offset += image.length;
            }

            for (byte[] image : images) {
                out.write(image);
            }
        }
    }

    /** ICONDIR。予約領域 0 / 種別 1（アイコン）/ 件数。 */
    private static byte[] header(int count) {
        return ByteBuffer.allocate(HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 0)
                .putShort((short) 1)
                .putShort((short) count)
                .array();
    }

    /** ICONDIRENTRY。幅と高さは 256 のとき 0 で表す決まりになっている。 */
    private static byte[] entry(int size, int length, int offset) {
        return ByteBuffer.allocate(ENTRY_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) (size == 256 ? 0 : size))
                .put((byte) (size == 256 ? 0 : size))
                .put((byte) 0)
                .put((byte) 0)
                .putShort((short) 1)
                .putShort((short) 32)
                .putInt(length)
                .putInt(offset)
                .array();
    }

    /** 1 サイズ分を描く。座標はすべて辺長に対する比率で持ち、サイズ間で見た目を揃える。 */
    private static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        g.setColor(BACKGROUND);
        g.fill(new RoundRectangle2D.Double(
                r(size, 0.02), r(size, 0.02), r(size, 0.96), r(size, 0.96), r(size, 0.22), r(size, 0.22)));

        drawPaper(g, size);
        drawClamp(g, size);

        g.dispose();
        return image;
    }

    /** 書類。角折れは 32px 未満だと 1px 未満になって濁るので描かない。 */
    private static void drawPaper(Graphics2D g, int size) {
        double left = 0.36;
        double right = 0.82;
        double top = 0.16;
        double bottom = 0.84;
        double fold = 0.14;

        if (size < 32) {
            g.setColor(PAPER);
            g.fill(new RoundRectangle2D.Double(
                    r(size, left),
                    r(size, top),
                    r(size, right - left),
                    r(size, bottom - top),
                    r(size, 0.04),
                    r(size, 0.04)));
            return;
        }

        GeneralPath body = new GeneralPath(Path2D.WIND_NON_ZERO);
        body.moveTo(r(size, left), r(size, top));
        body.lineTo(r(size, right - fold), r(size, top));
        body.lineTo(r(size, right), r(size, top + fold));
        body.lineTo(r(size, right), r(size, bottom));
        body.lineTo(r(size, left), r(size, bottom));
        body.closePath();

        g.setColor(PAPER);
        g.fill(body);

        GeneralPath corner = new GeneralPath(Path2D.WIND_NON_ZERO);
        corner.moveTo(r(size, right - fold), r(size, top));
        corner.lineTo(r(size, right), r(size, top + fold));
        corner.lineTo(r(size, right - fold), r(size, top + fold));
        corner.closePath();

        g.setColor(PAPER_FOLD);
        g.fill(corner);
    }

    /** クランプ。左から伸びた顎が書類を挟んでいる形。 */
    private static void drawClamp(Graphics2D g, int size) {
        double spine = 0.14;
        double jaw = 0.13;
        double left = 0.12;
        double reach = 0.50;
        double top = 0.27;
        double bottom = 0.73;

        g.setColor(CLAMP);
        g.setStroke(new BasicStroke((float) r(size, 0.02)));

        g.fill(new RoundRectangle2D.Double(
                r(size, left), r(size, top), r(size, spine), r(size, bottom - top), r(size, 0.04), r(size, 0.04)));

        g.fill(new RoundRectangle2D.Double(
                r(size, left), r(size, top), r(size, reach - left), r(size, jaw), r(size, 0.04), r(size, 0.04)));

        g.fill(new RoundRectangle2D.Double(
                r(size, left),
                r(size, bottom - jaw),
                r(size, reach - left),
                r(size, jaw),
                r(size, 0.04),
                r(size, 0.04)));
    }

    /** 比率を実座標に直す。 */
    private static double r(int size, double ratio) {
        return size * ratio;
    }
}
