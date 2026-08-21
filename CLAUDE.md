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

### JavaFX

- `PDFRenderer` の呼び出しは必ずバックグラウンドスレッド（`Task` / `Service`）
- JavaFX Application Thread では、レンダリング済み `Image` の差し込みのみ
- UI から `pdf-core` を同期呼び出ししない。ファイル I/O を伴う操作はすべて非同期

### テスト

- `pdf-core` はユニットテストで網羅する。UI に依存しないため容易であり、ここが品質の担保点
- ArchUnit で INV-1 を検証する（`pdf-core` から `pdf-ai` への依存がないこと）
- `pdf-ai` のテストは LLM をモックする。実 API を叩くテストは CI に入れない
- パスワード関連は、例外メッセージにパスワード文字列が含まれないことを明示的にテストする

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

このリポジトリは **分類 D**（実行コードが公開面に出ない＝利用者の手元でしか動かない）。
**分類 D で読むのは `security-baseline.md` §2 の 2 項目だけ**——秘密を commit しない（INV-6）と、
依存を放置しない。**加えて、インストーラを配るので §5.3（配る側の供給網）も読む**:

```
gh api repos/propagandist/.github/contents/docs/security-baseline.md --jq .content | base64 -d
```

確かめ方は同 `docs/security-verification.md`（手元 / 既存ジョブ / 週次の 3 層）。

**★ 分類が変わるのは、実行コードが公開面に出た日**——サーバを持つ、ブラウザで動かす、
`pdf-core` をライブラリとして publish する（`SPEC.md` §9）。そのとき読む節が増える。

---

## 作業の進め方

- 1 コミット 1 関心事。モジュールをまたぐ変更は分割する
- 実装前に、その変更が INV-1 〜 INV-6 のいずれかに触れないか確認する
- 仕様に書かれていない判断が必要になった場合、勝手に決めず `HANDOVER.md` の未決事項として記録し、確認を求める
- `SPEC.md` と実装が乖離した場合、`SPEC.md` を正とする。仕様側を変えるべきだと考える場合は理由を添えて提案する
