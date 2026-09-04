[CmdletBinding()]
param(
    [switch]$EnableMonitoring,
    [switch]$ResetDevState,
    [switch]$ReconfigureTelegram,
    [switch]$SkipBrowserInstall,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$PostgresContainerName = "vulcan-monitor-postgres"
$PostgresVolumeName = "vulcan-monitor-postgres-data"
$PostgresImage = "postgres:18.6"
$PostgresDatabase = "vulcan_monitor"
$PostgresUsername = "vulcan"
$PostgresPassword = "vulcan-local-dev-only"
$PostgresHostPort = 54329
$ApplicationPort = 8080
$DockerExecutable = $null

function Show-DevHelp {
    @'
Vulcan Schedule Monitor local development runner

Usage:
  .\scripts\dev.ps1                         Start connection/catalog smoke mode
  .\scripts\dev.ps1 -EnableMonitoring       Start with automatic monitoring enabled
  .\scripts\dev.ps1 -ReconfigureTelegram   Replace the protected Telegram token
  .\scripts\dev.ps1 -ResetDevState          Delete the local database and protected secrets
  .\scripts\dev.ps1 -SkipBrowserInstall     Skip the idempotent Chromium install step
  .\scripts\dev.ps1 -Help                   Show this help without performing any setup
'@ | Write-Host
}

function Get-RepositoryRoot {
    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
}

function Assert-Windows {
    if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
        throw "scripts/dev.ps1 is a Windows-only local development runner."
    }
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @()
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = $ArgumentList -join " "
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "start failed"
        }
        $standardOutput = $process.StandardOutput.ReadToEndAsync()
        $standardError = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            StandardOutput = $standardOutput.GetAwaiter().GetResult()
            StandardError = $standardError.GetAwaiter().GetResult()
        }
    }
    catch {
        throw "A required local command could not be started."
    }
    finally {
        $process.Dispose()
    }
}

function Get-JavaMajorVersion {
    param([Parameter(Mandatory)][string]$VersionText)

    $match = [regex]::Match(
        $VersionText,
        '(?im)^\s*(?:openjdk|java)\s+version\s+"(?<major>\d+)(?=[.\-"])')
    if (-not $match.Success) {
        return $null
    }
    return [int]$match.Groups["major"].Value
}

function Assert-Java21 {
    $javaCommand = Get-Command java -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $javaCommand) {
        throw "Java 21 is required. Install a Java 21 JDK and run scripts/dev.ps1 again."
    }

    try {
        $probe = Invoke-NativeCapture -FilePath $javaCommand.Source -ArgumentList @("-version")
    }
    catch {
        throw "Java could not be started. Install a working Java 21 JDK and run scripts/dev.ps1 again."
    }
    if ($probe.ExitCode -ne 0) {
        throw "Java could not be started. Install a working Java 21 JDK and run scripts/dev.ps1 again."
    }

    $versionText = $probe.StandardOutput + [Environment]::NewLine + $probe.StandardError
    if ((Get-JavaMajorVersion -VersionText $versionText) -ne 21) {
        throw "Java 21 is required. Select a Java 21 JDK and run scripts/dev.ps1 again."
    }
}

function Assert-Docker {
    $dockerCommand = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $dockerCommand) {
        throw "Docker is required. Install Docker Desktop and run scripts/dev.ps1 again."
    }

    $script:DockerExecutable = $dockerCommand.Source
    $probe = Invoke-NativeCapture -FilePath $script:DockerExecutable -ArgumentList @("info")
    if ($probe.ExitCode -ne 0) {
        throw "Start Docker Desktop and run scripts/dev.ps1 again."
    }
}

function Test-DockerContainerExists {
    param([Parameter(Mandatory)][string]$Name)

    $probe = Invoke-NativeCapture `
        -FilePath $script:DockerExecutable `
        -ArgumentList @("container", "inspect", $Name)
    return $probe.ExitCode -eq 0
}

function Test-DockerVolumeExists {
    param([Parameter(Mandatory)][string]$Name)

    $probe = Invoke-NativeCapture `
        -FilePath $script:DockerExecutable `
        -ArgumentList @("volume", "inspect", $Name)
    return $probe.ExitCode -eq 0
}

function Invoke-DockerQuietly {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$FailureMessage
    )

    $probe = Invoke-NativeCapture `
        -FilePath $script:DockerExecutable `
        -ArgumentList $Arguments
    if ($probe.ExitCode -ne 0) {
        throw $FailureMessage
    }
}

function Ensure-DevDirectory {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $Path)
    }
}

function Assert-DpapiAvailable {
    try {
        if ($null -eq ("System.Security.Cryptography.ProtectedData" -as [type])) {
            Add-Type -AssemblyName System.Security
        }
        [void][System.Security.Cryptography.ProtectedData]
        [void][System.Security.Cryptography.DataProtectionScope]
    }
    catch {
        throw "Windows DPAPI is unavailable for the current PowerShell runtime."
    }
}

function Protect-DevSecret {
    param(
        [Parameter(Mandatory)][string]$PlainText,
        [Parameter(Mandatory)][string]$Path
    )

    $plainBytes = $null
    $protectedBytes = $null
    $temporaryPath = "$Path.tmp-$([guid]::NewGuid().ToString('N'))"
    try {
        $plainBytes = [System.Text.Encoding]::UTF8.GetBytes($PlainText)
        $protectedBytes = [System.Security.Cryptography.ProtectedData]::Protect(
            $plainBytes,
            $null,
            [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
        $encoded = [Convert]::ToBase64String($protectedBytes)
        [System.IO.File]::WriteAllText(
            $temporaryPath,
            $encoded,
            [System.Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
    }
    catch {
        throw "Could not protect a local development secret with Windows DPAPI."
    }
    finally {
        if ($null -ne $plainBytes) {
            [Array]::Clear($plainBytes, 0, $plainBytes.Length)
        }
        if ($null -ne $protectedBytes) {
            [Array]::Clear($protectedBytes, 0, $protectedBytes.Length)
        }
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function Unprotect-DevSecret {
    param([Parameter(Mandatory)][string]$Path)

    $protectedBytes = $null
    $plainBytes = $null
    try {
        $encoded = [System.IO.File]::ReadAllText($Path).Trim()
        $protectedBytes = [Convert]::FromBase64String($encoded)
        $plainBytes = [System.Security.Cryptography.ProtectedData]::Unprotect(
            $protectedBytes,
            $null,
            [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
        return [System.Text.Encoding]::UTF8.GetString($plainBytes)
    }
    catch {
        throw "A protected local development secret cannot be decrypted by this Windows user. Run scripts/dev.ps1 -ResetDevState only if a fresh local environment is intended."
    }
    finally {
        if ($null -ne $protectedBytes) {
            [Array]::Clear($protectedBytes, 0, $protectedBytes.Length)
        }
        if ($null -ne $plainBytes) {
            [Array]::Clear($plainBytes, 0, $plainBytes.Length)
        }
    }
}

function Get-OrCreateMasterKey {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][bool]$DatabaseStateExists
    )

    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $masterKey = Unprotect-DevSecret -Path $Path
        $decoded = $null
        try {
            $decoded = [Convert]::FromBase64String($masterKey)
            if ($decoded.Length -ne 32) {
                throw "invalid length"
            }
        }
        catch {
            throw "The protected local master key is invalid. Run scripts/dev.ps1 -ResetDevState only if a fresh local environment is intended."
        }
        finally {
            if ($null -ne $decoded) {
                [Array]::Clear($decoded, 0, $decoded.Length)
            }
        }
        return $masterKey
    }

    if ($DatabaseStateExists) {
        throw "Local PostgreSQL state exists but its protected master key is missing. Run scripts/dev.ps1 -ResetDevState only if deleting that state is intended."
    }

    $randomBytes = [byte[]]::new(32)
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($randomBytes)
        $masterKey = [Convert]::ToBase64String($randomBytes)
        Protect-DevSecret -PlainText $masterKey -Path $Path
        Write-Host "Created local development encryption key."
        return $masterKey
    }
    finally {
        $generator.Dispose()
        [Array]::Clear($randomBytes, 0, $randomBytes.Length)
    }
}

function Save-TelegramToken {
    param([Parameter(Mandatory)][string]$Path)

    $secureToken = Read-Host "Telegram bot token" -AsSecureString
    $pointer = [IntPtr]::Zero
    $plainToken = $null
    try {
        $pointer = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
        $plainToken = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        if ([string]::IsNullOrWhiteSpace($plainToken)) {
            throw "Telegram bot token cannot be empty."
        }
        Protect-DevSecret -PlainText $plainToken -Path $Path
    }
    finally {
        $plainToken = $null
        if ($pointer -ne [IntPtr]::Zero) {
            [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
        $secureToken.Dispose()
    }
}

function Get-TelegramToken {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][bool]$Reconfigure
    )

    if ($Reconfigure -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Save-TelegramToken -Path $Path
        Write-Host "Telegram token stored using Windows DPAPI."
    }
    return Unprotect-DevSecret -Path $Path
}

function Assert-PortAvailable {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$Purpose
    )

    $inUse = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
        Where-Object { $_.Port -eq $Port } |
        Select-Object -First 1
    if ($null -ne $inUse) {
        throw "Local TCP port $Port is already occupied. Stop the conflicting $Purpose process and run scripts/dev.ps1 again."
    }
}

function Get-PostgresContainerDetails {
    param([Parameter(Mandatory)][string]$Name)

    $probe = Invoke-NativeCapture `
        -FilePath $script:DockerExecutable `
        -ArgumentList @("container", "inspect", $Name)
    if ($probe.ExitCode -ne 0) {
        throw "The local PostgreSQL container could not be inspected."
    }
    return ($probe.StandardOutput | ConvertFrom-Json)
}

function Assert-PostgresContainerCompatible {
    param(
        [Parameter(Mandatory)]$Details,
        [Parameter(Mandatory)][string]$VolumeName
    )

    $portBindings = @()
    if ($null -ne $Details.HostConfig.PortBindings) {
        $bindingProperty = $Details.HostConfig.PortBindings.PSObject.Properties["5432/tcp"]
        if ($null -ne $bindingProperty) {
            $portBindings = @($bindingProperty.Value)
        }
    }
    $volumeMounts = @($Details.Mounts | Where-Object {
        $_.Type -eq "volume" -and
        $_.Name -eq $VolumeName -and
        $_.Destination -eq "/var/lib/postgresql"
    })
    $requiredEnvironment = @(
        "POSTGRES_DB=$PostgresDatabase",
        "POSTGRES_USER=$PostgresUsername",
        "POSTGRES_PASSWORD=$PostgresPassword"
    )
    $environmentMatches = $true
    foreach ($entry in $requiredEnvironment) {
        if ($Details.Config.Env -notcontains $entry) {
            $environmentMatches = $false
        }
    }

    $compatible =
        $Details.Config.Image -eq $PostgresImage -and
        $portBindings.Count -eq 1 -and
        $portBindings[0].HostIp -eq "127.0.0.1" -and
        $portBindings[0].HostPort -eq [string]$PostgresHostPort -and
        $volumeMounts.Count -eq 1 -and
        $environmentMatches
    if (-not $compatible) {
        throw "Container '$PostgresContainerName' exists with incompatible image, port, volume, or database settings. It was not changed. Use -ResetDevState only if deleting its local data is intended."
    }
}

function Ensure-Postgres {
    if (Test-DockerContainerExists -Name $PostgresContainerName) {
        $details = Get-PostgresContainerDetails -Name $PostgresContainerName
        Assert-PostgresContainerCompatible -Details $details -VolumeName $PostgresVolumeName
        if (-not $details.State.Running) {
            Assert-PortAvailable -Port $PostgresHostPort -Purpose "database"
            Invoke-DockerQuietly -Arguments @("start", $PostgresContainerName) `
                -FailureMessage "The local PostgreSQL container could not be started."
        }
        return
    }

    Assert-PortAvailable -Port $PostgresHostPort -Purpose "database"
    if (-not (Test-DockerVolumeExists -Name $PostgresVolumeName)) {
        Invoke-DockerQuietly -Arguments @("volume", "create", $PostgresVolumeName) `
            -FailureMessage "The local PostgreSQL volume could not be created."
    }
    Invoke-DockerQuietly -Arguments @(
        "run",
        "--detach",
        "--name", $PostgresContainerName,
        "--publish", "127.0.0.1:${PostgresHostPort}:5432",
        "--volume", "${PostgresVolumeName}:/var/lib/postgresql",
        "--env", "POSTGRES_DB=$PostgresDatabase",
        "--env", "POSTGRES_USER=$PostgresUsername",
        "--env", "POSTGRES_PASSWORD=$PostgresPassword",
        $PostgresImage
    ) -FailureMessage "The local PostgreSQL container could not be created."
}

function Wait-Postgres {
    $timeout = [System.Diagnostics.Stopwatch]::StartNew()
    while ($timeout.Elapsed -lt [TimeSpan]::FromSeconds(60)) {
        $probe = Invoke-NativeCapture `
            -FilePath $script:DockerExecutable `
            -ArgumentList @(
                "exec",
                $PostgresContainerName,
                "pg_isready",
                "--username", $PostgresUsername,
                "--dbname", $PostgresDatabase)
        if ($probe.ExitCode -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "PostgreSQL did not become ready within 60 seconds. Check Docker Desktop and the '$PostgresContainerName' logs."
}

function Reset-LocalDevState {
    param(
        [Parameter(Mandatory)][string]$DevDirectory,
        [Parameter(Mandatory)][string[]]$SecretPaths
    )

    Write-Host "This will delete the local Vulcan Schedule Monitor development database and local protected secrets."
    $confirmation = Read-Host "Type RESET to continue"
    if ($confirmation -cne "RESET") {
        Write-Host "Reset cancelled."
        return $false
    }

    if (Test-DockerContainerExists -Name $PostgresContainerName) {
        Invoke-DockerQuietly -Arguments @("rm", "--force", $PostgresContainerName) `
            -FailureMessage "The local PostgreSQL container could not be removed."
    }
    if (Test-DockerVolumeExists -Name $PostgresVolumeName) {
        Invoke-DockerQuietly -Arguments @("volume", "rm", $PostgresVolumeName) `
            -FailureMessage "The local PostgreSQL volume could not be removed."
    }
    foreach ($path in $SecretPaths) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Remove-Item -LiteralPath $path -Force
        }
    }
    if ((Test-Path -LiteralPath $DevDirectory -PathType Container) -and
        $null -eq (Get-ChildItem -LiteralPath $DevDirectory -Force | Select-Object -First 1)) {
        Remove-Item -LiteralPath $DevDirectory
    }
    Write-Host "Local development state was reset. Run .\scripts\dev.ps1 to provision a fresh environment."
    return $true
}

function Ensure-PlaywrightChromium {
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][bool]$Skip
    )

    if ($Skip) {
        Write-Host "Playwright Chromium installation skipped."
        return
    }

    Write-Host "Ensuring Playwright Chromium is installed..."
    Push-Location $RepositoryRoot
    try {
        & (Join-Path $RepositoryRoot "mvnw.cmd") `
            -Dexec.mainClass=com.microsoft.playwright.CLI `
            '-Dexec.args=install chromium' `
            exec:java
        if ($LASTEXITCODE -ne 0) {
            throw "Playwright Chromium installation failed."
        }
    }
    finally {
        Pop-Location
    }
}

function Start-Application {
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$MasterKey,
        [Parameter(Mandatory)][string]$TelegramToken,
        [Parameter(Mandatory)][bool]$MonitoringEnabled
    )

    $mavenWrapper = Join-Path $RepositoryRoot "mvnw.cmd"
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $env:ComSpec
    $startInfo.Arguments = '/d /s /c ""{0}" spring-boot:run"' -f $mavenWrapper
    $startInfo.WorkingDirectory = $RepositoryRoot
    $startInfo.UseShellExecute = $false
    $startInfo.EnvironmentVariables["SERVER_PORT"] = [string]$ApplicationPort
    $startInfo.EnvironmentVariables["SPRING_DATASOURCE_URL"] =
        "jdbc:postgresql://localhost:$PostgresHostPort/$PostgresDatabase"
    $startInfo.EnvironmentVariables["SPRING_DATASOURCE_USERNAME"] = $PostgresUsername
    $startInfo.EnvironmentVariables["SPRING_DATASOURCE_PASSWORD"] = $PostgresPassword
    $startInfo.EnvironmentVariables["VULCAN_CONNECTION_ENABLED"] = "true"
    $startInfo.EnvironmentVariables["VULCAN_CONNECTION_PUBLIC_BASE_URL"] = "http://localhost:8080"
    $startInfo.EnvironmentVariables["VULCAN_MASTER_KEY"] = $MasterKey
    $startInfo.EnvironmentVariables["TELEGRAM_BOT_ENABLED"] = "true"
    $startInfo.EnvironmentVariables["TELEGRAM_BOT_TOKEN"] = $TelegramToken
    $startInfo.EnvironmentVariables["VULCAN_MONITORING_ENABLED"] = $MonitoringEnabled.ToString().ToLowerInvariant()

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "The Spring Boot application process could not be started."
        }
        return $process
    }
    catch {
        $process.Dispose()
        throw "The Spring Boot application process could not be started."
    }
    finally {
        [void]$startInfo.EnvironmentVariables.Remove("VULCAN_MASTER_KEY")
        [void]$startInfo.EnvironmentVariables.Remove("TELEGRAM_BOT_TOKEN")
        $MasterKey = $null
        $TelegramToken = $null
    }
}

if ($Help) {
    Show-DevHelp
    exit 0
}

try {
    Write-Host "LOCAL DEVELOPMENT ONLY" -ForegroundColor Yellow
    Write-Host "This runner uses a loopback development database and is not a production deployment model."

    Assert-Windows
    $repositoryRoot = Get-RepositoryRoot
    Assert-Java21
    Assert-Docker

    $devDirectory = Join-Path $repositoryRoot ".dev"
    $masterKeyPath = Join-Path $devDirectory "vulcan-master-key.dpapi"
    $telegramTokenPath = Join-Path $devDirectory "telegram-bot-token.dpapi"

    if ($ResetDevState) {
        [void](Reset-LocalDevState `
            -DevDirectory $devDirectory `
            -SecretPaths @($masterKeyPath, $telegramTokenPath))
        exit 0
    }

    Assert-DpapiAvailable
    $databaseStateExists =
        (Test-DockerContainerExists -Name $PostgresContainerName) -or
        (Test-DockerVolumeExists -Name $PostgresVolumeName)
    Ensure-DevDirectory -Path $devDirectory
    $masterKeyExisted = Test-Path -LiteralPath $masterKeyPath -PathType Leaf
    $telegramTokenExisted = Test-Path -LiteralPath $telegramTokenPath -PathType Leaf
    $masterKey = Get-OrCreateMasterKey `
        -Path $masterKeyPath `
        -DatabaseStateExists $databaseStateExists
    $telegramToken = Get-TelegramToken `
        -Path $telegramTokenPath `
        -Reconfigure $ReconfigureTelegram.IsPresent
    if ($masterKeyExisted -and $telegramTokenExisted -and -not $ReconfigureTelegram) {
        Write-Host "Using existing protected local development secrets."
    }

    Ensure-Postgres
    Wait-Postgres
    Assert-PortAvailable -Port $ApplicationPort -Purpose "application"
    Ensure-PlaywrightChromium `
        -RepositoryRoot $repositoryRoot `
        -Skip $SkipBrowserInstall.IsPresent

    Write-Host ""
    Write-Host "Vulcan Schedule Monitor local development"
    Write-Host "PostgreSQL: running"
    Write-Host "Database: localhost:$PostgresHostPort/$PostgresDatabase"
    Write-Host "Telegram: enabled"
    Write-Host "VULCAN connection: enabled"
    if ($EnableMonitoring) {
        Write-Host "Monitoring: ENABLED" -ForegroundColor Yellow
        Write-Host "Automatic VULCAN monitoring is enabled for subscribed classes." -ForegroundColor Yellow
    }
    else {
        Write-Host "Monitoring: disabled"
    }
    Write-Host "Application: http://localhost:8080"
    Write-Host "Starting application..."

    $application = Start-Application `
        -RepositoryRoot $repositoryRoot `
        -MasterKey $masterKey `
        -TelegramToken $telegramToken `
        -MonitoringEnabled $EnableMonitoring.IsPresent
    $masterKey = $null
    $telegramToken = $null
    $application.WaitForExit()
    $applicationExitCode = $application.ExitCode
    $application.Dispose()
    if ($applicationExitCode -ne 0) {
        throw "The Spring Boot application exited with code $applicationExitCode."
    }
}
catch {
    Write-Host "Error: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
