<#
.SYNOPSIS
    Sandbox の中で MSI / EXE を入れて、確かめて、消す。結果を C:\out へ置く。

.DESCRIPTION
    docs/HANDOVER.md 4-4「人が見るもの」の 2〜5 番にあたる。
    実機でやると残骸の切り分けができないため人手に残っていたが、Sandbox は閉じれば
    消えるので、入れる → 消す → もう一度入れる を何度でもやり直せる。

    ホスト側（tools/sandbox/Invoke-InstallCheckInSandbox.ps1）が組んだマップを前提にする。

        C:\dist  dist/（読み取り専用。MSI / EXE / ZIP）
        C:\src   リポジトリ（読み取り専用。AppLaunch.ps1 を読む）
        C:\out   結果の置き場（唯一書ける先）

.NOTES
    ★ ここは Windows PowerShell 5.1 で走る。UTF-8 BOM 付きで保存すること。

    ★ Sandbox の既定ユーザー WDAGUtilityAccount は Administrators のメンバーである。
      したがって「標準ユーザーの環境で入るか」はここでは確かめられない。
      **4-4 の 2 番のうち、確かめられているのはここまでである。**

    ★ Sandbox は再起動できない（再起動は Sandbox の終了になる）。だから
      「再起動を要求しない」ことは前提であり、確かめる対象でもある。
      msiexec が 3010 を返したらそこで落とす。
#>
[CmdletBinding()]
param(
    # EXE をサイレントで入れるときの引数。jpackage の exe が何を受け取るかは
    # 実測して決める（初回はここを変えながら run.log を読む）。
    [string] $ExeSilentArgs = '/qn',

    # 期待する UpgradeCode。既定は pdf-desktop/build.gradle.kts の upgradeUuid。
    # ★ わざと違う値を渡して「落ちること」を確かめられるようにしてある。
    #   通ることだけを見ても、検知できる保証にはならない。
    [string] $ExpectedUpgradeCode = '{3210BCE4-3635-4EFC-8EC1-DC77881091BB}'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Out = 'C:\out'
$Log = Join-Path $Out 'run.log'
$ExitCodeFile = Join-Path $Out 'exit-code.txt'

. 'C:\src\tools\smoke\AppLaunch.ps1'

function Write-Log([string] $Message) {
    $line = '[{0:HH:mm:ss}] {1}' -f (Get-Date), $Message
    Write-Host $line
    Add-Content -Path $Log -Value $line -Encoding UTF8
}

<#
    MSI の Property テーブルを読む。

    入れずに読めるので、UpgradeCode / ProductCode の照合はインストールの前にできる。
    Win32_Product は使わない——列挙するだけで全製品の再構成が走り、遅いうえに副作用がある。
#>
function Get-MsiProperties([string] $MsiPath) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.GetType().InvokeMember(
        'OpenDatabase', 'InvokeMethod', $null, $installer, @($MsiPath, 0))
    $view = $database.GetType().InvokeMember(
        'OpenView', 'InvokeMethod', $null, $database, @('SELECT Property, Value FROM Property'))
    # ★ InvokeMember の戻り値はパイプラインへ流れる。[void] で捨てないと、この関数の戻り値が
    #   「ハッシュテーブル 1 個」ではなく配列になり、$props['ProductCode'] が
    #   「文字列を Int32 に変換できない」で落ちる（2026-08-23 に踏んだ）。
    [void] $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null)
    $result = @{}
    while ($true) {
        $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
        if (-not $record) { break }
        $name = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
        $value = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(2))
        $result[$name] = $value
    }
    [void] $view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null)
    return $result
}

<# 外部コマンドを走らせ、終了コードを返す。 #>
function Invoke-Installer([string] $FilePath, [string[]] $Arguments, [string] $What) {
    Write-Log ('{0}: {1} {2}' -f $What, (Split-Path -Leaf $FilePath), ($Arguments -join ' '))
    $p = Start-Process -FilePath $FilePath -ArgumentList $Arguments -Wait -PassThru
    Write-Log ('  終了コード {0}' -f $p.ExitCode)
    return $p.ExitCode
}

<#
    インストーラの終了コードを検める。

    ★ 3010 は「成功したが再起動が要る」。Sandbox は再起動できないので、ここを黙って
      通すと以降の確認が意味を失う。そもそも配布物が再起動を要求しないこと自体が
      確かめたいことである。
#>
function Assert-InstallerSucceeded([int] $Code, [string] $What) {
    if ($Code -eq 3010) {
        throw "$What が再起動を要求した（3010）。配布物が再起動を要求してはならない。"
    }
    if ($Code -ne 0) {
        throw "$What が失敗した（終了コード $Code）"
    }
}

<# アンインストール情報のある場所を全部見る。マシン単位とユーザー単位で置き場が違う。 #>
function Get-UninstallEntries([string] $ProductCode) {
    $roots = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall'
    )
    $found = @()
    foreach ($root in $roots) {
        $path = Join-Path $root $ProductCode
        if (Test-Path $path) { $found += $path }
    }
    return @($found)
}

<# スタートメニューのショートカット。目で見る必要はない。 #>
function Get-StartMenuShortcuts {
    $roots = @(
        (Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\PDFjig'),
        (Join-Path $env:AppData 'Microsoft\Windows\Start Menu\Programs\PDFjig')
    )
    $found = @()
    foreach ($root in $roots) {
        if (Test-Path $root) {
            $found += @(Get-ChildItem -Path $root -Filter '*.lnk' -Recurse -File |
                Select-Object -ExpandProperty FullName)
        }
    }
    return @($found)
}

<# 入った状態を検める。入った実行ファイルの場所を返す。 #>
function Assert-Installed([string] $ExpectedRoot, [string] $ProductCode, [string] $What) {
    $exe = Join-Path $ExpectedRoot 'PDFjig.exe'
    if (-not (Test-Path $exe)) {
        throw "$What : $exe が無い"
    }
    Write-Log ('  入った: ' + $exe)

    # ★ @() で包む。関数の戻り値はパイプラインを通るため、要素が 1 つの配列はスカラーに解け、
    #   Set-StrictMode の下で .Count が「そんなプロパティは無い」になる。
    #   ショートカットがちょうど 1 つのときだけ落ちる（2026-08-23 に踏んだ）。
    $shortcuts = @(Get-StartMenuShortcuts)
    if ($shortcuts.Count -eq 0) {
        throw "$What : スタートメニューにショートカットが出ていない"
    }
    Write-Log ('  スタートメニュー: {0}' -f ($shortcuts -join ' / '))

    $entries = @(Get-UninstallEntries $ProductCode)
    if ($entries.Count -eq 0) {
        throw "$What : アンインストール情報が無い（$ProductCode）"
    }
    Write-Log ('  アンインストール情報: {0}' -f ($entries -join ' / '))

    return $exe
}

<# 消えた状態を検める。Sandbox には他に何も入っていないので、残骸の判定が正確になる。 #>
function Assert-Removed([string] $ExpectedRoot, [string] $ProductCode, [string] $What) {
    $leftovers = @()
    if (Test-Path $ExpectedRoot) {
        $files = @(Get-ChildItem -Path $ExpectedRoot -Recurse -File -ErrorAction SilentlyContinue)
        if ($files.Count -gt 0) {
            $leftovers += ('ファイルが {0} 個残っている: {1}' -f $files.Count, $ExpectedRoot)
        }
    }
    $shortcuts = @(Get-StartMenuShortcuts)
    if ($shortcuts.Count -gt 0) {
        $leftovers += ('ショートカットが残っている: {0}' -f ($shortcuts -join ' / '))
    }
    $entries = @(Get-UninstallEntries $ProductCode)
    if ($entries.Count -gt 0) {
        $leftovers += ('アンインストール情報が残っている: {0}' -f ($entries -join ' / '))
    }
    if ($leftovers.Count -gt 0) {
        throw ("$What : 消したのに残った —— " + ($leftovers -join ' / '))
    }
    Write-Log '  残骸なし'
}

$code = 1
try {
    New-Item -ItemType Directory -Path $Out -Force | Out-Null
    Write-Log '== インストーラを検める =='

    $msi = @(Get-ChildItem -Path 'C:\dist' -Filter '*.msi' -File)
    $exe = @(Get-ChildItem -Path 'C:\dist' -Filter 'PDFjig-*.exe' -File)
    if ($msi.Count -ne 1) { throw ('C:\dist の MSI が 1 つでない（{0} 個）' -f $msi.Count) }
    if ($exe.Count -ne 1) { throw ('C:\dist の EXE が 1 つでない（{0} 個）' -f $exe.Count) }
    Write-Log ('MSI: {0}' -f $msi[0].Name)
    Write-Log ('EXE: {0}' -f $exe[0].Name)

    # ── UpgradeCode は入れる前に読める ──────────────────────────────────────
    $props = Get-MsiProperties $msi[0].FullName
    $productCode = $props['ProductCode']
    $upgradeCode = $props['UpgradeCode']
    Write-Log ('ProductCode  {0}' -f $productCode)
    Write-Log ('UpgradeCode  {0}' -f $upgradeCode)
    if ($upgradeCode -ne $ExpectedUpgradeCode) {
        throw ('UpgradeCode が想定と違う。想定 {0} / 実際 {1}。' -f $ExpectedUpgradeCode, $upgradeCode +
            'これを変えると次版のインストールがアップグレードではなく新規扱いになり、旧版が残る。')
    }
    Write-Log '  build.gradle.kts の upgradeUuid と一致した'

    $machineRoot = Join-Path $env:ProgramFiles 'PDFjig'
    $userRoot = Join-Path $env:LocalAppData 'PDFjig'

    # ── MSI をマシン単位で入れる ────────────────────────────────────────────
    Write-Log '--- MSI ---'
    $c = Invoke-Installer 'msiexec.exe' @(
        '/i', $msi[0].FullName, '/qn', '/norestart',
        '/l*v', (Join-Path $Out 'msi-install.log')) 'MSI を入れる'
    Assert-InstallerSucceeded $c 'MSI のインストール'
    $installedExe = Assert-Installed $machineRoot $productCode 'MSI'

    Write-Log '  起動を確かめる'
    Assert-AppLaunches $installedExe 90 $Out 'msi'
    Write-Log '  起動して画面が組み上がった'

    # ── 同じ MSI をもう一度。上書きになり、エントリが 2 つに増えない ─────────
    Write-Log '--- MSI をもう一度（上書きになるか）---'
    $c = Invoke-Installer 'msiexec.exe' @(
        '/i', $msi[0].FullName, '/qn', '/norestart',
        '/l*v', (Join-Path $Out 'msi-reinstall.log')) 'MSI をもう一度入れる'
    Assert-InstallerSucceeded $c 'MSI の再インストール'
    $entries = @(Get-UninstallEntries $productCode)
    if ($entries.Count -ne 1) {
        throw ('2 度目のインストールでアンインストール情報が {0} 件になった。上書きになっていない。' -f
            $entries.Count)
    }
    Write-Log '  上書きになった（アンインストール情報は 1 件のまま）'

    # ── 消す ────────────────────────────────────────────────────────────────
    Write-Log '--- MSI を消す ---'
    $c = Invoke-Installer 'msiexec.exe' @(
        '/x', $productCode, '/qn', '/norestart',
        '/l*v', (Join-Path $Out 'msi-uninstall.log')) 'MSI を消す'
    Assert-InstallerSucceeded $c 'MSI のアンインストール'
    Assert-Removed $machineRoot $productCode 'MSI'

    # ── EXE をユーザー単位で入れる ──────────────────────────────────────────
    # MSI と EXE は同時に入らない（ProductCode が同じ）。間にアンインストールを挟んである。
    Write-Log '--- EXE ---'
    $c = Invoke-Installer $exe[0].FullName ($ExeSilentArgs -split ' ') 'EXE を入れる'
    Assert-InstallerSucceeded $c 'EXE のインストール'
    $installedExe = Assert-Installed $userRoot $productCode 'EXE'

    Write-Log '  起動を確かめる'
    Assert-AppLaunches $installedExe 90 $Out 'exe'
    Write-Log '  起動して画面が組み上がった'

    Write-Log '--- EXE を消す ---'
    $c = Invoke-Installer 'msiexec.exe' @(
        '/x', $productCode, '/qn', '/norestart',
        '/l*v', (Join-Path $Out 'exe-uninstall.log')) 'EXE を消す'
    Assert-InstallerSucceeded $c 'EXE のアンインストール'
    Assert-Removed $userRoot $productCode 'EXE'

    Write-Log '== すべて通った =='
    $code = 0
} catch {
    Write-Log ('失敗: ' + $_.Exception.Message)
    Write-Log $_.ScriptStackTrace
    $code = 1
} finally {
    Write-Log ('== 終わり（{0}）==' -f $code)
    # ★ 最後に書く。ここが「置き場へ全部出し終えた」印であり、ホストはこれを見て閉じにかかる。
    Set-Content -Path $ExitCodeFile -Value $code -Encoding Ascii
    Start-Sleep -Seconds 2
    & shutdown.exe /s /t 0
}
