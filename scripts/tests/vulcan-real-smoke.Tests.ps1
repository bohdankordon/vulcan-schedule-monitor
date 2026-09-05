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
    Write-Host 'Synthetic PowerShell smoke contracts passed.'
}
finally {
    # The absolute target was verified above and is an owned, uniquely-created synthetic directory.
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
