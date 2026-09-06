# Explicit diagnostic: two schedule dispatches maximum; session writes are rolled back.
[CmdletBinding()]
param([switch]$Run)
$sequenceAuthorized = $Run.IsPresent
. (Join-Path $PSScriptRoot 'vulcan-persisted-session-java-baseline.ps1')

function New-MonitoringSequenceReport([string]$Category = 'NOT_AUTHORIZED') {
    return [ordered]@{
        schemaVersion = 1; variant = 'PERSISTED_MONITORING_SEQUENCE'; result = 'FAIL'; category = $Category
        persistedSessionLoaded = $false; targetResolved = $false; 'account.connected' = $false; 'account.reconnectRequired' = $false
        scopeCount = 0; gateInitiallyClear = $false; accountBlockedAfterCurrent = $false
        spacingConfiguredMillis = 500; spacingAppliedBeforeNext = $false
        'current.sessionPersistedAfterSuccess' = 'UNAVAILABLE'; 'next.loadedPostCurrentSession' = 'UNAVAILABLE'
        databaseSessionRestoredAfterRollback = 'UNAVAILABLE'
        'current.outcome' = 'NOT_REACHED'; 'next.outcome' = 'NOT_REACHED'; 'next.disposition' = 'NOT_REACHED'
        totalScheduleRequests = 0; retries = 0; requests = @()
    }
}

function Assert-MonitoringSequenceReport($Report) {
    $shape = New-MonitoringSequenceReport
    if ($Report -isnot [Collections.IDictionary] -or $Report.Count -ne $shape.Count) { throw 'UNSAFE_OUTPUT_GUARD' }
    foreach ($key in $Report.Keys) { if (@($shape.Keys) -cnotcontains $key) { throw 'UNSAFE_OUTPUT_GUARD' } }
    if ($Report.variant -cne 'PERSISTED_MONITORING_SEQUENCE' -or ($Report.schemaVersion -isnot [int] -and $Report.schemaVersion -isnot [long]) -or $Report.schemaVersion -ne 1 -or $Report.result -cnotin @('SUCCESS','FAIL')) { throw 'UNSAFE_OUTPUT_GUARD' }
    if ($Report.category -cnotin @('NOT_AUTHORIZED','INVALID_INPUT','APP_RUNNING','DATABASE_UNAVAILABLE','NO_ACCOUNT','AMBIGUOUS_ACCOUNT','ACCOUNT_NOT_CONNECTED','NO_TARGET','AMBIGUOUS_TARGET','SESSION_UNAVAILABLE','UNSAFE_SESSION','BASELINE_COMPLETED','BUDGET_EXHAUSTED','HARNESS_FAILURE','BUILD_FAILURE','KEY_UNAVAILABLE','CHILD_FAILURE','UNSAFE_OUTPUT_GUARD','SEQUENCE_COMPLETED','ROLLBACK_VERIFICATION_FAILED','TRANSACTION_REQUIRED','INVALID_PLAN')) { throw 'UNSAFE_OUTPUT_GUARD' }
    foreach ($key in @('persistedSessionLoaded','targetResolved','account.connected','account.reconnectRequired','gateInitiallyClear','accountBlockedAfterCurrent','spacingAppliedBeforeNext')) {
        if ($Report[$key] -isnot [bool]) { throw 'UNSAFE_OUTPUT_GUARD' }
    }
    foreach ($key in @('current.sessionPersistedAfterSuccess','next.loadedPostCurrentSession','databaseSessionRestoredAfterRollback')) {
        if ($Report[$key] -isnot [bool] -and $Report[$key] -cne 'UNAVAILABLE') { throw 'UNSAFE_OUTPUT_GUARD' }
    }
    foreach ($key in @('scopeCount','totalScheduleRequests','retries')) {
        $value = $Report[$key]
        if ($Report.category -ceq 'CHILD_FAILURE' -and $key -cne 'scopeCount' -and $value -ceq 'UNAVAILABLE') { continue }
        if (($value -isnot [int] -and $value -isnot [long]) -or $value -lt 0 -or $value -gt 2) { throw 'UNSAFE_OUTPUT_GUARD' }
    }
    if ($Report.scopeCount -ne 0 -and $Report.scopeCount -ne 2) { throw 'UNSAFE_OUTPUT_GUARD' }
    if (($Report.spacingConfiguredMillis -isnot [int] -and $Report.spacingConfiguredMillis -isnot [long]) -or $Report.spacingConfiguredMillis -ne 500) { throw 'UNSAFE_OUTPUT_GUARD' }
    foreach ($key in @('current.outcome','next.outcome')) {
        if ($Report[$key] -cnotin @('NOT_REACHED','SUCCESS','BASELINE_ESTABLISHED','TRANSITIONS','AUTHENTICATION_REQUIRED','TRANSIENT_RECOVERY_FAILURE','DEFERRED_RATE_LIMIT','TRANSIENT_FAILURE_EXHAUSTED','INTERRUPTED','PERMANENT_FAILURE','PROTOCOL_FAILURE','BUDGET_EXHAUSTED')) { throw 'UNSAFE_OUTPUT_GUARD' }
    }
    if ($Report['next.disposition'] -cnotin @('DISPATCHED','SKIPPED_ACCOUNT_BLOCKED','SKIPPED_INTERRUPTED','SKIPPED_BUDGET','NOT_REACHED')) { throw 'UNSAFE_OUTPUT_GUARD' }
    if ($Report.requests -isnot [array] -or $Report.requests.Count -gt 2) { throw 'UNSAFE_OUTPUT_GUARD' }
    $expectedKeys = @('scope','attemptNumber','statusFamily','status429','contentFamily','outcome','retryAfterPresent','retryAfterSeconds','cookieCountBefore','cookieCountAfter','cookieCountChanged','cookieMaterialChanged')
    $attempts = @{ CURRENT = 0; NEXT = 0 }; $retries = 0
    foreach ($request in $Report.requests) {
        if ($request -isnot [Collections.IDictionary] -or $request.Count -ne $expectedKeys.Count) { throw 'UNSAFE_OUTPUT_GUARD' }
        foreach ($key in $request.Keys) { if ($expectedKeys -cnotcontains $key) { throw 'UNSAFE_OUTPUT_GUARD' } }
        if ($request.scope -cnotin @('CURRENT','NEXT') -or ($request.attemptNumber -isnot [int] -and $request.attemptNumber -isnot [long])) { throw 'UNSAFE_OUTPUT_GUARD' }
        $attempts[$request.scope]++
        if ($request.attemptNumber -ne $attempts[$request.scope]) { throw 'UNSAFE_OUTPUT_GUARD' }
        if ($request.attemptNumber -gt 1) { $retries++ }
        if ($request.scope -ceq 'CURRENT' -and $attempts.NEXT -gt 0) { throw 'UNSAFE_OUTPUT_GUARD' }
        $native = New-NativeBaselineReport
        $native.javaRequestAttempted = $true; $native.javaScheduleRequests = 1
        foreach ($name in @('statusFamily','status429','contentFamily')) { $native["java.$name"] = $request[$name] }
        foreach ($name in @('cookieCountBefore','cookieCountAfter','cookieCountChanged','cookieMaterialChanged')) { $native["session.$name"] = $request[$name] }
        $native.javaOutcome = $request.outcome; $native.retryAfterPresent = $request.retryAfterPresent; $native.retryAfterSeconds = $request.retryAfterSeconds
        Assert-NativeBaselineReport $native
    }
    if ($Report.category -ceq 'CHILD_FAILURE') {
        if ($Report.totalScheduleRequests -cne 'UNAVAILABLE' -or $Report.retries -cne 'UNAVAILABLE' -or $Report.requests.Count -ne 0 -or $Report.databaseSessionRestoredAfterRollback -cne 'UNAVAILABLE') { throw 'UNSAFE_OUTPUT_GUARD' }
    } elseif ($Report.totalScheduleRequests -ne $Report.requests.Count -or $Report.retries -ne $retries) { throw 'UNSAFE_OUTPUT_GUARD' }
    if ($Report.result -ceq 'SUCCESS' -and ($Report.totalScheduleRequests -ne 2 -or !$Report.persistedSessionLoaded -or !$Report.targetResolved -or $Report.databaseSessionRestoredAfterRollback -isnot [bool] -or !$Report.databaseSessionRestoredAfterRollback -or $Report['next.disposition'] -cne 'DISPATCHED' -or @($Report.requests | Where-Object outcome -cne 'SUCCESS').Count -ne 0)) { throw 'UNSAFE_OUTPUT_GUARD' }
}

function Invoke-MonitoringSequenceChild([string]$Classpath,[byte[]]$Payload) {
    $start = [Diagnostics.ProcessStartInfo]::new('java')
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($argument in @('-cp',$Classpath,'io.github.bohdankordon.vulcanschedulemonitor.devsmoke.VulcanPersistedMonitoringSequence','--authorized-persisted-monitoring-sequence')) { $start.ArgumentList.Add($argument) }
    foreach ($name in @($start.Environment.Keys)) {
        if ($name -match '^(JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS|SPRING_.*|VULCAN_.*|TELEGRAM_.*)$') { [void]$start.Environment.Remove($name) }
    }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.BaseStream.Write($Payload,0,$Payload.Length); $process.StandardInput.Close()
        if (!$process.WaitForExit(180000)) { $process.Kill($true); throw 'CHILD_FAILURE' }
        $safe = ConvertFrom-SafeProfileJson $stdout.GetAwaiter().GetResult()
        if (![string]::IsNullOrWhiteSpace($stderr.GetAwaiter().GetResult())) { throw 'CHILD_FAILURE' }
        Assert-MonitoringSequenceReport $safe
        if (($process.ExitCode -eq 0) -ne ($safe.result -ceq 'SUCCESS')) { throw 'CHILD_FAILURE' }
        return $safe
    } finally { if (!$process.HasExited) { $process.Kill($true) }; $process.Dispose() }
}

function Invoke-MonitoringSequenceOnce([string]$Classpath,[byte[]]$Payload) {
    try { return (Invoke-MonitoringSequenceChild $Classpath $Payload) }
    catch {
        $report = New-MonitoringSequenceReport 'CHILD_FAILURE'
        $report.totalScheduleRequests = 'UNAVAILABLE'; $report.retries = 'UNAVAILABLE'
        # The child could have dispatched; no restart. PostgreSQL rolls back on connection loss,
        # but no restored=true claim can be made without the child's verified report.
        return $report
    }
}

function Invoke-MonitoringSequence([bool]$Authorized) {
    $report = New-MonitoringSequenceReport; $payload = $null
    try {
        if ($Authorized) {
            $report.category = 'APP_RUNNING'
            if (!(Test-PersistedBaselineAppStopped)) { throw 'APP_RUNNING' }
            $root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
            $report.category = 'BUILD_FAILURE'
            Push-Location $root
            try {
                $build = & .\mvnw.cmd -B -ntp -DskipTests test-compile dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/persisted-monitoring-sequence-classpath.txt' 2>&1
                if ($LASTEXITCODE -ne 0) { throw 'BUILD_FAILURE' }; $build = $null
            } finally { Pop-Location }
            $report.category = 'APP_RUNNING'
            if (!(Test-PersistedBaselineAppStopped)) { throw 'APP_RUNNING' }
            $report.category = 'KEY_UNAVAILABLE'; $payload = Read-PersistedBaselineKey $root
            $classpath = (Join-Path $root 'target/test-classes') + ';' + (Join-Path $root 'target/classes') + ';' + [IO.File]::ReadAllText((Join-Path $root 'target/persisted-monitoring-sequence-classpath.txt')).Trim()
            $report = Invoke-MonitoringSequenceOnce $classpath $payload
        }
    } catch { # Finite boundary only.
    } finally { if ($null -ne $payload) { [Array]::Clear($payload,0,$payload.Length) } }
    try { Assert-MonitoringSequenceReport $report } catch { $report = New-MonitoringSequenceReport 'UNSAFE_OUTPUT_GUARD' }
    Write-Output (ConvertTo-Json -InputObject $report -Depth 5)
    return $(if ($report.result -ceq 'SUCCESS') { 0 } else { 1 })
}
if ($MyInvocation.InvocationName -ne '.') {
    $items = @(Invoke-MonitoringSequence $sequenceAuthorized)
    Write-Output $items[0]; exit $items[-1]
}
