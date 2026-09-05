Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../vulcan-native-session-java-baseline.ps1')
$script:cases = 0
function Assert-AcceptTest([bool]$Condition) { if (!$Condition) { throw 'Synthetic Accept contract failed' } }
function Assert-AcceptNoLeak([string]$Text) {
    foreach ($marker in @('SUPER_SECRET_TOKEN', 'SUPER_SECRET_GUID', 'SUPER_SECRET_COOKIE', 'SECRET_TENANT', 'SECRET_JOURNAL', 'https://')) { Assert-AcceptTest (!$Text.Contains($marker)) }
}
function Assert-RejectedReport($Report) {
    $caught = $false
    try { Assert-NativeBaselineReport $Report }
    catch { $caught = $true; Assert-AcceptNoLeak $_.Exception.ToString(); Assert-AcceptTest ($_.Exception.Message -ceq 'UNSAFE_OUTPUT_GUARD') }
    Assert-AcceptTest $caught
    $script:cases++
}

# No opt-in may resolve a HAR path, build, or launch the child in either mode.
function Get-HarLocalPath { throw 'Unexpected input access' }
foreach ($variant in @('UNTOUCHED', 'ACCEPT_STAR_STAR')) {
    $items = @(Invoke-NativeSessionBaseline $false 'DO_NOT_OPEN' $variant)
    Assert-AcceptNoLeak $items[0]
    $report = ConvertFrom-SafeProfileJson $items[0]
    Assert-AcceptTest ($report.category -ceq 'NOT_AUTHORIZED' -and $report.variant -ceq $variant -and !$report.acceptInjected -and $report.javaScheduleRequests -eq 0 -and $items[-1] -eq 1)
    $script:cases++
}

# A lost Accept child cannot prove dispatch or injection. The supervisor must retain
# UNAVAILABLE and the spent-invocation rule, never manufacture zero requests.
$lost = New-NativeBaselineReport 'CHILD_FAILURE' 'ACCEPT_STAR_STAR'
$lost.nativeEvidenceValidated = $true
$lost.javaRequestAttempted = 'UNAVAILABLE'; $lost.javaScheduleRequests = 'UNAVAILABLE'; $lost.acceptInjected = 'UNAVAILABLE'
Assert-NativeBaselineReport $lost
$roundTrip = ConvertFrom-SafeProfileJson (ConvertTo-Json -InputObject $lost)
Assert-NativeBaselineReport $roundTrip
Assert-AcceptTest ($roundTrip.javaScheduleRequests -ceq 'UNAVAILABLE' -and $roundTrip.retries -eq 0 -and $roundTrip.acceptInjected -ceq 'UNAVAILABLE')
Assert-AcceptNoLeak (ConvertTo-Json -InputObject $roundTrip)
$script:cases++

foreach ($key in @('variant', 'acceptInjected', 'unknown')) {
    $bad = New-NativeBaselineReport -Variant 'ACCEPT_STAR_STAR'
    $bad[$key] = 'SUPER_SECRET_TOKEN'
    Assert-RejectedReport $bad
}
$bad = New-NativeBaselineReport
$bad.acceptInjected = $true
Assert-RejectedReport $bad
$bad = New-NativeBaselineReport -Variant 'ACCEPT_STAR_STAR'
$bad.acceptInjected = 'UNAVAILABLE'
Assert-RejectedReport $bad
$bad = New-NativeBaselineReport -Variant 'ACCEPT_STAR_STAR'
$bad.result = 'SUCCESS'; $bad.javaOutcome = 'SUCCESS'; $bad.nativeEvidenceValidated = $true; $bad.javaRequestAttempted = $true; $bad.javaScheduleRequests = 1
Assert-RejectedReport $bad
function Invoke-NativeBaselineChild {
    param($Classpath, $Payload, $Variant)
    $script:childCalls++
    throw 'SUPER_SECRET_TOKEN SUPER_SECRET_COOKIE'
}
foreach ($variant in @('UNTOUCHED', 'ACCEPT_STAR_STAR')) {
    $script:childCalls = 0
    $lost = Invoke-NativeBaselineOnce 'SYNTHETIC_UNUSED' ([byte[]](1, 2, 3)) (New-NativeBaselineReport -Variant $variant)
    Assert-NativeBaselineReport $lost
    Assert-AcceptTest ($script:childCalls -eq 1 -and $lost.category -ceq 'CHILD_FAILURE' -and $lost.javaScheduleRequests -ceq 'UNAVAILABLE' -and $lost.javaRequestAttempted -ceq 'UNAVAILABLE' -and $lost.retries -eq 0)
    Assert-AcceptNoLeak (ConvertTo-Json -InputObject $lost)
    if ($variant -ceq 'ACCEPT_STAR_STAR') { Assert-AcceptTest ($lost.acceptInjected -ceq 'UNAVAILABLE') }
    $script:cases++
}
Write-Output "Synthetic Accept variant PowerShell contracts passed: $script:cases cases."
