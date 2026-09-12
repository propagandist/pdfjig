---
paths:
  - "pdf-*/src/main/java/**"
  - "pdf-archtest/src/test/java/**"
---

<!--
維持: 不変条件の理由・図・NG/OK 例と、モジュールの責務・言語機能の正本。
  **不変条件そのもの（何を破ってはならないか）は CLAUDE.md が持つ**——
  依頼が来た時点で効く関門であり、ファイルを開く前に読まれる必要がある。
  ここが持つのは「なぜそうなのか」と「どう書くか」である。
paths の根拠: 実装は 5 モジュールのいずれかの src を開くところから始まる。
  ★★ src/main だけに絞ってある——**テストを書く作業では載らない。**
  #75 の設計は src/** だったが、そのままだと uiTest を書くとき 4 本が同時に載り、
  **合計が develop の 505 行を超える**（2026-09-10 実測。**frontmatter と HTML コメントは
  context へ載る前に剥がれる**ので、その実効行で 542 対 505 の +37。素の wc -l では +107）。
  **逃がすつもりが増やしていた。** 絞った後は実効 419 で、86 行減る。
  ★ pdf-archtest だけは test/java である。あそこには main が無く、ArchUnit の本体があそこにある。
  ★ docs だけの作業・workflows・tools/**・整形設定・起票や PR でも発火しない。
★ 限界: **INV に反する実装を「求められた」時点では、まだ何も開いていない。**
  だから CLAUDE.md 側に「実装せずに矛盾を指摘すること」と Non-goals を逐語で残してある。
  **この 2 か所を消さないこと。**ここが載るのは、既に書き始めた後だけである。
-->

## 不変条件の理由と例

### INV-1: `pdf-core` は `pdf-ai` に依存してはならない

依存の向きは一方通行である。

**図は `CLAUDE.md` の INV-1 にある**（README / `docs/SPEC.md` と同じ図。
**1 つ直したら 3 つとも直す**）。

`pdf-core` の `build.gradle.kts` に `pdf-ai` が現れることは絶対にない。
`pdf-core` のコードが `AiProvider` やその関連型を import することもない。

この依存方向が崩れると設計全体が意味を失う。ArchUnit テストで機械的に検証すること。

### INV-2: AI はファイルを変更しない

`pdf-ai` のすべての公開メソッドは `Proposal<T>` を返す。
`Path` を受け取ってファイルを書き出すメソッドを `pdf-ai` に置いてはならない。

適用は必ず「提案 → 差分表示 → ユーザー承認 → `pdf-core` による適用」の順を踏む。
「すべて自動適用」に相当する機能は実装しない。

### INV-3: AI 不在で全機能が動く

`NoOpProvider` が既定であり、API キー未設定・Ollama 未起動でもアプリケーションは正常に起動し、
AI 以外のすべての機能が利用可能でなければならない。

AI 機能の呼び出し箇所は必ず `provider.isAvailable()` で分岐し、
利用不可の場合は機能を非表示にするかグレーアウトする。例外を投げてはならない。

### INV-4: PDF 本文を書き換えない

ページの並べ替え・抽出・削除・回転・暗号化は行う。
PDF 本文のテキストやベクタコンテンツを改変する実装は追加しない。

**文書に属するもののうち、書き換えてよいのは下の左列だけである。含意で押し通さないこと**
——「本文ではないから触ってよい」と読むと、タグ構造も添付も入る。

| 書き換えてよい | 書き換えない |
|---|---|
| しおり `/Outlines` | **タグ構造 `/StructTreeRoot`** — 直った証拠を機械で出せない。壊れたタグは「支援技術で読めるふりをする文書」を作る（優先順位 2） |
| 文書情報 `/Info` と XMP | **添付ファイル `/EmbeddedFiles`** — PDF に実行可能なものを詰める経路になる |
| ページラベル `/PageLabels` | **注釈**（墨消しの実行を含む）— 塗り潰しの下のテキストは**検出はする。除去はしない**（本文の改変そのもの） |

★★ **右列に例外が 1 つある。出力に含めないページを指す参照は、取り除く**——しおり・リンク注釈・
名前付き宛先・開いたときの移動先が対象である。**体裁の整えではなく、取り除いたものを本当に
取り除くための処理であり**（`docs/SPEC.md` §4.2.1、`PageReferences`）、
**外すと `GHSA-xw3x-275f-ffcv` で塞いだ漏えい経路が戻る。**


### INV-5: パスワードは `char[]` で扱う

`String` でパスワードを受け取る・保持する・返すメソッドを書いてはならない。

★★ **ゼロ埋めを持つのは `Password` である**（#146）。**素の `char[]` を引数や戻り値に取る口を
新しく作らない**——`pdf-archtest` が口の集合を突き合わせており、増えると落ちる。

**作った場所で try-with-resources に載せる。受け取った側は読むだけで、消さない。**
**枠より長く生きる仕事へ渡すときは、枠ごと渡す**（`BackgroundTasks#run`）——
**走り出したかどうかで持ち主が変わり、それを知っているのはその仕組みだけである。**

パスワードを以下に出力してはならない:
ログ / 例外メッセージ / スタックトレース / 設定ファイル / CLI 引数 / URL

PDFBox の例外をそのまま再スローしないこと。必ずラップし、メッセージを再構築する。

```java
// NG
catch (InvalidPasswordException e) { throw new RuntimeException("failed: " + password, e); }

// OK
catch (InvalidPasswordException e) { throw new PdfjigException(ErrorCode.INVALID_PASSWORD); }
```


## 言語機能・リソース管理・命名

### 言語機能

- Java 21。`record`、`sealed interface`、パターンマッチングを積極的に使う
- 値オブジェクトは `record`。可変状態を持つ型は必要な場合に限る
- `Optional` は戻り値にのみ使う。フィールドや引数には使わない
- チェック例外は使わない。`PdfjigException`（unchecked）に `ErrorCode` enum を持たせる

### リソース管理

- `PdfDocument` は `AutoCloseable`。必ず try-with-resources で扱う
- PDFBox の `PDDocument` を直接触るコードは `pdf-core` の内部にのみ存在する。他モジュールに漏らさない
- Excel 出力は **必ず SXSSF を使う**。`XSSFWorkbook` は全行をメモリに保持し、大きな表で OOM を起こす

### 命名

- インタフェースに `I` プレフィックスを付けない
- 実装クラスは役割を表す名前にする（`DefaultTextExtraction` ではなく `PdfBoxTextExtraction`）
- `pdf-ai` の実装は `AnthropicProvider` / `OllamaProvider` / `NoOpProvider`


## モジュール別の責務


### pdf-core

確定的処理のみ。外部ネットワーク通信を一切行わない。**ArchUnit テストで機械的に検証すること。**
PDFBox / tabula-java / Apache POI への依存はこのモジュールに閉じる。

主要インタフェース: `PageOperations` / `TextExtraction` / `TableExtraction` / `Encryption` / `Exporter`

### pdf-ai

LLM プロバイダの抽象化。`AiProvider` インタフェースと 4 実装。

- LLM に渡すのは抽出済みテキストのみ。PDF バイナリ・ページ画像は渡さない
- 出力は JSON Schema で強制。パース失敗時は空の `Proposal` を返してフォールバックさせる
- 文書境界検出はページ単位の分類タスクに分解する。全ページを一度に投げない
- API キーは Windows Credential Manager（DPAPI）に格納する。設定ファイルに平文で置かない

### pdf-cli

`pdf-core` と `pdf-ai` の機能をサブコマンドとして公開。picocli を使う。

パスワードは `--password` のような引数で受け取らない。
`--password-stdin` / `--password-env` / `--password-file` のいずれかとする。

### pdf-desktop

JavaFX。サムネイル一覧、範囲選択、AI 提案の差分表示と承認、設定画面。
