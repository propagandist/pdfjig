package io.github.propagandist.pdfjig.desktop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 出どころのファイルを、画面に出す名前にする。
 *
 * <p><b>ふだんはファイル名だけを出す。</b>同じ名前のファイルが並んだときだけ、
 * <b>区別が付くところまで親フォルダを遡って添える</b>（{@code report.pdf（work）}。#128）。
 * 親も同じ名前なら、もう 1 段遡る（{@code r.pdf（a\work）} と {@code r.pdf（b\work）}）。
 *
 * <p><b>★★ 名前が同じだと、取り消せない操作の対象を取り違えさせる</b>——外す確認の窓、
 * 保護が落ちる窓（{@link ProtectionPrompt}）、書き出しの鍵を訊く窓（{@link PasswordPrompt}）が、
 * <b>どれもこの名前で相手を指す。</b>帯の色だけでは、色が見えない利用者に手がかりが無い
 * （{@code CLAUDE.md} 優先順位 2）。
 *
 * <p><b>★ 大文字と小文字は区別しない。</b>Windows では {@code Report.pdf} と {@code report.pdf} は
 * 同じ名前として扱われ、<b>読み上げでは同じに聞こえる。</b>
 *
 * <p>常に親フォルダを出す形（案 2）と、フルパスを読み上げにだけ入れる形（案 3）は採らない——
 * 前者はふつうの使い方で一覧が長くなり、後者は画面に出ていないものが読み上げから出る。
 */
final class SourceNames {

    private SourceNames() {}

    /**
     * 並んだファイルを、区別の付く名前にする。
     *
     * <p><b>同じファイルを 2 度開いたときは、区別が付かないまま返す</b>——遡っても違いが出ないので、
     * フルパスまで遡った形になる。<b>同じものを指しているので、取り違えても害が無い。</b>
     *
     * @param paths 並んだファイル。順は問わない
     * @return {@code paths} と同じ順の名前
     */
    static List<String> of(List<Path> paths) {
        List<List<String>> parents =
                paths.stream().map(SourceNames::parentNames).toList();
        List<String> names = new ArrayList<>(paths.size());
        for (int i = 0; i < paths.size(); i++) {
            String name = fileName(paths.get(i));
            List<List<String>> peers = new ArrayList<>();
            for (int j = 0; j < paths.size(); j++) {
                if (j != i && fold(fileName(paths.get(j))).equals(fold(name))) {
                    peers.add(parents.get(j));
                }
            }
            names.add(peers.isEmpty() ? name : name + "（" + distinguishing(parents.get(i), peers, paths.get(i)) + "）");
        }
        return List.copyOf(names);
    }

    /**
     * 他と区別が付くところまで、親フォルダを末尾から遡る。
     *
     * @param mine  このファイルの親（根から順に）
     * @param peers 同じ名前の他のファイルの親
     */
    private static String distinguishing(List<String> mine, List<List<String>> peers, Path path) {
        for (int depth = 1; depth <= mine.size(); depth++) {
            String suffix = fold(tail(mine, depth));
            int d = depth;
            if (peers.stream().noneMatch(peer -> fold(tail(peer, d)).equals(suffix))) {
                return tail(mine, depth);
            }
        }
        return String.valueOf(path.toAbsolutePath().getParent());
    }

    /** 末尾の {@code depth} 段を、区切りでつなぐ。 */
    private static String tail(List<String> names, int depth) {
        return String.join("\\", names.subList(Math.max(0, names.size() - depth), names.size()));
    }

    /**
     * 親フォルダを、根から順に並べる。根（{@code C:\}）も 1 段に数える——
     * {@code C:\report.pdf} と {@code D:\report.pdf} は根でしか違わない。
     */
    private static List<String> parentNames(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        List<String> names = new ArrayList<>();
        if (parent == null) {
            return names;
        }
        Path root = parent.getRoot();
        if (root != null) {
            names.add(root.toString().replaceAll("[\\\\/]+$", ""));
        }
        for (Path segment : parent) {
            names.add(segment.toString());
        }
        return names;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
