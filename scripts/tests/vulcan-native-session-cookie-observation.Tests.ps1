Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../vulcan-native-session-java-baseline.ps1')
$script:cases = 0
function Assert-CookieTest([bool]$Condition) { if (!$Condition) { throw 'Synthetic cookie observation contract failed' } }
function Assert-CookieNoLeak([string]$Text) {
    foreach ($marker in @('SUPER_SECRET_COOKIE_A', 'SUPER_SECRET_COOKIE_B', 'cookieNames', 'https://')) { Assert-CookieTest (!$Text.Contains($marker)) }
}
foreach ($variant in @('UNTOUCHED', 'ACCEPT_STAR_STAR')) {
    $report = New-NativeBaselineReport -Variant $variant
    Assert-NativeBaselineReport $report
    foreach ($key in @('session.cookieCountBefore', 'session.cookieCountAfter', 'session.cookieCountChanged', 'session.cookieMaterialChanged')) {
        Assert-CookieTest ($report[$key] -ceq 'UNAVAILABLE')
    }
    $report.javaRequestAttempted = $true; $report.javaScheduleRequests = 1; $report.javaOutcome = 'RATE_LIMITED'
    $report['session.cookieCountBefore'] = 2; $report['session.cookieCountAfter'] = 2
    $report['session.cookieCountChanged'] = $false; $report['session.cookieMaterialChanged'] = $true
    Assert-NativeBaselineReport $report
    Assert-CookieNoLeak (ConvertTo-Json -InputObject $report)
    $script:cases++
}
foreach ($key in @('session.cookieCountBefore', 'session.cookieCountAfter', 'session.cookieCountChanged', 'session.cookieMaterialChanged', 'session.cookieNames')) {
    $bad = New-NativeBaselineReport
    $bad[$key] = 'SUPER_SECRET_COOKIE_A SUPER_SECRET_COOKIE_B'
    $caught = $false
    try { Assert-NativeBaselineReport $bad } catch {
        $caught = $true; Assert-CookieNoLeak $_.Exception.ToString()
        Assert-CookieTest ($_.Exception.Message -ceq 'UNSAFE_OUTPUT_GUARD')
    }
    Assert-CookieTest $caught
    $script:cases++
}
function Invoke-NativeBaselineChild { throw 'SUPER_SECRET_COOKIE_A SUPER_SECRET_COOKIE_B' }
foreach ($variant in @('UNTOUCHED', 'ACCEPT_STAR_STAR')) {
    $lost = Invoke-NativeBaselineOnce 'SYNTHETIC_UNUSED' ([byte[]](1, 2, 3)) (New-NativeBaselineReport -Variant $variant)
    Assert-NativeBaselineReport $lost
    foreach ($key in @('session.cookieCountBefore', 'session.cookieCountAfter', 'session.cookieCountChanged', 'session.cookieMaterialChanged')) {
        Assert-CookieTest ($lost[$key] -ceq 'UNAVAILABLE')
    }
    Assert-CookieNoLeak (ConvertTo-Json -InputObject $lost)
    $script:cases++
}
Write-Output "Synthetic cookie observation PowerShell contracts passed: $script:cases cases."
