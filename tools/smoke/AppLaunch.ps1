<#
.SYNOPSIS
    PDFjig が起動して画面が組み上がるところまでを UI Automation で確かめる。dot-source して使う。

.DESCRIPTION
    確かめるのは「配ったものがその環境で立ち上がるか」であって、機能が正しいかではない。
    機能は pdf-desktop の uiTest が見ている。ここで深追いしないのには理由がある。

    JavaFX は Windows の UI Automation プロバイダを自前で持っており（WinAccessible）、
    コントロールは外から見える。ただし AutomationId には Node#setId の値ではなく
    内部の連番が入る（jfx21 WinAccessible.java、"JavaFX"+id）。外から掴めるのは
    Name（AccessibleAttribute.TEXT）と ControlType だけであり、画面の文言を変えると
    ここは壊れる。深い操作を書けば書くほど、文言を触れなくなる。

    ★ 呼ぶ側が 2 つある——ZIP を検める Verify-AppImage.ps1 と、インストーラを検める
      tools/sandbox/guest/Verify-Installers.ps1。**書き写して 2 か所に置いてはならない。**
      分かれた瞬間から、片方だけ直した壊れた確認が通るようになる。

.NOTES
    ★ Windows PowerShell 5.1 で実行すること（pwsh ではない）。
      UIAutomationClient / UIAutomationTypes は .NET Framework のアセンブリであり、
      .NET Core には無い。pwsh で走らせると Add-Type の時点で落ちる。

    ★ 窓を持つのは Start-Process が返すプロセスではない。
      jpackage の Windows ランチャーは自分の子として本体を起こす。2026-08-22 の実測では
      親（Start-Process が返す PID）は窓を持たず、子のほうが PDFjig という表題の窓を持つ。
      返り値の PID だけを見ると「90 秒待っても窓が出なかった」で必ず落ちる。
      そのため、起動によって増えた同名プロセスをすべて候補として扱う。

    ★ UTF-8 BOM 付きで保存すること。5.1 は BOM 無しを ANSI として読み、
      UIA へ渡す「開く」が化けてボタンが永遠に見つからなくなる。
#>

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

# ツールバーに出ている文言。定義元は pdf-desktop の MainWindow#buildActions()
# （Action の toolText）で、Action#toolButton がそれを setAccessibleText へ渡す。
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

<#
    失敗したときに残せるものを残す。

    ★ 置き場は引数で受け取る。呼ぶ側のスクリプト変数を覗きに行くと、dot-source した
      先によって振る舞いが変わり、追えなくなる。
#>
function Save-Evidence([string] $Name, [int[]] $KnownIds, [string] $ArtifactDir, [string] $Prefix = 'smoke') {
    if (-not $ArtifactDir) {
        return
    }
    if (-not (Test-Path $ArtifactDir)) {
        New-Item -ItemType Directory -Path $ArtifactDir -Force | Out-Null
    }
    Save-Screenshot (Join-Path $ArtifactDir "$Prefix-failure.png")

    $report = Join-Path $ArtifactDir "$Prefix-processes.txt"
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
<#
    実行ファイルを起こし、窓が出てツールバーが組み上がるところまでを確かめて、閉じる。

    ZIP から展開したものでも、インストールされたものでも、確かめることは同じである。

    ★ -Verb RunAs を付けてはならない。管理者への昇格を求めずに動くこと自体が
      確かめたいことの 1 つである。
#>
function Assert-AppLaunches(
        [string] $ExePath,
        [int] $Seconds = 90,
        [string] $ArtifactDir,
        [string] $EvidencePrefix = 'smoke',
        [string] $ExpectedTitleLike = '*PDFjig*') {
    $appName = [System.IO.Path]::GetFileNameWithoutExtension($ExePath)
    # 起動の前に、既に動いているぶんを控えておく。ここから増えたものだけを相手にする。
    $before = @(Get-ProcessIds $appName)

    Write-Step "起動する: $ExePath"
    $launcher = Start-Process -FilePath $ExePath -PassThru

    try {
        $window = Wait-ForWindow $appName $before $launcher $Seconds
        $title = $window.Current.Name
        Write-Step "窓が出た: $title"
        if ($title -notlike $ExpectedTitleLike) {
            throw "窓の表題が想定と違う: $title"
        }

        [void] (Wait-ForToolButton $window $OpenButtonName $Seconds)
        Write-Step "ツールバーの「$OpenButtonName」を確認した"
    } catch {
        Save-Evidence $appName $before $ArtifactDir $EvidencePrefix
        throw
    } finally {
        try {
            Stop-App $appName $before
        } catch {
            Write-Warning $_.Exception.Message
        }
    }
}
