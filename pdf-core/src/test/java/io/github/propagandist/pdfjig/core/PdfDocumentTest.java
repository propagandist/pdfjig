package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfDocumentTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("平文の PDF はパスワードなしで開ける")
    void opensPlainDocument() throws Exception {
        Path pdf = TestPdfs.plain(tempDir.resolve("plain.pdf"), 3);

        try (PdfDocument document = PdfDocument.open(pdf)) {
            assertEquals(3, document.pageCount());
            assertFalse(document.encrypted());
        }
    }

    @Test
    @DisplayName("存在しないファイルは FILE_NOT_FOUND")
    void reportsMissingFile() {
        Path missing = tempDir.resolve("does-not-exist.pdf");

        assertEquals(
                ErrorCode.FILE_NOT_FOUND,
                assertThrows(PdfjigException.class, () -> PdfDocument.open(missing))
                        .errorCode());
    }

    @Test
    @DisplayName("暗号化文書をパスワードなしで開くと PASSWORD_REQUIRED")
    void requiresPasswordForEncryptedDocument() throws Exception {
        Path pdf = TestPdfs.encrypted(tempDir.resolve("encrypted.pdf"), "secret");

        assertEquals(
                ErrorCode.PASSWORD_REQUIRED,
                assertThrows(PdfjigException.class, () -> PdfDocument.open(pdf)).errorCode());
    }
}
