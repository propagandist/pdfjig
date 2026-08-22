package io.github.propagandist.pdfjig.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 分割の切り方。
 *
 * <p>いずれの実装も {@link EncryptionPropagation} を持つ（SPEC.md §4.3）。
 * 分割は最も暗号化を落としやすい操作であり、指定を省略できる形にはしない。
 */
public sealed interface SplitStrategy {

    /** 入力が暗号化されていた場合の扱い。 */
    EncryptionPropagation encryptionPropagation();

    /**
     * 指定ページ数ごとに区切る。
     *
     * @param pages                 1 文書あたりのページ数
     * @param encryptionPropagation 入力が暗号化されていた場合の扱い
     */
    record EveryNPages(int pages, EncryptionPropagation encryptionPropagation) implements SplitStrategy {

        public EveryNPages {
            if (pages < 1) {
                throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
            }
            requirePropagation(encryptionPropagation);
        }
    }

    /**
     * 与えられた範囲だけを切り出す。
     *
     * <p>範囲の重なりを許す。同じページを複数の出力に含めたい場合があり、
     * 禁止すると回避手段がないため。
     *
     * @param ranges                切り出す範囲。順序が出力の順序になる
     * @param encryptionPropagation 入力が暗号化されていた場合の扱い
     */
    record ByRanges(List<PageRange> ranges, EncryptionPropagation encryptionPropagation) implements SplitStrategy {

        public ByRanges {
            if (ranges == null || ranges.isEmpty()) {
                throw new PdfjigException(ErrorCode.NO_INPUT);
            }
            ranges = List.copyOf(ranges);
            requirePropagation(encryptionPropagation);
        }
    }

    /**
     * 指定したページを各文書の先頭にして区切る。
     *
     * <p>文書境界の検出結果を適用するための口である。AI が提案した境界も、
     * 利用者が承認したうえでこの形に落ちてから pdf-core に渡る（CLAUDE.md INV-2）。
     *
     * <p>指定は正規化される。重複と順序の乱れは取り除き、先頭ページ 1 が
     * 含まれていない場合は補う（最初の文書は必ず 1 ページ目から始まるため）。
     *
     * @param startPages            各文書の先頭ページ（1 始まり）
     * @param encryptionPropagation 入力が暗号化されていた場合の扱い
     */
    record AtBoundaries(List<Integer> startPages, EncryptionPropagation encryptionPropagation)
            implements SplitStrategy {

        public AtBoundaries {
            if (startPages == null) {
                throw new PdfjigException(ErrorCode.NO_INPUT);
            }
            if (startPages.stream().anyMatch(page -> page == null || page < 1)) {
                throw new PdfjigException(ErrorCode.PAGE_OUT_OF_RANGE);
            }
            List<Integer> normalized = new ArrayList<>(new TreeSet<>(startPages));
            if (normalized.isEmpty() || normalized.get(0).intValue() != 1) {
                normalized.add(0, 1);
            }
            startPages = List.copyOf(normalized);
            requirePropagation(encryptionPropagation);
        }
    }

    /** ページ数ごとに区切る。暗号化は引き継がない。 */
    static SplitStrategy everyNPages(int pages) {
        return new EveryNPages(pages, EncryptionPropagation.NONE);
    }

    /** 指定範囲だけを切り出す。暗号化は引き継がない。 */
    static SplitStrategy byRanges(List<PageRange> ranges) {
        return new ByRanges(ranges, EncryptionPropagation.NONE);
    }

    /** 指定ページを先頭にして区切る。暗号化は引き継がない。 */
    static SplitStrategy atBoundaries(List<Integer> startPages) {
        return new AtBoundaries(startPages, EncryptionPropagation.NONE);
    }

    private static void requirePropagation(EncryptionPropagation propagation) {
        if (propagation == null) {
            throw new IllegalArgumentException("encryptionPropagation は null にできません。");
        }
    }
}
