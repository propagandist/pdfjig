# HANDOVER.md — pdfjig

Claude Code への引き継ぎ文書。
前提知識は `SPEC.md`、遵守事項は `CLAUDE.md` にある。**両方を読んでから着手すること。**

現状: Phase 0 〜 Phase 4 まで完了。`pdf-core` は基盤型・テキスト抽出・ページ操作・描画、
`pdf-desktop` はサムネイル一覧、ドラッグ&ドロップ並べ替え、削除・回転・範囲指定、
結合・分割、パスワード入力までが動き、jpackage で MSI / EXE / ZIP を作れる。

**残っているのは v0.1.0 のタグ打ちと、その前の実機確認だけである**（下の「Phase 4 の残り」）。
その先は M1。着手前に、未決事項のうち設定ファイルの配置・ログ方針・権限フラグへの態度を決める必要がある。

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
| 表示名 | `PDFjig` | コマンド名・パッケージ名は `pdfjig` のまま。コマンドは小文字が自然 |
| ベンダー名 | `PROPAGANDIST CORPORATION` | MSI の発行元 |
| 配布形式 | MSI + EXE + ZIP | 形式ごとに役割を分ける（下記 4-1） |
| 最小 Windows | 10 21H2 (x64) | サポート終了済みの古いビルドを切る |
| JavaFX ランタイム | Gluon の jmods を Gradle で取得 | JavaFX 同梱 JDK を全員に強いない |
| MSI UpgradeCode | `3210BCE4-3635-4EFC-8EC1-DC77881091BB` | **二度と変えない。** 変えると旧版が残る |

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

### 4-1. jpackage（実装済み）

`installDist` → `jlink` → `jpackage` の 3 段。`./gradlew :pdf-desktop:packageAll -Pversion=0.1.0`
で `dist/` に 3 つの成果物ができる。手元で回すには WiX 3.14 を PATH に通す必要がある
（jpackage が要求するのは 3.x で、v4 では動かない）。

配布形式は役割で分けた。同じものを 2 つ並べるのではなく、

- **EXE** — ユーザー単位。管理者権限が要らない。個人向け
- **MSI** — マシン単位。`--win-dir-chooser` で入れ先を選べる。情報システム部門による一括配布向け
- **ZIP** — インストール不要。インストーラ自体が使えない環境向け

EXE と MSI は同じ製品であり、両方を同時には入れられない。jpackage は ProductCode を
名前とバージョンから決めており、両者で同一になるため。二重に入るよりは入らないほうが説明できる。

実測サイズは各 60MB 前後（ランタイムイメージ 59MB、アプリイメージ 82MB）。

### 4-2. GitHub Actions によるリリース自動化（実装済み）

`.github/workflows/release.yml`。タグ `v*` の push、または `workflow_dispatch` で走る。
`runs-on` は `windows-2022` に固定し、WiX もランナーイメージのプリインストールに頼らず
ワークフロー内で用意する。イメージ更新で黙って壊れるのを避けるため。

**draft リリースとして作る。** 実機で触ってから公開する。

### 4-3. Phase 4 の残り

ここから先は人が行う。

1. **実機確認**（下の「リリース前の確認」）
2. `docs/RELEASE_NOTES.md` の内容を確認する（リリース本文になる）
3. `git tag v0.1.0 && git push origin v0.1.0`
4. Actions が draft リリースを作るので、成果物を触ってから公開する

**この時点で AI 機能はゼロ。それで問題ない。**
AI なしで実用に耐えるツールとして一度リリースした事実を履歴に残すことが、
`CLAUDE.md` INV-3 の裏付けになる。

### 4-4. リリース前の確認

CI は GUI を起動できない（Monocle 等が要る）。以下は実機で行う。

1. ZIP を展開し `PDFjig.exe` を直接起動 → 管理者権限なしで起動する
2. EXE を実行 → 管理者昇格を求められずに入る。スタートメニューに `PDFjig` が出る
3. MSI を実行 → インストール先を選べる。`%ProgramFiles%` に入る
4. **ネットワークを遮断したまま**以下が動く（INV-3 の確認）
   - PDF を開く → サムネイルが出る
   - 並べ替え → 回転 → 削除 → 保存 → 開き直して順序と向きが保たれている
   - 結合・分割・範囲抽出
   - パスワード付き PDF を開く
5. 日本語のファイル名・パスの PDF を開ける
6. アンインストールでファイルが残らない
7. 同じ MSI をもう一度入れて上書きになる（UpgradeCode の確認）

タグを打つ前に `v0.1.0-rc1` のような捨てタグでワークフローを一度通しておくと、
CI 側の失敗を本番のタグで踏まずに済む。確認後にタグとドラフトを消す。

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
- [ ] `SPEC.md` §4.2 にない `PageOperations.assemble(Path, List<PageSelection>, Path)` と
      値型 `PageSelection` を追加した。UI で並べ替え・削除・回転を続けて行った結果は、
      一度の保存で確定させないと「並べ替えた状態で回転したら並べ替えが消えた文書が
      出てくる」ことになる。個別操作のままでは利用者を誤解させるため
      （`CLAUDE.md` 優先順位 2）。既存の `reorder` / `rotate` / `extractPages` /
      `deletePages` はそのまま残してある。
      → **承認され、`SPEC.md` §4.2 に追記済み**（Phase 4）
- [x] 上に伴い、UI では回転も編集セッションに含めた。`HANDOVER.md` 3-3 は回転を
      独立した操作として挙げているが、サムネイル一覧の上で回して保存するほうが
      並べ替え・削除と揃う。独立操作に戻すべきなら指示がほしい

### UI（Phase 3）

- [ ] サムネイル一覧は縦 1 列の `ListView` にした。`GridView` は ControlsFX への依存が
      必要になる。仮想化が効き、セルが表示された時点で描画を始められる点は同じ
- [ ] サムネイルの長辺は 160px、キャッシュは 200 枚とした
- [ ] 保存は一時ファイルに書いてから置き換える。保存先ダイアログは上書きを確認するが
      `pdf-core` は既存の出力を拒む。先に消すと書き込み失敗で元のファイルが失われる
- [ ] 結合の入力は名前順に並べ、その順序を見せて承認を求める。ファイル選択ダイアログが
      返す順序は環境によって変わり、選んだ順に結合されると思い込ませてしまう
- [ ] UI の分割は編集中の並びを対象にする。`pdf-core` の `split` は元の並びを切るため
      使っていない
- [ ] 起動引数でファイルを開けるようにした。ファイルの関連付けから開く経路であり、
      UI を手で操作せずに動作を確かめる手段でもある
- [ ] JavaFX を必要とする部分（サムネイルの供給、ダイアログ）にはユニットテストを
      書いていない。Toolkit の初期化が要り、CI では Monocle などの追加が必要になる。
      画面に依存しない `PageOrder` と `LruCache` はテストで固めてある

### パッケージング（Phase 4）

- [ ] jlink のルートモジュールを定数として build ファイルに固定した。jdeps での導出コマンドは
      コメントに残してある。依存を足したときは導出をやり直すこと。
      `jdk.localedata` はサービス経由の読み込みで jdeps が検出できないため手で足してある。
      外すと `ja` ロケールが消えて日付・数値の書式が英語圏のものに化ける
- [ ] `--include-locales=en,ja` でロケールを絞った。UI が日本語であり、それ以外の環境では
      英語にフォールバックする。他言語の書式が必要になったらここを広げる
- [ ] インストーラに渡す `--description` だけ ASCII にした。MSI のサマリ情報は
      コードページ 1033 で書かれ、日本語を渡すと最初の非 ASCII 文字以降が黙って落ちて
      `PDF ` だけが残る。切れた文字列を配るより正直である。
      ランチャー exe のバージョン情報（タスクマネージャに出る）は日本語のまま
- [ ] `.pdf` のファイル関連付け（`--file-associations`）は行わない。既定の PDF ビューアを
      奪う挙動であり、利用者の意図しない変更になる。起動引数で開く経路は既にある
- [ ] POI は v0.1.0 では一切使われないが依存に残してある（約 14MB）。M1 の XLSX 出力で
      要るものであり、外して戻すほうが build を余計に触ることになる
- [ ] `dist/` を出力先にした。`.gitignore` が `/dist/` と `*.msi` / `*.exe` を除外済み

---

## 未決事項

実装中に判断が必要になった場合、勝手に決めずここに追記して確認を求めること。

- [x] パッケージ名は `io.github.propagandist.pdfjig` に統一した（Phase 1 で確定）。
      Maven Central 公開時の groupId と揃えてあり、後の改名は不要
- [x] アプリケーション名の表示は `PDFjig` に決めた（Phase 4）。アイコンは治具が書類を
      挟んでいる図の仮アイコンを `tools/icon/GenerateIcon.java` で生成して同梱してある。
      意匠を差し替えたくなったら同じツールを回すか `.ico` を上書きする
- [x] Phase 4 の jpackage に必要な情報はすべて決まった（Phase 4）。表示名 `PDFjig`、
      ベンダー `PROPAGANDIST CORPORATION`、UpgradeCode は上の「確定している判断」を参照
- [x] 対応する最小 Windows バージョンは 10 21H2 (x64) とした（Phase 4）
- [ ] 設定ファイルの配置場所（`%APPDATA%\pdfjig\` を想定）。
      保存する設定がまだ無いため M1 で決める
- [ ] ログ出力先とローテーション方針。同じく M1
- [ ] 権限フラグで「テキスト抽出禁止」が設定された文書から、pdfjig がテキストを
      抽出してよいか。権限フラグは暗号学的に強制されず（`SPEC.md` §6.1）、PDFBox は
      これを無視して抽出できてしまう。M1 の暗号化機能で態度を決める必要がある
- [ ] コード署名証明書。v0.1.0 は未署名で出すため、初回起動時に SmartScreen の警告が出る。
      README とリリースノートには断ってある。取得するかどうかは実際の配布状況を見て決めたい
