package io.github.propagandist.pdfjig.cli;

import io.github.propagandist.pdfjig.core.BuildInfo;
import io.github.propagandist.pdfjig.core.PdfDocument;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * pdfjig CLI のエントリポイント。
 *
 * <p><b>パスワードを {@code --password} のような引数で受け取ってはならない。</b>
 * {@code ps} やシェル履歴から見えるため。実装する際は
 * {@code --password-stdin} / {@code --password-env} / {@code --password-file}
 * のいずれかに限定する（CLAUDE.md INV-5）。
 */
@Command(
        name = "pdfjig",
        mixinStandardHelpOptions = true,
        versionProvider = PdfjigCommand.VersionProvider.class,
        description = "PDF を綴じ、解き、取り出すためのユーティリティ。",
        subcommands = {PdfjigCommand.Info.class})
public final class PdfjigCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /**
     * {@code --version} が出す文言。
     *
     * <p>版数は {@link BuildInfo} から取る。{@code @Command} に直接書くと、
     * ビルドが付ける版数と黙って食い違う。
     */
    static final class VersionProvider implements CommandLine.IVersionProvider {

        @Override
        public String[] getVersion() {
            return new String[] {"pdfjig " + BuildInfo.version()};
        }
    }

    /** 文書の基本情報を表示する。 */
    @Command(name = "info", description = "ページ数と暗号化状態を表示する。")
    static final class Info implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<pdf>", description = "対象の PDF ファイル")
        private Path input;

        @Override
        public Integer call() {
            try (PdfDocument document = PdfDocument.open(input)) {
                System.out.println("pages: " + document.pageCount());
                System.out.println("encrypted: " + document.encrypted());
            }
            return 0;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PdfjigCommand()).execute(args);
        System.exit(exitCode);
    }
}
