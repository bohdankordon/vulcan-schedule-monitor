Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../vulcan-native-session-java-baseline.ps1')
$script:cases = 0
function Assert-NativeTest([bool]$Condition) { if (!$Condition) { throw 'Synthetic native-session contract failed' } }
function Assert-NativeNoLeak([string]$Text) {
    foreach ($marker in @('SUPER_SECRET_TOKEN', 'SUPER_SECRET_GUID', 'SUPER_SECRET_COOKIE', 'SECRET_TENANT', 'SECRET_JOURNAL', '918273645', 'https://', 'vulcan.net.pl')) { Assert-NativeTest (!$Text.Contains($marker)) }
}
function New-NativeFixture {
    return @{ log = @{ entries = @(@{
        request = @{ method = 'POST'; url = 'https://synthetic.vulcan.net.pl/SECRET_TENANT/PlanLekcji.mvc/GetPlanLekcjiContext'; httpVersion = 'HTTP/2'
            headers = @(
                @{ name = 'Referer'; value = 'https://synthetic.vulcan.net.pl/SECRET_TENANT/Other.mvc' },
                @{ name = 'Content-Type'; value = 'application/x-www-form-urlencoded' },
                @{ name = 'X-V-RequestVerificationToken'; value = 'SUPER_SECRET_TOKEN' },
                @{ name = 'X-V-AppGuid'; value = 'SUPER_SECRET_GUID' },
                @{ name = 'Cookie'; value = 'CookieOne=SUPER_SECRET_COOKIE; CookieTwo=SUPER_SECRET_COOKIE' }
            )
            postData = @{ mimeType = 'application/x-www-form-urlencoded'; text = 'dataOd=2026-08-31T00%3A00%3A00&dataDo=2026-09-06T00%3A00%3A00&data=2026-09-05T00%3A00%3A00&idDziennik=918273645' }
        }
        response = @{ status = 200; content = @{ mimeType = 'application/json'; text = '{"success":true,"data":{"planLekcji":[{"secret":"SECRET_JOURNAL"}],"planLekcjiZeZmianami":[]}}' } }
    }) } }
}
function Set-NativeHeader($Fixture, [string]$Name, [string]$Value) {
    $Fixture.log.entries[0].request.headers = @($Fixture.log.entries[0].request.headers | Where-Object { $_.name -cne $Name }) + @(@{ name = $Name; value = $Value })
}
function Test-Rejection([scriptblock]$Mutate) {
    $fixture = New-NativeFixture
    & $Mutate $fixture
    $caught = $false
    try { $unexpected = Get-NativeSessionPayload (ConvertTo-Json -InputObject $fixture -Depth 15 -Compress) }
    catch { $caught = $true; Assert-NativeNoLeak $_.Exception.ToString(); Assert-NativeTest ($_.Exception.Message -ceq 'INVALID_NATIVE_EVIDENCE') }
    Assert-NativeTest $caught
    $script:cases++
}

$payload = Get-NativeSessionPayload (ConvertTo-Json -InputObject (New-NativeFixture) -Depth 15)
try {
    $stream = [IO.MemoryStream]::new($payload, $false)
    $reader = [IO.BinaryReader]::new($stream, [Text.UTF8Encoding]::new($false, $true))
    try {
        Assert-NativeTest ([Text.Encoding]::ASCII.GetString($reader.ReadBytes(4)) -ceq 'NSJ1')
        $values = @()
        for ($i = 0; $i -lt 9; $i++) { $values += [Text.Encoding]::UTF8.GetString($reader.ReadBytes($reader.ReadInt32())) }
        Assert-NativeTest ($values[2] -ceq 'SUPER_SECRET_TOKEN' -and $values[3] -ceq 'SUPER_SECRET_GUID' -and $values[8] -ceq '918273645' -and $stream.Position -eq $stream.Length)
    } finally { $reader.Dispose(); $stream.Dispose() }
} finally { [Array]::Clear($payload, 0, $payload.Length) }
$script:cases++
Test-Rejection { param($h) $h.log.entries[0].response.status = 429 }
Test-Rejection { param($h) $h.log.entries[0].response.content.mimeType = 'text/html' }
Test-Rejection { param($h) $h.log.entries[0].response.content.text = '{"success":false,"data":{"planLekcji":[],"planLekcjiZeZmianami":[]}}' }
Test-Rejection { param($h) $h.log.entries[0].response.content.text = '{"success":true,"data":{"planLekcji":[]}}' }
Test-Rejection { param($h) $h.log.entries += $h.log.entries[0] }
Test-Rejection { param($h) $h.log.entries[0].request.method = 'GET' }
Test-Rejection { param($h) $h.log.entries[0].request.url = $h.log.entries[0].request.url.Replace('GetPlanLekcjiContext', 'GetCache') }
Test-Rejection { param($h) $h.log.entries[0].request.url = $h.log.entries[0].request.url.Replace('synthetic.vulcan.net.pl', 'external.invalid') }
Test-Rejection { param($h) $h.log.entries[0].request.url = $h.log.entries[0].request.url.Replace('https:', 'http:') }
Test-Rejection { param($h) $h.log.entries[0].request.url = $h.log.entries[0].request.url.Replace('.pl/', '.pl:444/') }
Test-Rejection { param($h) $h.log.entries[0].request.url = $h.log.entries[0].request.url.Replace('https://', 'https://private@') }
Test-Rejection { param($h) $h.log.entries[0].request.url += '?secret=SUPER_SECRET_TOKEN' }
Test-Rejection { param($h) $h.log.entries[0].request.url = $h.log.entries[0].request.url.Replace('/SECRET_TENANT/', '/other/../SECRET_TENANT/') }
Test-Rejection { param($h) Set-NativeHeader $h 'Referer' 'https://external.invalid/SECRET_TENANT/' }
Test-Rejection { param($h) Set-NativeHeader $h 'Referer' 'https://synthetic.vulcan.net.pl/other/' }
Test-Rejection { param($h) Set-NativeHeader $h 'X-V-RequestVerificationToken' '' }
Test-Rejection { param($h) Set-NativeHeader $h 'X-V-AppGuid' '' }
Test-Rejection { param($h) $h.log.entries[0].request.headers = @($h.log.entries[0].request.headers | Where-Object { $_.name -cne 'X-V-RequestVerificationToken' }) }
Test-Rejection { param($h) $h.log.entries[0].request.headers = @($h.log.entries[0].request.headers | Where-Object { $_.name -cne 'X-V-AppGuid' }) }
Test-Rejection { param($h) Set-NativeHeader $h 'Cookie' 'SUPER_SECRET_COOKIE' }
Test-Rejection { param($h) Set-NativeHeader $h 'Cookie' 'a=SUPER_SECRET_COOKIE; a=SUPER_SECRET_COOKIE' }
Test-Rejection { param($h) Set-NativeHeader $h 'Cookie' "a=SUPER_SECRET_COOKIE`r`nInjected: value" }
Test-Rejection { param($h) $h.log.entries[0].request.postData.text += '&extra=SECRET_JOURNAL' }
Test-Rejection { param($h) $h.log.entries[0].request.postData.text = $h.log.entries[0].request.postData.text.Replace('&idDziennik=918273645', '') }
Test-Rejection { param($h) $h.log.entries[0].request.postData.text = $h.log.entries[0].request.postData.text.Replace('918273645', 'SECRET_JOURNAL') }
Test-Rejection { param($h) $h.log.entries[0].request.postData.text = $h.log.entries[0].request.postData.text.Replace('2026-08-31T', '2026-08-31 ') }
Test-Rejection { param($h) $h.log.entries[0].request.postData.text = $h.log.entries[0].request.postData.text.Replace('2026-09-06', '2026-09-13') }
Test-Rejection { param($h) $h.log.entries[0].request.postData.text = $h.log.entries[0].request.postData.text.Replace('2026-09-05', '2026-09-07') }
foreach ($raw in @('{SUPER_SECRET_TOKEN', '{"log":{"entries":[],"entries":[]}}')) {
    $caught = $false
    try { $unexpected = Get-NativeSessionPayload $raw } catch { $caught = $true; Assert-NativeNoLeak $_.Exception.ToString() }
    Assert-NativeTest $caught; $script:cases++
}
$report = New-NativeBaselineReport
Assert-NativeBaselineReport $report
Assert-NativeNoLeak (ConvertTo-Json -InputObject $report)
$report.javaRequestAttempted = $true; $report.javaScheduleRequests = 1
Assert-NativeBaselineReport $report
$report.category = 'CHILD_FAILURE'; $report.javaRequestAttempted = 'UNAVAILABLE'; $report.javaScheduleRequests = 'UNAVAILABLE'
Assert-NativeBaselineReport $report
$script:cases++
foreach ($key in @('unknown', 'javaOutcome', 'java.statusFamily', 'session.cookieCount')) {
    $bad = New-NativeBaselineReport; $bad[$key] = 'SUPER_SECRET_TOKEN'
    $caught = $false
    try { Assert-NativeBaselineReport $bad } catch { $caught = $true; Assert-NativeNoLeak $_.Exception.ToString() }
    Assert-NativeTest $caught; $script:cases++
}
# No -Run must not even resolve an input path, build, or launch a child.
function Get-HarLocalPath { throw 'Unexpected input access' }
$items = @(Invoke-NativeSessionBaseline $false 'DO_NOT_OPEN')
Assert-NativeNoLeak $items[0]
$report = ConvertFrom-SafeProfileJson $items[0]
Assert-NativeTest ($report.category -ceq 'NOT_AUTHORIZED' -and $items[-1] -eq 1 -and $report.javaScheduleRequests -eq 0)
$script:cases++
Write-Output "Synthetic native-session PowerShell contracts passed: $script:cases cases."
