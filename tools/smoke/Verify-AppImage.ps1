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

    Assert-AppLaunches $exe.FullName $TimeoutSeconds $ArtifactDir
    Write-Step '起動の確認は通った'
} catch {
    Write-Host "失敗: $($_.Exception.Message)" -ForegroundColor Red
    throw
} finally {
    Remove-Item -Path $workDir -Recurse -Force -ErrorAction SilentlyContinue
}
