<#
.SYNOPSIS
    MSI / EXE を Windows Sandbox の中で入れて、確かめて、消す。

.DESCRIPTION
    docs/HANDOVER.md 4-4「人が見るもの」の 2〜5 番を機械で見る。
    実機でやると残骸が他のソフトのものと区別できず、失敗したときに環境が壊れる。
    Sandbox は閉じれば消えるので、何度でもやり直せる。

    ★ 確かめられないことがある。Sandbox の既定ユーザーは Administrators のメンバー
      なので、「標準ユーザーの環境で入るか」は分からない。また Sandbox は
      「綺麗な Windows」であって利用者の環境ではない——4-4 の 1 番・7 番・8 番は
      ここへ移せない。

.EXAMPLE
    # 先に dist/ を作っておくこと（./gradlew :pdf-desktop:packageAll -Pversion=0.0.1）。
    # あるいは捨てタグの draft release から gh release download で取ってくる。
    ./tools/sandbox/Invoke-InstallCheckInSandbox.ps1

.NOTES
    ★ .ps1 は UTF-8 BOM 付きで保存すること。理由は SandboxHost.ps1 の .NOTES。
#>
[CmdletBinding()]
param(
    # 検めるインストーラの置き場。既定はリポジトリの dist/。
    [string] $DistDir,

    # EXE をサイレントで入れるときの引数。2026-08-23 実測で /qn が効く。
    [string] $ExeSilentArgs = '/qn',

    # 期待する UpgradeCode。既定は pdf-desktop/build.gradle.kts の upgradeUuid。
    # ★ わざと違う値を渡すと落ちる。検知が空振りしていないことを、そうやって確かめる。
    [string] $ExpectedUpgradeCode = '{3210BCE4-3635-4EFC-8EC1-DC77881091BB}',

    # Sandbox に渡すメモリ。ホストのコミットにそのまま乗る（SandboxHost.ps1 の
    # Assert-HostHasHeadroom）。
    [int] $MemoryInMB = 4096,

    # インストール・起動・アンインストールを一巡する。uiTest より短い。
    [int] $TimeoutSeconds = 900,

    # 走らせる前に、起きている Sandbox を落とす。
    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'SandboxHost.ps1')

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $DistDir) {
    $DistDir = Join-Path $repoRoot 'dist'
}
$outputDir = Join-Path $env:LOCALAPPDATA 'pdfjig\sandbox\installcheck'
$configPath = Join-Path $env:LOCALAPPDATA 'pdfjig\sandbox\installcheck.wsb'

# ── 検める対象があるか ───────────────────────────────────────────────────────
# 中で気づくと Sandbox のログを掘ることになる。ここで判る分はここで判る。
if (-not (Test-Path $DistDir)) {
    throw ("検める先が無い: $DistDir" +
        '（./gradlew :pdf-desktop:packageAll -Pversion=0.0.1 で作るか、' +
        '捨てタグの draft release から gh release download で取ってくること）')
}
$msi = @(Get-ChildItem -Path $DistDir -Filter '*.msi' -File)
$exe = @(Get-ChildItem -Path $DistDir -Filter 'PDFjig-*.exe' -File)
if ($msi.Count -ne 1 -or $exe.Count -ne 1) {
    throw ('{0} に MSI と EXE が 1 つずつ要る（MSI {1} 個 / EXE {2} 個）' -f
        $DistDir, $msi.Count, $exe.Count)
}

if ($Force) {
    Stop-Sandbox
}

# ── マップ ───────────────────────────────────────────────────────────────────
# 書ける先は C:\out だけ。dist/ もリポジトリも読み取り専用で渡す。
$mapped = @(
    @{ Host = $DistDir;   Sandbox = 'C:\dist'; ReadOnly = $true }
    @{ Host = $repoRoot;  Sandbox = 'C:\src';  ReadOnly = $true }
    @{ Host = $outputDir; Sandbox = 'C:\out';  ReadOnly = $false }
)

if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

$logon = New-GuestLogonCommand 'C:\src\tools\sandbox\guest\Verify-Installers.ps1'
$logon += (' -ExeSilentArgs "' + $ExeSilentArgs + '"')
$logon += (' -ExpectedUpgradeCode "' + $ExpectedUpgradeCode + '"')

$null = New-SandboxConfigFile `
    -Path $configPath `
    -MappedFolders $mapped `
    -LogonCommand $logon `
    -MemoryInMB $MemoryInMB `
    -Networking $false

Write-Host ('==> 検める: {0}' -f $msi[0].Name)
Write-Host ('           {0}' -f $exe[0].Name)
Write-Host ('==> 結果の置き場: {0}' -f $outputDir)

$code = Invoke-Sandbox -ConfigPath $configPath -OutputDir $outputDir `
    -MemoryInMB $MemoryInMB -TimeoutSeconds $TimeoutSeconds

Write-Host ('==> msiexec のログと証拠は {0} に残してある' -f $outputDir)

exit $code
