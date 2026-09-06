# Explicit one-shot local-dev diagnostic. Never invoke -Run as part of tests.
[CmdletBinding()]
param([switch]$Run)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$persistedAuthorized = $Run.IsPresent
# Definitions only; neither helper's guarded CLI is executed. No HAR functions are called.
. (Join-Path $PSScriptRoot 'vulcan-native-session-java-baseline.ps1')

function New-PersistedBaselineReport([string]$Category = 'NOT_AUTHORIZED') {
    $report = New-NativeBaselineReport
    foreach ($key in @('acceptInjected', 'nativeEvidenceValidated', 'session.cookieCount', 'session.refererContext', 'form.exactFieldSet', 'form.timestampShapesMatch', 'form.weekSemanticsMatch')) { $report.Remove($key) }
    $report.variant = 'PERSISTED_CONNECT_SESSION'; $report.category = $Category
    foreach ($key in @('persistedSessionLoaded', 'targetResolved', 'account.connected', 'account.reconnectRequired', 'form.weekStartValid', 'form.weekEndValid')) { $report[$key] = $false }
    return $report
}

function Assert-PersistedBaselineReport($Report) {
    $shape = New-PersistedBaselineReport
    if ($Report -isnot [Collections.IDictionary] -or $Report.Count -ne $shape.Count) { throw 'UNSAFE_OUTPUT_GUARD' }
    foreach ($key in $Report.Keys) { if (@($shape.Keys) -cnotcontains $key) { throw 'UNSAFE_OUTPUT_GUARD' } }
    if ($Report.variant -cne 'PERSISTED_CONNECT_SESSION' -or $Report.category -cnotin @('NOT_AUTHORIZED', 'INVALID_INPUT', 'APP_RUNNING', 'DATABASE_UNAVAILABLE', 'NO_ACCOUNT', 'AMBIGUOUS_ACCOUNT', 'ACCOUNT_NOT_CONNECTED', 'NO_TARGET', 'AMBIGUOUS_TARGET', 'SESSION_UNAVAILABLE', 'UNSAFE_SESSION', 'BASELINE_COMPLETED', 'BUDGET_EXHAUSTED', 'HARNESS_FAILURE', 'BUILD_FAILURE', 'KEY_UNAVAILABLE', 'CHILD_FAILURE', 'UNSAFE_OUTPUT_GUARD')) { throw 'UNSAFE_OUTPUT_GUARD' }
    $native = New-NativeBaselineReport
    foreach ($key in $Report.Keys) { if ($native.Contains($key) -and $key -cnotin @('variant','category')) { $native[$key] = $Report[$key] } }
    $native.category = if ($Report.category -ceq 'CHILD_FAILURE') { 'CHILD_FAILURE' } else { 'BASELINE_COMPLETED' }
    $native.nativeEvidenceValidated = $Report.persistedSessionLoaded
    Assert-NativeBaselineReport $native
    foreach ($key in @('persistedSessionLoaded', 'targetResolved', 'account.connected', 'account.reconnectRequired', 'form.weekStartValid', 'form.weekEndValid')) {
        if ($Report[$key] -isnot [bool]) { throw 'UNSAFE_OUTPUT_GUARD' }
    }
    if ($Report.javaRequestAttempted -is [bool] -and $Report.javaRequestAttempted -and
        (!$Report.persistedSessionLoaded -or !$Report.targetResolved -or !$Report['account.connected'] -or $Report['account.reconnectRequired'] -or !$Report['form.weekStartValid'] -or !$Report['form.weekEndValid'])) { throw 'UNSAFE_OUTPUT_GUARD' }
}

function Test-PersistedBaselineAppStopped {
    return !(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 8080).Count)
}

function Read-PersistedBaselineKey([string]$Root) {
    # Same CurrentUser DPAPI format as dev.ps1. Only this master key, no bot/login credentials.
    $path = Get-HarLocalPath (Join-Path $Root '.dev/vulcan-master-key.dpapi')
    $cipher = $null; $clear = $null
    try {
        $stream = [IO.File]::OpenRead($path)
        try {
            if ($stream.Length -lt 1 -or $stream.Length -gt 4096) { throw 'KEY_UNAVAILABLE' }
            $reader = [IO.StreamReader]::new($stream)
            try { $cipher = [Convert]::FromBase64String($reader.ReadToEnd().Trim()) } finally { $reader.Dispose() }
        } finally { $stream.Dispose() }
        $clear = [Security.Cryptography.ProtectedData]::Unprotect($cipher, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        if ($clear.Length -ne 44) { throw 'KEY_UNAVAILABLE' }
        return ,$clear.Clone()
    } catch { throw [InvalidOperationException]::new('KEY_UNAVAILABLE') }
    finally {
        if ($null -ne $cipher) { [Array]::Clear($cipher, 0, $cipher.Length) }
        if ($null -ne $clear) { [Array]::Clear($clear, 0, $clear.Length) }
    }
}

function Invoke-PersistedBaselineChild([string]$Classpath, [byte[]]$Payload) {
    $start = [Diagnostics.ProcessStartInfo]::new('java')
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($argument in @('-cp', $Classpath, 'io.github.bohdankordon.vulcanschedulemonitor.devsmoke.VulcanPersistedSessionJavaBaseline', '--authorized-persisted-session-baseline')) { $start.ArgumentList.Add($argument) }
    foreach ($name in @($start.Environment.Keys)) {
        if ($name -match '^(JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS|SPRING_.*|VULCAN_.*|TELEGRAM_.*)$') { [void]$start.Environment.Remove($name) }
    }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.BaseStream.Write($Payload, 0, $Payload.Length); $process.StandardInput.Close()
        if (!$process.WaitForExit(120000)) { $process.Kill($true); throw 'CHILD_FAILURE' }
        $safe = ConvertFrom-SafeProfileJson $stdout.GetAwaiter().GetResult()
        if (![string]::IsNullOrWhiteSpace($stderr.GetAwaiter().GetResult())) { throw 'CHILD_FAILURE' }
        Assert-PersistedBaselineReport $safe
        if (($process.ExitCode -eq 0) -ne ($safe.result -ceq 'SUCCESS')) { throw 'CHILD_FAILURE' }
        return $safe
    } finally { if (!$process.HasExited) { $process.Kill($true) }; $process.Dispose() }
}

function Invoke-PersistedBaselineOnce([string]$Classpath, [byte[]]$Payload) {
    try { return (Invoke-PersistedBaselineChild $Classpath $Payload) }
    catch {
        # Child may have dispatched: budget spent, no restart and no invented cookie observations.
        $report = New-PersistedBaselineReport 'CHILD_FAILURE'
        $report.javaRequestAttempted = 'UNAVAILABLE'; $report.javaScheduleRequests = 'UNAVAILABLE'
        return $report
    }
}

function Invoke-PersistedBaseline([bool]$Authorized) {
    $report = New-PersistedBaselineReport
    $payload = $null
    try {
        if ($Authorized) {
            $report.category = 'APP_RUNNING'
            if (!(Test-PersistedBaselineAppStopped)) { throw 'APP_RUNNING' }
            $root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
            $report.category = 'BUILD_FAILURE'
            Push-Location $root
            try {
                $build = & .\mvnw.cmd -B -ntp -DskipTests test-compile dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/persisted-session-baseline-classpath.txt' 2>&1
                if ($LASTEXITCODE -ne 0) { throw 'BUILD_FAILURE' }
                $build = $null
            } finally { Pop-Location }
            $report.category = 'APP_RUNNING'
            if (!(Test-PersistedBaselineAppStopped)) { throw 'APP_RUNNING' }
            $report.category = 'KEY_UNAVAILABLE'
            $payload = Read-PersistedBaselineKey $root
            $classpath = (Join-Path $root 'target/test-classes') + ';' + (Join-Path $root 'target/classes') + ';' + [IO.File]::ReadAllText((Join-Path $root 'target/persisted-session-baseline-classpath.txt')).Trim()
            $report = Invoke-PersistedBaselineOnce $classpath $payload
        }
    } catch { # Discard all exception details; retain only finite boundary.
    } finally { if ($null -ne $payload) { [Array]::Clear($payload, 0, $payload.Length) } }
    try { Assert-PersistedBaselineReport $report } catch { $report = New-PersistedBaselineReport 'UNSAFE_OUTPUT_GUARD' }
    Write-Output (ConvertTo-Json -InputObject $report -Depth 4)
    return $(if ($report.result -ceq 'SUCCESS') { 0 } else { 1 })
}

if ($MyInvocation.InvocationName -ne '.') {
    $items = @(Invoke-PersistedBaseline $persistedAuthorized)
    Write-Output $items[0]
    exit $items[-1]
}
