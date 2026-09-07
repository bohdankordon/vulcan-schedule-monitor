#Requires -Version 7.0
[CmdletBinding()]
param([switch]$Configure, [switch]$Run, [switch]$Clear, [switch]$Help)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Show-VulcanSmokeHelp {
    @'
REAL VULCAN SMOKE - LOCAL DEVELOPMENT ONLY (Windows, PowerShell 7, Java 21)

  .\scripts\vulcan-real-smoke.ps1 -Configure   Manually store a separate DPAPI credential bundle
  .\scripts\vulcan-real-smoke.ps1 -Run         One authorized real connection attempt
  .\scripts\vulcan-real-smoke.ps1 -Clear       Delete only the smoke credential bundle
  .\scripts\vulcan-real-smoke.ps1 -Help        This help; no setup, secrets, or network

Use only an account you are authorized to test. Run makes real VULCAN requests.
Monitoring and Telegram are disabled. CAPTCHA/MFA are never bypassed.
No account/password/session is persisted to the application database.
Chromium must already be installed; this script never installs it.
DPAPI CurrentUser protects at rest, not against arbitrary code running as this Windows user.
Never commit or share .dev contents. Do not configure real credentials through an agent prompt.
'@ | Write-Host
}

function Get-SmokeRepositoryRoot {
    return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}

function Convert-SecureInputToBytes {
    param([Parameter(Mandatory)][Security.SecureString]$Value)
    $pointer = [IntPtr]::Zero
    $characters = [char[]]::new($Value.Length)
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
        for ($index = 0; $index -lt $characters.Length; $index++) {
            $characters[$index] = [char]([int][Runtime.InteropServices.Marshal]::ReadInt16($pointer, $index * 2) -band 0xffff)
        }
        return ,([Text.Encoding]::UTF8.GetBytes($characters))
    }
    finally {
        [Array]::Clear($characters, 0, $characters.Length)
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
}

function New-SmokePayload {
    param([string]$Portal, [Security.SecureString]$Login, [Security.SecureString]$Password)
    $stream = [IO.MemoryStream]::new()
    $writer = [IO.BinaryWriter]::new($stream, [Text.Encoding]::UTF8, $true)
    $fields = [Collections.Generic.List[byte[]]]::new()
    try {
        $fields.Add([Text.Encoding]::UTF8.GetBytes($Portal.Trim()))
        $fields.Add((Convert-SecureInputToBytes $Login))
        $fields.Add((Convert-SecureInputToBytes $Password))
        $limits = @(8192, 4096, 4096)
        $writer.Write([Text.Encoding]::ASCII.GetBytes('VSM1'))
        for ($index = 0; $index -lt 3; $index++) {
            [byte[]]$field = $fields[$index]
            if ($field.Length -eq 0 -or $field.Length -gt $limits[$index]) { throw 'Invalid input size' }
            $writer.Write([int]$field.Length) # BinaryWriter uses little endian; Java reads the same format.
            $writer.Write($field)
        }
        $writer.Flush()
        return ,($stream.ToArray())
    }
    finally {
        foreach ($field in $fields) { [Array]::Clear($field, 0, $field.Length) }
        $buffer = $stream.GetBuffer()
        [Array]::Clear($buffer, 0, $buffer.Length)
        $writer.Dispose()
        $stream.Dispose()
    }
}

function Protect-SmokePayload {
    param([byte[]]$Payload, [string]$Path)
    $ciphertext = $null
    try {
        $ciphertext = [Security.Cryptography.ProtectedData]::Protect(
            $Payload, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
        [IO.File]::WriteAllText($Path, [Convert]::ToBase64String($ciphertext), [Text.UTF8Encoding]::new($false))
    }
    finally {
        if ($null -ne $ciphertext) { [Array]::Clear($ciphertext, 0, $ciphertext.Length) }
    }
}

function Unprotect-SmokePayload {
    param([string]$Path)
    $ciphertext = $null
    try {
        $encoded = [IO.File]::ReadAllText($Path)
        if ($encoded.Length -gt 65536) { throw 'Invalid protected input size' }
        $ciphertext = [Convert]::FromBase64String($encoded)
        return ,([Security.Cryptography.ProtectedData]::Unprotect(
            $ciphertext, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser))
    }
    finally {
        if ($null -ne $ciphertext) { [Array]::Clear($ciphertext, 0, $ciphertext.Length) }
    }
}

function New-SmokeProcessInfo {
    param([string]$Executable, [string]$RepositoryRoot)
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable
    $info.WorkingDirectory = $RepositoryRoot
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    # Do not inherit credentials, JVM agents/debug flags, HTTP debug, or Playwright tracing flags.
    $info.Environment.Clear()
    foreach ($name in @('PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'COMSPEC',
            'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'APPDATA', 'LOCALAPPDATA', 'TEMP', 'TMP',
            'JAVA_HOME', 'PLAYWRIGHT_BROWSERS_PATH')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($null -ne $value) { $info.Environment[$name] = $value }
    }
    $info.Environment['VULCAN_MONITORING_ENABLED'] = 'false'
    $info.Environment['TELEGRAM_BOT_ENABLED'] = 'false'
    return $info
}

function Invoke-SmokeChild {
    param([Diagnostics.ProcessStartInfo]$Info, [byte[]]$InputBytes = @())
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $Info
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw 'Child could not start' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        try {
            $process.StandardInput.BaseStream.Write($InputBytes, 0, $InputBytes.Length)
            $process.StandardInput.Close()
        }
        finally { [Array]::Clear($InputBytes, 0, $InputBytes.Length) }
        if (-not $process.WaitForExit(300000)) { throw 'Child deadline expired' }
        [void]$stderr.GetAwaiter().GetResult() # Never forward third-party stderr.
        return [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.GetAwaiter().GetResult() }
    }
    finally {
        if ($started -and -not $process.HasExited) {
            $process.Kill($true) # Also close descendant Chromium/driver processes on timeout/cancel.
            [void]$process.WaitForExit(5000)
        }
        $process.Dispose()
    }
}

function Write-SmokeReport {
    param([string]$Output, [int]$ExitCode)
    $stages = 'PORTAL_VALIDATION|BROWSER_AUTH|SESSION_CAPTURE|SESSION_MATERIAL_RECONSTRUCTION|VERIFY_CACHE_REQUEST|VERIFY_CACHE_PARSE|VERIFY_SCHOOL_YEAR|VERIFY_TREE_REQUEST|VERIFY_TREE_PARSE|SESSION_SNAPSHOT|VERIFIED'
    $categories = 'SUCCESS|INVALID_INPUT|INVALID_CREDENTIALS|MFA_REQUIRED|CAPTCHA_REQUIRED|UNSUPPORTED_AUTH_FLOW|SESSION_AUTHENTICATION|TRANSIENT|PROTOCOL_FAILURE|HARNESS_FAILURE'
    $lines = @($Output -split '\r?\n' | Where-Object { $_.Length -gt 0 })
    if ($Output.Length -gt 8192 -or $lines.Count -lt 14 -or $lines.Count -gt 18) { throw 'Invalid diagnostic output' }
    $keys = [Collections.Generic.HashSet[string]]::new()
    foreach ($line in $lines) {
        $valid = $line -ceq 'REAL VULCAN SMOKE - LOCAL DEVELOPMENT ONLY' -or
            $line -cmatch "^stage\.($stages)=(PASS|FAIL|INCOMPLETE|NOT_REACHED)$" -or
            $line -cmatch '^http\.(VERIFY_CACHE_REQUEST|VERIFY_TREE_REQUEST)=(INFORMATIONAL|SUCCESS|REDIRECT|CLIENT_ERROR|SERVER_ERROR|OTHER),(JSON|HTML|OTHER),(true|false)$' -or
            $line -cmatch '^httpFailure=(AUTHENTICATION_REQUIRED|RATE_LIMITED|SERVER_ERROR|PERMANENT_HTTP|TRANSPORT_ERROR|SESSION_REDIRECT|UNEXPECTED_HTML)$' -or
            $line -cmatch '^cacheFailure=(PERIODS_SCHEMA|PERIOD_ID_SCHEMA|PERIOD_NUMBER_SCHEMA|PERIOD_START_SCHEMA|PERIOD_END_SCHEMA|PERIOD_START_TIME_FORMAT|PERIOD_END_TIME_FORMAT|PERIOD_START_TIME_ONLY|PERIOD_END_TIME_ONLY|PERIOD_NUMBER_RANGE|DUPLICATE_PERIOD_ID)$' -or
            $line -cmatch '^classCount=[0-9]{1,10}$' -or
            $line -cmatch "^category=($categories)$" -or
            $line -cmatch '^result=(SUCCESS|FAIL)$'
        if (-not $valid -or -not $keys.Add(($line -split '=', 2)[0])) { throw 'Invalid diagnostic output' }
    }
    foreach ($stage in ($stages -split '\|')) {
        if (-not $keys.Contains("stage.$stage")) { throw 'Incomplete diagnostic output' }
    }
    if (-not $keys.Contains('category') -or -not $keys.Contains('result') -or
        (($ExitCode -eq 0) -ne ($lines -ccontains 'result=SUCCESS'))) { throw 'Inconsistent diagnostic output' }
    foreach ($line in $lines) { Write-Host $line }
}

function Invoke-VulcanRealSmoke {
    param([switch]$Configure, [switch]$Run, [switch]$Clear, [switch]$Help)
    if ($Help) { Show-VulcanSmokeHelp; return 0 }
    $phase = 'SETUP'
    $payload = $null
    try {
        if (-not $IsWindows) { throw 'Windows required' }
        if (([int]$Configure.IsPresent + [int]$Run.IsPresent + [int]$Clear.IsPresent) -ne 1) {
            Show-VulcanSmokeHelp
            return 2
        }
        $root = Get-SmokeRepositoryRoot
        $secret = Join-Path $root '.dev/vulcan-real-smoke.dpapi'
        if ($Clear) {
            $phase = 'CLEAR'
            if (Test-Path -LiteralPath $secret -PathType Leaf) { Remove-Item -LiteralPath $secret -Force }
            Write-Host 'Smoke credential bundle cleared.'
            return 0
        }
        if ($null -eq ('System.Security.Cryptography.ProtectedData' -as [type])) {
            Add-Type -AssemblyName System.Security.Cryptography.ProtectedData
        }
        if ($Configure) {
            $phase = 'CONFIGURE'
            $portal = Read-Host 'VULCAN portal URL'
            $login = $null
            $password = $null
            try {
                $login = Read-Host 'VULCAN login' -AsSecureString
                $password = Read-Host 'VULCAN password' -AsSecureString
                $payload = New-SmokePayload -Portal $portal -Login $login -Password $password
                Protect-SmokePayload -Payload $payload -Path $secret
                Write-Host 'Smoke credential bundle protected with DPAPI CurrentUser.'
                return 0
            }
            finally {
                $portal = $null
                if ($null -ne $login) { $login.Dispose() }
                if ($null -ne $password) { $password.Dispose() }
            }
        }
        if (-not (Test-Path -LiteralPath $secret -PathType Leaf)) {
            Write-Host 'Configure the smoke credential bundle manually with -Configure first.'
            return 2
        }
        $phase = 'BUILD'
        # Compile the opt-in test-source main before decrypting. Maven never receives the payload.
        $build = New-SmokeProcessInfo -Executable (Join-Path $env:SYSTEMROOT 'System32/cmd.exe') -RepositoryRoot $root
        $build.Arguments = '/d /s /c "mvnw.cmd -q -ntp -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/vulcan-real-smoke-classpath.txt"'
        $compiled = Invoke-SmokeChild -Info $build
        if ($compiled.ExitCode -ne 0) { throw 'Build failed' }
        $classpath = [IO.File]::ReadAllText((Join-Path $root 'target/vulcan-real-smoke-classpath.txt')).Trim()
        $java = (Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        $driver = New-SmokeProcessInfo -Executable $java -RepositoryRoot $root
        $driver.ArgumentList.Add('-cp')
        $driver.ArgumentList.Add("target/test-classes;target/classes;$classpath")
        $driver.ArgumentList.Add('io.github.bohdankordon.vulcanschedulemonitor.devsmoke.VulcanRealSmoke')
        $driver.ArgumentList.Add('--authorized-local-smoke')
        $phase = 'INPUT'
        $payload = Unprotect-SmokePayload -Path $secret
        if ($payload.Length -gt 32768) { throw 'Invalid input size' }
        $phase = 'DRIVER'
        $result = Invoke-SmokeChild -Info $driver -InputBytes $payload
        Write-SmokeReport -Output $result.Output -ExitCode $result.ExitCode
        return $result.ExitCode
    }
    catch {
        Write-Host "smokeHarness=$phase result=FAIL" # Never render ErrorRecord/Exception/InputObject.
        return 2
    }
    finally {
        if ($null -ne $payload) { [Array]::Clear($payload, 0, $payload.Length) }
    }
}

# Dot-sourcing only defines functions for isolated synthetic tests; normal use dispatches one mode.
if ($MyInvocation.InvocationName -ne '.') {
    exit (Invoke-VulcanRealSmoke -Configure:$Configure -Run:$Run -Clear:$Clear -Help:$Help)
}
