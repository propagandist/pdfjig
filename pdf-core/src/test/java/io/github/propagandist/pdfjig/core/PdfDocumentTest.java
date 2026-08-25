package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("平文の PDF に電子署名はない")
    void reportsNoSignatureForPlainDocument() throws Exception {
        Path pdf = TestPdfs.plain(tempDir.resolve("plain.pdf"), 2);

        try (PdfDocument document = PdfDocument.open(pdf)) {
            assertFalse(document.signed());
        }
    }

    @Test
    @DisplayName("電子署名のある PDF は署名済みと分かる")
    void detectsSignature() throws Exception {
        Path pdf = TestPdfs.signed(tempDir.resolve("signed.pdf"), 2);

        try (PdfDocument document = PdfDocument.open(pdf)) {
            assertTrue(document.signed());
        }
    }

    @Test
    @DisplayName("署名欄があるだけでは署名済みとしない")
    void doesNotTreatEmptySignatureFieldAsSigned() throws Exception {
        Path pdf = TestPdfs.withEmptySignatureField(tempDir.resolve("field.pdf"), 2);

        try (PdfDocument document = PdfDocument.open(pdf)) {
            assertFalse(document.signed());
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
