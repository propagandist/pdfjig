<#
.SYNOPSIS
    配った成果物がその環境で起動するかを確かめる。

.DESCRIPTION
    ZIP を展開して PDFjig.exe を起動し、窓が出て画面が組み上がったところまでを見る。
    確かめるのは「配ったものがその環境で立ち上がるか」であって、機能が正しいかではない。
    機能は pdf-desktop の uiTest が見ている。ここで深追いしないのには理由がある。

    JavaFX は Windows の UI Automation プロバイダを自前で持っており（WinAccessible）、
    コントロールは外から見える。ただし AutomationId には Node#setId の値ではなく
    内部の連番が入る（jfx21 WinAccessible.java、"JavaFX"+id）。外から掴めるのは
    Name（AccessibleAttribute.TEXT）と ControlType だけであり、画面の文言を変えると
    ここは壊れる。深い操作を書けば書くほど、文言を触れなくなる。

.NOTES
    ★ Windows PowerShell 5.1 で実行すること（pwsh ではない）。
      UIAutomationClient / UIAutomationTypes は .NET Framework のアセンブリであり、
      .NET Core には無い。pwsh で走らせると Add-Type の時点で落ちる。

    ★ 窓を持つのは Start-Process が返すプロセスではない。
      jpackage の Windows ランチャーは自分の子として本体を起こす。2026-08-22 の実測では
      親（Start-Process が返す PID）は窓を持たず、子のほうが PDFjig という表題の窓を持つ。
      返り値の PID だけを見ると「90 秒待っても窓が出なかった」で必ず落ちる。
      そのため、起動によって増えた同名プロセスをすべて候補として扱う。
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

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
# Expand-Archive は 1 万近いファイルを持つランタイムイメージには遅すぎる（手元で数分）。
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ツールバーに出ている文言。pdf-desktop の MainWindow が setAccessibleText で渡している値。
# ここを変えるなら向こうも変えること（逆も同じ）。
$OpenButtonName = '開く'

$Automation = [System.Windows.Automation.AutomationElement]
$TreeScope = [System.Windows.Automation.TreeScope]

function Write-Step([string] $Message) {
    Write-Host "==> $Message"
}

<#
    その名前で動いているプロセスの PID。1 つも無ければ空の配列。

    ★ 受け取る側は必ず @() で包むこと。関数の戻り値はパイプラインを通るため、
      要素が 1 つの配列はスカラーに解けてしまい、Set-StrictMode の下で .Count が
      「そんなプロパティは無い」になる。プロセスがちょうど 1 つのときだけ落ちる。
#>
function Get-ProcessIds([string] $Name) {
    return @(Get-Process -Name $Name -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty Id)
}

<# この起動で増えたプロセス。 #>
function Get-StartedProcesses([string] $Name, [int[]] $KnownIds) {
    return @(Get-Process -Name $Name -ErrorAction SilentlyContinue |
        Where-Object { $KnownIds -notcontains $_.Id })
}

<#
    画面を撮る。UI Automation の失敗は理由が読めない。絵が無いと、
    「窓が出ていない」のか「出ているが中身が違う」のかも分からないまま終わる。
#>
function Save-Screenshot([string] $Path) {
    try {
        $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bitmap = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.CopyFromScreen(
                    $bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
            } finally {
                $graphics.Dispose()
            }
            $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
            Write-Host "画面を保存した: $Path"
        } finally {
            $bitmap.Dispose()
        }
    } catch {
        # 撮れないこと自体は検証の失敗ではない。元の失敗を覆い隠さない。
        Write-Warning "画面を保存できなかった: $($_.Exception.Message)"
    }
}

<# 失敗したときに残せるものを残す。 #>
function Save-Evidence([string] $Name, [int[]] $KnownIds) {
    if (-not $ArtifactDir) {
        return
    }
    if (-not (Test-Path $ArtifactDir)) {
        New-Item -ItemType Directory -Path $ArtifactDir -Force | Out-Null
    }
    Save-Screenshot (Join-Path $ArtifactDir 'smoke-failure.png')

    $report = Join-Path $ArtifactDir 'smoke-processes.txt'
    Get-Process | Select-Object Id, ProcessName, MainWindowTitle, Responding |
        Format-Table -AutoSize | Out-String -Width 200 | Set-Content -Path $report -Encoding UTF8

    '' | Add-Content -Path $report -Encoding UTF8
    'この起動で増えたプロセス:' | Add-Content -Path $report -Encoding UTF8
    foreach ($started in @(Get-StartedProcesses $Name $KnownIds)) {
        "  $($started.Id) / 表題「$($started.MainWindowTitle)」/ 応答 $($started.Responding)" |
            Add-Content -Path $report -Encoding UTF8
    }
    Write-Host "プロセス一覧を保存した: $report"
}

<#
    起動したアプリの窓が出てくるまで待つ。

    どのプロセスが窓を持つかは決め打てない（.NOTES）。増えた同名プロセスを順に当たる。
#>
function Wait-ForWindow(
        [string] $Name,
        [int[]] $KnownIds,
        [System.Diagnostics.Process] $Launcher,
        [int] $Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $started = @(Get-StartedProcesses $Name $KnownIds)
        if ($started.Count -eq 0 -and $Launcher.HasExited) {
            throw "起動した直後に終了した（終了コード $($Launcher.ExitCode)）"
        }
        foreach ($candidate in $started) {
            $condition = New-Object System.Windows.Automation.PropertyCondition(
                $Automation::ProcessIdProperty, $candidate.Id)
            $window = $Automation::RootElement.FindFirst($TreeScope::Children, $condition)
            if ($window) {
                return $window
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Seconds 秒待っても窓が出なかった"
}

<#
    ツールバーのボタンが出てくるまで待つ。

    窓が出ただけでは足りない。JavaFX の窓は Scene が組み上がる前から存在しており、
    「起動したが画面は真っ白」を通してしまう。中身が UI Automation から見えることまでを
    起動の証拠とする。
#>
function Wait-ForToolButton(
        [System.Windows.Automation.AutomationElement] $Window,
        [string] $Name,
        [int] $Seconds) {
    $condition = New-Object System.Windows.Automation.AndCondition(
        (New-Object System.Windows.Automation.PropertyCondition(
            $Automation::ControlTypeProperty,
            [System.Windows.Automation.ControlType]::Button)),
        (New-Object System.Windows.Automation.PropertyCondition(
            $Automation::NameProperty, $Name)))

    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $button = $Window.FindFirst($TreeScope::Descendants, $condition)
        if ($button) {
            return $button
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Seconds 秒待ってもツールバーの「$Name」が見つからなかった"
}

<# 閉じる。行儀よく頼んでから、聞かなければ落とす。親子とも面倒を見る。 #>
function Stop-App([string] $Name, [int[]] $KnownIds) {
    foreach ($target in @(Get-StartedProcesses $Name $KnownIds)) {
        try {
            [void] $target.CloseMainWindow()
        } catch {
            # 既に終わっていることがある。閉じ方の失敗で検証を落とさない。
        }
    }

    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        if (@(Get-StartedProcesses $Name $KnownIds).Count -eq 0) {
            Write-Step '終了した'
            return
        }
        Start-Sleep -Milliseconds 500
    }

    Write-Warning '閉じる要求に応じなかったので落とす'
    foreach ($target in @(Get-StartedProcesses $Name $KnownIds)) {
        try {
            $target.Kill()
        } catch {
            Write-Warning "落とせなかった: $($target.Id)"
        }
    }
}

# ─────────────────────────────────────────────────────────────────────────────

$ZipPath = (Resolve-Path $ZipPath).Path
Write-Step "検める ZIP: $ZipPath"

$workDir = Join-Path ([System.IO.Path]::GetTempPath()) ('pdfjig-smoke-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $workDir -Force | Out-Null

$appName = $null
$before = @()
try {
    Write-Step '展開する'
    # Expand-Archive は使わない。同じことを桁違いに速く済ませられる。
    [System.IO.Compression.ZipFile]::ExtractToDirectory($ZipPath, $workDir)

    $exe = Get-ChildItem -Path $workDir -Filter 'PDFjig.exe' -Recurse -File |
        Select-Object -First 1
    if (-not $exe) {
        throw 'ZIP の中に PDFjig.exe が無い'
    }
    $appName = [System.IO.Path]::GetFileNameWithoutExtension($exe.Name)

    # 起動の前に、既に動いているぶんを控えておく。ここから増えたものだけを相手にする。
    $before = @(Get-ProcessIds $appName)

    Write-Step "起動する: $($exe.FullName)"
    # 管理者への昇格を求めずに動くこと自体が確かめたいことの 1 つ。
    # -Verb RunAs を付けてはならない。
    $launcher = Start-Process -FilePath $exe.FullName -PassThru

    $window = Wait-ForWindow $appName $before $launcher $TimeoutSeconds
    $title = $window.Current.Name
    Write-Step "窓が出た: $title"
    if ($title -notlike '*PDFjig*') {
        throw "窓の表題が想定と違う: $title"
    }

    [void] (Wait-ForToolButton $window $OpenButtonName $TimeoutSeconds)
    Write-Step "ツールバーの「$OpenButtonName」を確認した"

    Stop-App $appName $before
    Write-Step '起動の確認は通った'
} catch {
    Write-Host "失敗: $($_.Exception.Message)" -ForegroundColor Red
    if ($appName) {
        Save-Evidence $appName $before
        try { Stop-App $appName $before } catch { Write-Warning $_.Exception.Message }
    }
    throw
} finally {
    Remove-Item -Path $workDir -Recurse -Force -ErrorAction SilentlyContinue
}
