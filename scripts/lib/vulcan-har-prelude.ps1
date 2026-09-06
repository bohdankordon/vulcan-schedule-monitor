# Pure offline helpers. No CLI, file access, external resource resolution, or logging.
function Get-HarPreludeCategories {
    return @('PLAN_PAGE', 'PLAN_CONTEXT', 'GET_CACHE', 'GET_TREE', 'REFRESH_SESSION', 'HOME', 'DZIENNIK', 'STATIC', 'OTHER_ALLOWED')
}

function Get-HarPreludeSchema {
    $schema = @{
        'prelude.sequence' = 'sequence'; 'prelude.requestCount' = 'count'
        'prelude.containsPlanPage' = 'bool'; 'prelude.containsGetCache' = 'bool'
        'prelude.containsGetTree' = 'bool'; 'prelude.containsRefreshSession' = 'bool'
        'prelude.anyResponseSetCookie' = 'bool'; 'prelude.anyCookieMaterialChange' = 'optionalBool'
        'prelude.immediatePrevious.endpointCategory' = @((Get-HarPreludeCategories)) + @('ABSENT')
        'prelude.immediatePrevious.method' = @('GET', 'POST', 'OTHER', 'ABSENT')
        'prelude.immediatePrevious.statusFamily' = @('2xx', '3xx', '4xx', '5xx', 'UNAVAILABLE')
        'prelude.immediatePrevious.responseSetCookiePresent' = 'optionalBool'
        'target.cookieCount' = 'count'
        'target.cookieMaterialChangedFromImmediatelyPreviousRequest' = 'optionalBool'
    }
    return $schema
}

function Get-HarPreludeEntrySchema {
    return @{
        sequenceIndex = 'index'; relativeOrder = 'order'
        relativeTimeBucket = @('WITHIN_1_SECOND', 'ONE_TO_FIVE_SECONDS', 'FIVE_TO_TEN_SECONDS')
        method = @('GET', 'POST', 'OTHER'); endpointCategory = @(Get-HarPreludeCategories)
        resourceFamily = @('DOCUMENT', 'XHR_FETCH', 'SCRIPT', 'STYLE', 'IMAGE', 'OTHER', 'UNKNOWN')
        statusFamily = @('2xx', '3xx', '4xx', '5xx', 'UNAVAILABLE')
        responseSetCookiePresent = 'bool'; responseSetCookieHeaderCount = 'count'
        requestCookiePresent = 'bool'; requestCookieCount = 'count'
        cookieCountChangedSincePrevious = 'optionalBool'; cookieMaterialChangedSincePrevious = 'optionalBool'
    }
}

function Assert-HarPreludeSequence($Rows) {
    if ($Rows -isnot [array] -or $Rows.Count -gt 10) { throw 'UNSAFE_OUTPUT_GUARD' }
    $schema = Get-HarPreludeEntrySchema
    for ($index = 0; $index -lt $Rows.Count; $index++) {
        $row = $Rows[$index]
        if ($row -isnot [Collections.IDictionary] -or $row.Count -ne $schema.Count) { throw 'UNSAFE_OUTPUT_GUARD' }
        foreach ($key in $row.Keys) {
            if (@($schema.Keys) -cnotcontains $key) { throw 'UNSAFE_OUTPUT_GUARD' }
            $rule = $schema[$key]; $value = $row[$key]
            if ($rule -is [array]) {
                if ($value -isnot [string] -or $rule -cnotcontains $value) { throw 'UNSAFE_OUTPUT_GUARD' }
            } elseif ($rule -eq 'bool') {
                if ($value -isnot [bool]) { throw 'UNSAFE_OUTPUT_GUARD' }
            } elseif ($rule -eq 'optionalBool') {
                if ($value -isnot [bool] -and ($value -isnot [string] -or $value -cne 'UNAVAILABLE')) { throw 'UNSAFE_OUTPUT_GUARD' }
            } elseif (($value -isnot [int] -and $value -isnot [long]) -or $value -lt 0 -or $value -gt 1000) { throw 'UNSAFE_OUTPUT_GUARD' }
        }
        if ($row.sequenceIndex -ne $index -or $row.relativeOrder -ne ($Rows.Count - $index) -or $row.responseSetCookiePresent -ne ($row.responseSetCookieHeaderCount -gt 0)) { throw 'UNSAFE_OUTPUT_GUARD' }
        if ($index -eq 0 -and ($row.cookieCountChangedSincePrevious -isnot [string] -or $row.cookieCountChangedSincePrevious -cne 'UNAVAILABLE' -or $row.cookieMaterialChangedSincePrevious -isnot [string] -or $row.cookieMaterialChangedSincePrevious -cne 'UNAVAILABLE')) { throw 'UNSAFE_OUTPUT_GUARD' }
    }
}

function Test-HarPreludeTarget($Report) {
    if ($Report['matchingRequestCount'] -ne 1 -or $Report['response.statusFamily'] -cne '2xx' -or $Report['response.status429'] -ne $false -or $Report['response.contentFamily'] -cne 'json' -or $Report['form.fieldCount'] -ne 4) { return $false }
    foreach ($key in @('response.jsonParseable', 'response.expectedEnvelopePresent', 'response.planLekcjiArrayPresent', 'response.planLekcjiZeZmianamiArrayPresent', 'form.exactExpectedFieldSet', 'form.weekIsMondayToSunday', 'form.dataWithinWeek')) {
        if ($Report[$key] -isnot [bool] -or !$Report[$key]) { return $false }
    }
    return $true
}

function Assert-HarPreludeSummary($Report) {
    if (!(Test-HarPreludeTarget $Report)) { throw 'UNSAFE_OUTPUT_GUARD' }
    $rows = $Report['prelude.sequence']
    if ($Report['prelude.requestCount'] -ne $rows.Count -or $Report['target.cookieCount'] -gt 1000) { throw 'UNSAFE_OUTPUT_GUARD' }
    foreach ($pair in @(@('containsPlanPage', 'PLAN_PAGE'), @('containsGetCache', 'GET_CACHE'), @('containsGetTree', 'GET_TREE'), @('containsRefreshSession', 'REFRESH_SESSION'))) {
        if ($Report["prelude.$($pair[0])"] -ne (@($rows | Where-Object { $_.endpointCategory -ceq $pair[1] }).Count -gt 0)) { throw 'UNSAFE_OUTPUT_GUARD' }
    }
    if ($Report['prelude.anyResponseSetCookie'] -ne (@($rows | Where-Object { $_.responseSetCookiePresent }).Count -gt 0)) { throw 'UNSAFE_OUTPUT_GUARD' }
    if (![object]::Equals($Report['prelude.anyCookieMaterialChange'], (Get-HarPreludeAnyChange $rows $Report['target.cookieMaterialChangedFromImmediatelyPreviousRequest']))) { throw 'UNSAFE_OUTPUT_GUARD' }
    if ($rows.Count -eq 0) {
        if ($Report['prelude.immediatePrevious.endpointCategory'] -cne 'ABSENT' -or $Report['prelude.immediatePrevious.method'] -cne 'ABSENT' -or $Report['prelude.immediatePrevious.statusFamily'] -cne 'UNAVAILABLE' -or $Report['prelude.immediatePrevious.responseSetCookiePresent'] -isnot [string] -or $Report['target.cookieMaterialChangedFromImmediatelyPreviousRequest'] -isnot [string]) { throw 'UNSAFE_OUTPUT_GUARD' }
    } else {
        foreach ($key in @('endpointCategory', 'method', 'statusFamily', 'responseSetCookiePresent')) {
            if ($Report["prelude.immediatePrevious.$key"] -cne $rows[-1][$key]) { throw 'UNSAFE_OUTPUT_GUARD' }
        }
    }
}

function Get-HarPreludeAnyChange($Rows, $TargetChange) {
    $changes = @($Rows | Select-Object -Skip 1 | ForEach-Object { $_.cookieMaterialChangedSincePrevious }) + @($TargetChange)
    $unknown = $false
    foreach ($change in $changes) {
        if ($change -is [bool]) { if ($change) { return $true } } else { $unknown = $true }
    }
    if ($unknown) { return 'UNAVAILABLE' }
    return $false
}

function Get-HarPreludeEndpoint($Uri) {
    $path = $Uri.AbsolutePath
    if ($path -cmatch '/PlanLekcji\.mvc/GetPlanLekcjiContext$') { return 'PLAN_CONTEXT' }
    if ($path -cmatch '/PlanLekcji\.mvc(?:/.*)?$') { return 'PLAN_PAGE' }
    if ($path -cmatch '/DziennikCache\.mvc/GetCache$') { return 'GET_CACHE' }
    if ($path -cmatch '/Dziennik\.mvc/GetTree$') { return 'GET_TREE' }
    if ($path -cmatch '/Home\.mvc/RefreshSession$') { return 'REFRESH_SESSION' }
    if ($path -cmatch '/(?:Home|Start|Default|Index)\.mvc(?:/.*)?$' -or $path -ceq '/') { return 'HOME' }
    if ($path -cmatch '/(?:Dziennik|Dzienniki|Journal)\.mvc(?:/.*)?$') { return 'DZIENNIK' }
    if ($path -match '\.(?:js|css|png|jpe?g|gif|svg|ico|webp|avif|woff2?|ttf)(?:$)') { return 'STATIC' }
    return 'OTHER_ALLOWED'
}

function Get-HarPreludeTime($Value) {
    if ($Value -isnot [string] -or $Value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})$') { throw 'INVALID_HAR' }
    $time = [DateTimeOffset]::MinValue
    if (![DateTimeOffset]::TryParse($Value, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$time)) { throw 'INVALID_HAR' }
    return $time
}

function Get-HarPreludeCookies($Request, $Headers) {
    $present = $Headers.ContainsKey('Cookie')
    $pairs = [Collections.Generic.List[string]]::new()
    if ($present) {
        if (![string]::IsNullOrWhiteSpace($Headers['Cookie'])) {
            foreach ($pair in $Headers['Cookie'].Split(';')) {
                $parts = $pair.Trim().Split('=', 2)
                if ($parts.Count -ne 2 -or $parts[0] -cnotmatch '^[!#$%&''*+.^_`|~0-9A-Za-z-]+$' -or $parts[1] -cnotmatch '^[\x21\x23-\x2b\x2d-\x3a\x3c-\x5b\x5d-\x7e]*$') { throw 'INVALID_HAR' }
                $pairs.Add($parts[0] + '=' + $parts[1])
            }
        }
        $count = $pairs.Count
    } else { $count = if ($Request.Contains('cookies')) { (Get-HarArray $Request['cookies']).Count } else { 0 } }
    if ($count -gt 1000) { throw 'INVALID_HAR' }
    $pairs.Sort([StringComparer]::Ordinal)
    return @{ present = $present; count = $count; pairs = $pairs.ToArray() }
}

function Get-HarPreludeCookieDelta($Previous, $Current) {
    if ($null -eq $Previous -or !$Previous.present -or !$Current.present) { return @{ count = 'UNAVAILABLE'; material = 'UNAVAILABLE' } }
    $different = $Previous.count -ne $Current.count
    if (!$different) {
        for ($i = 0; $i -lt $Previous.pairs.Count; $i++) {
            if ($Previous.pairs[$i] -cne $Current.pairs[$i]) { $different = $true; break }
        }
    }
    return @{ count = ($Previous.count -ne $Current.count); material = $different }
}

function Get-HarPreludeResource($Entry, $Headers) {
    if ($Entry.Contains('_resourceType')) {
        switch -CaseSensitive ([string]$Entry['_resourceType']) {
            'document' { return 'DOCUMENT' }; 'xhr' { return 'XHR_FETCH' }; 'fetch' { return 'XHR_FETCH' }
            'script' { return 'SCRIPT' }; 'stylesheet' { return 'STYLE' }; 'image' { return 'IMAGE' }
        }
    }
    if ($Headers.ContainsKey('X-Requested-With') -and $Headers['X-Requested-With'] -ceq 'XMLHttpRequest') { return 'XHR_FETCH' }
    switch -CaseSensitive ([string]$Headers['Sec-Fetch-Dest']) {
        'document' { return 'DOCUMENT' }; 'iframe' { return 'DOCUMENT' }; 'script' { return 'SCRIPT' }; 'style' { return 'STYLE' }; 'image' { return 'IMAGE' }
        'empty' { if ($Headers['Sec-Fetch-Mode'] -cin @('cors', 'same-origin')) { return 'XHR_FETCH' } }
    }
    $content = $Entry['response']['content']
    if ($null -eq $content -or !$content.Contains('mimeType')) { return 'UNKNOWN' }
    $mime = Get-HarMediaType ([string]$content['mimeType'])
    if ($mime -in @('application/javascript', 'text/javascript')) { return 'SCRIPT' }
    if ($mime -eq 'text/css') { return 'STYLE' }
    if ($mime -match '^image/') { return 'IMAGE' }
    # HTML MIME alone does not prove navigation rather than an XHR auth response.
    return 'OTHER'
}

function ConvertTo-HarPreludeReport([string]$Json) {
    $report = ConvertTo-HarSafeReport $Json
    $report['schemaVersion'] = 3
    if ($report.result -cne 'SUCCESS') { return $report }
    if (!(Test-HarPreludeTarget $report)) { return [ordered]@{ schemaVersion = 3; result = 'UNSUPPORTED_TARGET_REQUEST' } }
    try {
        $har = ConvertFrom-SafeProfileJson $Json
        $entries = Get-HarArray $har['log']['entries']
        $targetIndex = -1
        for ($i = 0; $i -lt $entries.Count; $i++) {
            $uri = Get-HarUri $entries[$i]['request']['url']
            if ($null -ne $uri -and $uri.AbsolutePath -cmatch '/PlanLekcji\.mvc/GetPlanLekcjiContext$') { $targetIndex = $i; break }
        }
        $target = $entries[$targetIndex]
        $targetUri = Get-HarUri $target['request']['url']
        if ($targetUri.Scheme -cne 'https' -or $targetUri.Port -ne 443 -or ($targetUri.IdnHost -ine 'vulcan.net.pl' -and !$targetUri.IdnHost.EndsWith('.vulcan.net.pl', [StringComparison]::OrdinalIgnoreCase))) { throw 'INVALID_HAR' }
        $targetTime = Get-HarPreludeTime $target['startedDateTime']
        $candidates = [Collections.Generic.List[object]]::new()
        for ($i = 0; $i -lt $entries.Count; $i++) {
            if ($i -eq $targetIndex) { continue }
            $entry = $entries[$i]
            $uri = Get-HarUri $entry['request']['url']
            if (!(Test-HarSameOrigin $uri $targetUri)) { continue }
            $time = Get-HarPreludeTime $entry['startedDateTime']
            $seconds = ($targetTime - $time).TotalSeconds
            if ($seconds -lt 0 -or $seconds -gt 10 -or ($seconds -eq 0 -and $i -gt $targetIndex)) { continue }
            $candidates.Add(@{ entry = $entry; uri = $uri; time = $time; index = $i; seconds = $seconds })
        }
        $selected = @($candidates | Sort-Object time, index | Select-Object -Last 10)
        $rows = [Collections.Generic.List[object]]::new()
        $previous = $null
        foreach ($candidate in $selected) {
            $entry = $candidate.entry; $request = Get-HarObject $entry['request']; $response = Get-HarObject $entry['response']
            $headers = Get-HarHeaders $request['headers']
            $cookies = Get-HarPreludeCookies $request $headers
            $delta = Get-HarPreludeCookieDelta $previous $cookies
            $setCookieCount = 0
            foreach ($header in (Get-HarArray $response['headers'])) {
                if ((Get-HarString $header['name']) -ieq 'Set-Cookie') { $setCookieCount++ }
            }
            if ($setCookieCount -gt 1000) { throw 'INVALID_HAR' }
            $status = $response['status']
            if (($status -isnot [int] -and $status -isnot [long]) -or $status -lt 0 -or $status -gt 599) { throw 'INVALID_HAR' }
            $method = Get-HarString $request['method']
            $rows.Add([ordered]@{
                sequenceIndex = $rows.Count; relativeOrder = $selected.Count - $rows.Count
                relativeTimeBucket = $(if ($candidate.seconds -le 1) { 'WITHIN_1_SECOND' } elseif ($candidate.seconds -le 5) { 'ONE_TO_FIVE_SECONDS' } else { 'FIVE_TO_TEN_SECONDS' })
                method = $(if ($method -cin @('GET', 'POST')) { $method } else { 'OTHER' })
                endpointCategory = (Get-HarPreludeEndpoint $candidate.uri); resourceFamily = (Get-HarPreludeResource $entry $headers)
                statusFamily = $(if ($status -ge 200) { ([int][Math]::Floor($status / 100)).ToString() + 'xx' } else { 'UNAVAILABLE' })
                responseSetCookiePresent = ($setCookieCount -gt 0); responseSetCookieHeaderCount = $setCookieCount
                requestCookiePresent = $cookies.present; requestCookieCount = $cookies.count
                cookieCountChangedSincePrevious = $delta.count; cookieMaterialChangedSincePrevious = $delta.material
            })
            $previous = $cookies
        }
        $targetCookies = Get-HarPreludeCookies $target['request'] (Get-HarHeaders $target['request']['headers'])
        $targetDelta = Get-HarPreludeCookieDelta $previous $targetCookies
        $report['prelude.sequence'] = $rows.ToArray()
        $report['prelude.requestCount'] = $rows.Count
        foreach ($pair in @(@('containsPlanPage', 'PLAN_PAGE'), @('containsGetCache', 'GET_CACHE'), @('containsGetTree', 'GET_TREE'), @('containsRefreshSession', 'REFRESH_SESSION'))) {
            $report["prelude.$($pair[0])"] = @($rows | Where-Object { $_.endpointCategory -ceq $pair[1] }).Count -gt 0
        }
        $report['prelude.anyResponseSetCookie'] = @($rows | Where-Object { $_.responseSetCookiePresent }).Count -gt 0
        $report['prelude.anyCookieMaterialChange'] = Get-HarPreludeAnyChange $rows $targetDelta.material
        foreach ($key in @('endpointCategory', 'method', 'statusFamily', 'responseSetCookiePresent')) {
            $report["prelude.immediatePrevious.$key"] = if ($rows.Count -gt 0) { $rows[$rows.Count - 1][$key] } elseif ($key -in @('endpointCategory', 'method')) { 'ABSENT' } else { 'UNAVAILABLE' }
        }
        $report['target.cookieCount'] = $targetCookies.count
        $report['target.cookieMaterialChangedFromImmediatelyPreviousRequest'] = $targetDelta.material
        Assert-HarSafeOutput $report
        return $report
    } catch { return [ordered]@{ schemaVersion = 3; result = 'INVALID_HAR' } }
}
