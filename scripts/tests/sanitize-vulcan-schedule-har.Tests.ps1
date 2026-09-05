# Synthetic fixtures only. No provider access and no external test modules.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$scriptFile = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../sanitize-vulcan-schedule-har.ps1'))
. $scriptFile
$script:caseCount = 0
$script:caseName = 'setup'
function Assert-Synthetic([bool]$Condition) {
    if (!$Condition) { throw "Synthetic HAR contract failed: $script:caseName" }
}
function Assert-NoLeak([string]$Text) {
    foreach ($marker in @('SUPER_SECRET_COOKIE', 'SUPER_SECRET_TOKEN', 'SUPER_SECRET_APPGUID', 'SCHOOL_IDENTIFIER_123',
        'CLASS_NAME_SECRET', 'TEACHER_SECRET', 'SUBJECT_SECRET', 'ROOM_SECRET', 'JOURNAL_SECRET',
        'cookieNameSecret', 'userAgentSecret', 'https://', 'http://', 'native.invalid', 'PlanLekcji.mvc', 'GetPlanLekcjiContext')) {
        Assert-Synthetic (!$Text.Contains($marker))
    }
}
function New-SyntheticHar {
    $headers = @(
        @{ name = 'X-V-RequestVerificationToken'; value = 'SUPER_SECRET_TOKEN' },
        @{ name = 'X-V-AppGuid'; value = 'SUPER_SECRET_APPGUID' },
        @{ name = 'X-Requested-With'; value = 'XMLHttpRequest' },
        @{ name = 'Origin'; value = 'https://native.invalid' },
        @{ name = 'Referer'; value = 'https://native.invalid/SCHOOL_IDENTIFIER_123/PlanLekcji.mvc?token=SUPER_SECRET_TOKEN' },
        @{ name = 'Content-Type'; value = 'application/x-www-form-urlencoded; charset=UTF-8' },
        @{ name = 'Accept'; value = 'application/json, text/javascript, */*; q=0.01' },
        @{ name = 'Accept-Language'; value = 'pl-PL' },
        @{ name = 'User-Agent'; value = 'userAgentSecret' },
        @{ name = 'Sec-Fetch-Site'; value = 'same-origin' },
        @{ name = 'Sec-Fetch-Mode'; value = 'cors' },
        @{ name = 'Sec-Fetch-Dest'; value = 'empty' },
        @{ name = 'Cookie'; value = 'cookieNameSecret=SUPER_SECRET_COOKIE; cookieNameSecret2=SUPER_SECRET_COOKIE' }
    )
    $lesson = @{ name = 'CLASS_NAME_SECRET'; teacher = 'TEACHER_SECRET'; subject = 'SUBJECT_SECRET'; room = 'ROOM_SECRET'; id = 'JOURNAL_SECRET' }
    return @{ log = @{ version = '1.2'; entries = @(@{
        request = @{ method = 'POST'; url = 'https://native.invalid/SCHOOL_IDENTIFIER_123/PlanLekcji.mvc/GetPlanLekcjiContext?account=SUPER_SECRET_TOKEN'; httpVersion = 'h2'; headers = $headers
            cookies = @(@{ name = 'cookieNameSecret'; value = 'SUPER_SECRET_COOKIE' }, @{ name = 'cookieNameSecret2'; value = 'SUPER_SECRET_COOKIE' })
            postData = @{ mimeType = 'application/x-www-form-urlencoded'; text = 'dataOd=2026-08-31T00%3A00%3A00&dataDo=2026-09-06T00%3A00%3A00&idDziennik=JOURNAL_SECRET&data=2026-09-05T00%3A00%3A00' }
        }
        response = @{ status = 200; headers = @(@{ name = 'Set-Cookie'; value = 'cookieNameSecret=SUPER_SECRET_COOKIE' })
            content = @{ mimeType = 'application/json; charset=utf-8'; text = (ConvertTo-Json -Depth 10 -Compress -InputObject @{ success = $true; data = @{ planLekcji = @($lesson, $lesson); planLekcjiZeZmianami = @($lesson) } }) }
        }
    }) } }
}
function Set-SyntheticHeader($Har, [string]$Name, [string]$Value) {
    $Har.log.entries[0].request.headers = @($Har.log.entries[0].request.headers | Where-Object { $_.name -ne $Name }) + @(@{ name = $Name; value = $Value })
}
function Run-Case([string]$Name, [scriptblock]$Mutate, [scriptblock]$Check) {
    $script:caseName = $Name
    $har = New-SyntheticHar
    & $Mutate $har
    $report = ConvertTo-HarSafeReport (ConvertTo-Json -InputObject $har -Depth 20 -Compress)
    Assert-HarSafeOutput $report
    Assert-NoLeak (ConvertTo-Json -InputObject $report -Depth 4)
    & $Check $report
    $script:caseCount++
}

Run-Case 'valid JSON and safe array counts' {} {
    param($r)
    Assert-Synthetic ($r.result -eq 'SUCCESS' -and $r['response.statusFamily'] -eq '2xx' -and !$r['response.status429'])
    Assert-Synthetic ($r['response.jsonParseable'] -and $r['response.expectedEnvelopePresent'])
    Assert-Synthetic ($r['response.planLekcjiArrayPresent'] -and $r['response.planLekcjiCount'] -eq 2 -and $r['response.planLekcjiZeZmianamiCount'] -eq 1)
    Assert-Synthetic ($r['form.exactExpectedFieldSet'] -and $r['form.fieldCount'] -eq 4 -and $r['form.weekIsMondayToSunday'] -and $r['form.dataWithinWeek'])
    Assert-Synthetic ($r['headers.verificationToken.present'] -and $r['headers.verificationToken.nonBlank'] -and $r['headers.appGuid.nonBlank'])
    Assert-Synthetic ($r['httpVersion'] -eq 'HTTP_2' -and $r['refererContext'] -eq 'PLAN_PAGE' -and $r['originMatchesRequestOrigin'])
    Assert-Synthetic ($r['cookieCount'] -eq 2 -and $r['headers.accept.category'] -eq 'JSON_CAPABLE')
}
Run-Case '429 does not parse body' { param($h) $h.log.entries[0].response.status = 429 } { param($r) Assert-Synthetic ($r['response.status429'] -and $r['response.statusFamily'] -eq '4xx' -and !$r['response.jsonParseable']) }
Run-Case 'HTML body ignored even with invalid encoded content' { param($h) $h.log.entries[0].response.content = @{ mimeType = 'text/html'; text = '<html>SUPER_SECRET_TOKEN</html>'; encoding = 'invalid' } } { param($r) Assert-Synthetic ($r['response.contentFamily'] -eq 'html' -and !$r['response.jsonParseable']) }
Run-Case 'no target' { param($h) $h.log.entries = @() } { param($r) Assert-Synthetic ($r.result -eq 'NOT_FOUND') }
Run-Case 'multiple targets' { param($h) $h.log.entries = @($h.log.entries[0], $h.log.entries[0]) } { param($r) Assert-Synthetic ($r.result -eq 'AMBIGUOUS' -and $r.matchingRequestCount -eq 2) }
Run-Case 'wrong method' { param($h) $h.log.entries[0].request.method = 'GET' } { param($r) Assert-Synthetic ($r.result -eq 'UNSUPPORTED_TARGET_REQUEST') }
Run-Case 'missing tokens' { param($h) $h.log.entries[0].request.headers = @($h.log.entries[0].request.headers | Where-Object { $_.name -notin @('X-V-AppGuid', 'X-V-RequestVerificationToken') }) } { param($r) Assert-Synthetic (!$r['headers.verificationToken.present'] -and !$r['headers.appGuid.nonBlank']) }
Run-Case 'blank token' { param($h) Set-SyntheticHeader $h 'X-V-RequestVerificationToken' ' ' } { param($r) Assert-Synthetic ($r['headers.verificationToken.present'] -and !$r['headers.verificationToken.nonBlank']) }
Run-Case 'missing cookies' { param($h) $h.log.entries[0].request.Remove('cookies'); $h.log.entries[0].request.headers = @($h.log.entries[0].request.headers | Where-Object { $_.name -ne 'Cookie' }) } { param($r) Assert-Synthetic (!$r.cookieHeaderPresent -and $r.cookieCount -eq 0) }
Run-Case 'cookie header count fallback' { param($h) $h.log.entries[0].request.Remove('cookies') } { param($r) Assert-Synthetic ($r.cookieCount -eq 2) }
Run-Case 'extra form field' { param($h) $h.log.entries[0].request.postData.text += '&SUPER_SECRET_TOKEN=SUPER_SECRET_COOKIE' } { param($r) Assert-Synthetic (!$r['form.exactExpectedFieldSet'] -and $r['form.fieldCount'] -eq 5) }
Run-Case 'duplicate form field' { param($h) $h.log.entries[0].request.postData.text += '&data=SUPER_SECRET_TOKEN' } { param($r) Assert-Synthetic (!$r['form.exactExpectedFieldSet'] -and $r['form.dataShape'] -eq 'OTHER') }
Run-Case 'wrong timestamp' { param($h) $h.log.entries[0].request.postData.text = $h.log.entries[0].request.postData.text.Replace('2026-08-31T', '2026-08-31 ') } { param($r) Assert-Synthetic ($r['form.dataOdShape'] -eq 'OTHER' -and !$r['form.weekIsMondayToSunday']) }
Run-Case 'invalid date' { param($h) $h.log.entries[0].request.postData.text = $h.log.entries[0].request.postData.text.Replace('2026-08-31', '2026-02-31') } { param($r) Assert-Synthetic ($r['form.dataOdShape'] -eq 'OTHER') }
Run-Case 'wrong week boundaries' { param($h) $h.log.entries[0].request.postData.text = $h.log.entries[0].request.postData.text.Replace('2026-09-06', '2026-09-13') } { param($r) Assert-Synthetic (!$r['form.weekIsMondayToSunday']) }
Run-Case 'anchor outside week' { param($h) $h.log.entries[0].request.postData.text = $h.log.entries[0].request.postData.text.Replace('2026-09-05', '2026-09-07') } { param($r) Assert-Synthetic (!$r['form.dataWithinWeek']) }
Run-Case 'HAR params form representation' { param($h) $h.log.entries[0].request.postData = @{ mimeType = 'application/x-www-form-urlencoded'; params = @(@{ name = 'dataOd'; value = '2026-08-31T00:00:00' }, @{ name = 'dataDo'; value = '2026-09-06T00:00:00' }, @{ name = 'data'; value = '2026-09-05T00:00:00' }, @{ name = 'idDziennik'; value = 'JOURNAL_SECRET' }) } } { param($r) Assert-Synthetic ($r['form.exactExpectedFieldSet'] -and $r['form.weekIsMondayToSunday']) }
Run-Case 'other allowed referer' { param($h) Set-SyntheticHeader $h 'Referer' 'https://native.invalid/SCHOOL_IDENTIFIER_123/Other.mvc' } { param($r) Assert-Synthetic ($r.refererContext -eq 'OTHER_ALLOWED') }
Run-Case 'endpoint is not plan page' { param($h) Set-SyntheticHeader $h 'Referer' 'https://native.invalid/SCHOOL_IDENTIFIER_123/PlanLekcji.mvc/GetPlanLekcjiContext' } { param($r) Assert-Synthetic ($r.refererContext -eq 'OTHER_ALLOWED') }
Run-Case 'journal referer' { param($h) Set-SyntheticHeader $h 'Referer' 'https://native.invalid/SCHOOL_IDENTIFIER_123/Dziennik.mvc' } { param($r) Assert-Synthetic ($r.refererContext -eq 'JOURNAL_PAGE') }
Run-Case 'landing referer' { param($h) Set-SyntheticHeader $h 'Referer' 'https://native.invalid/SCHOOL_IDENTIFIER_123/Home.mvc/Index' } { param($r) Assert-Synthetic ($r.refererContext -eq 'HOME_OR_LANDING') }
Run-Case 'external referer' { param($h) Set-SyntheticHeader $h 'Referer' 'https://elsewhere.invalid/PlanLekcji.mvc' } { param($r) Assert-Synthetic ($r.refererContext -eq 'UNKNOWN') }
Run-Case 'different application referer' { param($h) Set-SyntheticHeader $h 'Referer' 'https://native.invalid/OtherApplication/PlanLekcji.mvc' } { param($r) Assert-Synthetic ($r.refererContext -eq 'UNKNOWN') }
Run-Case 'origin mismatch' { param($h) Set-SyntheticHeader $h 'Origin' 'https://elsewhere.invalid' } { param($r) Assert-Synthetic (!$r.originMatchesRequestOrigin) }
Run-Case 'HTTP 1.1' { param($h) $h.log.entries[0].request.httpVersion = 'HTTP/1.1' } { param($r) Assert-Synthetic ($r.httpVersion -eq 'HTTP_1_1') }
Run-Case 'arbitrary protocol headers' { param($h) foreach ($key in @('Sec-Fetch-Mode', 'Sec-Fetch-Site', 'Accept', 'X-Requested-With', 'Content-Type')) { Set-SyntheticHeader $h $key 'SUPER_SECRET_TOKEN' }; $h.log.entries[0].request.httpVersion = 'SUPER_SECRET_TOKEN' } { param($r) Assert-Synthetic ($r['headers.secFetchMode.category'] -eq 'other' -and $r['headers.accept.category'] -eq 'OTHER' -and $r.httpVersion -eq 'OTHER') }
Run-Case 'Accept HTML preference' { param($h) Set-SyntheticHeader $h 'Accept' 'text/html,application/json;q=0.8' } { param($r) Assert-Synthetic ($r['headers.accept.category'] -eq 'HTML_PREFERRED') }
Run-Case 'Accept generic and disabled JSON' { param($h) Set-SyntheticHeader $h 'Accept' 'application/json;q=0,*/*' } { param($r) Assert-Synthetic ($r['headers.accept.category'] -eq 'GENERIC') }
Run-Case 'no header values' { param($h) $h.log.entries[0].request.headers = @() } { param($r) Assert-Synthetic ($r['headers.accept.category'] -eq 'ABSENT' -and $r['request.contentTypeFamily'] -eq 'absent') }
Run-Case 'duplicate headers fail closed' { param($h) $h.log.entries[0].request.headers += @(@{ name = 'x-v-appguid'; value = 'SUPER_SECRET_APPGUID' }) } { param($r) Assert-Synthetic ($r.result -eq 'INVALID_HAR') }
Run-Case 'URL suffix not substring or query match' { param($h) $h.log.entries[0].request.url = 'https://native.invalid/Other.mvc?endpoint=PlanLekcji.mvc/GetPlanLekcjiContext' } { param($r) Assert-Synthetic ($r.result -eq 'NOT_FOUND') }
Run-Case 'controller must be complete path segment' { param($h) $h.log.entries[0].request.url = 'https://native.invalid/NotPlanLekcji.mvc/GetPlanLekcjiContext' } { param($r) Assert-Synthetic ($r.result -eq 'NOT_FOUND') }
Run-Case 'malformed HAR structure' { param($h) $h.log.entries = @{ private = 'SUPER_SECRET_TOKEN' } } { param($r) Assert-Synthetic ($r.result -eq 'INVALID_HAR') }
Run-Case 'malformed response JSON' { param($h) $h.log.entries[0].response.content.text = '{SUPER_SECRET_TOKEN' } { param($r) Assert-Synthetic (!$r['response.jsonParseable']) }
Run-Case 'base64 JSON' { param($h) $c = $h.log.entries[0].response.content; $c.encoding = 'base64'; $c.text = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($c.text)) } { param($r) Assert-Synthetic ($r['response.jsonParseable'] -and $r['response.planLekcjiCount'] -eq 2) }
Run-Case 'wrong envelope' { param($h) $h.log.entries[0].response.content.text = '{"success":false,"data":{"planLekcji":[]}}' } { param($r) Assert-Synthetic ($r['response.jsonParseable'] -and !$r['response.expectedEnvelopePresent']) }
Run-Case 'array root' { param($h) $h.log.entries[0].response.content.text = '["SUPER_SECRET_TOKEN"]' } { param($r) Assert-Synthetic ($r['response.rootKind'] -eq 'ARRAY' -and !$r['response.expectedEnvelopePresent']) }

# Each table row adds one deterministic fingerprint classification case.
foreach ($row in @(
    @('User-Agent', 'Mozilla/5.0 Chrome/123.0.0.0 Safari/537.36', 'headers.userAgent.family', 'CHROMIUM_BROWSER'),
    @('User-Agent', 'Java-http-client/21.0.1', 'headers.userAgent.family', 'JAVA_HTTP_CLIENT'),
    @('User-Agent', 'Mozilla/5.0 Firefox/125.0', 'headers.userAgent.family', 'FIREFOX_BROWSER'),
    @('User-Agent', 'Mozilla/5.0 Version/17.0 Safari/605.1', 'headers.userAgent.family', 'SAFARI_BROWSER'),
    @('User-Agent', 'SUPER_SECRET_TOKEN', 'headers.userAgent.family', 'OTHER'),
    @('Sec-CH-UA-Mobile', '?0', 'headers.secChUaMobile.category', 'NOT_MOBILE'),
    @('Sec-CH-UA-Mobile', '?1', 'headers.secChUaMobile.category', 'MOBILE'),
    @('Sec-CH-UA-Mobile', 'SUPER_SECRET_TOKEN', 'headers.secChUaMobile.category', 'OTHER'),
    @('Sec-CH-UA-Platform', '"Windows"', 'headers.secChUaPlatform.category', 'WINDOWS'),
    @('Sec-CH-UA-Platform', '"macOS"', 'headers.secChUaPlatform.category', 'MACOS'),
    @('Sec-CH-UA-Platform', '"Linux"', 'headers.secChUaPlatform.category', 'LINUX'),
    @('Sec-CH-UA-Platform', '"Android"', 'headers.secChUaPlatform.category', 'ANDROID'),
    @('Sec-CH-UA-Platform', '"iOS"', 'headers.secChUaPlatform.category', 'IOS'),
    @('Sec-CH-UA-Platform', '"Windows SUPER_SECRET_TOKEN"', 'headers.secChUaPlatform.category', 'OTHER'),
    @('Accept', '*/*', 'headers.accept.profile', 'STAR_STAR_ONLY'),
    @('Accept', 'application/json', 'headers.accept.profile', 'JSON_EXPLICIT'),
    @('Accept', 'application/json, text/javascript, */*; q=0.01', 'headers.accept.profile', 'JQUERY_JSON'),
    @('Accept', 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8', 'headers.accept.profile', 'HTML_NAVIGATION'),
    @('Accept', 'application/json,SUPER_SECRET_TOKEN', 'headers.accept.profile', 'OTHER'),
    @('Accept', '*/*;q=0.8', 'headers.accept.profile', 'OTHER'),
    @('Accept-Language', 'en-US,en;q=0.9', 'headers.acceptLanguage.family', 'EN'),
    @('Accept-Language', 'pl-PL', 'headers.acceptLanguage.family', 'PL'),
    @('Accept-Language', 'uk-UA,pl;q=0.5', 'headers.acceptLanguage.family', 'UK'),
    @('Accept-Language', 'ru-RU', 'headers.acceptLanguage.family', 'RU'),
    @('Accept-Language', 'fr-FR', 'headers.acceptLanguage.family', 'OTHER'),
    @('Accept-Language', 'pl-PL SUPER_SECRET_TOKEN', 'headers.acceptLanguage.family', 'OTHER'),
    @('Sec-Fetch-Dest', 'empty', 'headers.secFetchDest.category', 'empty'),
    @('Sec-Fetch-Dest', 'document', 'headers.secFetchDest.category', 'document'),
    @('Sec-Fetch-Dest', 'iframe', 'headers.secFetchDest.category', 'iframe'),
    @('Sec-Fetch-Dest', 'script', 'headers.secFetchDest.category', 'script'),
    @('Sec-Fetch-Dest', 'style', 'headers.secFetchDest.category', 'style'),
    @('Sec-Fetch-Dest', 'image', 'headers.secFetchDest.category', 'image'),
    @('Sec-Fetch-Dest', 'SUPER_SECRET_TOKEN', 'headers.secFetchDest.category', 'other')
)) {
    Run-Case 'finite fingerprint table' { param($h) Set-SyntheticHeader $h $row[0] $row[1] } { param($r) Assert-Synthetic ($r[$row[2]] -ceq $row[3] -and $r.schemaVersion -eq 2) }
}
Run-Case 'client hints and unknown values stay private' {
    param($h)
    foreach ($header in @('Sec-CH-UA', 'Priority', 'Cache-Control', 'Pragma', 'DNT', 'Unknown-SUPER_SECRET_TOKEN')) { Set-SyntheticHeader $h $header 'SUPER_SECRET_TOKEN' }
} { param($r) foreach ($key in @('secChUa', 'priority', 'cacheControl', 'pragma', 'dnt')) { Assert-Synthetic $r["headers.$key.present"] }; Assert-Synthetic ($r.requestHeaderCount -eq 19) }
Run-Case 'multiple languages and encoding tokens' {
    param($h)
    Set-SyntheticHeader $h 'Accept-Language' 'pl-PL,en;q=0.8'
    Set-SyntheticHeader $h 'Accept-Encoding' 'gzip, deflate, br, zstd;q=0, SUPER_SECRET_TOKEN'
} { param($r) Assert-Synthetic $r['headers.acceptLanguage.multipleLanguages']; foreach ($key in @('gzip', 'deflate', 'br', 'zstd', 'other')) { Assert-Synthetic $r["headers.acceptEncoding.$key"] } }
Run-Case 'absent fingerprint fields' { param($h) $h.log.entries[0].request.headers = @() } {
    param($r)
    foreach ($key in @('userAgent.family', 'secChUaMobile.category', 'secChUaPlatform.category', 'accept.profile', 'acceptLanguage.family')) { Assert-Synthetic ($r["headers.$key"] -ceq 'ABSENT') }
    Assert-Synthetic (!$r['headers.acceptLanguage.multipleLanguages'] -and !$r['headers.acceptEncoding.other'] -and $r.requestHeaderCount -eq 0)
}

function New-SyntheticJavaProfile($Native) {
    $profile = [ordered]@{}
    foreach ($key in (Get-FingerprintSchema).Keys) { $profile[$key] = $Native[$key] }
    $profile['schemaVersion'] = 2; $profile['profileSource'] = 'JAVA_LOOPBACK'
    $profile['actualObservedHttpVersion'] = 'HTTP_1_1'; $profile['clientPreferredVersion'] = 'HTTP_2'
    foreach ($key in @('exactExpectedFieldSet', 'weekIsMondayToSunday', 'dataWithinWeek')) { $profile["form.$key"] = $Native["form.$key"] }
    foreach ($key in @('dataOd', 'dataDo', 'data')) { $profile["form.${key}Shape"] = $Native["form.${key}Shape"] }
    $profile['form.urlEncoded'] = $true
    return $profile
}
$script:caseName = 'offline comparison booleans and mismatch count'
$native = ConvertTo-HarSafeReport (ConvertTo-Json -InputObject (New-SyntheticHar) -Depth 20)
$projection = New-SyntheticJavaProfile $native
$comparison = Add-HarJavaComparison $native $projection
Assert-Synthetic ($comparison['comparison.mismatchCount'] -eq 0 -and $comparison['comparison.httpVersionPreferenceCompatible'])
Assert-Synthetic ($comparison['java.actualObservedHttpVersion'] -eq 'HTTP_1_1')
$projection['headers.userAgent.family'] = 'JAVA_HTTP_CLIENT'
$projection['headers.accept.profile'] = 'ABSENT'
$projection['headers.acceptLanguage.present'] = $false
$projection['headers.acceptEncoding.gzip'] = $true
$comparison = Add-HarJavaComparison $native $projection
Assert-Synthetic ($comparison['comparison.mismatchCount'] -eq 4 -and !$comparison['comparison.userAgentFamilyMatches'] -and !$comparison['comparison.acceptEncodingProfileMatches'])
Assert-NoLeak (ConvertTo-Json -InputObject $comparison)
$script:caseCount++
$script:caseName = 'unknown protocol and invalid form do not claim compatibility'
$projection['clientPreferredVersion'] = 'UNKNOWN'; $projection['form.dataWithinWeek'] = $false
$comparison = Add-HarJavaComparison $native $projection
Assert-Synthetic (!$comparison['comparison.httpVersionPreferenceCompatible'] -and !$comparison['comparison.formStructureMatches'] -and $comparison['comparison.mismatchCount'] -eq 6)
$script:caseCount++
$script:caseName = 'schema v2 rejects v1 extension keys and raw projection values'
foreach ($badVersion in @(1, 3, '2', 2.0)) {
    $projection['schemaVersion'] = $badVersion
    $caught = $false
    try { Add-HarJavaComparison $native $projection } catch { $caught = $true; Assert-NoLeak $_.Exception.Message }
    Assert-Synthetic $caught
}
$projection['schemaVersion'] = 2; $projection['headers.userAgent.family'] = 'SUPER_SECRET_TOKEN'
$caught = $false
try { Add-HarJavaComparison $native $projection } catch { $caught = $true; Assert-NoLeak $_.Exception.Message }
Assert-Synthetic $caught
$script:caseCount++

$script:caseName = 'malformed input and exception reduction'
foreach ($raw in @('{SUPER_SECRET_TOKEN', '', '{"log":"https://native.invalid/SCHOOL_IDENTIFIER_123"}',
    '{"log":{"entries":[],"entries":[]}}', '/*SUPER_SECRET_TOKEN*/{"log":{"entries":[]}}', '[{"log":{"entries":[]}}]')) {
    $r = ConvertTo-HarSafeReport $raw
    Assert-Synthetic ($r.result -eq 'INVALID_HAR')
    Assert-NoLeak (ConvertTo-Json -InputObject $r)
}
$script:caseCount++
$script:caseName = 'output guard rejects unknown keys values and types'
foreach ($bad in @(@{ result = 'SUCCESS'; secret = 'SUPER_SECRET_TOKEN' }, @{ result = 'SUPER_SECRET_TOKEN' },
    @{ result = 'AMBIGUOUS'; matchingRequestCount = -1 }, @{ result = 'AMBIGUOUS'; matchingRequestCount = 1000001 },
    @{ result = 'AMBIGUOUS'; matchingRequestCount = '2' }, @{ result = 'NOT_FOUND'; cookieCount = 2 }, @{ RESULT = 'NOT_FOUND' })) {
    $bad['schemaVersion'] = 2
    $caught = $false
    try { Assert-HarSafeOutput $bad } catch { $caught = $true; Assert-NoLeak $_.Exception.Message; Assert-Synthetic ($_.Exception.Message -eq 'UNSAFE_OUTPUT_GUARD') }
    Assert-Synthetic $caught
}
$script:caseCount++
$script:caseName = 'success schema rejects values in every output type'
$valid = ConvertTo-HarSafeReport (ConvertTo-Json -InputObject (New-SyntheticHar) -Depth 20)
foreach ($mutation in @(@('headers.userAgent.present', 'SUPER_SECRET_TOKEN'), @('httpVersion', 'SUPER_SECRET_TOKEN'), @('cookieCount', 1.5), @('cookieCount', 1000001))) {
    $copy = [ordered]@{}
    foreach ($key in $valid.Keys) { $copy[$key] = $valid[$key] }
    $copy[$mutation[0]] = $mutation[1]
    $caught = $false
    try { Assert-HarSafeOutput $copy } catch { $caught = $true; Assert-NoLeak $_.Exception.Message }
    Assert-Synthetic $caught
}
$script:caseCount++

# Exercise real CLI stdout, stderr, exit code and optional JSON using synthetic files only.
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('VulcanHarSynthetic-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
function Run-SyntheticCli([string]$Source, [string]$Destination, [string]$JavaPath = '', [bool]$Reduced = $false) {
    $start = [Diagnostics.ProcessStartInfo]::new('pwsh')
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @('-NoProfile', '-File', $scriptFile, '-InputPath', $Source)) { $start.ArgumentList.Add($argument) }
    if ($Destination) { $start.ArgumentList.Add('-OutputPath'); $start.ArgumentList.Add($Destination) }
    if ($JavaPath) { $start.ArgumentList.Add('-JavaProfilePath'); $start.ArgumentList.Add($JavaPath) }
    if ($Reduced) { $start.ArgumentList.Add('-SanitizedInput') }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(15000)) { $process.Kill($true); throw 'Synthetic child timeout' }
        $out = $stdout.GetAwaiter().GetResult(); $err = $stderr.GetAwaiter().GetResult()
        Assert-NoLeak $out; Assert-NoLeak $err
        Assert-Synthetic ($err -eq '')
        return @{ report = (ConvertFrom-Json -InputObject $out -AsHashtable); code = $process.ExitCode; stdout = $out }
    } finally { $process.Dispose() }
}
try {
    $inputFile = Join-Path $testRoot 'synthetic.har'
    $outputFile = Join-Path $testRoot 'synthetic.sanitized.json'
    $syntheticRaw = ConvertTo-Json -InputObject (New-SyntheticHar) -Depth 20
    [IO.File]::WriteAllText($inputFile, $syntheticRaw)
    $script:caseName = 'CLI stdout stderr and JSON leak check'
    $r = Run-SyntheticCli $inputFile $outputFile
    Assert-Synthetic ($r.code -eq 0 -and $r.report.result -eq 'SUCCESS')
    $safeFile = [IO.File]::ReadAllText($outputFile)
    Assert-NoLeak $safeFile
    Assert-Synthetic ($safeFile.Trim() -eq $r.stdout.Trim())
    Assert-Synthetic ([IO.File]::ReadAllText($inputFile) -ceq $syntheticRaw)
    $script:caseCount++
    $script:caseName = 'CLI accepts only validated v2 profiles for offline comparison'
    $profileFile = Join-Path $testRoot 'java.sanitized.json'
    $projection = New-SyntheticJavaProfile $r.report
    [IO.File]::WriteAllText($profileFile, (ConvertTo-Json -InputObject $projection))
    $compared = Run-SyntheticCli $outputFile '' $profileFile $true
    Assert-Synthetic ($compared.code -eq 0 -and $compared.report['comparison.mismatchCount'] -eq 0)
    $projection['unknown'] = 'SUPER_SECRET_TOKEN'
    [IO.File]::WriteAllText($profileFile, (ConvertTo-Json -InputObject $projection))
    $compared = Run-SyntheticCli $outputFile '' $profileFile $true
    Assert-Synthetic ($compared.code -eq 1 -and $compared.report.result -eq 'UNSAFE_OUTPUT_GUARD')
    $script:caseCount++
    $script:caseName = 'CLI malformed input does not leak parsing exception'
    [IO.File]::WriteAllText($inputFile, '{SUPER_SECRET_TOKEN https://native.invalid')
    $r = Run-SyntheticCli $inputFile ''
    Assert-Synthetic ($r.code -eq 1 -and $r.report.result -eq 'INVALID_HAR')
    $script:caseCount++
    $script:caseName = 'CLI nonexistent input does not leak path'
    $r = Run-SyntheticCli (Join-Path $testRoot 'SCHOOL_IDENTIFIER_123.har') ''
    Assert-Synthetic ($r.code -eq 1 -and $r.report.result -eq 'INVALID_INPUT')
    $script:caseCount++
    $script:caseName = 'CLI refuses overwrite'
    $r = Run-SyntheticCli $inputFile $outputFile
    Assert-Synthetic ($r.code -eq 1 -and $r.report.result -eq 'INVALID_INPUT' -and [IO.File]::ReadAllText($outputFile) -ceq $safeFile)
    $script:caseCount++
    $script:caseName = 'CLI refuses network path before file access'
    foreach ($source in @('https://native.invalid/SUPER_SECRET_TOKEN.har', '\\native.invalid\SUPER_SECRET_TOKEN.har')) {
        $r = Run-SyntheticCli $source ''
        Assert-Synthetic ($r.code -eq 1 -and $r.report.result -eq 'INVALID_INPUT')
    }
    $script:caseCount++
    $script:caseName = 'CLI refuses oversized input'
    $large = [IO.File]::Open($inputFile, [IO.FileMode]::Create, [IO.FileAccess]::Write)
    try { $large.SetLength(33554433) } finally { $large.Dispose() }
    $r = Run-SyntheticCli $inputFile ''
    Assert-Synthetic ($r.code -eq 1 -and $r.report.result -eq 'INVALID_INPUT')
    $script:caseCount++
    if ($IsWindows) {
        $script:caseName = 'CLI rejects parent junction before traversing it'
        $localDirectory = Join-Path $testRoot 'local'
        $junction = Join-Path $testRoot 'junction'
        [void][IO.Directory]::CreateDirectory($localDirectory)
        [IO.File]::WriteAllText((Join-Path $localDirectory 'synthetic.har'), $syntheticRaw)
        [void](New-Item -ItemType Junction -Path $junction -Target $localDirectory)
        try {
            $r = Run-SyntheticCli (Join-Path $junction 'synthetic.har') ''
            Assert-Synthetic ($r.code -eq 1 -and $r.report.result -eq 'INVALID_INPUT')
        } finally {
            # Remove the link itself, never recurse through it.
            if (![IO.Path]::GetFullPath($junction).StartsWith($testRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Synthetic cleanup boundary failed' }
            Remove-Item -LiteralPath $junction -Force
        }
        $script:caseCount++
    }
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if (!$resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'VulcanHarSynthetic-*') { throw 'Synthetic cleanup boundary failed' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Output "Synthetic offline HAR contracts passed: $script:caseCount cases."
