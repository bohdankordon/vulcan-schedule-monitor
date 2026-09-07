param([switch]$ReportStdin)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../vulcan-persisted-monitoring-sequence-baseline.ps1')
if ($ReportStdin) {
    Assert-MonitoringSequenceReport (ConvertFrom-SafeProfileJson ([Console]::In.ReadToEnd()))
    Write-Output 'Sequence report validation passed'
    exit 0
}
function Assert-Test([bool]$ok) { if (!$ok) { throw 'Synthetic sequence contract failed' } }
$cases = 0
$report = New-MonitoringSequenceReport
Assert-MonitoringSequenceReport $report
$cases++
function Read-PersistedBaselineKey { throw 'Unexpected key access' }
function Test-PersistedBaselineAppStopped { throw 'Unexpected application access' }
$items = @(Invoke-MonitoringSequence $false)
Assert-Test ((ConvertFrom-SafeProfileJson $items[0]).category -ceq 'NOT_AUTHORIZED')
$cases++
function Test-PersistedBaselineAppStopped { return $false }
$items = @(Invoke-MonitoringSequence $true)
Assert-Test ((ConvertFrom-SafeProfileJson $items[0]).category -ceq 'APP_RUNNING')
$cases++
$script:children = 0
function Invoke-MonitoringSequenceChild { $script:children++; throw 'SUPER_SECRET_COOKIE_A' }
$lost = Invoke-MonitoringSequenceOnce 'SYNTHETIC' ([byte[]](1,2))
Assert-MonitoringSequenceReport $lost
Assert-Test ($script:children -eq 1 -and $lost.totalScheduleRequests -ceq 'UNAVAILABLE' -and $lost.databaseSessionRestoredAfterRollback -ceq 'UNAVAILABLE')
$cases++
foreach ($key in @('cookieNames','category','totalScheduleRequests','scopeCount','gateInitiallyClear','databaseSessionRestoredAfterRollback','next.disposition','current.outcome')) {
    $bad = New-MonitoringSequenceReport; $bad[$key] = 'SUPER_SECRET_COOKIE_A https://SECRET_TENANT'
    $caught = $false
    try { Assert-MonitoringSequenceReport $bad } catch { $caught = $true; Assert-Test ($_.Exception.Message -ceq 'UNSAFE_OUTPUT_GUARD'); Assert-Test (!$_.Exception.ToString().Contains('SUPER_SECRET')) }
    Assert-Test $caught; $cases++
}
$valid = New-MonitoringSequenceReport
$valid.totalScheduleRequests = 1
$valid.requests = @(@{ scope='CURRENT'; attemptNumber=1; statusFamily='4xx'; status429=$true; contentFamily='UNAVAILABLE'; outcome='RATE_LIMITED'; retryAfterPresent=$false; retryAfterSeconds='UNAVAILABLE'; cookieCountBefore=4; cookieCountAfter=5; cookieCountChanged=$true; cookieMaterialChanged=$true })
Assert-MonitoringSequenceReport $valid
Assert-MonitoringSequenceReport (ConvertFrom-SafeProfileJson (ConvertTo-Json $valid -Depth 5))
$cases++
foreach ($key in @('cookieNames','outcome','cookieMaterialChanged','scope','attemptNumber')) {
    $bad = ConvertFrom-SafeProfileJson (ConvertTo-Json $valid -Depth 5)
    $bad.requests[0][$key] = 'SUPER_SECRET_TOKEN'
    $caught = $false
    try { Assert-MonitoringSequenceReport $bad } catch { $caught = $true; Assert-Test ($_.Exception.Message -ceq 'UNSAFE_OUTPUT_GUARD') }
    Assert-Test $caught; $cases++
}
$bad = New-MonitoringSequenceReport; $bad.totalScheduleRequests = 3
$caught = $false; try { Assert-MonitoringSequenceReport $bad } catch { $caught = $true }; Assert-Test $caught
$cases++
# Every new field is closed against arbitrary text; counts are bounded on both sides.
foreach ($key in @((New-MonitoringSequenceReport).Keys | Where-Object { $_ -match '^current\.(persistence|liveCookieTopology|materialRoundTrip)\.' })) {
    $isCount = $key -match '(expectedCookieCount|actualCookieCount|totalCookieCount|cookieCountBefore|cookieCountAfter)$'
    foreach ($value in @('SUPER_SECRET_TOKEN https://SECRET_DOMAIN/SECRET_PATH', $(if ($isCount) { -1 } else { 1 }), $(if ($isCount) { 1001 } else { 'false' }), $(if ($isCount) { $true } else { @{} }))) {
        $bad = New-MonitoringSequenceReport; $bad[$key] = $value
        $caught = $false
        try { Assert-MonitoringSequenceReport $bad } catch { $caught = $true; Assert-Test ($_.Exception.Message -ceq 'UNSAFE_OUTPUT_GUARD') }
        Assert-Test $caught; $cases++
    }
    $valid = New-MonitoringSequenceReport; $valid[$key] = $(if ($isCount) { 1000 } else { $true })
    Assert-MonitoringSequenceReport $valid; $cases++
}
Write-Output "Sequence PowerShell contracts passed: $cases cases."
