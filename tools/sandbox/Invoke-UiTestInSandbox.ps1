<#
.SYNOPSIS
    :pdf-desktop:uiTest を Windows Sandbox の中で走らせる。手元ではこちらを使う。

.DESCRIPTION
    ./gradlew :pdf-desktop:uiTest を直に叩くと、TestFX の Glass Robot が実マウスを動かすため
    開発機を 2 分ほど取り上げられ、画面をロックしていると落ちる。Sandbox は独自の入力スタックを
    持つ別 VM なので、中の SendInput はホストのカーソルに触らない。窓を最小化しても、
    画面をロックしても走り切る。

    描画は Windows の Glass / Prism のままであり、配布対象と同じ経路を通る
    （docs/HANDOVER.md「UI テストの自動化」）。

    ★ CI はこの経路を通らない。build.yml は windows ランナーの上で直に uiTest を叩いている。
      ここが緑でも CI が緑である根拠にはならない（逆も同じ）。

.EXAMPLE
    ./tools/sandbox/Invoke-UiTestInSandbox.ps1

.EXAMPLE
    # 依存が読み取り専用キャッシュに無いとき（新しい依存を足した直後など）
    ./tools/sandbox/Invoke-UiTestInSandbox.ps1 -AllowNetwork

.NOTES
    ★ 事前に一度、ホストで ./gradlew build を通しておくこと。
      Sandbox は ~/.gradle を読み取り専用でマップして依存を引く。ホスト側に無いものは
      （ネットワークを切っている以上）中でも手に入らない。

    ★ .ps1 は UTF-8 BOM 付きで保存すること。理由は SandboxHost.ps1 の .NOTES。
#>
[CmdletBinding()]
param(
    # 読み取り専用の依存キャッシュで足りないときに、中側の --offline を外す。
    [switch] $AllowNetwork,

    # JDK 21 の場所。既定は JAVA_HOME。
    [string] $JdkPath = $env:JAVA_HOME,

    # 中側が exit-code.txt を書くまで待つ上限。
    # 2026-08-23 実測で cold 5 分 / warm 4 分（起動を含む）。桁を大きくしない——
    # 上限が緩いほど、遅くなったことに気づくのが遅れる。
    [int] $TimeoutSeconds = 1800,

    # 走らせる前に、起きている Sandbox を落とす。
    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'SandboxHost.ps1')

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gradleHome = Join-Path $env:USERPROFILE '.gradle'
$outputDir = Join-Path $env:LOCALAPPDATA 'pdfjig\sandbox\uitest'
$configPath = Join-Path $env:LOCALAPPDATA 'pdfjig\sandbox\uitest.wsb'

# ── JDK を検める ─────────────────────────────────────────────────────────────
# 中で落ちてから気づくと、Sandbox のログを掘ることになる。ここで判る分はここで判る。
if (-not $JdkPath) {
    throw 'JAVA_HOME が設定されていない。-JdkPath で JDK 21 の場所を渡すこと。'
}
if (-not (Test-Path (Join-Path $JdkPath 'bin\java.exe'))) {
    throw ('JDK に見えない（bin\java.exe が無い）: {0}' -f $JdkPath)
}
# release ファイルは JDK が自分で置く版の記録。java -version を起こすより速く確かで、
# 版違いを「Gradle のツールチェイン解決が offline で失敗する」という読みにくい形で
# 踏まずに済む。
$releaseFile = Join-Path $JdkPath 'release'
if (Test-Path $releaseFile) {
    $versionLine = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="21\.' | Select-Object -First 1
    if (-not $versionLine) {
        throw ('JDK 21 ではない: {0}（build.gradle.kts のツールチェインは 21）' -f $JdkPath)
    }
}

if ($Force) {
    Stop-Sandbox
}

# ── マップ ───────────────────────────────────────────────────────────────────
# 書ける先は C:\out だけにしてある。ホストの build/ も ~/.gradle も汚さない。
$mapped = @(
    @{ Host = $repoRoot;                            Sandbox = 'C:\src';         ReadOnly = $true }
    @{ Host = $JdkPath;                             Sandbox = 'C:\jdk';         ReadOnly = $true }
    @{ Host = (Join-Path $gradleHome 'caches');     Sandbox = 'C:\gradle-ro';   ReadOnly = $true }
    @{ Host = (Join-Path $gradleHome 'wrapper');    Sandbox = 'C:\gradle-dist'; ReadOnly = $true }
    @{ Host = $outputDir;                           Sandbox = 'C:\out';         ReadOnly = $false }
)

if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

$guest = 'C:\src\tools\sandbox\guest\Run-UiTest.ps1'
$logon = New-GuestLogonCommand $guest
if ($AllowNetwork) {
    $logon += ' -AllowNetwork'
}

$null = New-SandboxConfigFile `
    -Path $configPath `
    -MappedFolders $mapped `
    -LogonCommand $logon `
    -Networking ([bool] $AllowNetwork)

Write-Host ('==> リポジトリ: {0}' -f $repoRoot)
Write-Host ('==> JDK:        {0}' -f $JdkPath)
Write-Host ('==> 結果の置き場: {0}' -f $outputDir)
if ($AllowNetwork) {
    Write-Host '==> ネットワークは有効（--offline を外す）'
} else {
    Write-Host '==> ネットワークは遮断（INV-3 の確認を兼ねる）'
}

$code = Invoke-Sandbox -ConfigPath $configPath -OutputDir $outputDir -TimeoutSeconds $TimeoutSeconds

# レポートを手元へ持ってくる。build/ の下なので .gitignore に足す必要はない。
$reportSource = Join-Path $outputDir 'reports'
if (Test-Path $reportSource) {
    $reportTarget = Join-Path $repoRoot 'build\reports\tests\uiTest-sandbox'
    if (Test-Path $reportTarget) {
        Remove-Item -Path $reportTarget -Recurse -Force
    }
    New-Item -ItemType Directory -Path $reportTarget -Force | Out-Null
    Copy-Item -Path (Join-Path $reportSource '*') -Destination $reportTarget -Recurse -Force
    Write-Host ('==> レポート: {0}\index.html' -f $reportTarget)
}

exit $code
