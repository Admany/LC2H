param(
    [Parameter(Mandatory = $true)]
    [string]$Lane,
    [string]$JavaPath = "C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot\bin\java.exe",
    [int]$ReadyTimeoutSeconds = 180,
    [int]$PostReadySeconds = 4,
    [int]$PostCommandSeconds = 0,
    [string]$JfrFile = "",
    [string[]]$JvmArgs = @(),
    [string[]]$Commands = @()
)

$ErrorActionPreference = "Stop"
$lanePath = (Resolve-Path -LiteralPath $Lane).Path
$logPath = Join-Path $lanePath "logs\latest.log"

$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $JavaPath
$startInfo.WorkingDirectory = $lanePath
$jfrArgument = if ([string]::IsNullOrWhiteSpace($JfrFile)) {
    ""
} else {
    " -XX:StartFlightRecording=filename=`"$JfrFile`",settings=profile,dumponexit=true"
}
$extraJvmArguments = if ($JvmArgs.Count -eq 0) {
    ""
} else {
    " " + (($JvmArgs | ForEach-Object {
        if ($_ -match '\s') { '"' + ($_ -replace '"', '\\"') + '"' } else { $_ }
    }) -join " ")
}
$startInfo.Arguments = "-Xms2G -Xmx8G$jfrArgument$extraJvmArguments @libraries/net/minecraftforge/forge/1.20.1-47.4.13/win_args.txt nogui"
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $startInfo
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
[void]$process.Start()

$stdout = [System.IO.File]::Create((Join-Path $lanePath "stdout-run.txt"))
$stderr = [System.IO.File]::Create((Join-Path $lanePath "stderr-run.txt"))
$stdoutCopy = $process.StandardOutput.BaseStream.CopyToAsync($stdout)
$stderrCopy = $process.StandardError.BaseStream.CopyToAsync($stderr)

$ready = $false
$deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
    if (Test-Path -LiteralPath $logPath) {
        $tail = Get-Content -LiteralPath $logPath -Tail 16 -ErrorAction SilentlyContinue
        if ($tail -match "Done \(") {
            $ready = $true
            break
        }
    }
    Start-Sleep -Milliseconds 250
}

if ($ready) {
    Start-Sleep -Seconds $PostReadySeconds
    foreach ($command in $Commands) {
        if ($command -match '^wait:(\d+)$') {
            Start-Sleep -Seconds ([int]$Matches[1])
            continue
        }
        $process.StandardInput.WriteLine($command)
        $process.StandardInput.Flush()
        Start-Sleep -Seconds 1
    }
    if ($PostCommandSeconds -gt 0 -and -not $process.HasExited) {
        Start-Sleep -Seconds $PostCommandSeconds
    }
}

if (-not $process.HasExited) {
    $process.StandardInput.WriteLine("stop")
    $process.StandardInput.Flush()
}

$forcedStop = $false
if (-not $process.WaitForExit(45000)) {
    $forcedStop = $true
    $process.Kill()
    $process.WaitForExit()
}

$stdoutCopy.GetAwaiter().GetResult() | Out-Null
$stderrCopy.GetAwaiter().GetResult() | Out-Null
$stdout.Dispose()
$stderr.Dispose()
$stopwatch.Stop()

$generationMs = $null
$readySeconds = $null
if (Test-Path -LiteralPath $logPath) {
    foreach ($line in Get-Content -LiteralPath $logPath) {
        if ($line -match "Time elapsed: (\d+) ms") {
            $generationMs = [long]$Matches[1]
        }
        if ($line -match "Done \(([0-9.]+)s\)!") {
            $readySeconds = [double]$Matches[1]
        }
    }
}

[pscustomobject]@{
    lane = $lanePath
    java = $JavaPath
    readyObserved = $ready
    forcedStop = $forcedStop
    exitCode = $process.ExitCode
    wallSeconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 3)
    generationMs = $generationMs
    readySeconds = $readySeconds
    log = $logPath
}
