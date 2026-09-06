# Entirely synthetic, including CLI fixtures. Never reads .dev or provider captures.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$sanitizer = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../sanitize-vulcan-schedule-har.ps1'))
. $sanitizer
$script:preludeCases = 0
$script:preludeCase = 'setup'
$syntheticBase = 'https://synthetic.vulcan.net.pl/SECRET_TENANT/SECRET_UNIT/'
$cookieA = 'secretCookieName=SUPER_SECRET_COOKIE_A'
$cookieB = 'secretCookieName=SUPER_SECRET_COOKIE_B'
function Assert-PreludeTest([bool]$Value) { if (!$Value) { throw "Synthetic prelude contract failed: $script:preludeCase" } }
function Assert-PreludeNoLeak([string]$Text) {
    foreach ($marker in @('SECRET_', 'SUPER_SECRET', 'secretCookieName', 'anotherCookie', 'https://', 'http://', 'synthetic.vulcan.net.pl', 'PlanLekcji.mvc', '2026-09-06', 'timestampSecret')) { Assert-PreludeTest (!$Text.Contains($marker)) }
}
function New-PreludeEntry([string]$Endpoint, [int]$Seconds, [string]$Cookie = $cookieA, [string]$Method = 'GET') {
    $time = [DateTimeOffset]::Parse('2026-09-06T12:00:00Z').AddSeconds($Seconds)
    return @{
        startedDateTime = $time.ToString('o'); _resourceType = 'xhr'
        request = @{ method = $Method; url = $syntheticBase + $Endpoint + '?id=SECRET_ID&query=SECRET_QUERY'; headers = @(@{ name = 'Cookie'; value = $Cookie }) }
        response = @{ status = 200; headers = @(); content = @{ mimeType = 'application/json'; text = 'SECRET_RESPONSE_BODY' } }
    }
}
function New-PreludeHar {
    $target = New-PreludeEntry 'PlanLekcji.mvc/GetPlanLekcjiContext' 0 $cookieB 'POST'
    $target.request.httpVersion = 'HTTP/2'
    $target.request.headers += @(
        @{ name = 'X-V-RequestVerificationToken'; value = 'SUPER_SECRET_TOKEN' },
        @{ name = 'X-V-AppGuid'; value = 'SUPER_SECRET_GUID' },
        @{ name = 'Referer'; value = $syntheticBase + 'Other.mvc' },
        @{ name = 'Content-Type'; value = 'application/x-www-form-urlencoded' }
    )
    $target.request.postData = @{ mimeType = 'application/x-www-form-urlencoded'; text = 'dataOd=2026-08-31T00%3A00%3A00&dataDo=2026-09-06T00%3A00%3A00&data=2026-09-06T00%3A00%3A00&idDziennik=SECRET_JOURNAL' }
    $target.response.content.text = '{"success":true,"data":{"planLekcji":[{"name":"SECRET_CLASS"}],"planLekcjiZeZmianami":[]}}'
    $plan = New-PreludeEntry 'PlanLekcji.mvc' -4
    $plan._resourceType = 'document'
    $plan.response.headers = @(@{ name = 'Set-Cookie'; value = $cookieB + '; Path=/SECRET_TENANT; HttpOnly' }, @{ name = 'Set-Cookie'; value = 'anotherCookie=SECRET_VALUE' })
    return @{ log = @{ entries = @($plan, $target) } }
}
function Test-PreludeCase([string]$Name, [scriptblock]$Mutate, [scriptblock]$Check) {
    $script:preludeCase = $Name
    $har = New-PreludeHar
    & $Mutate $har
    $report = ConvertTo-HarPreludeReport (ConvertTo-Json -InputObject $har -Depth 25 -Compress)
    Assert-HarSafeOutput $report
    Assert-PreludeNoLeak (ConvertTo-Json -InputObject $report -Depth 8)
    & $Check $report
    $script:preludeCases++
}

Test-PreludeCase 'plan GET, response cookies, target rotation' {} {
    param($r)
    Assert-PreludeTest ($r.result -ceq 'SUCCESS' -and $r.schemaVersion -eq 3 -and $r['prelude.requestCount'] -eq 1)
    $row = $r['prelude.sequence'][0]
    Assert-PreludeTest ($row.endpointCategory -ceq 'PLAN_PAGE' -and $row.method -ceq 'GET' -and $row.resourceFamily -ceq 'DOCUMENT')
    Assert-PreludeTest ($row.responseSetCookiePresent -and $row.responseSetCookieHeaderCount -eq 2 -and $r['prelude.containsPlanPage'])
    Assert-PreludeTest ($r['target.cookieCount'] -eq 1 -and $r['target.cookieMaterialChangedFromImmediatelyPreviousRequest'] -and $r['prelude.anyCookieMaterialChange'])
}
Test-PreludeCase 'XHR cache next cookie change' {
    param($h) $h.log.entries = @($h.log.entries[0], (New-PreludeEntry 'DziennikCache.mvc/GetCache' -2 $cookieB 'POST'), $h.log.entries[-1])
} {
    param($r) $row = $r['prelude.sequence'][1]
    Assert-PreludeTest ($row.endpointCategory -ceq 'GET_CACHE' -and $row.resourceFamily -ceq 'XHR_FETCH' -and $row.cookieMaterialChangedSincePrevious -and !$row.cookieCountChangedSincePrevious -and $r['prelude.containsGetCache'])
    Assert-PreludeTest (!$r['target.cookieMaterialChangedFromImmediatelyPreviousRequest'])
}
Test-PreludeCase 'cookie ordering only' {
    param($h) $h.log.entries[0].request.headers[0].value = $cookieA + '; anotherCookie=SECRET_VALUE'
    $h.log.entries[-1].request.headers[0].value = 'anotherCookie=SECRET_VALUE; ' + $cookieA
} { param($r) Assert-PreludeTest (!$r['target.cookieMaterialChangedFromImmediatelyPreviousRequest'] -and !$r['prelude.anyCookieMaterialChange']) }
Test-PreludeCase 'cookie addition' {
    param($h) $next = New-PreludeEntry 'Other.mvc/Query' -1 ($cookieA + '; anotherCookie=SECRET_VALUE')
    $h.log.entries = @($h.log.entries[0], $next, $h.log.entries[-1])
} { param($r) Assert-PreludeTest ($r['prelude.sequence'][1].cookieCountChangedSincePrevious -and $r['prelude.sequence'][1].cookieMaterialChangedSincePrevious) }
Test-PreludeCase 'case-sensitive cookie value' {
    param($h) $h.log.entries[-1].request.headers[0].value = 'secretCookieName=super_secret_cookie_a'
} { param($r) Assert-PreludeTest $r['target.cookieMaterialChangedFromImmediatelyPreviousRequest'] }
Test-PreludeCase 'duplicate pair multiplicity' {
    param($h) $h.log.entries[-1].request.headers[0].value = $cookieA + '; ' + $cookieA
} { param($r) Assert-PreludeTest ($r['target.cookieCount'] -eq 2 -and $r['target.cookieMaterialChangedFromImmediatelyPreviousRequest']) }
Test-PreludeCase 'external and different origin excluded' {
    param($h) $external = New-PreludeEntry 'PlanLekcji.mvc' -1
    $external.request.url = 'https://external.invalid/SECRET_TENANT/PlanLekcji.mvc'; $external.Remove('startedDateTime')
    $port = New-PreludeEntry 'PlanLekcji.mvc' -1; $port.request.url = $port.request.url.Replace('.pl/', '.pl:444/')
    $h.log.entries = @($h.log.entries[0], $external, $port, $h.log.entries[-1])
} { param($r) Assert-PreludeTest ($r['prelude.requestCount'] -eq 1) }
foreach ($pair in @(@('DziennikCache.mvc/GetCache', 'GET_CACHE'), @('Dziennik.mvc/GetTree', 'GET_TREE'), @('Home.mvc/RefreshSession', 'REFRESH_SESSION'), @('Home.mvc/Index', 'HOME'), @('Dziennik.mvc/Index', 'DZIENNIK'), @('PlanLekcji.mvc/Index', 'PLAN_PAGE'), @('assets/file.js', 'STATIC'), @('Other.mvc/SECRET_ACTION', 'OTHER_ALLOWED'), @('NotPlanLekcji.mvc', 'OTHER_ALLOWED'))) {
    Test-PreludeCase ('endpoint ' + $pair[1]) { param($h) $h.log.entries[0].request.url = $syntheticBase + $pair[0] } { param($r) Assert-PreludeTest ($r['prelude.sequence'][0].endpointCategory -ceq $pair[1]) }
}
Test-PreludeCase 'static mime and fetch inference' {
    param($h) $e = $h.log.entries[0]; $e.Remove('_resourceType'); $e.request.url = $syntheticBase + 'assets/file.css'; $e.response.content.mimeType = 'text/css'
} { param($r) Assert-PreludeTest ($r['prelude.sequence'][0].resourceFamily -ceq 'STYLE') }
Test-PreludeCase 'maximum ten preceding requests' {
    param($h) $target = $h.log.entries[-1]; $h.log.entries = @()
    for ($i = 0; $i -lt 15; $i++) { $h.log.entries += New-PreludeEntry 'Other.mvc/Query' -2 }
    $h.log.entries += $target
} { param($r) Assert-PreludeTest ($r['prelude.requestCount'] -eq 10 -and $r['prelude.sequence'][0].relativeOrder -eq 10 -and $r['prelude.sequence'][-1].relativeOrder -eq 1) }
Test-PreludeCase 'time window and chronological sorting' {
    param($h) $h.log.entries = @((New-PreludeEntry 'Dziennik.mvc/GetTree' -1), (New-PreludeEntry 'Home.mvc/Index' -11), (New-PreludeEntry 'DziennikCache.mvc/GetCache' -10), $h.log.entries[-1], (New-PreludeEntry 'Other.mvc/Query' 1))
} { param($r) Assert-PreludeTest ($r['prelude.requestCount'] -eq 2 -and $r['prelude.sequence'][0].endpointCategory -ceq 'GET_CACHE' -and $r['prelude.sequence'][0].relativeTimeBucket -ceq 'FIVE_TO_TEN_SECONDS' -and $r['prelude.immediatePrevious.endpointCategory'] -ceq 'GET_TREE') }
Test-PreludeCase 'equal timestamps use HAR order' {
    param($h) $h.log.entries = @((New-PreludeEntry 'Home.mvc/Index' 0), $h.log.entries[-1], (New-PreludeEntry 'Other.mvc/Query' 0))
} { param($r) Assert-PreludeTest ($r['prelude.requestCount'] -eq 1 -and $r['prelude.sequence'][0].relativeTimeBucket -ceq 'WITHIN_1_SECOND') }
Test-PreludeCase 'no bounded prelude' { param($h) $h.log.entries = @($h.log.entries[-1]) } {
    param($r) Assert-PreludeTest ($r['prelude.requestCount'] -eq 0 -and $r['prelude.immediatePrevious.endpointCategory'] -ceq 'ABSENT' -and $r['target.cookieMaterialChangedFromImmediatelyPreviousRequest'] -ceq 'UNAVAILABLE')
}
Test-PreludeCase 'cookie headers unavailable' {
    param($h) $h.log.entries[0].request.headers = @(); $h.log.entries[0].request.cookies = @(@{ name = 'secretCookieName'; value = 'SUPER_SECRET_COOKIE_A' })
} { param($r) Assert-PreludeTest ($r['prelude.sequence'][0].requestCookieCount -eq 1 -and !$r['prelude.sequence'][0].requestCookiePresent -and $r['target.cookieMaterialChangedFromImmediatelyPreviousRequest'] -ceq 'UNAVAILABLE') }
Test-PreludeCase 'missing time rejected' { param($h) $h.log.entries[0].Remove('startedDateTime') } { param($r) Assert-PreludeTest ($r.result -ceq 'INVALID_HAR') }
Test-PreludeCase 'offset without date rejected' { param($h) $h.log.entries[0].startedDateTime = 'timestampSecret' } { param($r) Assert-PreludeTest ($r.result -ceq 'INVALID_HAR') }
Test-PreludeCase 'wrong native method' { param($h) $h.log.entries[-1].request.method = 'GET' } { param($r) Assert-PreludeTest ($r.result -ceq 'UNSUPPORTED_TARGET_REQUEST') }
Test-PreludeCase '429 native rejected' { param($h) $h.log.entries[-1].response.status = 429 } { param($r) Assert-PreludeTest ($r.result -ceq 'UNSUPPORTED_TARGET_REQUEST') }
Test-PreludeCase 'HTML native rejected' { param($h) $h.log.entries[-1].response.content.mimeType = 'text/html' } { param($r) Assert-PreludeTest ($r.result -ceq 'UNSUPPORTED_TARGET_REQUEST') }
Test-PreludeCase 'invalid envelope rejected' { param($h) $h.log.entries[-1].response.content.text = '{"success":false}' } { param($r) Assert-PreludeTest ($r.result -ceq 'UNSUPPORTED_TARGET_REQUEST') }
Test-PreludeCase 'missing array rejected' { param($h) $h.log.entries[-1].response.content.text = '{"success":true,"data":{"planLekcji":[]}}' } { param($r) Assert-PreludeTest ($r.result -ceq 'UNSUPPORTED_TARGET_REQUEST') }
Test-PreludeCase 'multiple schedule requests' { param($h) $h.log.entries += $h.log.entries[-1] } { param($r) Assert-PreludeTest ($r.result -ceq 'AMBIGUOUS') }
Test-PreludeCase 'zero target' { param($h) $h.log.entries = @($h.log.entries[0]) } { param($r) Assert-PreludeTest ($r.result -ceq 'NOT_FOUND') }
Test-PreludeCase 'unallowed target host' { param($h) $h.log.entries[-1].request.url = $h.log.entries[-1].request.url.Replace('synthetic.vulcan.net.pl', 'external.invalid') } { param($r) Assert-PreludeTest ($r.result -ceq 'INVALID_HAR') }
Test-PreludeCase 'invalid Cookie header' { param($h) $h.log.entries[0].request.headers[0].value = 'SUPER_SECRET_COOKIE_A' } { param($r) Assert-PreludeTest ($r.result -ceq 'INVALID_HAR') }
Test-PreludeCase 'time zone offsets are normalized internally' {
    param($h) $h.log.entries[-1].startedDateTime = '2026-09-06T14:00:00+02:00'
} { param($r) Assert-PreludeTest ($r['prelude.requestCount'] -eq 1 -and $r['prelude.sequence'][0].relativeTimeBucket -ceq 'ONE_TO_FIVE_SECONDS') }
Test-PreludeCase 'wrong known-field form rejected' {
    param($h) $h.log.entries[-1].request.postData.text += '&extra=SECRET_ID'
} { param($r) Assert-PreludeTest ($r.result -ceq 'UNSUPPORTED_TARGET_REQUEST') }
Test-PreludeCase 'same-origin older requests outside window absent' {
    param($h) $h.log.entries[0].startedDateTime = '2026-09-06T11:59:49Z'
} { param($r) Assert-PreludeTest ($r['prelude.requestCount'] -eq 0 -and $r['prelude.immediatePrevious.method'] -ceq 'ABSENT') }
Test-PreludeCase 'path basename lookalikes not classified as known endpoints' {
    param($h) $h.log.entries[0].request.url = $syntheticBase + 'Home.mvc/RefreshSessionExtra'
} { param($r) Assert-PreludeTest ($r['prelude.sequence'][0].endpointCategory -ceq 'HOME' -and !$r['prelude.containsRefreshSession']) }
foreach ($raw in @('{"log":{"entries":[],"entries":[]}}', '{SUPER_SECRET_COOKIE_A')) {
    $script:preludeCase = 'invalid JSON'
    $r = ConvertTo-HarPreludeReport $raw
    Assert-PreludeTest ($r.result -ceq 'INVALID_HAR' -and $r.schemaVersion -eq 3)
    Assert-PreludeNoLeak (ConvertTo-Json $r)
    $script:preludeCases++
}
$script:preludeCase = 'output guard unknown nested key or raw string'
foreach ($key in @('rawUrl', 'endpointCategory', 'requestCookieCount')) {
    $r = ConvertTo-HarPreludeReport (ConvertTo-Json (New-PreludeHar) -Depth 25)
    $r['prelude.sequence'][0][$key] = 'SUPER_SECRET_COOKIE_A'
    $caught = $false
    try { Assert-HarSafeOutput $r } catch { $caught = $true; Assert-PreludeNoLeak $_.Exception.ToString() }
    Assert-PreludeTest $caught
    $script:preludeCases++
}

$script:preludeCase = 'v2 stays unchanged and v3 retains fingerprint comparison'
$raw = ConvertTo-Json (New-PreludeHar) -Depth 25
$v2 = ConvertTo-HarSafeReport $raw; $v3 = ConvertTo-HarPreludeReport $raw
Assert-PreludeTest ($v2.schemaVersion -eq 2 -and !$v2.Contains('prelude.sequence'))
foreach ($key in $v2.Keys) { if ($key -cne 'schemaVersion') { Assert-PreludeTest ($v2[$key] -ceq $v3[$key]) } }
$java = [ordered]@{}
foreach ($key in (Get-FingerprintSchema).Keys) { $java[$key] = $v2[$key] }
$java['schemaVersion'] = 2; $java['profileSource'] = 'JAVA_LOOPBACK'; $java['actualObservedHttpVersion'] = 'HTTP_1_1'; $java['clientPreferredVersion'] = 'HTTP_2'
foreach ($key in @('exactExpectedFieldSet', 'weekIsMondayToSunday', 'dataWithinWeek')) { $java["form.$key"] = $true }
$java['form.urlEncoded'] = $true
foreach ($key in @('dataOd', 'dataDo', 'data')) { $java["form.${key}Shape"] = 'ISO_T_DATETIME' }
$compared = Add-HarJavaComparison $v3 $java
Assert-HarSafeOutput $compared
Assert-PreludeTest ($compared.schemaVersion -eq 3 -and $compared['comparison.mismatchCount'] -eq 0)
Assert-PreludeNoLeak (ConvertTo-Json $compared -Depth 8)
$script:preludeCases++

# Validate stdout, stderr and the optional JSON artifact from the actual offline CLI.
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('VulcanPreludeSynthetic-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
try {
    $inputFile = Join-Path $testRoot 'synthetic.har'; $outputFile = Join-Path $testRoot 'safe.json'
    [IO.File]::WriteAllText($inputFile, (ConvertTo-Json (New-PreludeHar) -Depth 25))
    $start = [Diagnostics.ProcessStartInfo]::new('pwsh')
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($arg in @('-NoProfile', '-File', $sanitizer, '-InputPath', $inputFile, '-OutputPath', $outputFile, '-IncludePrelude')) { $start.ArgumentList.Add($arg) }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(30000)) { $process.Kill($true); throw 'Synthetic CLI timeout' }
        $out = $stdout.GetAwaiter().GetResult(); $err = $stderr.GetAwaiter().GetResult()
        Assert-PreludeNoLeak $out; Assert-PreludeNoLeak $err
        Assert-PreludeTest ($process.ExitCode -eq 0 -and [string]::IsNullOrWhiteSpace($err))
        $saved = [IO.File]::ReadAllText($outputFile)
        Assert-PreludeNoLeak $saved
        $r = ConvertFrom-SafeProfileJson $saved; Assert-HarSafeOutput $r
        Assert-PreludeTest ($r.schemaVersion -eq 3 -and $r.result -ceq 'SUCCESS')
        $script:preludeCases++
        $roundTrip = @(Invoke-HarSanitizer $outputFile '' '' $true $true)
        Assert-PreludeNoLeak $roundTrip[0]
        Assert-PreludeTest ($roundTrip[-1] -eq 0 -and (ConvertFrom-SafeProfileJson $roundTrip[0])['schemaVersion'] -eq 3)
        $script:preludeCases++
        [IO.File]::WriteAllText($outputFile, (ConvertTo-Json $v2 -Depth 8))
        $denied = @(Invoke-HarSanitizer $outputFile '' '' $true $true)
        Assert-PreludeNoLeak $denied[0]
        Assert-PreludeTest ($denied[-1] -eq 1 -and (ConvertFrom-SafeProfileJson $denied[0])['result'] -ceq 'UNSAFE_OUTPUT_GUARD')
        $script:preludeCases++
    } finally { $process.Dispose() }
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if (!$resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'VulcanPreludeSynthetic-*') { throw 'Synthetic cleanup boundary failed' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Output "Synthetic offline prelude contracts passed: $script:preludeCases cases."
