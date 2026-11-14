# Builds Nuvio-Windows-x64-Setup.exe, the web installer that fetches the newest
# Windows MSI from GitHub Releases. It is compiled with the C# compiler that
# ships inside .NET Framework 4, so no SDK has to be installed on the runner.
param(
    [Parameter(Mandatory = $true)]
    [string] $Version,
    [string] $OutputDirectory = "build/windows-web-installer"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sourceDir = Join-Path $PSScriptRoot "web-installer"
# The same icon the MSI and the installed app use.
$iconPath = Join-Path $repoRoot "composeApp/src/desktopMain/resources/icons/nuvio-app-icon-transparent.ico"
$compiler = Join-Path $env:WINDIR "Microsoft.NET/Framework64/v4.0.30319/csc.exe"
if (-not (Test-Path $compiler)) {
    throw "The .NET Framework 4 C# compiler was not found at $compiler"
}

# File versions must be numeric, so 0.1.31-alpha becomes 0.1.31.0.
if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)') {
    throw "Version '$Version' does not start with major.minor.patch"
}
$fileVersion = "$($Matches[1]).$($Matches[2]).$($Matches[3]).0"

$outputDir = Join-Path $repoRoot $OutputDirectory
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$outputPath = Join-Path $outputDir "Nuvio-Windows-x64-Setup.exe"

$versionSource = Join-Path $outputDir "SetupVersion.cs"
@(
    "using System.Reflection;"
    "[assembly: AssemblyVersion(`"$fileVersion`")]"
    "[assembly: AssemblyFileVersion(`"$fileVersion`")]"
    "[assembly: AssemblyInformationalVersion(`"$Version`")]"
) | Set-Content -Path $versionSource -Encoding utf8

& $compiler `
    -nologo `
    -target:winexe `
    -platform:anycpu `
    -optimize+ `
    -warnaserror+ `
    "-out:$outputPath" `
    "-win32icon:$iconPath" `
    "-win32manifest:$(Join-Path $sourceDir 'NuvioSetup.manifest')" `
    -r:System.Web.Extensions.dll `
    -r:System.Windows.Forms.dll `
    -r:System.Drawing.dll `
    (Join-Path $sourceDir "NuvioSetup.cs") `
    $versionSource
if ($LASTEXITCODE -ne 0) {
    throw "Compiling the web installer failed with exit code $LASTEXITCODE."
}

Remove-Item $versionSource
Get-Item $outputPath | Format-List Name, Length, LastWriteTime
