#Requires -Version 7.0
# Built-in-only Windows contract tests. The copied script can access only a new synthetic temp root.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Assert-Contract([bool]$Condition) { if (-not $Condition) { throw 'Synthetic smoke contract failed' } }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('VulcanSmokeContract-' + [guid]::NewGuid().ToString('N'))
$testRoot = [IO.Path]::GetFullPath($testRoot)
if (-not $testRoot.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Invalid synthetic test root'
}
try {
    [void][IO.Directory]::CreateDirectory((Join-Path $testRoot 'scripts'))
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../vulcan-real-smoke.ps1') -Destination (Join-Path $testRoot 'scripts/vulcan-real-smoke.ps1')
    . (Join-Path $testRoot 'scripts/vulcan-real-smoke.ps1')
    Assert-Contract ((Get-SmokeRepositoryRoot).TrimEnd('\') -eq $testRoot.TrimEnd('\'))
    Assert-Contract ((Invoke-VulcanRealSmoke -Help) -eq 0)
    Assert-Contract (-not (Test-Path -LiteralPath (Join-Path $testRoot '.dev')))
    # Mock only interactive input. -Run is never invoked, and no child can receive real credentials.
    function Read-Host {
        param([string]$Prompt, [switch]$AsSecureString)
        if ($Prompt -eq 'VULCAN portal URL') { return 'https://school.vulcan.net.pl/synthetic/' }
        Assert-Contract $AsSecureString.IsPresent
        if ($Prompt -eq 'VULCAN login') { return ConvertTo-SecureString 'synthetic-login' -AsPlainText -Force }
        return ConvertTo-SecureString 'synthetic-password-ą-😀' -AsPlainText -Force
    }
    Assert-Contract ((Invoke-VulcanRealSmoke -Configure) -eq 0)
    $secret = Join-Path $testRoot '.dev/vulcan-real-smoke.dpapi'
    $cipher = [IO.File]::ReadAllText($secret)
    Assert-Contract ($cipher -cmatch '^[A-Za-z0-9+/=]+$')
    Assert-Contract (-not $cipher.Contains('synthetic'))
    $plain = Unprotect-SmokePayload -Path $secret
    try {
        $reader = [IO.BinaryReader]::new([IO.MemoryStream]::new($plain), [Text.Encoding]::UTF8)
        Assert-Contract ([Text.Encoding]::ASCII.GetString($reader.ReadBytes(4)) -eq 'VSM1')
        foreach ($expected in @('https://school.vulcan.net.pl/synthetic/', 'synthetic-login', 'synthetic-password-ą-😀')) {
            Assert-Contract ([Text.Encoding]::UTF8.GetString($reader.ReadBytes($reader.ReadInt32())) -ceq $expected)
        }
        Assert-Contract ($reader.BaseStream.Position -eq $reader.BaseStream.Length)
        $reader.Dispose()
    }
    finally { [Array]::Clear($plain, 0, $plain.Length) }
    [IO.File]::WriteAllText((Join-Path $testRoot '.dev/keep.txt'), 'synthetic unrelated state')
    Assert-Contract ((Invoke-VulcanRealSmoke -Clear) -eq 0)
    Assert-Contract (-not (Test-Path -LiteralPath $secret))
    Assert-Contract (Test-Path -LiteralPath (Join-Path $testRoot '.dev/keep.txt'))
    Assert-Contract ((Invoke-VulcanRealSmoke -Clear) -eq 0)

    $info = New-SmokeProcessInfo -Executable (Get-Process -Id $PID).Path -RepositoryRoot $testRoot
    Assert-Contract ($info.Environment['VULCAN_MONITORING_ENABLED'] -ceq 'false')
    Assert-Contract ($info.Environment['TELEGRAM_BOT_ENABLED'] -ceq 'false')
    Assert-Contract (-not $info.Environment.ContainsKey('JAVA_TOOL_OPTIONS'))
    Assert-Contract (-not $info.Environment.ContainsKey('TELEGRAM_BOT_TOKEN'))
    $info.ArgumentList.Add('-NoProfile')
    $info.ArgumentList.Add('-Command')
    $info.ArgumentList.Add('$bytes=[Console]::OpenStandardInput(); $count=0; while($bytes.ReadByte() -ne -1){$count++}; [Console]::WriteLine($count); [Console]::Error.WriteLine("synthetic private stderr")')
    $inputBytes = [Text.Encoding]::UTF8.GetBytes('synthetic stdin only')
    $expectedLength = $inputBytes.Length
    $child = Invoke-SmokeChild -Info $info -InputBytes $inputBytes
    Assert-Contract ($child.ExitCode -eq 0 -and $child.Output.Trim() -eq [string]$expectedLength)
    Assert-Contract (@($inputBytes | Where-Object { $_ -ne 0 }).Count -eq 0)
    Assert-Contract (-not $child.Output.Contains('private'))

    $valid = "REAL VULCAN SMOKE - LOCAL DEVELOPMENT ONLY`n"
    foreach ($stage in @('PORTAL_VALIDATION','BROWSER_AUTH','SESSION_CAPTURE','SESSION_MATERIAL_RECONSTRUCTION',
        'VERIFY_CACHE_REQUEST','VERIFY_CACHE_PARSE','VERIFY_SCHOOL_YEAR','VERIFY_TREE_REQUEST','VERIFY_TREE_PARSE','SESSION_SNAPSHOT','VERIFIED')) {
        $valid += "stage.$stage=PASS`n"
    }
    $valid += "classCount=1`ncategory=SUCCESS`nresult=SUCCESS`n"
    Write-SmokeReport -Output $valid -ExitCode 0
    $failed = $valid.Replace('stage.VERIFY_CACHE_PARSE=PASS', 'stage.VERIFY_CACHE_PARSE=FAIL').Replace('category=SUCCESS', 'category=PROTOCOL_FAILURE').Replace('result=SUCCESS', 'result=FAIL')
    foreach ($failure in @('PERIODS_SCHEMA', 'PERIOD_ID_SCHEMA', 'PERIOD_NUMBER_SCHEMA', 'PERIOD_START_SCHEMA',
            'PERIOD_END_SCHEMA', 'PERIOD_START_TIME_FORMAT', 'PERIOD_END_TIME_FORMAT', 'PERIOD_START_TIME_ONLY', 'PERIOD_END_TIME_ONLY', 'PERIOD_NUMBER_RANGE', 'DUPLICATE_PERIOD_ID')) {
        Write-SmokeReport -Output ($failed + "cacheFailure=$failure`n") -ExitCode 1 6>$null
    }
    foreach ($extra in @("cacheFailure=UNKNOWN`n", "cacheFailure=https://private.example/secret`n",
            "cacheFailure=PERIODS_SCHEMA private-value`n", "cacheFailure=periods_schema`n",
            "cacheFailure=PERIODS_SCHEMA`ncacheFailure=PERIOD_ID_SCHEMA`n",
            "cacheFailure=PERIODS_SCHEMA`nprivate-value`n")) {
        $rejected = $false
        $script:reportWrites = 0
        # Assert the entire report is rejected before even its safe prefix is printed.
        function Write-Host { $script:reportWrites++ }
        try { Write-SmokeReport -Output ($failed + $extra) -ExitCode 1 } catch { $rejected = $true }
        finally { Remove-Item Function:Write-Host }
        Assert-Contract $rejected
        Assert-Contract ($script:reportWrites -eq 0)
    }
    foreach ($invalid in @($valid + "https://private.example/secret`n", $valid.Replace('PASS', 'private-value'), $valid + "result=SUCCESS`n")) {
        $rejected = $false
        try { Write-SmokeReport -Output $invalid -ExitCode 0 } catch { $rejected = $true }
        Assert-Contract $rejected
    }
    # The new mode remains independently opt-in; invalid combinations never decrypt or launch.
    Assert-Contract ((Invoke-VulcanRealSmoke) -eq 2)
    Assert-Contract ((Invoke-VulcanRealSmoke -Run -InvestigateSchedule429) -eq 2)
    Assert-Contract ((Invoke-VulcanRealSmoke -Configure -InvestigateSchedule429) -eq 2)
    $investigation = @'
REAL VULCAN SCHEDULE 429 INVESTIGATION
category=BROWSER_RATE_LIMITED
result=FAIL
browserSource=NATIVE_UI_REQUEST
browserScheduleRequests=1
javaScheduleRequests=0
blockedExtraScheduleRequests=0
javaPermitted=false
decisionCase=1
persistedMonitoringRefererContext=UNAVAILABLE
retries=0
javaHeaderEvidence=SYNTHETIC_PRODUCTION_TRANSPORT
browser.statusFamily=4xx
browser.status429=true
browser.refererContext=PLAN_PAGE
'@
    Write-Schedule429Report -Output $investigation -ExitCode 1
    $harnessFailure = $investigation.Replace('category=BROWSER_RATE_LIMITED','category=HARNESS_FAILURE') + "`nstage=PLAN_CONTEXT_NAVIGATION`nfailureCategory=NOT_FOUND"
    Write-Schedule429Report -Output $harnessFailure -ExitCode 1
    foreach ($badFailure in @(
        $harnessFailure.Replace('PLAN_CONTEXT_NAVIGATION','https://private.example/secret'),
        $harnessFailure.Replace('NOT_FOUND','NOT_FOUND private-value'),
        $harnessFailure.Replace("`nfailureCategory=NOT_FOUND",''),
        $harnessFailure.Replace("`nstage=PLAN_CONTEXT_NAVIGATION",''))) {
        $rejected = $false; $script:reportWrites = 0
        function Write-Host { $script:reportWrites++ }
        try { Write-Schedule429Report -Output $badFailure -ExitCode 1 } catch { $rejected = $true }
        finally { Remove-Item Function:Write-Host }
        Assert-Contract $rejected
        Assert-Contract ($script:reportWrites -eq 0)
    }

    foreach ($invalid in @(
        $investigation + "`nrawUrl=https://private.example/secret",
        $investigation + "`nbrowser.cookieCount=PrivateCookie=secret",
        $investigation + "`nbrowser.refererContext=PLAN_PAGE",
        $investigation.Replace('browserScheduleRequests=1','browserScheduleRequests=2'),
        $investigation.Replace('javaScheduleRequests=0','javaScheduleRequests=1'),
        $investigation.Replace('PLAN_PAGE','PLAN_PAGE private-value'),
        $investigation.Replace('retries=0','retries=1'))) {
        $rejected = $false; $script:reportWrites = 0
        function Write-Host { $script:reportWrites++ }
        try { Write-Schedule429Report -Output $invalid -ExitCode 1 } catch { $rejected = $true }
        finally { Remove-Item Function:Write-Host }
        Assert-Contract $rejected
        Assert-Contract ($script:reportWrites -eq 0)
    }
    # Exercise both modes with a synthetic bundle and a mocked child, never real network.
    Assert-Contract ((Invoke-VulcanRealSmoke -Configure) -eq 0)
    [void][IO.Directory]::CreateDirectory((Join-Path $testRoot 'target'))
    [IO.File]::WriteAllText((Join-Path $testRoot 'target/vulcan-real-smoke-classpath.txt'), 'synthetic-classpath')
    $script:childModes = [Collections.Generic.List[string]]::new()
    function Invoke-SmokeChild {
        param([Diagnostics.ProcessStartInfo]$Info, [byte[]]$InputBytes = @())
        if ($Info.Arguments.Contains('test-compile')) {
            Assert-Contract ($InputBytes.Length -eq 0)
            return [pscustomobject]@{ ExitCode = 0; Output = '' }
        }
        Assert-Contract ([Text.Encoding]::ASCII.GetString($InputBytes[0..3]) -ceq 'VSM1')
        Assert-Contract (-not (($Info.ArgumentList -join ' ').Contains('synthetic-password')))
        Assert-Contract (-not $Info.Environment.ContainsKey('VULCAN_MASTER_KEY'))
        [Array]::Clear($InputBytes, 0, $InputBytes.Length)
        $mode = $Info.ArgumentList[$Info.ArgumentList.Count - 1]
        $script:childModes.Add($mode)
        if ($mode -ceq '--authorized-schedule-429-investigation') {
            Assert-Contract ($Info.ArgumentList -contains 'io.github.bohdankordon.vulcanschedulemonitor.devsmoke.VulcanSchedule429Investigation')
            return [pscustomobject]@{ ExitCode = 1; Output = $investigation }
        }
        Assert-Contract ($mode -ceq '--authorized-local-smoke')
        Assert-Contract ($Info.ArgumentList -contains 'io.github.bohdankordon.vulcanschedulemonitor.devsmoke.VulcanRealSmoke')
        return [pscustomobject]@{ ExitCode = 0; Output = $valid }
    }
    Assert-Contract ((Invoke-VulcanRealSmoke -InvestigateSchedule429) -eq 1)
    Assert-Contract ((Invoke-VulcanRealSmoke -Run) -eq 0)
    Assert-Contract ($script:childModes.Count -eq 2)
    Write-Host 'Synthetic PowerShell smoke contracts passed.'
}
finally {
    # The absolute target was verified above and is an owned, uniquely-created synthetic directory.
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
