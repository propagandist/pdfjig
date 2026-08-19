package io.github.propagandist.pdfjig.core;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/**
 * テスト用の PDF をその場で生成する。
 *
 * <p>リポジトリに PDF を置かない（CLAUDE.md INV-6）。フィクスチャは常にここで作る。
 */
final class TestPdfs {

    private TestPdfs() {
    }

    /** 指定ページ数の平文 PDF を作る。 */
    static Path plain(Path target, int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }
            document.save(target.toFile());
        }
        return target;
    }

    /** AES-256 で暗号化した 1 ページの PDF を作る。 */
    static Path encrypted(Path target, String userPassword) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());

            AccessPermission permissions = new AccessPermission();
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(userPassword, userPassword, permissions);
            policy.setEncryptionKeyLength(256);
            document.protect(policy);

            document.save(target.toFile());
        }
        return target;
    }
}
