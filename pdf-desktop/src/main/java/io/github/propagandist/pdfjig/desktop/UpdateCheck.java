package io.github.propagandist.pdfjig.desktop;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Optional;

/**
 * 手元の版が最新かどうかを GitHub に問い合わせる。
 *
 * <p><b>★ このアプリが外へ出る唯一の経路である</b>（{@code AboutDialog} の
 * {@code hostServices.showDocument} は既定のブラウザに URL を渡すだけで、アプリ自身は通信しない）。
 * ArchUnit がそれを縛っている（{@code desktopReachesTheNetworkOnlyThroughUpdateCheck}）——
 * <b>README が公開している約束が、機械で守られている状態を保つ</b>。
 *
 * <p><b>押したときだけ通信する。起動時には何もしない。</b>想定利用者にはクラウド送信が
 * 社内規程で禁じられている現場が含まれる（{@code docs/SPEC.md} §5.3）。既定で外へ出ないので、
 * 説明が「押したときだけ GitHub に問い合わせる」の 1 文で済む——覚えておく状態もゼロで、
 * 設定ファイルに項目が増えない（#72 の案 A）。
 *
 * <p><b>落とさない。実行しない。</b>新しい版があれば版数を出し、あとは既定のブラウザへ渡す。
 * 無署名の実行物をアプリが落として起動する形は取らない（#16）。
 *
 * <h2>なぜリダイレクトを読むのか</h2>
 *
 * {@code api.github.com} の {@code releases/latest} でも同じことはできるが、採らなかった
 * （2026-08-29 実測。#72）。
 *
 * <ul>
 *   <li><b>rate limit が IP あたり 60 req/h である。</b>いちばん届けたい層——情報システム部門が
 *       MSI で一括配布した現場——は<b>NAT の内側で 1 つの外向き IP を共有する</b>。
 *       リダイレクトにはこの制約が無い
 *   <li><b>落ちてくる量が 18,975 バイト対ヘッダだけ</b>である
 *   <li><b>解析の手は変わらない。</b>JDK に JSON パーサは無く、{@code pdf-desktop} に足せば
 *       配布物と CVE を見る対象が増える。どちらを選んでも正規表現 1 本になる
 * </ul>
 *
 * <h2>なぜ {@link HttpURLConnection} なのか</h2>
 *
 * {@code HttpClient} は {@code java.net.http} にあり、{@code runtimeModules} に足すと
 * <b>ランタイムイメージが 623,804 バイト増える</b>（2026-08-29 実測）。
 * {@link HttpURLConnection} は {@code java.base} にあるので、<b>配布物のサイズが 1 バイトも動かない</b>。
 * リダイレクトを読む用途とも相性がよい——{@link HttpURLConnection#setInstanceFollowRedirects}
 * で 302 を追わずに {@code Location} を読める。
 */
final class UpdateCheck {

    /**
     * 問い合わせ先が返すはずの行き先。
     *
     * <p>ここから始まらない {@code Location} は読まない。<b>読むのは版数だけで追いはしない</b>ので
     * 実害のある経路ではないが、<b>想定していない応答を「新しい版」として出さない</b>ためである
     * （{@code CLAUDE.md} 優先順位 2）。
     */
    private static final String TAG_PREFIX = AppInfo.REPOSITORY + "/releases/tag/";

    /**
     * 待つ時間（ミリ秒）。接続と読み取りに別々にかかる。
     *
     * <p>遮断された環境では接続の側で待つことになる。<b>押した人が待つ上限は合わせて 10 秒</b>で、
     * その間ボタンは「確認しています…」に変わる。
     */
    private static final int TIMEOUT_MS = 5_000;

    private UpdateCheck() {}

    /**
     * 確認する。
     *
     * <p><b>バックグラウンドスレッドから呼ぶこと</b>（{@code CLAUDE.md}「JavaFX」）。
     * 遮断された環境では {@link #TIMEOUT_MS} の 2 倍まで返らない。
     *
     * <p><b>例外を投げない。</b>失敗はすべて {@link UpdateStatus.Unavailable} になる。
     *
     * @return 確認の結果
     */
    static UpdateStatus check() {
        Optional<ReleaseVersion> installed = ReleaseVersion.parse(AppInfo.version());
        if (installed.isEmpty()) {
            // 版数が読めないのは、焼き込みが壊れているとき（BuildInfo は "unknown" を返す)。
            // ★ 問い合わせる前にやめる——比べられない答えのために外へ出ない。
            return new UpdateStatus.Unavailable();
        }
        return ask().<UpdateStatus>map(latest -> compare(installed.get(), latest))
                .orElseGet(UpdateStatus.Unavailable::new);
    }

    /**
     * 版を比べる。
     *
     * @param installed 手元の版
     * @param latest    公開されている最新
     * @return 確認の結果。{@link UpdateStatus.Unavailable} にはならない
     */
    static UpdateStatus compare(ReleaseVersion installed, ReleaseVersion latest) {
        if (installed.isOlderThan(latest)) {
            // 開発版でも答えは変わらない。公開版へ移るべき状態である。
            return new UpdateStatus.Available(latest);
        }
        if (installed.development()) {
            return new UpdateStatus.Development(latest);
        }
        return new UpdateStatus.UpToDate();
    }

    /**
     * {@code Location} から、公開されている版を読む。
     *
     * <p><b>接尾辞の付いたタグは読まない。</b>{@code release.yml} が版数を
     * {@code ^\d+\.\d+\.\d+$} で縛っており、{@code /releases/latest} は prerelease を指さない。
     * <b>ここへ接尾辞が来るのは、こちらの想定が崩れているとき</b>であり、
     * そのときは「確認できませんでした」と答えるほうが正直である。
     *
     * @param location 応答の {@code Location} ヘッダ。{@code null} でもよい
     * @return 公開されている版。読めなければ空
     */
    static Optional<ReleaseVersion> published(String location) {
        if (location == null || !location.startsWith(TAG_PREFIX)) {
            return Optional.empty();
        }
        return ReleaseVersion.parse(location.substring(TAG_PREFIX.length())).filter(version -> !version.development());
    }

    /**
     * 画面に出す 1 行。
     *
     * @param status 確認の結果
     * @return 利用者に見せる文
     */
    static String describe(UpdateStatus status) {
        return switch (status) {
            case UpdateStatus.Available available ->
                "新しい版 " + available.latest().text() + " が公開されています。";
            case UpdateStatus.UpToDate ignored -> "お使いの版が最新です。";
            case UpdateStatus.Development development ->
                "開発版です。公開されている最新は " + development.latest().text() + " です。";
            case UpdateStatus.Unavailable ignored -> "更新を確認できませんでした。";
        };
    }

    /**
     * 問い合わせる。
     *
     * <p><b>User-Agent を空にする。</b>指定しないと {@link HttpURLConnection} は
     * {@code Java/21.0.8} のような実行環境の版を送る（2026-08-30 実測）。
     * <b>送らないと決めたのは、どの版が何台あるかを GitHub 側に残さないためである</b>（#72）。
     * 空でも 302 は返る（同日実測。curl の {@code -A ""} / {@code -H "User-Agent:"} でも同じ）。
     *
     * <p><b>本文を読まない。</b>{@code HEAD} で投げ、302 のヘッダだけを見る。
     */
    private static Optional<ReleaseVersion> ask() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)
                    URI.create(AppInfo.LATEST_RELEASE).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "");
            connection.connect();
            return published(connection.getHeaderField("Location"));
        } catch (IOException | RuntimeException e) {
            // 遮断・DNS 不達・証明書・応答の形違い——どれも「確認できなかった」に倒す。
            // 記録は残す。押しても何も起きないという報告を受けたときの手がかりになる（#13）。
            Logs.warn(LogEvent.UPDATE_NOT_CHECKED, e);
            return Optional.empty();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
