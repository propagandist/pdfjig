package io.github.propagandist.pdfjig.desktop;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * テストが答えを仕込む {@link FileDialogs}。
 *
 * <p>Windows の共通ダイアログは自動テストから操作できない。ここを差し替えることで、
 * 「開く」「保存」を経由する一連の流れを画面の上で通せるようにする。
 *
 * <p><b>仕込んだ答えは一度使うと消える。</b> 残したままにすると、2 度目のダイアログにも
 * 同じ答えが返り、呼ばれていないはずの経路が黙って進む。取り消し（空）が既定である。
 */
final class StubFileDialogs implements FileDialogs {

    private Path open;

    private List<Path> openMultiple;

    private Path save;

    private Path folder;

    /** 直前の「保存」で渡された既定のファイル名。 */
    private String lastSuggestedName;

    /** 直前のダイアログで渡された、始めるフォルダ。渡されなければ {@code null}。 */
    private Path lastInitial;

    void willOpen(Path path) {
        open = path;
    }

    /**
     * 「開く」に仕込んだ答えがまだ使われていないか。
     *
     * <p>クリックが窓に届いたかどうかの判定に使う。届いていなければダイアログは
     * 呼ばれず、答えは残ったままになる。
     */
    boolean openPending() {
        return open != null;
    }

    void willOpenMultiple(Path... paths) {
        openMultiple = List.of(paths);
    }

    void willSaveTo(Path path) {
        save = path;
    }

    void willChooseFolder(Path path) {
        folder = path;
    }

    /** 直前の「保存」に渡された既定のファイル名。まだ呼ばれていなければ {@code null}。 */
    String lastSuggestedName() {
        return lastSuggestedName;
    }

    /** 直前のダイアログに渡された、始めるフォルダ。 */
    Path lastInitial() {
        return lastInitial;
    }

    @Override
    public Optional<Path> openPdf(Path initial) {
        lastInitial = initial;
        Path chosen = open;
        open = null;
        return Optional.ofNullable(chosen);
    }

    @Override
    public Optional<List<Path>> openPdfs(Path initial) {
        lastInitial = initial;
        List<Path> chosen = openMultiple;
        openMultiple = null;
        return Optional.ofNullable(chosen);
    }

    @Override
    public Optional<Path> savePdf(Path initial, String suggestedName) {
        lastInitial = initial;
        lastSuggestedName = suggestedName;
        Path chosen = save;
        save = null;
        return Optional.ofNullable(chosen);
    }

    @Override
    public Optional<Path> chooseFolder(Path initial) {
        lastInitial = initial;
        Path chosen = folder;
        folder = null;
        return Optional.ofNullable(chosen);
    }
}
