<#
.SYNOPSIS
    Sandbox の中で MSI / EXE を入れて、確かめて、消す。結果を C:\out へ置く。

.DESCRIPTION
    ★ 中身は tools/smoke/InstallCheck.ps1 が持つ。ここが持つのは Sandbox 固有のことだけである
      ——置き場のマップと、終わったら閉じることである。**中身をこちらへ写さないこと。**

    ホスト側（tools/sandbox/Invoke-InstallCheckInSandbox.ps1）が組んだマップを前提にする。

        C:\dist  dist/（読み取り専用。MSI / EXE / ZIP）
        C:\src   リポジトリ（読み取り専用。InstallCheck.ps1 と AppLaunch.ps1 を読む）
        C:\out   結果の置き場（唯一書ける先）

.NOTES
    ★ ここは Windows PowerShell 5.1 で走る。UTF-8 BOM 付きで保存すること。

    ★ Sandbox は再起動できない（再起動は Sandbox の終了になる）。だから
      「再起動を要求しない」ことは前提であり、確かめる対象でもある。
      その判定は InstallCheck.ps1 が持つ（msiexec が 3010 を返したら落とす）。
#>
[CmdletBinding()]
param(
    # EXE をサイレントで入れるときの引数。
    [string] $ExeSilentArgs = '/qn',

    # 期待する UpgradeCode。既定は InstallCheck.ps1 の既定に任せる。
    [string] $ExpectedUpgradeCode
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Out = 'C:\out'
$ExitCodeFile = Join-Path $Out 'exit-code.txt'

$code = 1
try {
    New-Item -ItemType Directory -Path $Out -Force | Out-Null

    $arguments = @{
        DistDir = 'C:\dist'
        OutDir = $Out
        ExeSilentArgs = $ExeSilentArgs
    }
    # 渡されたときだけ上書きする。既定は InstallCheck.ps1 が持つ（正本を 2 つにしない）。
    if ($ExpectedUpgradeCode) { $arguments['ExpectedUpgradeCode'] = $ExpectedUpgradeCode }

    & 'C:\src\tools\smoke\InstallCheck.ps1' @arguments
    $code = 0
} catch {
    $line = '[{0:HH:mm:ss}] 失敗: {1}' -f (Get-Date), $_.Exception.Message
    Write-Host $line
    Add-Content -Path (Join-Path $Out 'run.log') -Value $line -Encoding UTF8
    Add-Content -Path (Join-Path $Out 'run.log') -Value $_.ScriptStackTrace -Encoding UTF8
    $code = 1
} finally {
    # ★ 最後に書く。ここが「置き場へ全部出し終えた」印であり、ホストはこれを見て閉じにかかる。
    Set-Content -Path $ExitCodeFile -Value $code -Encoding Ascii
    Start-Sleep -Seconds 2
    & shutdown.exe /s /t 0
}
