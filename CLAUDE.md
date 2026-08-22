# CLAUDE.md — pdfjig

このファイルは実装エージェント向けの指示である。作業前に必ず全文を読むこと。
仕様の詳細は `SPEC.md`、実装順序は `HANDOVER.md` を参照。

---

## 不変条件

以下は設計の根幹であり、いかなる理由があっても破ってはならない。
これらに反する実装を求められた場合は、実装せずに矛盾を指摘すること。

### INV-1: `pdf-core` は `pdf-ai` に依存してはならない

依存の向きは一方通行である。

```
pdf-desktop ──┐
              ├──> pdf-ai ──> pdf-core
pdf-cli ──────┘
              └────────────> pdf-core
```

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

以下は Non-goals であり、要望されても実装しない:
テキスト直接編集 / 電子署名 / フォーム作成 / PDF 生成・帳票出力 / 全文 RAG チャット

### INV-5: パスワードは `char[]` で扱う

`String` でパスワードを受け取る・保持する・返すメソッドを書いてはならない。
使用後は `java.util.Arrays.fill(pw, '\0')` でゼロ埋めする。

パスワードを以下に出力してはならない:
ログ / 例外メッセージ / スタックトレース / 設定ファイル / CLI 引数 / URL

PDFBox の例外をそのまま再スローしないこと。必ずラップし、メッセージを再構築する。

```java
// NG
catch (InvalidPasswordException e) { throw new RuntimeException("failed: " + password, e); }

// OK
catch (InvalidPasswordException e) { throw new PdfjigException(ErrorCode.INVALID_PASSWORD); }
```

### INV-6: 実業務の PDF をコミットしない

テストフィクスチャは自前で生成したもの、または明確にライセンスが確認できる公開文書のみ。
`.gitignore` で `*.pdf` を除外し、`!src/test/resources/**/*.pdf` で明示的に許可する構成を維持する。

Public リポジトリであり、一度コミットされた機密文書は取り返しがつかない。

---

## コーディング規約

### 整形

整形は Spotless が正である。**手で整えない。コミット前に `./gradlew spotlessApply` を掛ける。**
`spotlessCheck` は `check` に載っているため、`./gradlew build` が通れば整形も揃っている。

- Java は palantir-java-format（4 スペース / 120 桁）。**Javadoc は整形されない**ので、
  日本語の桁揃えは手で保ってよい
- `*.gradle.kts` は ktlint。md / yml / toml / css は行末の空白と末尾の改行だけを見る
- 機械的な整形に馴染まない一角は `// spotless:off` 〜 `// spotless:on` で退避できる。多用しないこと

改行は `.gitattributes` が正であり、LF に固定する（Windows のシェルが読む `*.bat` / `*.cmd` /
`*.ps1` だけが CRLF）。**`.gitattributes` と `build.gradle.kts` の `lineEndings` は対になっている。
片方だけ変えないこと。** 経緯は `HANDOVER.md`「整形を Spotless に寄せた」。

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

画面の節点には `setId` で識別子を付ける。テストは文言ではなくこれで掴む。

| 対象 | 形 | 例 |
|---|---|---|
| ツールバー | `tool-<操作>` | `tool-open` `tool-rotate-right` |
| メニュー | `menu-<操作>` | `menu-open` `menu-about` |
| 主画面の部品 | `<役割>` | `thumbnail-list` `status-label` |
| サムネイル | `thumbnail-tile-<並びの位置>` | `thumbnail-tile-0` |
| ダイアログ | `<用途>-dialog` | `password-dialog` `range-dialog` |
| ダイアログの中身 | `<用途>-<役割>` | `password-field` `range-first` `page-count-input` |

ツールバーとメニューは同じ `Action` から作られる。id もそこから配ること。
別々に書くと、片方だけ直したときに掴めなくなる。

サムネイルのタイルは行ごと使い回される。**受け持つページが変わったら id も付け替え、
空きタイルでは外すこと。** 残すと同じ id の節点が一覧に 2 つ並ぶ。

**id はテストとの契約である。** 変えるときは `pdf-desktop/src/uiTest` を必ず見る。

### JavaFX

- `PDFRenderer` の呼び出しは必ずバックグラウンドスレッド（`Task` / `Service`）
- JavaFX Application Thread では、レンダリング済み `Image` の差し込みのみ
- UI から `pdf-core` を同期呼び出ししない。ファイル I/O を伴う操作はすべて非同期
- Windows のネイティブなファイル選択は `FileDialogs` の向こう側に置く。
  直に `FileChooser` / `DirectoryChooser` を使わない。あの境界の外は自動テストから
  操作できず、「開く → 編集 → 保存」を画面の上で通せなくなる
- ツールバーのボタンには `setAccessibleText` を付ける。Windows の UI Automation から
  見えるのは Name だけで、`setId` は届かない（JavaFX は AutomationId に内部の連番を返す）。
  **これは起動スモーク `tools/smoke/Verify-AppImage.ps1` との契約であり、
  文言を変えるならあちらも変える**

### テスト

- `pdf-core` の公開メソッドは、**正常系・境界・壊れた入力**の 3 つを見る。値を持つだけの
  `record` / `enum` は除く。UI に依存しないため容易であり、ここが品質の担保点
- ArchUnit で INV-1 を検証する（`pdf-core` から `pdf-ai` への依存がないこと）
- `pdf-ai` のテストは LLM をモックする。実 API を叩くテストは CI に入れない
- パスワード関連は、例外メッセージにパスワード文字列が含まれないことを明示的にテストする
- テスト用の PDF はリポジトリに置かず、`pdf-core` の testFixtures にある `TestPdfs` で
  その場で作る（INV-6）。**生成の作法をモジュールごとに書かない。**
  分かれた瞬間から、片方だけ直した壊れた入力でテストが通るようになる

#### 画面のテスト

置き場は `pdf-desktop/src/uiTest`（`./gradlew :pdf-desktop:uiTest`）。
デスクトップセッションを要するため `build` には含めない。

**書く前に `HANDOVER.md`「UI テストの自動化」を読むこと。**
理由の分からない落ち方をする罠がいくつかある。

- 節点は id で掴む（上の「命名」）。文言で掴んでよいのはメニュー項目だけである。
  `MenuItem` は `Node` ではなく、id では掴めない
- ダイアログの中身は `clickWhenReady` を通す。窓が出る前の節点を押すと
  「no nodes were visible」で落ちる。主画面のボタンは常に出ているので直に押してよい
- 書き出しを伴うテストは、**出力ファイルを開き直して中身まで確かめる。**
  画面の上で何かが変わったことだけを見ても、ファイルが正しい保証にはならない
- `@Start` / `@Stop` は各テストクラスが自分で持つ。TestFX は宣言されたメソッドの中からしか
  探さず、土台クラスに置いても呼ばれない

#### 不安定なテストの扱い

**環境の側の揺れは吸収してよい。判断の側の揺れは吸収してはならない。**

- 吸収してよい例 — 窓が前面に出るまでの間、クリックが OS に取りこぼされる。
  押した結果が起きたかを見て押し直す（`DesktopUiTest#clickUntilAccepted`）
- 吸収してはならない例 — 結果が出ないので待ち時間を延ばす。それは非同期の完了条件を
  正しく見ていないということであり、待てば直るものではない
- **吸収するなら諦める上限を必ず置き、超えたら理由を書いて落とす。**
  いつまでも retry するテストは、壊れていることを報せない
- なぜその吸収が要るのかをコードのコメントに残す。残っていないリトライは、
  次に読む者には「意味の分からないおまじない」にしか見えない

#### CI で走らせないテスト

遅すぎる・環境を選ぶという理由で CI から外すことはある。**外すなら次の 3 つを揃える。**

- **外した理由をテストクラスの Javadoc に書く。実測した数字を添える。**
  「遅いから」では、次に読む者が縮められるのかどうかを判断できない
- **人が見る手順（`HANDOVER.md` 4-4）に、その項目が CI で守られていないことを明記する。**
  書かなければ、いつか誰かが「テストがあるから大丈夫」と読む
- **手元では走る状態を保つ。** 走らせる手段ごと消したなら、それは削除であって除外ではない

**★ 手元で通ったことは、CI で通る根拠にならない。** 逆も同じ。
別の環境なので、担保したい側で実際に走らせて確かめること。

---

## モジュール別の責務

### pdf-core

確定的処理のみ。外部ネットワーク通信を一切行わない。
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

---

## 判断に迷ったときの優先順位

1. **データを壊さないこと** — 出力が入力より劣化する経路を作らない
2. **利用者を誤解させないこと** — 権限フラグの実効性、暗号化の伝播、AI 提案の確信度
3. **AI なしで動くこと** — INV-3
4. **機能の充実**

2 と 4 が衝突した場合は 2 を取る。
「便利だが誤解を招く」機能より、「少し不便だが正直」な挙動を選ぶ。

---

## CI / ワークフロー

GitHub Actions の無料枠 2,000 分/月は **org 全体で共有**されている。枯らすと全リポジトリの
CI と本番デプロイが同時に止まる（2026-08-09 に実際に起きた）。
**ワークフローを増やす・トリガーを変える前に** org の判断規約を読むこと:

```
gh api repos/propagandist/.github/contents/docs/ci-strategy.md --jq .content | base64 -d
```

**規約の中身をここへ写さない。** 両方に書けば必ず片方が腐る。

**★ pdfjig が public である限り、標準ランナーの消費は無料で org の枠を食わない。**
枠を根拠にした判断（起動契機を絞る・ジョブをまとめる）はここでは効かないが、
`timeout-minutes` / `permissions` の最小化 / action の SHA ピンは枠と無関係に効く。
**private へ変えた瞬間にこの前提は崩れる。**

---

## セキュリティ

このリポジトリは **分類 P**（利用者の手元で動くものを配る）。
**読むのは `security-baseline.md` の §2、§3.2 / §3.3 / §3.10 / §3.12、§4.5、§5.1、§5.3**:

```
gh api repos/propagandist/.github/contents/docs/security-baseline.md --jq .content | base64 -d
```

確かめ方は同 `docs/security-verification.md`（手元 / 既存ジョブ / 週次の 3 層）。
**付録 P がこのリポジトリの実測**なので、検査を足すときはそこを見る。

**★ §3.3 はそのまま読まない。** 守る値「ファイルは ID で引いて、実体の場所はサーバが決める」は
**サーバ前提**で、**利用者が自分の PC で自分のファイルを指定するのは正常な使い方**
（`PdfjigCommand` の `@Parameters Path input`）。**残るのは zip slip / zip 爆弾と、
アプリが自分で決める出力先だけ**（同 §3.3 の★★）。

**★ ③ 週次 cron を足すなら、org 基準 §0 の 3 条件を満たすこと**——public であること・
見るのは同梱物に限ること・**CVE が出たときに何をするかまで決まっていること**。
**P では気づいても再リリースしなければ直らない**ので、3 番目が抜けると「監視しているつもり」になる。

**★ 分類が増えるのは、`pdf-core` をライブラリとして publish した日**（`SPEC.md` §9）。
**P ＋ L** になり、§5.3.1（座標）が足される。

**法務は区分 4**（預からない＝当社の設備が個人データを受け取らない）。
**個人データの流れ・外部へ出る先・保存期間を変える前に** 同 `docs/legal-baseline.md`
（**軸が違う。分類とは別に決まる**）。**★ BYOK は鍵の所在で区分が動く**（同 §1）。

---

## 作業の進め方

- 1 コミット 1 関心事。モジュールをまたぐ変更は分割する
- 実装前に、その変更が INV-1 〜 INV-6 のいずれかに触れないか確認する
- 仕様に書かれていない判断が必要になった場合、勝手に決めず `HANDOVER.md` の未決事項として記録し、確認を求める
- `SPEC.md` と実装が乖離した場合、`SPEC.md` を正とする。仕様側を変えるべきだと考える場合は理由を添えて提案する
