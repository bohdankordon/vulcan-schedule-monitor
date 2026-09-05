# Offline only. Never write parsed HAR objects or exception messages to any stream.
[CmdletBinding()]
param([string]$InputPath, [string]$OutputPath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-HarOutputSchema {
    $schema = @{
        result = @('SUCCESS', 'INVALID_INPUT', 'INVALID_HAR', 'NOT_FOUND', 'AMBIGUOUS', 'UNSUPPORTED_TARGET_REQUEST', 'UNSAFE_OUTPUT_GUARD')
        matchingRequestCount = 'count'
        'request.method' = @('POST')
        'response.statusFamily' = @('1xx', '2xx', '3xx', '4xx', '5xx', 'UNKNOWN')
        'response.contentFamily' = @('json', 'html', 'other')
        httpVersion = @('HTTP_2', 'HTTP_1_1', 'OTHER', 'UNKNOWN')
        'request.contentTypeFamily' = @('form_urlencoded', 'other', 'absent')
        refererContext = @('PLAN_PAGE', 'JOURNAL_PAGE', 'HOME_OR_LANDING', 'OTHER_ALLOWED', 'UNKNOWN')
        'headers.xRequestedWith.category' = @('XMLHttpRequest', 'OTHER', 'ABSENT')
        'headers.contentType.category' = @('application/x-www-form-urlencoded', 'OTHER', 'ABSENT')
        'headers.accept.category' = @('JSON_CAPABLE', 'GENERIC', 'HTML_PREFERRED', 'OTHER', 'ABSENT')
        'headers.secFetchMode.category' = @('cors', 'same-origin', 'navigate', 'other', 'absent')
        'headers.secFetchSite.category' = @('same-origin', 'same-site', 'cross-site', 'none', 'other', 'absent')
        'response.rootKind' = @('OBJECT', 'ARRAY', 'SCALAR', 'UNAVAILABLE')
    }
    foreach ($key in @('response.status429', 'cookieHeaderPresent', 'originMatchesRequestOrigin',
        'form.exactExpectedFieldSet', 'form.weekIsMondayToSunday', 'form.dataWithinWeek',
        'response.jsonParseable', 'response.expectedEnvelopePresent', 'response.planLekcjiArrayPresent',
        'response.planLekcjiZeZmianamiArrayPresent', 'headers.verificationToken.nonBlank', 'headers.appGuid.nonBlank')) {
        $schema[$key] = 'bool'
    }
    foreach ($header in @('verificationToken', 'appGuid', 'xRequestedWith', 'origin', 'referer', 'accept',
        'acceptLanguage', 'userAgent', 'secFetchSite', 'secFetchMode', 'secFetchDest', 'secFetchUser', 'cookie', 'contentType')) {
        $schema["headers.$header.present"] = 'bool'
    }
    foreach ($key in @('cookieCount', 'form.fieldCount', 'response.planLekcjiCount', 'response.planLekcjiZeZmianamiCount')) { $schema[$key] = 'count' }
    foreach ($key in @('dataOd', 'dataDo', 'data')) { $schema["form.${key}Shape"] = @('ISO_T_DATETIME', 'OTHER') }
    return $schema
}

function Assert-HarSafeOutput($Report) {
    $schema = Get-HarOutputSchema
    if ($Report -isnot [System.Collections.IDictionary] -or !$Report.Contains('result')) { throw 'UNSAFE_OUTPUT_GUARD' }
    foreach ($key in $Report.Keys) {
        if (@($schema.Keys) -cnotcontains $key) { throw 'UNSAFE_OUTPUT_GUARD' }
        $value = $Report[$key]
        $rule = $schema[$key]
        if ($rule -is [array]) {
            if ($value -isnot [string] -or $rule -cnotcontains $value) { throw 'UNSAFE_OUTPUT_GUARD' }
        } elseif ($rule -eq 'bool') {
            if ($value -isnot [bool]) { throw 'UNSAFE_OUTPUT_GUARD' }
        } elseif (($value -isnot [int] -and $value -isnot [long]) -or $value -lt 0 -or $value -gt 1000000) {
            throw 'UNSAFE_OUTPUT_GUARD'
        }
    }
    if ($Report['result'] -eq 'SUCCESS') {
        if ($Report.Count -ne $schema.Count) { throw 'UNSAFE_OUTPUT_GUARD' }
    } elseif ($Report['result'] -eq 'AMBIGUOUS') {
        if ($Report.Count -ne 2 -or !$Report.Contains('matchingRequestCount') -or $Report['matchingRequestCount'] -lt 2) { throw 'UNSAFE_OUTPUT_GUARD' }
    } elseif ($Report.Count -ne 1) { throw 'UNSAFE_OUTPUT_GUARD' }
}

function Get-HarObject($Value) {
    if ($Value -isnot [System.Collections.IDictionary]) { throw 'INVALID_HAR' }
    return ,$Value
}

function Get-HarArray($Value) {
    if ($Value -isnot [array] -or $Value.Count -gt 1000000) { throw 'INVALID_HAR' }
    return ,$Value
}

function Get-HarString($Value) {
    if ($Value -isnot [string]) { throw 'INVALID_HAR' }
    return $Value
}

function Assert-HarUniqueJsonKeys([System.Text.Json.JsonElement]$Element) {
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $Element.EnumerateObject()) {
            if (!$seen.Add($property.Name)) { throw 'INVALID_HAR' }
            Assert-HarUniqueJsonKeys $property.Value
        }
    } elseif ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        foreach ($item in $Element.EnumerateArray()) { Assert-HarUniqueJsonKeys $item }
    }
}

function Get-HarUri([string]$Value) {
    $uri = $null
    if (![uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin @('http', 'https') -or $uri.UserInfo) { return $null }
    return $uri
}

function Test-HarSameOrigin($Left, $Right) {
    return ($null -ne $Left -and $null -ne $Right -and $Left.Scheme -eq $Right.Scheme -and $Left.IdnHost -eq $Right.IdnHost -and $Left.Port -eq $Right.Port)
}

function Get-HarRefererContext($Referer, $Target) {
    if (!(Test-HarSameOrigin $Referer $Target)) { return 'UNKNOWN' }
    $applicationPath = $Target.AbsolutePath.Substring(0, $Target.AbsolutePath.Length - 'PlanLekcji.mvc/GetPlanLekcjiContext'.Length)
    if (!$Referer.AbsolutePath.StartsWith($applicationPath, [StringComparison]::Ordinal)) { return 'UNKNOWN' }
    $path = $Referer.AbsolutePath.Substring($applicationPath.Length)
    if ($path -match '^PlanLekcji\.mvc(?:/|$)' -and $path -notmatch '/GetPlanLekcjiContext/?$') { return 'PLAN_PAGE' }
    if ($path -match '^(?:Dziennik|Dzienniki|Journal)\.mvc(?:/|$)') { return 'JOURNAL_PAGE' }
    if (!$path -or $path -match '^(?:Home|Start|Default|Index)\.mvc(?:/Index)?/?$') { return 'HOME_OR_LANDING' }
    return 'OTHER_ALLOWED'
}

function Get-HarHeaders($Value) {
    $headers = @{}
    foreach ($entry in (Get-HarArray $Value)) {
        $entry = Get-HarObject $entry
        $name = Get-HarString $entry['name']
        $value = Get-HarString $entry['value']
        # Duplicate headers are ambiguous: fail closed rather than pick a value.
        if ($headers.ContainsKey($name)) { throw 'INVALID_HAR' }
        $headers[$name] = $value
    }
    return $headers
}

function Get-HarMediaType([string]$Value) {
    return ($Value.Split(';')[0].Trim().ToLowerInvariant())
}

function Get-HarAcceptCategory([string]$Value, [bool]$Present) {
    if (!$Present) { return 'ABSENT' }
    $json = -1.0; $html = -1.0; $generic = -1.0
    foreach ($part in $Value.Split(',')) {
        $pieces = $part.Split(';')
        $media = $pieces[0].Trim().ToLowerInvariant()
        $q = 1.0
        foreach ($parameter in ($pieces | Select-Object -Skip 1)) {
            if ($parameter.Trim() -match '^q=(0(?:\.\d+)?|1(?:\.0+)?)$') { $q = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture) }
            elseif ($parameter.Trim() -match '^q=') { $q = 0.0 }
        }
        if ($q -le 0) { continue }
        if ($media -eq 'application/json' -or $media -match '^application/[^\s/]+\+json$') { $json = [Math]::Max($json, $q) }
        if ($media -eq 'text/html' -or $media -eq 'application/xhtml+xml') { $html = [Math]::Max($html, $q) }
        if ($media -eq '*/*') { $generic = [Math]::Max($generic, $q) }
    }
    if ($html -ge 0 -and $html -ge $json) { return 'HTML_PREFERRED' }
    if ($json -ge 0) { return 'JSON_CAPABLE' }
    if ($generic -ge 0) { return 'GENERIC' }
    return 'OTHER'
}

function Get-HarTimestamp([string]$Value) {
    if ($Value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})?$') { return $null }
    $parsed = [DateTimeOffset]::MinValue
    if (![DateTimeOffset]::TryParse($Value, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$parsed)) { return $null }
    return $parsed.DateTime
}

function Get-HarForm($PostData, $ContentType) {
    $fields = [System.Collections.Generic.List[object]]::new()
    if ($null -eq $PostData) { return ,$fields }
    $PostData = Get-HarObject $PostData
    $mime = Get-HarMediaType ([string]$PostData['mimeType'])
    if ($ContentType -ne 'application/x-www-form-urlencoded' -and $mime -ne 'application/x-www-form-urlencoded') { return ,$fields }
    if ($PostData.Contains('text')) {
        $body = Get-HarString $PostData['text']
        if ($body.Length -gt 1048576) { throw 'INVALID_HAR' }
        if ($body.Length -gt 0) {
            foreach ($part in $body.Split('&')) {
                if ($part -match '%(?![0-9a-fA-F]{2})') { throw 'INVALID_HAR' }
                $pair = $part.Split('=', 2)
                $name = [uri]::UnescapeDataString($pair[0].Replace('+', ' '))
                $value = if ($pair.Count -eq 2) { [uri]::UnescapeDataString($pair[1].Replace('+', ' ')) } else { '' }
                $fields.Add(@{ name = $name; value = $value })
            }
        }
    } elseif ($PostData.Contains('params')) {
        foreach ($param in (Get-HarArray $PostData['params'])) {
            $param = Get-HarObject $param
            $fields.Add(@{ name = (Get-HarString $param['name']); value = (Get-HarString $param['value']) })
        }
    }
    return ,$fields
}

function ConvertTo-HarSafeReport([string]$Json) {
    try {
        # Validate strict JSON first (PowerShell's converter also accepts comments).
        $document = [System.Text.Json.JsonDocument]::Parse($Json)
        try { Assert-HarUniqueJsonKeys $document.RootElement } finally { $document.Dispose() }
        # Preserve date-like strings verbatim internally, including HAR params values.
        $har = Get-HarObject (ConvertFrom-Json -InputObject $Json -AsHashtable -NoEnumerate -DateKind String -Depth 64 -ErrorAction Stop)
        $log = Get-HarObject $har['log']
        $targets = [System.Collections.Generic.List[object]]::new()
        foreach ($entry in (Get-HarArray $log['entries'])) {
            $entry = Get-HarObject $entry
            $request = Get-HarObject $entry['request']
            $uri = Get-HarUri (Get-HarString $request['url'])
            if ($null -ne $uri -and $uri.AbsolutePath -cmatch '/PlanLekcji\.mvc/GetPlanLekcjiContext$') { $targets.Add($entry) }
        }
        if ($targets.Count -eq 0) { return [ordered]@{ result = 'NOT_FOUND' } }
        if ($targets.Count -gt 1) { return [ordered]@{ result = 'AMBIGUOUS'; matchingRequestCount = $targets.Count } }
        $request = $targets[0]['request']
        if ((Get-HarString $request['method']) -cne 'POST') { return [ordered]@{ result = 'UNSUPPORTED_TARGET_REQUEST' } }
        $target = Get-HarUri $request['url']
        $response = Get-HarObject $targets[0]['response']
        $headers = Get-HarHeaders $request['headers']
        $content = Get-HarObject $response['content']
        $status = $response['status']
        if (($status -isnot [long] -and $status -isnot [int]) -or $status -lt 0 -or $status -gt 599) { throw 'INVALID_HAR' }
        $statusFamily = if ($status -ge 100) { ([int][Math]::Floor($status / 100)).ToString() + 'xx' } else { 'UNKNOWN' }
        $responseType = Get-HarMediaType (Get-HarString $content['mimeType'])
        $family = if ($responseType -eq 'application/json' -or $responseType -match '^application/[^\s/]+\+json$') { 'json' } elseif ($responseType -in @('text/html', 'application/xhtml+xml')) { 'html' } else { 'other' }
        $version = [string]$request['httpVersion']
        $httpVersion = if ([string]::IsNullOrWhiteSpace($version)) { 'UNKNOWN' } elseif ($version -in @('HTTP/2', 'HTTP/2.0', 'h2')) { 'HTTP_2' } elseif ($version -eq 'HTTP/1.1') { 'HTTP_1_1' } else { 'OTHER' }
        $contentType = Get-HarMediaType ([string]$headers['Content-Type'])
        $report = [ordered]@{
            result = 'SUCCESS'; matchingRequestCount = 1; 'request.method' = 'POST'
            'response.statusFamily' = $statusFamily; 'response.status429' = ($status -eq 429)
            'response.contentFamily' = $family; httpVersion = $httpVersion
            'request.contentTypeFamily' = $(if (!$headers.ContainsKey('Content-Type')) { 'absent' } elseif ($contentType -eq 'application/x-www-form-urlencoded') { 'form_urlencoded' } else { 'other' })
        }
        $headerNames = [ordered]@{ verificationToken = 'X-V-RequestVerificationToken'; appGuid = 'X-V-AppGuid'; xRequestedWith = 'X-Requested-With'; origin = 'Origin'; referer = 'Referer'; accept = 'Accept'; acceptLanguage = 'Accept-Language'; userAgent = 'User-Agent'; secFetchSite = 'Sec-Fetch-Site'; secFetchMode = 'Sec-Fetch-Mode'; secFetchDest = 'Sec-Fetch-Dest'; secFetchUser = 'Sec-Fetch-User'; cookie = 'Cookie'; contentType = 'Content-Type' }
        foreach ($key in $headerNames.Keys) { $report["headers.$key.present"] = $headers.ContainsKey($headerNames[$key]) }
        foreach ($key in @('verificationToken', 'appGuid')) { $report["headers.$key.nonBlank"] = ![string]::IsNullOrWhiteSpace($headers[$headerNames[$key]]) }
        $report['headers.xRequestedWith.category'] = if (!$headers.ContainsKey('X-Requested-With')) { 'ABSENT' } elseif ($headers['X-Requested-With'] -ceq 'XMLHttpRequest') { 'XMLHttpRequest' } else { 'OTHER' }
        $report['headers.contentType.category'] = if (!$headers.ContainsKey('Content-Type')) { 'ABSENT' } elseif ($contentType -eq 'application/x-www-form-urlencoded') { 'application/x-www-form-urlencoded' } else { 'OTHER' }
        $report['headers.accept.category'] = Get-HarAcceptCategory $headers['Accept'] $headers.ContainsKey('Accept')
        foreach ($pair in @(@('secFetchMode', 'Sec-Fetch-Mode', @('cors', 'same-origin', 'navigate')), @('secFetchSite', 'Sec-Fetch-Site', @('same-origin', 'same-site', 'cross-site', 'none')))) {
            $report["headers.$($pair[0]).category"] = if (!$headers.ContainsKey($pair[1])) { 'absent' } elseif ($pair[2] -ccontains $headers[$pair[1]]) { $headers[$pair[1]] } else { 'other' }
        }
        $report['cookieHeaderPresent'] = $headers.ContainsKey('Cookie')
        $report['cookieCount'] = if ($request.Contains('cookies')) { (Get-HarArray $request['cookies']).Count } elseif ($headers.ContainsKey('Cookie')) { @($headers['Cookie'].Split(';') | Where-Object { $_.Contains('=') }).Count } else { 0 }
        $report['refererContext'] = Get-HarRefererContext (Get-HarUri $headers['Referer']) $target
        $origin = Get-HarUri $headers['Origin']
        $report['originMatchesRequestOrigin'] = (Test-HarSameOrigin $origin $target) -and $origin.AbsolutePath -eq '/' -and !$origin.Query -and !$origin.Fragment
        $fields = Get-HarForm $request['postData'] $contentType
        $report['form.fieldCount'] = $fields.Count
        $expected = @('dataOd', 'dataDo', 'idDziennik', 'data')
        $report['form.exactExpectedFieldSet'] = $fields.Count -eq 4
        foreach ($key in $expected) { if (@($fields | Where-Object { $_.name -ceq $key }).Count -ne 1) { $report['form.exactExpectedFieldSet'] = $false } }
        $dates = @{}
        foreach ($key in @('dataOd', 'dataDo', 'data')) {
            $values = @($fields | Where-Object { $_.name -ceq $key })
            $dates[$key] = if ($values.Count -eq 1) { Get-HarTimestamp $values[0].value } else { $null }
            $report["form.${key}Shape"] = if ($null -ne $dates[$key]) { 'ISO_T_DATETIME' } else { 'OTHER' }
        }
        $from = $dates['dataOd']; $to = $dates['dataDo']; $anchor = $dates['data']
        $report['form.weekIsMondayToSunday'] = $null -ne $from -and $null -ne $to -and $from.DayOfWeek -eq 'Monday' -and $to.DayOfWeek -eq 'Sunday' -and ($to.Date - $from.Date).Days -eq 6
        $report['form.dataWithinWeek'] = $null -ne $from -and $null -ne $to -and $null -ne $anchor -and $anchor.Date -ge $from.Date -and $anchor.Date -le $to.Date
        $report['response.jsonParseable'] = $false
        $report['response.rootKind'] = 'UNAVAILABLE'
        $report['response.expectedEnvelopePresent'] = $false
        foreach ($key in @('planLekcji', 'planLekcjiZeZmianami')) { $report["response.${key}ArrayPresent"] = $false; $report["response.${key}Count"] = 0 }
        # Do not inspect HTML text or unsuccessful bodies, even if they claim JSON.
        if ($statusFamily -eq '2xx' -and $family -eq 'json' -and $content.Contains('text')) {
            try {
                $body = Get-HarString $content['text']
                if ($content.Contains('encoding')) {
                    if ($content['encoding'] -cne 'base64') { throw 'INVALID_HAR' }
                    $body = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($body))
                }
                $document = [System.Text.Json.JsonDocument]::Parse($body)
                try { Assert-HarUniqueJsonKeys $document.RootElement } finally { $document.Dispose() }
                $root = ConvertFrom-Json -InputObject $body -AsHashtable -NoEnumerate -DateKind String -Depth 64 -ErrorAction Stop
                # ConvertFrom-Json accepts empty input without validating JSON.
                if ([string]::IsNullOrWhiteSpace($body)) { throw 'INVALID_HAR' }
                $report['response.jsonParseable'] = $true
                $report['response.rootKind'] = if ($root -is [System.Collections.IDictionary]) { 'OBJECT' } elseif ($root -is [array]) { 'ARRAY' } else { 'SCALAR' }
                if ($root -is [System.Collections.IDictionary] -and $root['success'] -is [bool] -and $root['success'] -and $root['data'] -is [System.Collections.IDictionary]) {
                    $report['response.expectedEnvelopePresent'] = $true
                    foreach ($key in @('planLekcji', 'planLekcjiZeZmianami')) {
                        if ($root['data'][$key] -is [array]) { $report["response.${key}ArrayPresent"] = $true; $report["response.${key}Count"] = $root['data'][$key].Count }
                    }
                }
            } catch { # Deliberately suppress all parser diagnostics and content.
                $report['response.jsonParseable'] = $false
            }
        }
        try { Assert-HarSafeOutput $report } catch { return [ordered]@{ result = 'UNSAFE_OUTPUT_GUARD' } }
        return $report
    } catch { return [ordered]@{ result = 'INVALID_HAR' } }
}

function Get-HarLocalPath([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -match '^[\\/]{2}' -or $Value -match '^[a-zA-Z]+://' -or $Value -match '::') { throw 'INVALID_INPUT' }
    $path = [IO.Path]::GetFullPath($Value)
    # Local fixed disks only on Windows; never open a UNC or mapped network drive.
    if ($IsWindows) {
        if ($path -notmatch '^[a-zA-Z]:\\' -or $path.Substring(2).Contains(':')) { throw 'INVALID_INPUT' }
        $drive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($path))
        if ($drive.DriveType -ne [IO.DriveType]::Fixed) { throw 'INVALID_INPUT' }
    }
    # Walk from the root down: checking the full path first could traverse a
    # junction to a network share before discovering that a parent was a link.
    $cursor = [IO.Path]::GetPathRoot($path)
    foreach ($component in $path.Substring($cursor.Length).Split([IO.Path]::DirectorySeparatorChar)) {
        $cursor = [IO.Path]::Combine($cursor, $component)
        try { $attributes = [IO.File]::GetAttributes($cursor) }
        catch [IO.FileNotFoundException] { break }
        catch [IO.DirectoryNotFoundException] { break }
        if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'INVALID_INPUT' }
    }
    return $path
}

function Invoke-HarSanitizer([string]$Source, [string]$Destination) {
    $report = [ordered]@{ result = 'INVALID_INPUT' }
    try {
        $sourceFile = Get-HarLocalPath $Source
        $outputFile = if ($Destination) { Get-HarLocalPath $Destination } else { $null }
        if ($outputFile -and ([string]::Equals($sourceFile, $outputFile, [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetExtension($outputFile) -ne '.json' -or [IO.File]::Exists($outputFile))) { throw 'INVALID_INPUT' }
        $info = [IO.FileInfo]::new($sourceFile)
        if (!$info.Exists -or $info.Length -gt 33554432 -or $info.Length -eq 0) { throw 'INVALID_INPUT' }
        # Bounded local read. No temporary/raw copy and no external resource resolution.
        $stream = [IO.File]::OpenRead($sourceFile)
        try {
            if ($stream.Length -gt 33554432) { throw 'INVALID_INPUT' }
            $reader = [IO.StreamReader]::new($stream, [Text.UTF8Encoding]::new($false, $true))
            try { $raw = $reader.ReadToEnd() } finally { $reader.Dispose() }
        } finally { $stream.Dispose() }
        $report = ConvertTo-HarSafeReport $raw
        $raw = $null
        try { Assert-HarSafeOutput $report } catch { $report = [ordered]@{ result = 'UNSAFE_OUTPUT_GUARD' } }
        $safeJson = ConvertTo-Json -InputObject $report -Depth 4
        if ($outputFile) {
            $outStream = [IO.File]::Open($outputFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
            try {
                $writer = [IO.StreamWriter]::new($outStream, [Text.UTF8Encoding]::new($false))
                try { $writer.Write($safeJson) } finally { $writer.Dispose() }
            } finally { $outStream.Dispose() }
        }
    } catch { $report = [ordered]@{ result = 'INVALID_INPUT' } }
    # Guard immediately before stdout too. No untrusted field survives reduction.
    try { Assert-HarSafeOutput $report } catch { $report = [ordered]@{ result = 'UNSAFE_OUTPUT_GUARD' } }
    Write-Output (ConvertTo-Json -InputObject $report -Depth 4)
    if ($report['result'] -eq 'SUCCESS') { return 0 }
    return 1
}

if ($MyInvocation.InvocationName -ne '.') {
    $items = @(Invoke-HarSanitizer $InputPath $OutputPath)
    Write-Output $items[0]
    exit $items[-1]
}
