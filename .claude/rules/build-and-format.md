---
paths:
  - "build.gradle.kts"
  - "settings.gradle.kts"
  - "pdf-*/build.gradle.kts"
  - "gradle/libs.versions.toml"
  - ".editorconfig"
  - ".gitattributes"
  - ".git-blame-ignore-revs"
---

<!--
維持: 整形の規約の正本。**経緯は docs/HANDOVER.md「整形を Spotless に寄せた」が持つ。**
paths の根拠: 整形の設定を変える作業は build.gradle.kts か .editorconfig / .gitattributes を
  開くところから始まる。**.editorconfig と .gitattributes を入れるのは、
  どちらも Spotless の挙動そのものを変えるから**である（build.yml の paths-ignore が
  この 2 つを除外していないのと同じ理由）。
  ★ Java の実装・テスト・画面・docs のいずれでも発火しない。
★ 限界: **「手で整えてしまう」経路は、何も開かずに始まる。**
  保険は CLAUDE.md「忘れると静かに壊れる」の 1 行（手で整えない）。
-->

## 整形

整形は Spotless が正である。**手で整えない。コミット前に `./gradlew spotlessApply` を掛ける。**
`spotlessCheck` は `check` に載っているため、`./gradlew build` が通れば整形も揃っている。

- Java は palantir-java-format（4 スペース / 120 桁）。**Javadoc は整形されない**ので、
  日本語の桁揃えは手で保ってよい
- `*.gradle.kts` は ktlint。md / yml / toml / css は行末の空白と末尾の改行だけを見る
- 機械的な整形に馴染まない一角は `// spotless:off` 〜 `// spotless:on` で退避できる。多用しないこと

改行は `.gitattributes` が正であり、LF に固定する（Windows のシェルが読む `*.bat` / `*.cmd` /
`*.ps1` だけが CRLF）。**`.gitattributes` と `build.gradle.kts` の `lineEndings` は対になっている。
片方だけ変えないこと。** 経緯は `HANDOVER.md`「整形を Spotless に寄せた」。

