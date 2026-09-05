package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
    @DisplayName("RuntimeException で失敗しても、pdf-core の外へ出るのは PdfjigException だけ")
    void wrapsUncheckedFailures() throws Exception {
        // zip の中の Path は読めるが、toFile() を持たない。★ 投げるのは PDFBox ではなく
        // pdfjig 自身の path.toFile() であり、Loader へは入らない——ここで縛れるのは
        // 「RuntimeException を包む」ことだけで、PDFBox 由来の筋は縛れていない。
        // ★ Error は包んでいないので、この名前は RuntimeException に限った意味である。
        Path zip = tempDir.resolve("archive.zip");
        try (FileSystem archive = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path inside = archive.getPath("inside.pdf");
            Files.writeString(inside, "not a pdf");
            assertTrue(Files.isReadable(inside), "関門を通ることが前提の筋である");

            PdfjigException thrown = assertThrows(PdfjigException.class, () -> PdfDocument.open(inside));

            assertEquals(ErrorCode.NOT_A_PDF, thrown.errorCode());
            // 包んだ相手を見ておく。仕掛けが変わって別の理由で落ちても、空振りに気づける。
            assertEquals("java.lang.UnsupportedOperationException", thrown.causeType());
        }
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
