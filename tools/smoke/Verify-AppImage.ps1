<#
.SYNOPSIS
    配った成果物がその環境で起動するかを確かめる。

.DESCRIPTION
    ZIP を展開して PDFjig.exe を起動し、窓が出て画面が組み上がったところまでを見る。
    確かめるのは「配ったものがその環境で立ち上がるか」であって、機能が正しいかではない。
    機能は pdf-desktop の uiTest が見ている。ここで深追いしないのには理由がある。

    起動して画面が組み上がるところまでの確かめ方は AppLaunch.ps1 が持つ。
    インストーラを検める側（tools/sandbox/guest/Verify-Installers.ps1）と共有しており、
    ここが持つのは「ZIP を展開して PDFjig.exe を見つける」ところまでである。

.NOTES
    ★ Windows PowerShell 5.1 で実行すること（pwsh ではない）。
      UIAutomationClient / UIAutomationTypes は .NET Framework のアセンブリであり、
      .NET Core には無い。pwsh で走らせると Add-Type の時点で落ちる。

    ★ 踏んだ罠は AppLaunch.ps1 の .NOTES に書いてある。ここに写さない。
#>
[CmdletBinding()]
param(
    # 検める ZIP。
    [Parameter(Mandatory)]
    [string] $ZipPath,

    # 窓が出るのを待つ上限。
    [int] $TimeoutSeconds = 90,

    # 失敗したときに証拠を置く先。渡さなければ残さない。
    [string] $ArtifactDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Expand-Archive は 1 万近いファイルを持つランタイムイメージには遅すぎる（手元で数分）。
Add-Type -AssemblyName System.IO.Compression.FileSystem

. (Join-Path $PSScriptRoot 'AppLaunch.ps1')

<#
.SYNOPSIS
    同梱ランタイムに、外れても誰も赤くならないモジュールが入っているかを検める。

.DESCRIPTION
    jlink はモジュールを削る仕掛けであり、削られたものを使う経路は配布物でしか壊れない。
    手元の JDK で走る単体テストも uiTest もフルのモジュールを持つので、全部緑のまま通る。
    2026-08-30 に実際に踏んだ——jdk.crypto.ec が漏れており、配布物でだけ TLS が握手できず
    「更新を確認」が必ず失敗する状態で緑だった（#72）。

    ★ runtimeModules の全部は数えない。pdf-desktop/build.gradle.kts が正本であり、
      写せば必ず片方が腐る。ここが見るのは「jdeps が挙げないので手で足してあるもの」だけ
      ——コンパイルもテストも落ちない種類がそこに集まっている。

    ★ 足すときは build.gradle.kts の runtimeModules 側にも理由を書くこと。
#>
function Assert-RuntimeHasModules {
    param(
        # 展開した PDFjig.exe のあるフォルダ。隣に runtime\ がある。
        [Parameter(Mandatory)]
        [string] $AppDir
    )

    $required = @(
        # TLS の鍵交換（SunEC）。無いと HTTPS が握手で落ちる（#72 の「更新を確認」）。
        'jdk.crypto.ec',
        # 日本語ロケール。無いと日付・数値の書式が英語圏のものに化ける。
        'jdk.localedata'
    )

    $javaExe = Join-Path $AppDir 'runtime\bin\java.exe'
    if (-not (Test-Path $javaExe)) {
        throw "同梱ランタイムが見つからない: $javaExe"
    }

    Write-Step '同梱ランタイムのモジュールを検める'

    # --list-modules は stdout へ出る。2>&1 でまとめない——native の stderr を
    # ErrorActionPreference = 'Stop' の下でパイプへ流すと終端エラーになる
    # （tools/sandbox/SandboxHost.ps1 の Invoke-Native と同じ罠）。
    $listed = & $javaExe --list-modules
    if ($LASTEXITCODE -ne 0) {
        throw "java --list-modules が失敗した（終了コード $LASTEXITCODE）"
    }

    # 出力は "jdk.crypto.ec@21.0.8" の形。版は見ない。
    $present = $listed | ForEach-Object { ($_ -split '@')[0] }

    $missing = $required | Where-Object { $present -notcontains $_ }
    if ($missing) {
        throw ("同梱ランタイムに次のモジュールが無い: " + ($missing -join ', ') +
            "（pdf-desktop/build.gradle.kts の runtimeModules から漏れている）")
    }

    Write-Step ("モジュールの確認は通った: " + ($required -join ', '))
}

# ─────────────────────────────────────────────────────────────────────────────

$ZipPath = (Resolve-Path $ZipPath).Path
Write-Step "検める ZIP: $ZipPath"

$workDir = Join-Path ([System.IO.Path]::GetTempPath()) ('pdfjig-smoke-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $workDir -Force | Out-Null

try {
    Write-Step '展開する'
    # Expand-Archive は使わない。同じことを桁違いに速く済ませられる。
    [System.IO.Compression.ZipFile]::ExtractToDirectory($ZipPath, $workDir)

    $exe = Get-ChildItem -Path $workDir -Filter 'PDFjig.exe' -Recurse -File |
        Select-Object -First 1
    if (-not $exe) {
        throw 'ZIP の中に PDFjig.exe が無い'
    }

    Assert-RuntimeHasModules $exe.Directory.FullName

    Assert-AppLaunches $exe.FullName $TimeoutSeconds $ArtifactDir
    Write-Step '起動の確認は通った'
} catch {
    Write-Host "失敗: $($_.Exception.Message)" -ForegroundColor Red
    throw
} finally {
    Remove-Item -Path $workDir -Recurse -Force -ErrorAction SilentlyContinue
}
