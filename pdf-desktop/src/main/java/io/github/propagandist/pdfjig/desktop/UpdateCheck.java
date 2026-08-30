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
     * <p><b>★ 全体の上限ではない。名前解決はこの外である</b>——{@code HttpURLConnection} は
     * 解決してからでないと接続を始められず、{@link HttpURLConnection#setConnectTimeout} が
     * 効くのはその後である。<b>DNS を黙って落とすファイアウォールの内側では、
     * 解決だけで 10 秒を超えうる。</b>
     *
     * <p><b>「合わせて 10 秒」とは書かない</b>——実際に守れない数字を書くと、次に読む者が
     * それを根拠に何かを決める（{@link Logs} の世代数と同じ判断）。押している間ボタンは
     * 「確認しています…」に変わり、<b>窓は固まらない</b>（通信は背景スレッドである）。
     */
    private static final int TIMEOUT_MS = 5_000;

    private UpdateCheck() {}

    /**
     * 確認する。
     *
     * <p><b>バックグラウンドスレッドから呼ぶこと</b>（{@code CLAUDE.md}「JavaFX」）。
     * 遮断された環境では長く返らない（{@link #TIMEOUT_MS}）。
     *
     * <p><b>例外を投げない。</b>失敗はすべて {@link UpdateStatus.Unavailable} になる。
     *
     * <p><b>★ {@link UpdateStatus.Unavailable} を返すときは必ず記録を残す。</b>
     * 画面に出るのは 1 行だけなので、<b>「押しても何も起きない」という報告を受けたときに
     * 読む側の手がかりがそこにしか無い</b>（{@link LogEvent#UPDATE_NOT_CHECKED}）。
     *
     * @return 確認の結果
     */
    static UpdateStatus check() {
        Optional<ReleaseVersion> installed = ReleaseVersion.parse(AppInfo.version());
        if (installed.isEmpty()) {
            // 版数が読めないのは、焼き込みが壊れているとき（BuildInfo は "unknown" を返す)。
            // ★ 問い合わせる前にやめる——比べられない答えのために外へ出ない。
            Logs.warn(LogEvent.UPDATE_NOT_CHECKED);
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
     *
     * <p><b>★ 失敗は 2 通りあり、どちらも記録する。</b>例外になるもの（遮断・DNS 不達・証明書）と、
     * <b>例外にならないもの</b>——通信はできたが返ってきたものが想定と違う場合である。
     * <b>後者は実際に起きる</b>: TLS を傍受するプロキシや、ログインページへ 302 を返す
     * キャプティブポータルの内側がそれに当たり、<b>そこはまさに届けたい層の環境である</b>。
     * 記録しないと、画面の 1 行以外に手がかりが無くなる。
     *
     * <p><b>★★ その 2 通りを分けるために、状態行を先に読む。</b>
     * {@link HttpURLConnection#getHeaderField} は<b>内部の {@code IOException} を握り潰して
     * {@code null} を返す</b>ので、読み取りの時間切れ・途中の切断・4xx / 5xx が
     * <b>「応答の形違い」に化けて、原因が記録から消える</b>。
     * {@link HttpURLConnection#getResponseCode()} は同じ失敗を投げ直すため、上の約束が保たれる。
     * <b>戻り値は使わない。読むこと自体が目的である。</b>
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
            connection.getResponseCode();
            Optional<ReleaseVersion> published = published(connection.getHeaderField("Location"));
            if (published.isEmpty()) {
                Logs.warn(LogEvent.UPDATE_NOT_CHECKED);
            }
            return published;
        } catch (IOException | RuntimeException e) {
            // 遮断・DNS 不達・証明書——どれも「確認できなかった」に倒す（#13）。
            Logs.warn(LogEvent.UPDATE_NOT_CHECKED, e);
            return Optional.empty();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
