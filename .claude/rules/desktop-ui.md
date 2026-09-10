---
paths:
  - "pdf-desktop/src/main/java/**"
  - "pdf-desktop/src/main/resources/**"
  - "pdf-desktop/src/uiTest/java/**"
  - "tools/smoke/**"

<!--
維持: 画面の id の付け方と JavaFX の規約の正本。**CLAUDE.md へ写さない**——あちらには
  見出し語と行き先だけを置く。経緯と実測は docs/HANDOVER.md「UI テストの自動化」が持つ。
paths の根拠: 画面を触る作業は pdf-desktop/src/main か uiTest を開くところから始まる。
  tools/smoke/** を含めるのは、**ツールバーの文言が起動スモークとの契約だから**である
  ——あちらを直す作業も、この規約を読まずに始めてはならない。
  ★ pdf-core / pdf-ai / pdf-cli の実装では発火しない。単体テストだけの作業でも発火しない。
★ 限界: **「画面に文言を足す」判断は、まだ何も開かずに始まりうる。** そのときここは載らない。
  保険は CLAUDE.md「忘れると静かに壊れる」の 1 行（id とアクセシブル名はテストとの契約である）。
-->

## 画面の id と JavaFX

画面の節点には `setId` で識別子を付ける。テストは文言ではなくこれで掴む。

| 対象 | 形 | 例 |
|---|---|---|
| ツールバー | `tool-<操作>` | `tool-open` `tool-rotate-right` |
| メニュー | `menu-<操作>` | `menu-open` `menu-about` |
| 主画面の部品 | `<役割>` | `thumbnail-list` `status-label` |
| 並びの中で繰り返す部品 | `<役割>-<並びの位置>` | `thumbnail-tile-0` `source-remove-0` |
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
- **文字を持たないボタンには `setAccessibleText` を付ける**（ツールバー・一覧の「×」など、
  絵だけのもの全部）。Windows の UI Automation から見えるのは Name だけで、`setId` は届かない
  （JavaFX は AutomationId に内部の連番を返す）。**`setId` を付けたから足りる、にはならない。**
  ★ **同じものが並ぶなら Name で区別が付くこと**——「外す」が 3 つ並ぶと、
  読み上げからはどれを押しているのか分からない（#115）
  ── **ツールバーの文言は起動スモーク `tools/smoke/Verify-AppImage.ps1` との契約であり、
  変えるならあちらも変える。** **一覧の「×」の文言は `SourceLegendUiTest` との契約である**

