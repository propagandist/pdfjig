---
paths:
  - ".github/workflows/**"
  - ".github/dependabot.yml"
  - "pdf-core/build.gradle.kts"
  - "settings.gradle.kts"
  - "pdf-desktop/packaging/**"
---

<!--
維持: 枠・週次 cron・分類の増え方のうち、**ファイルを開いてから効く分だけ**を持つ。
  **org 正本への導線と雛形の文面は CLAUDE.md が持つ。1 文字も写さない**
  ——「ワークフローを増やそう」と思った時点では、まだ何も開いていないためである。
paths の根拠: ワークフローを触る作業は .github/workflows を開くところから始まる。
  pdf-core/build.gradle.kts と settings.gradle.kts を入れるのは、
  **publish の設定を足す作業がそこから始まり、その日に分類が P ＋ L へ増えるから**である。
  ★ Java の実装・テスト・docs・整形設定では発火しない。
★ 限界: **枠を根拠にした判断も、cron を足す判断も、何も開かずに始まりうる。**
  保険は CLAUDE.md「CI / ワークフロー」の雛形（org 正本への導線）である。
-->

## 枠と配布に関する、この版の実測

**★ pdfjig が public である限り、標準ランナーの消費は無料で org の枠を食わない。**
枠を根拠にした判断（起動契機を絞る・ジョブをまとめる）はここでは効かないが、
`timeout-minutes` / `permissions` の最小化 / action の SHA ピンは枠と無関係に効く。
**private へ変えた瞬間にこの前提は崩れる。**

**★ ③ 週次 cron を足すなら、org 基準 §0 の 3 条件を満たすこと**——public であること・
見るのは同梱物に限ること・**CVE が出たときに何をするかまで決まっていること**。
**P では気づいても再リリースしなければ直らない**ので、3 番目が抜けると「監視しているつもり」になる。

**★ 分類が増えるのは、`pdf-core` をライブラリとして publish した日**（`SPEC.md` §9）。
**P ＋ L** になり、§5.3.1（座標）が足される。
