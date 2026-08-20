package io.github.propagandist.pdfjig.core;

/**
 * 結合の指定。
 *
 * @param encryptionPropagation 入力が暗号化されていた場合の扱い
 */
public record MergeOptions(EncryptionPropagation encryptionPropagation) {

    public MergeOptions {
        if (encryptionPropagation == null) {
            throw new IllegalArgumentException("encryptionPropagation は null にできません。");
        }
    }

    /**
     * 既定の指定。暗号化を引き継がない。
     *
     * @return 引き継ぎなしの指定
     */
    public static MergeOptions defaults() {
        return new MergeOptions(EncryptionPropagation.NONE);
    }
}
