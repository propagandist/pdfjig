package io.github.propagandist.pdfjig.desktop;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Stage;

/**
 * Windows の共通ダイアログを出す {@link FileDialogs}。
 *
 * <p>アプリが Windows のファイル選択に触れるのはこの 1 か所だけである。
 */
final class NativeFileDialogs implements FileDialogs {

    private static final String PDF_FILTER_NAME = "PDF ファイル";

    private static final String PDF_GLOB = "*.pdf";

    private final Stage owner;

    NativeFileDialogs(Stage owner) {
        this.owner = owner;
    }

    @Override
    public Optional<Path> openPdf(Path initial) {
        FileChooser chooser = pdfChooser("PDF を開く", initial);
        return Optional.ofNullable(chooser.showOpenDialog(owner)).map(File::toPath);
    }

    @Override
    public Optional<List<Path>> openPdfs(Path initial) {
        FileChooser chooser = pdfChooser("追加する PDF を選ぶ", initial);
        return Optional.ofNullable(chooser.showOpenMultipleDialog(owner))
                .map(chosen -> chosen.stream().map(File::toPath).toList());
    }

    @Override
    public Optional<Path> savePdf(Path initial, String suggestedName) {
        FileChooser chooser = pdfChooser("名前を付けて保存", initial);
        chooser.setInitialFileName(suggestedName);
        return Optional.ofNullable(chooser.showSaveDialog(owner)).map(File::toPath);
    }

    @Override
    public Optional<Path> chooseFolder(Path initial) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("分割したファイルの保存先");
        setInitialDirectory(initial, chooser::setInitialDirectory);
        return Optional.ofNullable(chooser.showDialog(owner)).map(File::toPath);
    }

    private static FileChooser pdfChooser(String title, Path initial) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new ExtensionFilter(PDF_FILTER_NAME, PDF_GLOB));
        setInitialDirectory(initial, chooser::setInitialDirectory);
        return chooser;
    }

    /**
     * 始めるフォルダを渡す。
     *
     * <p>{@code null} のときは呼ばない。JavaFX は未設定なら {@code null} をそのまま
     * ネイティブへ素通しし、その先は Windows が覚えている場所になる。
     * ここで何かに決め打つより、そちらのほうが馴染みがある（{@link RecentFolders}）。
     */
    private static void setInitialDirectory(Path initial, Consumer<File> to) {
        if (initial != null) {
            to.accept(initial.toFile());
        }
    }
}
