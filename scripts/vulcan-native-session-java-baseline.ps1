# Explicit opt-in network diagnostic. Never invoke with real input during normal tests.
[CmdletBinding()]
param([switch]$Run, [string]$HarPath = '.dev/vulcan-schedule-native.har')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Dot-sourcing defines helpers only; the sanitizer's guarded CLI entrypoint does not run.
. (Join-Path $PSScriptRoot 'sanitize-vulcan-schedule-har.ps1')

function New-NativeBaselineReport([string]$Category = 'NOT_AUTHORIZED') {
    return [ordered]@{
        schemaVersion = 1; result = 'FAIL'; category = $Category
        nativeEvidenceValidated = $false; 'session.cookieCount' = 0; 'session.refererContext' = 'UNAVAILABLE'
        'form.exactFieldSet' = $false; 'form.timestampShapesMatch' = $false; 'form.weekSemanticsMatch' = $false
        javaRequestAttempted = $false; 'java.statusFamily' = 'UNAVAILABLE'; 'java.status429' = 'UNAVAILABLE'
        'java.contentFamily' = 'UNAVAILABLE'; javaOutcome = 'NOT_RUN'
        retryAfterPresent = 'UNAVAILABLE'; retryAfterSeconds = 'UNAVAILABLE'; javaScheduleRequests = 0; retries = 0
    }
}

function Assert-NativeBaselineReport($Report) {
    $schema = @{
        schemaVersion = 'one'; result = @('SUCCESS', 'FAIL')
        category = @('VALIDATION_ONLY', 'INVALID_INPUT', 'NOT_AUTHORIZED', 'INVALID_HAR', 'INVALID_NATIVE_EVIDENCE', 'INVALID_SESSION', 'FORM_MISMATCH', 'BUDGET_EXHAUSTED', 'BASELINE_COMPLETED', 'HARNESS_FAILURE', 'BUILD_FAILURE', 'UNSAFE_OUTPUT_GUARD', 'CHILD_FAILURE')
        nativeEvidenceValidated = 'bool'; 'session.cookieCount' = 'cookies'
        'session.refererContext' = @('PLAN_PAGE', 'JOURNAL_PAGE', 'HOME_OR_LANDING', 'OTHER_ALLOWED', 'UNAVAILABLE')
        'form.exactFieldSet' = 'bool'; 'form.timestampShapesMatch' = 'bool'; 'form.weekSemanticsMatch' = 'bool'
        javaRequestAttempted = 'optionalBool'; 'java.statusFamily' = @('2xx', '3xx', '4xx', '5xx', 'UNAVAILABLE')
        'java.status429' = 'optionalBool'; 'java.contentFamily' = @('json', 'html', 'other', 'UNAVAILABLE')
        javaOutcome = @('SUCCESS', 'RATE_LIMITED', 'AUTHENTICATION_REQUIRED', 'SESSION_REDIRECT', 'UNEXPECTED_HTML', 'SERVER_ERROR', 'PERMANENT_HTTP', 'TRANSPORT_ERROR', 'PROTOCOL_FAILURE', 'NOT_RUN')
        retryAfterPresent = 'optionalBool'; retryAfterSeconds = 'duration'; javaScheduleRequests = 'permit'; retries = 'zero'
    }
    if ($Report -isnot [System.Collections.IDictionary] -or $Report.Count -ne $schema.Count) { throw 'UNSAFE_OUTPUT_GUARD' }
    foreach ($key in $Report.Keys) {
        if (@($schema.Keys) -cnotcontains $key) { throw 'UNSAFE_OUTPUT_GUARD' }
        $rule = $schema[$key]; $value = $Report[$key]
        if ($rule -is [array]) { if ($value -isnot [string] -or $rule -cnotcontains $value) { throw 'UNSAFE_OUTPUT_GUARD' } }
        elseif ($rule -eq 'bool') { if ($value -isnot [bool]) { throw 'UNSAFE_OUTPUT_GUARD' } }
        elseif ($rule -eq 'optionalBool') { if ($value -isnot [bool] -and $value -cne 'UNAVAILABLE') { throw 'UNSAFE_OUTPUT_GUARD' } }
        elseif ($rule -eq 'duration' -and $value -ceq 'UNAVAILABLE') { continue }
        elseif ($rule -eq 'permit' -and $value -ceq 'UNAVAILABLE') { continue }
        else {
            if ($value -isnot [int] -and $value -isnot [long]) { throw 'UNSAFE_OUTPUT_GUARD' }
            $max = switch ($rule) { 'one' { 1 }; 'zero' { 0 }; 'permit' { 1 }; 'cookies' { 1000 }; 'duration' { 31536000 } }
            if ($value -lt 0 -or $value -gt $max -or ($rule -eq 'one' -and $value -ne 1)) { throw 'UNSAFE_OUTPUT_GUARD' }
        }
    }
    if (($Report.javaRequestAttempted -is [string] -and $Report.javaRequestAttempted -ceq 'UNAVAILABLE') -or ($Report.javaScheduleRequests -is [string] -and $Report.javaScheduleRequests -ceq 'UNAVAILABLE')) {
        if ($Report.category -cne 'CHILD_FAILURE' -or $Report.javaRequestAttempted -cne 'UNAVAILABLE' -or $Report.javaScheduleRequests -cne 'UNAVAILABLE') { throw 'UNSAFE_OUTPUT_GUARD' }
    } elseif ($Report.javaRequestAttempted -ne ($Report.javaScheduleRequests -eq 1)) { throw 'UNSAFE_OUTPUT_GUARD' }
    if ($Report.result -ceq 'SUCCESS' -and ($Report.javaOutcome -cne 'SUCCESS' -or !$Report.javaRequestAttempted -or !$Report.nativeEvidenceValidated)) { throw 'UNSAFE_OUTPUT_GUARD' }
}

function Get-NativeSessionPayload([string]$RawHar) {
    try {
        if ([Text.Encoding]::UTF8.GetByteCount($RawHar) -gt 33554432) { throw 'INVALID_HAR' }
        # Re-validate the raw source, including its captured successful response.
        $safe = ConvertTo-HarSafeReport $RawHar
        if ($safe.result -cne 'SUCCESS') { throw 'INVALID_NATIVE_EVIDENCE' }
        foreach ($key in @('response.jsonParseable', 'response.expectedEnvelopePresent', 'response.planLekcjiArrayPresent', 'response.planLekcjiZeZmianamiArrayPresent', 'form.exactExpectedFieldSet', 'form.weekIsMondayToSunday', 'form.dataWithinWeek')) {
            if (!$safe[$key]) { throw 'INVALID_NATIVE_EVIDENCE' }
        }
        if ($safe['response.statusFamily'] -cne '2xx' -or $safe['response.contentFamily'] -cne 'json' -or $safe['request.contentTypeFamily'] -cne 'form_urlencoded') { throw 'INVALID_NATIVE_EVIDENCE' }
        $har = ConvertFrom-SafeProfileJson $RawHar
        $targets = @($har['log']['entries'] | Where-Object {
            $candidate = Get-HarUri $_['request']['url']
            $null -ne $candidate -and $candidate.AbsolutePath -cmatch '/PlanLekcji\.mvc/GetPlanLekcjiContext$'
        })
        if ($targets.Count -ne 1) { throw 'INVALID_NATIVE_EVIDENCE' }
        $request = $targets[0]['request']
        $target = Get-HarUri $request['url']
        $headers = Get-HarHeaders $request['headers']
        $referer = Get-HarUri $headers['Referer']
        if ($request['method'] -cne 'POST' -or $target.Scheme -cne 'https' -or $target.Port -ne 443 -or $target.UserInfo -or $target.Query -or $target.Fragment -or
            ($target.IdnHost -ine 'vulcan.net.pl' -and !$target.IdnHost.EndsWith('.vulcan.net.pl', [StringComparison]::OrdinalIgnoreCase))) { throw 'INVALID_SESSION' }
        $prefix = $target.AbsolutePath.Substring(0, $target.AbsolutePath.Length - 'PlanLekcji.mvc/GetPlanLekcjiContext'.Length)
        if (!(Test-HarSameOrigin $referer $target) -or $referer.UserInfo -or $referer.Fragment -or !$referer.AbsolutePath.StartsWith($prefix, [StringComparison]::Ordinal)) { throw 'INVALID_SESSION' }
        foreach ($uri in @($target, $referer)) {
            if ($uri.OriginalString -cnotmatch '^https://[^/?#]+(?<rawPath>/[^?#]*)') { throw 'INVALID_SESSION' }
            $rawPath = $Matches['rawPath']
            if ($rawPath -cne $uri.AbsolutePath -or $rawPath -match '%|\\|//|/(?:\.|\.\.)(?:/|$)') { throw 'INVALID_SESSION' }
        }
        foreach ($name in @('X-V-RequestVerificationToken', 'X-V-AppGuid', 'Cookie')) {
            if (!$headers.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($headers[$name]) -or $headers[$name] -cnotmatch '^[\x20-\x7e]+$') { throw 'INVALID_SESSION' }
        }
        $cookieNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($pair in $headers['Cookie'].Split(';')) {
            $parts = $pair.Trim().Split('=', 2)
            if ($parts.Count -ne 2 -or $parts[0] -cnotmatch '^[!#$%&''*+.^_`|~0-9A-Za-z-]+$' -or
                $parts[1] -cnotmatch '^[\x21\x23-\x2b\x2d-\x3a\x3c-\x5b\x5d-\x7e]*$' -or !$cookieNames.Add($parts[0])) { throw 'INVALID_SESSION' }
        }
        if ($cookieNames.Count -lt 1 -or $cookieNames.Count -gt 1000) { throw 'INVALID_SESSION' }
        $fields = Get-HarForm $request['postData'] 'application/x-www-form-urlencoded'
        $form = @{}
        foreach ($field in $fields) { $form[$field.name] = $field.value }
        $journal = 0L
        if ($form['idDziennik'] -cnotmatch '^[1-9][0-9]{0,18}$' -or ![long]::TryParse($form['idDziennik'], [ref]$journal)) { throw 'INVALID_SESSION' }
        foreach ($name in @('dataOd', 'dataDo', 'data')) {
            if ($form[$name] -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$' -or $null -eq (Get-HarTimestamp $form[$name])) { throw 'INVALID_SESSION' }
        }
        $values = @($target.AbsoluteUri, $referer.AbsoluteUri, $headers['X-V-RequestVerificationToken'], $headers['X-V-AppGuid'], $headers['Cookie'], $form['dataOd'], $form['dataDo'], $form['data'], $form['idDziennik'])
        $buffer = [IO.MemoryStream]::new()
        try {
            $writer = [IO.BinaryWriter]::new($buffer, [Text.UTF8Encoding]::new($false, $true), $true)
            try {
                $writer.Write([byte[]](78, 83, 74, 49))
                foreach ($value in $values) {
                    $bytes = [Text.Encoding]::UTF8.GetBytes($value)
                    try {
                        if ($bytes.Length -lt 1 -or $bytes.Length -gt 8192) { throw 'INVALID_SESSION' }
                        $writer.Write([int]$bytes.Length); $writer.Write($bytes)
                    } finally { [Array]::Clear($bytes, 0, $bytes.Length) }
                }
                $writer.Flush()
                if ($buffer.Length -gt 65536) { throw 'INVALID_SESSION' }
                return ,$buffer.ToArray()
            } finally { $writer.Dispose() }
        } finally { [Array]::Clear($buffer.GetBuffer(), 0, $buffer.GetBuffer().Length); $buffer.Dispose() }
    } catch {
        # Never forward provider/parsing exceptions or attach them as inner exceptions.
        throw [InvalidOperationException]::new('INVALID_NATIVE_EVIDENCE')
    }
}

function Invoke-NativeBaselineChild([string]$Classpath, [byte[]]$Payload, [bool]$ValidationOnly = $false) {
    $start = [Diagnostics.ProcessStartInfo]::new('java')
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $mode = if ($ValidationOnly) { '--validate-native-session-input' } else { '--authorized-native-session-java-baseline' }
    foreach ($argument in @('-cp', $Classpath, 'io.github.bohdankordon.vulcanschedulemonitor.devsmoke.VulcanNativeSessionJavaBaseline', $mode)) { $start.ArgumentList.Add($argument) }
    # Do not inherit Java agent/debug options capable of persisting request material.
    foreach ($name in @('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) { [void]$start.Environment.Remove($name) }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.BaseStream.Write($Payload, 0, $Payload.Length); $process.StandardInput.Close()
        if (!$process.WaitForExit(60000)) { $process.Kill($true); throw 'HARNESS_FAILURE' }
        $safe = ConvertFrom-SafeProfileJson $stdout.GetAwaiter().GetResult()
        # Never expose child stderr, even on failure.
        if (![string]::IsNullOrWhiteSpace($stderr.GetAwaiter().GetResult())) { throw 'UNSAFE_OUTPUT_GUARD' }
        Assert-NativeBaselineReport $safe
        if (($safe.result -ceq 'SUCCESS') -ne ($process.ExitCode -eq 0)) { throw 'UNSAFE_OUTPUT_GUARD' }
        return $safe
    } finally { $process.Dispose() }
}

function Invoke-NativeSessionBaseline([bool]$Authorized, [string]$Source) {
    $report = New-NativeBaselineReport
    $payload = $null
    try {
        if ($Authorized) {
            $root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
            $allowed = [IO.Path]::GetFullPath((Join-Path $root '.dev/vulcan-schedule-native.har'))
            $path = Get-HarLocalPath $Source
            if (![string]::Equals($path, $allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'INVALID_INPUT' }
            $report.category = 'BUILD_FAILURE'
            # Build logs are private and discarded; no HAR is read before build success.
            $buildOutput = & (Join-Path $root 'mvnw.cmd') -B -ntp -DskipTests test-compile dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/native-session-baseline-classpath.txt' 2>&1
            if ($LASTEXITCODE -ne 0) { throw 'BUILD_FAILURE' }
            $buildOutput = $null
            $report.category = 'INVALID_NATIVE_EVIDENCE'
            $path = Get-HarLocalPath $path
            $stream = [IO.File]::OpenRead($path)
            try {
                if ($stream.Length -lt 1 -or $stream.Length -gt 33554432) { throw 'INVALID_HAR' }
                $reader = [IO.StreamReader]::new($stream, [Text.UTF8Encoding]::new($false, $true))
                try { $raw = $reader.ReadToEnd() } finally { $reader.Dispose() }
            } finally { $stream.Dispose() }
            $payload = Get-NativeSessionPayload $raw
            $raw = $null
            $classpath = (Join-Path $root 'target/test-classes') + ';' + (Join-Path $root 'target/classes') + ';' + [IO.File]::ReadAllText((Join-Path $root 'target/native-session-baseline-classpath.txt')).Trim()
            # A lost child report cannot prove whether dispatch happened. Never report
            # zero in that case; treat its one-shot budget as spent and do not restart.
            $report.category = 'CHILD_FAILURE'
            $report.nativeEvidenceValidated = $true
            $report.javaRequestAttempted = 'UNAVAILABLE'
            $report.javaScheduleRequests = 'UNAVAILABLE'
            $report = Invoke-NativeBaselineChild $classpath $payload
        }
    } catch { # The finite current category identifies the first failing boundary.
        if ($report.category -eq 'NOT_AUTHORIZED') { $report.category = 'INVALID_INPUT' }
    } finally { if ($null -ne $payload) { [Array]::Clear($payload, 0, $payload.Length) } }
    try { Assert-NativeBaselineReport $report } catch { $report = New-NativeBaselineReport 'UNSAFE_OUTPUT_GUARD' }
    Write-Output (ConvertTo-Json -InputObject $report -Depth 4)
    return $(if ($report.result -ceq 'SUCCESS') { 0 } else { 1 })
}

if ($MyInvocation.InvocationName -ne '.') {
    $items = @(Invoke-NativeSessionBaseline $Run.IsPresent $HarPath)
    Write-Output $items[0]
    exit $items[-1]
}
