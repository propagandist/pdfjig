<#
.SYNOPSIS
    Sandbox の中で :pdf-desktop:uiTest を走らせ、結果を C:\out へ置く。

.DESCRIPTION
    ホスト側（tools/sandbox/Invoke-UiTestInSandbox.ps1）が組んだマップを前提にする。

        C:\src         リポジトリ（読み取り専用）
        C:\jdk         JDK 21（読み取り専用）
        C:\gradle-ro   ~/.gradle/caches（読み取り専用。GRADLE_RO_DEP_CACHE）
        C:\gradle-dist ~/.gradle/wrapper（読み取り専用。展開済みの gradle.bat）
        C:\out         結果の置き場（唯一書ける先）

.NOTES
    ★ ここは Windows PowerShell 5.1 で走る。Sandbox に pwsh は入っていない。
      三項演算子や ?? は使えない。UTF-8 BOM 付きで保存すること。

    ★ gradlew は使わない。中の GRADLE_USER_HOME は空なので、叩くと Gradle 本体を
      毎回ダウンロードしにいく（150MB）。ネットワークは切ってあるので落ちる。
      マップした wrapper\dists の展開済み gradle.bat を直接呼び、どの dist かは
      gradle-wrapper.properties から解く。版の正は wrapper のままにしておく。

    ★ native コマンドの stderr を 2>&1 でパイプへ流すと、ErrorActionPreference=Stop の
      下では ErrorRecord が終端エラーになる。java -version も Gradle の警告も stderr へ
      出るので必ず踏む。Invoke-Native を通すこと（2026-08-23 にここで落ちた）。

    ★ ビルドは C:\work で行う。C:\src は読み取り専用であり、かつホストの作業ツリーを
      汚さないため。.git は写す——Spotless が .gitattributes を JGit で読む。3.2MB しかない。
#>
[CmdletBinding()]
param(
    # 依存が読み取り専用キャッシュから引けないときに、ホスト側から --offline を外す。
    [switch] $AllowNetwork
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Out = 'C:\out'
$Log = Join-Path $Out 'run.log'
$ExitCodeFile = Join-Path $Out 'exit-code.txt'
$GradleLog = Join-Path $Out 'gradle.log'
$WrapperProps = 'C:\src\gradle\wrapper\gradle-wrapper.properties'
$Work = 'C:\work'

function Write-Log([string] $Message) {
    $line = '[{0:HH:mm:ss}] {1}' -f (Get-Date), $Message
    Write-Host $line
    Add-Content -Path $Log -Value $line -Encoding UTF8
}

<# 外部コマンドを走らせ、出力（stderr 込み）と終了コードを返す。理由は .NOTES。 #>
function Invoke-Native([scriptblock] $Command) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Command 2>&1
        return [pscustomobject]@{ Output = $output; ExitCode = $LASTEXITCODE }
    } finally {
        $ErrorActionPreference = $prev
    }
}

<# wrapper が指す版の、展開済み gradle.bat。 #>
function Resolve-GradleLauncher {
    $line = Select-String -Path $WrapperProps -Pattern '^distributionUrl=' | Select-Object -First 1
    if (-not $line) { throw "distributionUrl が読めない: $WrapperProps" }
    if ($line.Line -notmatch 'gradle-([0-9.]+)-(bin|all)\.zip') {
        throw ('distributionUrl から版を読み取れない: ' + $line.Line)
    }
    $distRoot = Join-Path 'C:\gradle-dist\dists' ('gradle-{0}-{1}' -f $Matches[1], $Matches[2])
    if (-not (Test-Path $distRoot)) {
        throw ("展開済みの dist が無い: $distRoot" +
            '（ホストで一度 ./gradlew を走らせて wrapper に取ってこさせること）')
    }
    # ハッシュのフォルダ名は予測できないので総当たりで探す。
    $bat = Get-ChildItem -Path $distRoot -Filter 'gradle.bat' -Recurse -File | Select-Object -First 1
    if (-not $bat) { throw "gradle.bat が無い: $distRoot" }
    return $bat.FullName
}

$code = 1
try {
    New-Item -ItemType Directory -Path $Out -Force | Out-Null
    Write-Log '== uiTest を Sandbox で走らせる =='

    $env:JAVA_HOME = 'C:\jdk'
    # -version ではなく --version を使う。前者は stderr へ書くため 5.1 が ErrorRecord として
    # 扱い、ログの頭に「java.exe : 」が付いて読みにくい。--version は stdout へ出る。
    $java = Invoke-Native { & 'C:\jdk\bin\java.exe' --version }
    if ($java.ExitCode -ne 0) { throw ('java --version が失敗した: ' + ($java.Output | Out-String)) }
    Write-Log ('JDK: ' + ((($java.Output | Out-String).Trim() -split '\r?\n')[0]))

    $gradle = Resolve-GradleLauncher
    Write-Log ('gradle: ' + $gradle)

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $copy = Invoke-Native {
        robocopy 'C:\src' $Work /MIR /XD build dist .gradle /R:1 /W:1 /NFL /NDL /NJH /NJS /NP
    }
    # robocopy は「写した」でも 1 を返す。8 以上だけが失敗。
    if ($copy.ExitCode -ge 8) { throw ('robocopy が失敗した（終了コード {0}）' -f $copy.ExitCode) }
    Write-Log ('ソースを写した {0:N1} 秒' -f $sw.Elapsed.TotalSeconds)

    $env:GRADLE_USER_HOME = 'C:\gradle-home'
    $env:GRADLE_RO_DEP_CACHE = 'C:\gradle-ro'

    $arguments = @('-p', $Work, ':pdf-desktop:uiTest', '--console=plain', '--no-daemon', '--stacktrace')
    if (-not $AllowNetwork) { $arguments += '--offline' }
    Write-Log ('gradle ' + ($arguments -join ' '))

    $sw.Restart()
    $run = Invoke-Native { & $gradle @arguments }
    # Tee-Object は 5.1 では UTF-16 で書く。あとで読むものなので UTF-8 に揃える。
    ($run.Output | Out-String) | Set-Content -Path $GradleLog -Encoding UTF8
    $run.Output | Out-Host
    $code = $run.ExitCode
    Write-Log ('uiTest 終了コード {0} / {1:N1} 秒' -f $code, $sw.Elapsed.TotalSeconds)
} catch {
    Write-Log ('失敗: ' + $_.Exception.Message)
    Write-Log $_.ScriptStackTrace
    $code = 1
} finally {
    # ★ レポートを先に出す。exit-code.txt は「置き場へ全部出し終えた」印であり、
    #   これを見たホストは Sandbox を閉じにかかる。順番を逆にすると、写している
    #   最中に窓を落とされて、失敗したときに一番見たいものが欠ける。
    try {
        foreach ($pair in @(
                @{ From = "$Work\pdf-desktop\build\reports\tests\uiTest"; To = 'reports' },
                @{ From = "$Work\pdf-desktop\build\test-results\uiTest";  To = 'test-results' })) {
            if (Test-Path $pair.From) {
                $null = Invoke-Native {
                    robocopy $pair.From (Join-Path $Out $pair.To) /MIR /R:1 /W:1 /NFL /NDL /NJH /NJS /NP
                }
            }
        }
    } catch {
        # 写せないこと自体で結果を握り潰さない。元の終了コードを届けるほうが大事である。
        Write-Log ('レポートを写せなかった: ' + $_.Exception.Message)
    }

    Write-Log ('== 終わり（{0}）==' -f $code)

    # ★ 最後に書く。ここへ来なければホストは上限まで待って落ちる——それが正しい。
    Set-Content -Path $ExitCodeFile -Value $code -Encoding Ascii

    # 自分でも閉じにいく。ホストが落とす前に行儀よく終われるならそのほうがよい。
    # ★ ただしこれに頼らない。2026-08-23 の実測では shutdown を出しても
    #   WindowsSandbox.exe が残った。片付けはホスト側の責務にしてある。
    Start-Sleep -Seconds 2
    & shutdown.exe /s /t 0
}
