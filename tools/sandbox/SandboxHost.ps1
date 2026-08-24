<#
.SYNOPSIS
    Windows Sandbox を起こして、中の結果を受け取るための共通部。dot-source して使う。

.DESCRIPTION
    使い捨ての Windows を検証の土台にする。手元で uiTest を走らせても画面を奪われず
    （Sandbox は独自の入力スタックを持つ別 VM で、中の SendInput はホストのカーソルに
    触らない）、インストーラを入れて消してもホストに残らない。

    ここが持つのは「.wsb を書く」「起こす」「結果を待つ」の 3 つだけである。
    何を確かめるかは呼ぶ側と guest/ のスクリプトが持つ。

.NOTES
    ★ Sandbox の中に居るのは Windows PowerShell 5.1 だけである（pwsh は入っていない）。
      guest/ に置くスクリプトで三項演算子や ?? を使ってはならない。
      このファイル自身も 5.1 で読めるようにしてある——ホスト側の既定シェルが
      pwsh でない環境でも同じように動かせるほうがよい。

    ★ .ps1 は UTF-8 BOM 付きで保存すること。BOM が無いと 5.1 は ANSI として読み、
      日本語が化ける（tools/smoke/Verify-AppImage.ps1 と同じ理由）。

    ★ 生成した .wsb をコミットしてはならない。マップ元は絶対パスでしか書けず、
      クローン先が変われば動かなくなる。だから実行時に組み立てる。
#>

Set-StrictMode -Version Latest

<# Sandbox が使える状態か。使えなければ、何をすればよいかまで言って落とす。 #>
function Assert-SandboxAvailable {
    $exe = Join-Path $env:SystemRoot 'System32\WindowsSandbox.exe'
    if (Test-Path $exe) {
        return $exe
    }
    throw @"
Windows Sandbox が有効になっていない。管理者の PowerShell で次を実行し、再起動すること:

    Enable-WindowsOptionalFeature -Online -FeatureName Containers-DisposableClientVM -All
"@
}

<#
    .wsb を書き出す。

    MappedFolders は @{ Host = '...'; Sandbox = 'C:\...'; ReadOnly = $true } の配列。

    ★ マップ元が無いまま起こすと Sandbox は理由の読めない形で失敗する。ここで先に落とす。
    ★ マップ元どうしが入れ子になる構成は Sandbox の挙動が定義されていない。
      書ける先はリポジトリの外（%LOCALAPPDATA%）に取ってあり、重ならないようにしてある。
#>
function New-SandboxConfigFile {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [array] $MappedFolders,
        [Parameter(Mandatory)] [string] $LogonCommand,
        [int] $MemoryInMB = 4096,
        [bool] $Networking = $false,
        [bool] $VGpu = $true
    )

    $sb = New-Object System.Text.StringBuilder
    [void] $sb.AppendLine('<Configuration>')
    [void] $sb.AppendLine(('  <VGpu>{0}</VGpu>' -f $(if ($VGpu) { 'Enable' } else { 'Disable' })))
    [void] $sb.AppendLine(('  <Networking>{0}</Networking>' -f $(if ($Networking) { 'Enable' } else { 'Disable' })))
    [void] $sb.AppendLine(('  <MemoryInMB>{0}</MemoryInMB>' -f $MemoryInMB))
    # 検証に要らないものは渡さない。攻撃面を増やす理由がない。
    [void] $sb.AppendLine('  <AudioInput>Disable</AudioInput>')
    [void] $sb.AppendLine('  <VideoInput>Disable</VideoInput>')
    [void] $sb.AppendLine('  <PrinterRedirection>Disable</PrinterRedirection>')
    [void] $sb.AppendLine('  <MappedFolders>')

    foreach ($folder in $MappedFolders) {
        if (-not (Test-Path $folder.Host)) {
            throw ('マップ元が無い: {0}' -f $folder.Host)
        }
        $hostPath = [System.Security.SecurityElement]::Escape((Resolve-Path $folder.Host).Path)
        $sandboxPath = [System.Security.SecurityElement]::Escape($folder.Sandbox)
        $readOnly = $(if ($folder.ReadOnly) { 'true' } else { 'false' })
        [void] $sb.AppendLine('    <MappedFolder>')
        [void] $sb.AppendLine(('      <HostFolder>{0}</HostFolder>' -f $hostPath))
        [void] $sb.AppendLine(('      <SandboxFolder>{0}</SandboxFolder>' -f $sandboxPath))
        [void] $sb.AppendLine(('      <ReadOnly>{0}</ReadOnly>' -f $readOnly))
        [void] $sb.AppendLine('    </MappedFolder>')
    }

    [void] $sb.AppendLine('  </MappedFolders>')
    [void] $sb.AppendLine('  <LogonCommand>')
    [void] $sb.AppendLine(('    <Command>{0}</Command>' -f [System.Security.SecurityElement]::Escape($LogonCommand)))
    [void] $sb.AppendLine('  </LogonCommand>')
    [void] $sb.AppendLine('</Configuration>')

    $parent = Split-Path -Parent $Path
    if (-not (Test-Path $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Set-Content -Path $Path -Value $sb.ToString() -Encoding UTF8
    return $Path
}

<#
    中側のスクリプトを呼ぶ LogonCommand を組む。

    Sandbox の実行ポリシーは既定で Restricted であり、-ExecutionPolicy Bypass が要る。
    pwsh は入っていないので Windows PowerShell 5.1 を明示する。
#>
function New-GuestLogonCommand([string] $GuestScriptPath) {
    return ('C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' +
        ' -ExecutionPolicy Bypass -NoProfile -File "' + $GuestScriptPath + '"')
}

<#
    ホストに余力があるか。

    ★ Sandbox に渡したぶんはホストのコミットチャージにそのまま乗る。開発機では
      Docker / WSL2 が既に数十 GB をコミットしていることがあり、その上へ積むと
      ホストごと不安定になる。2026-08-23 に、コミットが上限の 8 割まで来ている
      状態で 8GB を渡して回したところ、テストが 26/29 落ち（クリックがまったく
      届かない形）、その直後にホストが予期しない再起動をした。
      **因果は確かめていない**が、余力を見ずに積む理由も無い。

    足りなければ落とす。黙って続けて、原因の読めない落ち方をさせない。
#>
function Assert-HostHasHeadroom([int] $NeedMB) {
    $os = Get-CimInstance Win32_OperatingSystem
    # FreeVirtualMemory は KB。コミットできる残り。
    $freeCommitMB = [int] ($os.FreeVirtualMemory / 1KB)
    # Sandbox 本体のぶんも要る。VM に渡す量ちょうどでは足りない。
    $marginMB = 2048
    Write-Host ('==> ホストのコミット残り {0:N0}MB / Sandbox に渡す {1:N0}MB' -f $freeCommitMB, $NeedMB)
    if ($freeCommitMB -lt ($NeedMB + $marginMB)) {
        throw ('ホストの余力が足りない（コミット残り {0:N0}MB、要る目安 {1:N0}MB）。' -f
            $freeCommitMB, ($NeedMB + $marginMB) +
            '重いものを閉じるか、-MemoryInMB を下げること。')
    }
}

<# Sandbox の窓が生きているか。 #>
function Test-SandboxRunning {
    $procs = @(Get-Process -Name 'WindowsSandbox', 'WindowsSandboxClient' -ErrorAction SilentlyContinue)
    return $procs.Count -gt 0
}

<# Sandbox が現れる（$Present = $true）／消える（$false）のを待つ。間に合えば $true。 #>
function Wait-SandboxProcess([int] $TimeoutSeconds, [bool] $Present) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Test-SandboxRunning) -eq $Present) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

<#
    Sandbox を起こし、中側が置き場へ exit-code.txt を書くまで待って、その値を返す。

    ★ 上限を必ず置く（CLAUDE.md「不安定なテストの扱い」）。いつまでも待つ待ち受けは、
      壊れていることを報せない。超えたら理由を書いて落とす。

    ★ 中側は exit-code.txt を finally で必ず書く。書かれないまま落ちると、ここは
      上限まで待って「ハングした」としか言えなくなる。

    @param OutputDir 中の C:\out にマップしてあるホスト側のフォルダ。ここを空にしてから起こす。
#>
function Invoke-Sandbox {
    param(
        [Parameter(Mandatory)] [string] $ConfigPath,
        [Parameter(Mandatory)] [string] $OutputDir,
        # 余力の判定にだけ使う。実際に渡す量は .wsb 側が持つ。
        [int] $MemoryInMB = 4096,
        [int] $TimeoutSeconds = 1800,
        # 中側が書き足していくログ。増えたぶんをここへ流し、待っている間を無言にしない。
        [string] $ProgressLogName = 'run.log'
    )

    $sandboxExe = Assert-SandboxAvailable
    Assert-HostHasHeadroom -NeedMB $MemoryInMB

    # Sandbox は同時に 1 つしか動かない。前のが残っていると起動が黙って失敗する。
    $running = @(Get-Process -Name 'WindowsSandbox', 'WindowsSandboxClient' -ErrorAction SilentlyContinue)
    if ($running.Count -gt 0) {
        # -join を先に済ませる。-f のほうが強く束縛するので、後ろへ置くと
        # 出来上がった 1 つの文字列に -join が掛かり、PID が 1 つしか出ない（2026-08-24 実測）。
        $ids = ($running | ForEach-Object { $_.Id }) -join ', '
        throw ('Windows Sandbox が既に動いている（PID {0}）。閉じてからにすること。' -f $ids)
    }

    if (Test-Path $OutputDir) {
        Get-ChildItem -Path $OutputDir -Force | Remove-Item -Recurse -Force
    } else {
        New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    }

    $exitCodeFile = Join-Path $OutputDir 'exit-code.txt'
    $progressLog = Join-Path $OutputDir $ProgressLogName

    Write-Host ('==> Sandbox を起こす: {0}' -f $ConfigPath)
    Start-Process -FilePath $sandboxExe -ArgumentList ('"' + $ConfigPath + '"')

    # ★ 起きたことを確かめてから待ちに入る。
    #   WindowsSandbox.exe は起動要求を出すだけで、受け付けられなくても Start-Process は
    #   何も返さない（前の Sandbox が片付いていないとき・.wsb が不正なときに起きる）。
    #   確かめずに待ちへ入ると、上限いっぱい黙ったまま「ハングした」という誤った診断だけが
    #   残る。2026-08-23 に実際に踏んだ。
    if (-not (Wait-SandboxProcess -TimeoutSeconds 60 -Present $true)) {
        throw ('Sandbox が起動しなかった（設定: {0}）。' -f $ConfigPath +
            '前の Sandbox が片付いていないか、.wsb が受け付けられていない。' +
            '同じ .wsb を手で開くと、Sandbox 側の理由がダイアログに出る。')
    }

    $started = Get-Date
    $shown = 0
    while (((Get-Date) - $started).TotalSeconds -lt $TimeoutSeconds) {
        if (Test-Path $progressLog) {
            $lines = @(Get-Content -Path $progressLog -ErrorAction SilentlyContinue)
            if ($lines.Count -gt $shown) {
                $lines[$shown..($lines.Count - 1)] | ForEach-Object { Write-Host ('    ' + $_) }
                $shown = $lines.Count
            }
        }
        if (Test-Path $exitCodeFile) {
            $raw = (Get-Content -Path $exitCodeFile -Raw).Trim()
            $code = 0
            if (-not [int]::TryParse($raw, [ref] $code)) {
                throw ('exit-code.txt が数値でない: "{0}"' -f $raw)
            }
            Write-Host ('==> 終了コード {0}（{1:N0} 秒）' -f $code, ((Get-Date) - $started).TotalSeconds)
            # 片付けはこちらの責務。中側の shutdown だけに任せると窓が残り、
            # 次の起動が「既に動いている」で断られる（2026-08-23 実測）。
            Stop-Sandbox
            return $code
        }
        # ★ 結果を見た後に窓の生死を見る。中側は結果を書いてから shutdown するので、
        #   順番を逆にすると正常終了を「消えた」と読んでしまう。
        if (-not (Test-SandboxRunning)) {
            throw ('Sandbox が結果を残さずに終わった（置き場: {0}）。' -f $OutputDir +
                '中側が exit-code.txt を書く前に落ちたか、窓が手で閉じられた。')
        }
        Start-Sleep -Seconds 3
    }

    # ★ 上限を超えたら閉じずに落とす。窓を残しておけば、どこで止まったのかを目で見られる。
    #   ここで片付けると、一番調べたい状態を自分で消すことになる。
    throw ('{0} 秒待っても中側が exit-code.txt を書かなかった。' -f $TimeoutSeconds +
        'Sandbox の窓は開いたままにしてある。どこで止まっているかを見ること' +
        '（置き場: ' + $OutputDir + '）')
}

<# 起きている Sandbox を落とす。窓を閉じるのと違って確認を求めてこない。 #>
function Stop-Sandbox {
    $procs = @(Get-Process -Name 'WindowsSandboxClient', 'WindowsSandbox' -ErrorAction SilentlyContinue)
    foreach ($p in $procs) {
        try {
            Stop-Process -Id $p.Id -Force
        } catch {
            Write-Warning ('落とせなかった: {0} ({1})' -f $p.ProcessName, $p.Id)
        }
    }
    if ($procs.Count -gt 0) {
        if (-not (Wait-SandboxProcess -TimeoutSeconds 30 -Present $false)) {
            throw '30 秒待っても Sandbox のプロセスが消えなかった。'
        }
        # プロセスが消えても VM の後片付けは続いている。ここを詰めると次の起動が黙って失敗する。
        Start-Sleep -Seconds 5
    }
}
