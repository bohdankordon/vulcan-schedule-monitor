# B-only experiment. No raw-HAR access or child launch unless explicitly invoked with -Run.
[CmdletBinding()]
param([switch]$Run, [string]$HarPath = '.dev/vulcan-schedule-native.har')

$acceptRunRequested = $Run.IsPresent
$acceptSourcePath = $HarPath
# Preserve arguments across dot-sourcing; reuse the reviewed extraction/child boundary.
. (Join-Path $PSScriptRoot 'vulcan-native-session-java-baseline.ps1')

if ($MyInvocation.InvocationName -ne '.') {
    $items = @(Invoke-NativeSessionBaseline $acceptRunRequested $acceptSourcePath 'ACCEPT_STAR_STAR')
    Write-Output $items[0]
    exit $items[-1]
}
