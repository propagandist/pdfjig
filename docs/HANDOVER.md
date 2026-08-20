# HANDOVER.md — pdfjig

Claude Code への引き継ぎ文書。
前提知識は `SPEC.md`、遵守事項は `CLAUDE.md` にある。**両方を読んでから着手すること。**

現状: Phase 0 〜 Phase 2 まで完了。初期文書、Gradle マルチモジュール構成、ArchUnit、CI、
`pdf-core` の基盤型・テキスト抽出・ページ操作が入っている。次は Phase 3（pdf-desktop の M0 範囲）。

---

## 確定している判断

議論を経て決定済み。再検討は不要。

| 項目 | 決定 | 理由 |
|---|---|---|
| スタック | Java 21 + JavaFX + PDFBox 3 | 表抽出の成熟実装が Java にしかない |
| ライセンス | Apache-2.0 | iText (AGPL) を排除する制約でもある |
| Excel 出力 | M1 に含める | CSV は先頭ゼロ消失・日付自動変換で実務データを壊す |
| M0 の UI | サムネイル並べ替えまで実装 | リスト表示だけなら既存 CLI に対する優位性がない |
| Maven Central | 初版では使わない | アプリケーションでありライブラリではない |
| 配布 | GitHub Releases + jpackage | MSI / EXE |

---

## Phase 0: 初期コミット

コードより先に、以下を置く。空リポジトリの段階で方針を固定するのが目的。

### 0-1. LICENSE

Apache License 2.0 の全文。

### 0-2. README.md

最低限、以下を含める。

- 1 行の説明: 「PDF を綴じ、解き、取り出すためのデスクトップユーティリティ」
- 名前の由来（治具 — 同じ作業を同じ精度で繰り返すための道具）
- 設計思想 4 点（`SPEC.md` §1）
- **Non-goals セクション** — テキスト直接編集 / 電子署名 / フォーム作成 / 帳票生成 / 全文 RAG チャット

Non-goals を最初から書いておくことで、後から要望が来たときに「方針変更」ではなく「当初からの設計」として説明できる。

### 0-3. .gitignore

Java / Gradle 用に加えて、以下を必ず含める。

```gitignore
*.pdf
!src/test/resources/**/*.pdf

*.key
.env
credentials.*
```

実業務の PDF が Public リポジトリに混入すると取り返しがつかない（`CLAUDE.md` INV-6）。

### 0-4. SECURITY.md

パスワード機能を持つ以上、脆弱性報告の窓口は最初から必要。
報告先メールアドレスと、対応方針（受領確認までの目安日数）を記載する。

### 0-5. SPEC.md / CLAUDE.md

本パッケージのファイルをそのまま配置する。

---

## Phase 1: プロジェクト骨格

### 1-1. Gradle マルチモジュール構成

```
pdfjig/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml    バージョンカタログ
├── pdf-core/build.gradle.kts
├── pdf-ai/build.gradle.kts
├── pdf-cli/build.gradle.kts
└── pdf-desktop/build.gradle.kts
```

依存宣言:

- `pdf-core` — PDFBox, tabula-java, POI のみ。**他モジュールに依存しない**
- `pdf-ai` — `pdf-core`
- `pdf-cli` — `pdf-core`, `pdf-ai`, picocli
- `pdf-desktop` — `pdf-core`, `pdf-ai`, JavaFX

### 1-2. ArchUnit テスト

最初に書くテストがこれ。INV-1 を機械的に守るため。

```java
@Test
void coreMustNotDependOnAi() {
    noClasses().that().resideInAPackage("..pdfjig.core..")
        .should().dependOnClassesThat().resideInAPackage("..pdfjig.ai..")
        .check(new ClassFileImporter().importPackages("com.propagandist.pdfjig"));
}
```

あわせて「`pdf-core` 外から PDFBox の型が見えないこと」も検証する。

### 1-3. CI

GitHub Actions。`push` / `pull_request` で `./gradlew build` を回す。
リリースタグでの jpackage ビルドは Phase 4 で追加する。

---

## Phase 2: pdf-core（M0 範囲）

依存のないところから積む。

### 2-1. 基盤型

`PdfDocument`（AutoCloseable）、`PdfjigException`、`ErrorCode`、`PageRange`、`Rotation`。

`PdfDocument.open(Path, char[])` の時点で INV-5 が関わる。
パスワードはコンストラクタで使用後に即ゼロ埋めし、フィールドに保持しない。

### 2-2. TextExtraction

`extractAll` / `extractByPage` / `extractWithPositions`。
PDFBox の `PDFTextStripper` を使う。座標付き抽出は `PDFTextStripper` を継承して
`writeString(String, List<TextPosition>)` をオーバーライドする。

### 2-3. PageOperations

`merge` / `split` / `reorder` / `rotate` / `extractPages` / `deletePages`。

**ここで暗号化の伝播を必ず実装する**（`SPEC.md` §4.3）。
M0 では暗号化「設定」機能はないが、暗号化された入力を受け取る可能性はある。
`EncryptionPropagation` の判定と警告発出は M0 の時点で入れておく。
後から足すと、既に平文出力してしまった利用者が発生する。

### 2-4. テスト

各操作について、ページ数・ページ順・回転角が期待通りかを検証する。
フィクスチャ PDF は PDFBox で生成するユーティリティをテストコードに置き、リポジトリには
生成済みファイルを最小限だけ置く。

---

## Phase 3: pdf-desktop（M0 範囲）

### 3-1. サムネイル一覧

要求: 固定サイズ、遅延レンダリング、LRU キャッシュ。

実装上の要点:

- `PDFRenderer#renderImageWithDPI` は必ず `Task` の中で呼ぶ（`CLAUDE.md` JavaFX 節）
- 可視範囲外のページはレンダリングしない。`ListView` / `GridView` の cell factory で
  cell が表示された時点でレンダリングを開始する
- キャッシュサイズは上限ページ数で管理する（例: 直近 200 ページ）。
  メモリ量ではなく枚数で切るほうが挙動が読みやすい
- レンダリング中はプレースホルダを表示し、UI をブロックしない

### 3-2. ドラッグ&ドロップ並べ替え

単一文書内のみ。文書間のページ移動は実装しない。

並べ替えは UI 上の順序リストを更新するだけで、この時点ではファイルを書き換えない。
「保存」操作で `PageOperations.reorder` を呼ぶ。

### 3-3. 基本操作の UI

結合（複数ファイル選択）、分割、ページ削除、回転、範囲抽出。
それぞれ確定的処理を直接呼ぶ。承認フローは不要（AI が関わらないため）。

### 3-4. パスワード入力ダイアログ

暗号化された PDF を開こうとした場合に表示する。
`PasswordField` を使い、`char[]` で受け渡す。入力値を `String` に変換しない。

M0 では「開く」だけ。設定・解除は M1。

---

## Phase 4: リリース

### 4-1. jpackage

`--type msi` および `--type exe`。JRE 同梱。

成果物サイズは 100MB 前後になる見込み。これは許容する
（Java 環境の事前インストールを不要にすることの対価）。

### 4-2. GitHub Actions によるリリース自動化

タグ push で Windows runner 上でビルドし、Releases に添付する。

### 4-3. v0.1.0 として公開

**この時点で AI 機能はゼロ。それで問題ない。**
AI なしで実用に耐えるツールとして一度リリースした事実を履歴に残すことが、
`CLAUDE.md` INV-3 の裏付けになる。

---

## M1 以降の概要

M0 完了後に着手する。詳細は改めて詰める。

| | 内容 |
|---|---|
| M1 | 表抽出（tabula-java）、CSV/JSON/XLSX 出力、暗号化一式、`AiProvider` 抽象化、表の正規化 |
| M2 | 文書境界検出、リネーム・分類、バッチ処理、CLI 公開 |
| M3 | OCR 対応、誤字補正 |

M1 で `AiProvider` を導入する際、実装順は `NoOpProvider` → `OllamaProvider` → `AnthropicProvider`。
NoOp を最初に作ることで INV-3 が構造的に守られる。

---

## 実装中に決めた判断

仕様に書かれておらず、実装を進めるために決めた事項。いずれも方針が違えば差し戻せる。

### テキスト抽出（Phase 2-2）

- [ ] 抽出時に座標順への並べ替え（`setSortByPosition(true)`）を既定にした。
      コンテンツストリーム順のままだと多段組の文書で読み順が崩れ、`pdf-ai` に渡す
      テキストの品質に直結するため
- [ ] 改行を LF に固定した。`System.lineSeparator()` に従うと、同じ入力から
      環境ごとに異なる出力が出る
- [ ] `PositionedText` の座標系をページ左上原点とした（PDF 本来は左下原点）。
      画面表示と向きを揃えるため。tabula-java は PDF 座標系を使うので、
      M1 の表抽出では変換が要る

### ページ操作（Phase 2-3）

- [ ] 出力先に同名のファイルがある場合は必ず失敗させる（`OUTPUT_ALREADY_EXISTS`）。
      上書きするかどうかは利用者の判断であり、確認は UI / CLI の責務とする。
      入力と同じパスを出力に指定して入力を壊す事故もこれで同時に防がれる
- [ ] `rotate` の指定は現在の回転角への加算とした。UI の「右に回す」という操作と
      一致させるため。絶対角の指定にはしていない
- [ ] 分割の出力ファイル名を `<入力名>_001.pdf` とした
- [ ] `EncryptionPropagation.INHERIT` / `PROMPT` は M0 では未対応とし、指定されたら
      失敗させる。引き継ぎには元のパスワードが必要であり、パスワードを受け取る経路を
      持つ M1 の暗号化機能とあわせて実装する
- [ ] パスワードが必要な入力に対するページ操作は M0 では行えない。同じく M1 で対応する
- [ ] `SPEC.md` §4.2 にない `PageOperations.assemble`（任意のページ列を書き出す）を
      追加した。UI で並べ替えと削除を続けて行った結果を 1 回の保存で書き出すには、
      順列に限られる `reorder` では表現できず、中間ファイルを経由すると失敗時に
      中途半端なファイルが残るため。仕様側を直すべきなら `SPEC.md` に追記したい

---

## 未決事項

実装中に判断が必要になった場合、勝手に決めずここに追記して確認を求めること。

- [x] パッケージ名は `io.github.propagandist.pdfjig` に統一した（Phase 1 で確定）。
      Maven Central 公開時の groupId と揃えてあり、後の改名は不要
- [ ] アプリケーション名の表示（`pdfjig` / `pdfjig — 治具` / `PDFjig`）とアイコン
- [ ] 設定ファイルの配置場所（`%APPDATA%\pdfjig\` を想定）
- [ ] ログ出力先とローテーション方針
- [ ] 権限フラグで「テキスト抽出禁止」が設定された文書から、pdfjig がテキストを
      抽出してよいか。権限フラグは暗号学的に強制されず（`SPEC.md` §6.1）、PDFBox は
      これを無視して抽出できてしまう。M1 の暗号化機能で態度を決める必要がある
- [ ] 対応する最小 Windows バージョン（10 の 21H2 以降を想定）
