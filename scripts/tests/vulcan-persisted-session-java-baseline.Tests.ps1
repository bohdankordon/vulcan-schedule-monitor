Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../vulcan-persisted-session-java-baseline.ps1')
function Assert-Test([bool]$ok) { if (!$ok) { throw 'Synthetic persisted baseline contract failed' } }
$cases = 0
$report = New-PersistedBaselineReport
Assert-PersistedBaselineReport $report
Assert-Test ($report.variant -ceq 'PERSISTED_CONNECT_SESSION')
$cases++
# Exercise the dev runner's DPAPI format with a newly generated synthetic key only.
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('persisted-baseline-test-' + [guid]::NewGuid().ToString('N'))
$fixtureDev = Join-Path $fixtureRoot '.dev'
[void][IO.Directory]::CreateDirectory($fixtureDev)
$fixtureFile = Join-Path $fixtureDev 'vulcan-master-key.dpapi'
$synthetic = [Text.Encoding]::UTF8.GetBytes([Convert]::ToBase64String([byte[]]::new(32)))
try {
    $encrypted = [Security.Cryptography.ProtectedData]::Protect($synthetic, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    [IO.File]::WriteAllText($fixtureFile, [Convert]::ToBase64String($encrypted))
    $loaded = Read-PersistedBaselineKey $fixtureRoot
    Assert-Test ([Convert]::ToBase64String($loaded) -ceq [Convert]::ToBase64String($synthetic))
    [Array]::Clear($loaded, 0, $loaded.Length)
    [IO.File]::WriteAllText($fixtureFile, 'INVALID_SYNTHETIC_DATA')
    $caught = $false
    try { $null = Read-PersistedBaselineKey $fixtureRoot } catch { $caught = $true; Assert-Test ($_.Exception.Message -ceq 'KEY_UNAVAILABLE') }
    Assert-Test $caught
} finally {
    [Array]::Clear($synthetic, 0, $synthetic.Length)
    # Remove only these explicitly created synthetic files/directories; no recursive operation.
    [IO.File]::Delete($fixtureFile); [IO.Directory]::Delete($fixtureDev); [IO.Directory]::Delete($fixtureRoot)
}
$cases += 2
# Default invocation cannot read keys, connect to a DB, build, or dispatch.
function Read-PersistedBaselineKey { throw 'Unexpected secret access' }
function Test-PersistedBaselineAppStopped { throw 'Unexpected application access' }
$items = @(Invoke-PersistedBaseline $false)
$report = ConvertFrom-SafeProfileJson $items[0]
Assert-PersistedBaselineReport $report
Assert-Test ($report.category -ceq 'NOT_AUTHORIZED' -and $report.javaScheduleRequests -eq 0)
$cases++
function Test-PersistedBaselineAppStopped { return $false }
$items = @(Invoke-PersistedBaseline $true)
Assert-Test ((ConvertFrom-SafeProfileJson $items[0]).category -ceq 'APP_RUNNING')
$cases++
foreach ($key in @('cookieNames','session.cookieCountAfter','session.cookieMaterialChanged','category','variant','targetResolved','javaOutcome')) {
    $bad = New-PersistedBaselineReport; $bad[$key] = 'SUPER_SECRET_COOKIE_A https://SECRET_TENANT'
    $caught = $false
    try { Assert-PersistedBaselineReport $bad } catch {
        $caught = $true; Assert-Test ($_.Exception.Message -ceq 'UNSAFE_OUTPUT_GUARD')
        Assert-Test (!$_.Exception.ToString().Contains('SUPER_SECRET'))
    }
    Assert-Test $caught; $cases++
}
$script:childCalls = 0
function Invoke-PersistedBaselineChild { $script:childCalls++; throw 'SUPER_SECRET_TOKEN SUPER_SECRET_COOKIE_B' }
$lost = Invoke-PersistedBaselineOnce 'UNUSED_SYNTHETIC' ([byte[]](1,2))
Assert-PersistedBaselineReport $lost
Assert-Test ($script:childCalls -eq 1 -and $lost.category -ceq 'CHILD_FAILURE' -and $lost.javaScheduleRequests -ceq 'UNAVAILABLE')
Assert-Test (!(ConvertTo-Json $lost).Contains('SUPER_SECRET'))
$cases++
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = [IO.File]::ReadAllText((Join-Path $root 'scripts/vulcan-persisted-session-java-baseline.ps1'))
Assert-Test (!$source.Contains('vulcan-real-smoke.dpapi') -and !$source.Contains('telegram-bot-token.dpapi') -and !$source.Contains('Get-NativeSessionPayload'))
Assert-Test ($source.Contains('DataProtectionScope]::CurrentUser') -and $source.Contains('RedirectStandardInput = $true'))
Assert-Test ($source.Contains('$persistedAuthorized = $Run.IsPresent'))
$cases++
# Public local-dev connection constants must continue matching the normal runner.
$dev = [IO.File]::ReadAllText((Join-Path $root 'scripts/dev.ps1'))
$driver = [IO.File]::ReadAllText((Join-Path $root 'src/test/java/io/github/bohdankordon/vulcanschedulemonitor/devsmoke/VulcanPersistedSessionJavaBaseline.java'))
foreach ($value in @('54329','vulcan_monitor','vulcan-local-dev-only','8080')) { Assert-Test ($dev.Contains($value) -and $driver.Contains($value)) }
$cases++
Write-Output "Persisted baseline PowerShell contracts passed: $cases cases."
