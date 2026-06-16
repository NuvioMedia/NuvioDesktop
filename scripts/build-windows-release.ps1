param(
    [string]$JavaHome = "C:\Program Files\Amazon Corretto\jdk21.0.4_7",
    [string]$VsDevCmd = "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\Common7\Tools\VsDevCmd.bat",
    [switch]$NoInstaller,
    [switch]$KeepRunningNuvio
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

Write-Host "== Nuvio Windows release build ==" -ForegroundColor Cyan
Write-Host "Repo: $RepoRoot"

if (!(Test-Path ".\gradlew.bat")) {
    throw "gradlew.bat not found. Are you running this from the Nuvio repo?"
}

if (!(Test-Path $JavaHome)) {
    throw "JAVA_HOME path not found: $JavaHome"
}

if (!(Test-Path $VsDevCmd)) {
    Write-Warning "Visual Studio DevCmd not found at: $VsDevCmd"
    Write-Warning "Native bridge compilation may fail. Set -VsDevCmd to correct path."
}

$env:JAVA_HOME = $JavaHome

$VersionFile = Join-Path $RepoRoot "iosApp\Configuration\Version.xcconfig"

if (!(Test-Path $VersionFile)) {
    throw "Version file not found: $VersionFile"
}

$VersionLines = Get-Content $VersionFile

$MarketingVersion = ($VersionLines | Where-Object { $_ -match '^MARKETING_VERSION=' }) -replace '^MARKETING_VERSION=', ''
$CurrentProjectVersion = ($VersionLines | Where-Object { $_ -match '^CURRENT_PROJECT_VERSION=' }) -replace '^CURRENT_PROJECT_VERSION=', ''

$MarketingVersion = $MarketingVersion.Trim()
$CurrentProjectVersion = $CurrentProjectVersion.Trim()

if ([string]::IsNullOrWhiteSpace($MarketingVersion)) {
    throw "MARKETING_VERSION not found in $VersionFile"
}

if ([string]::IsNullOrWhiteSpace($CurrentProjectVersion)) {
    throw "CURRENT_PROJECT_VERSION not found in $VersionFile"
}

$PortableZipName = "Nuvio-$MarketingVersion-x64-portable.zip"
$ReleaseDir = Join-Path $RepoRoot "release-assets"
$PortableZipPath = Join-Path $ReleaseDir $PortableZipName

Write-Host "Version: $MarketingVersion build $CurrentProjectVersion" -ForegroundColor Cyan
Write-Host "Portable ZIP: $PortableZipName" -ForegroundColor Cyan

if (!$KeepRunningNuvio) {
    Write-Host "Stopping running Nuvio.exe processes..." -ForegroundColor Yellow
    Get-Process Nuvio -ErrorAction SilentlyContinue | Stop-Process -Force
}

New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null

Write-Host "Stopping Gradle daemon..." -ForegroundColor Yellow
.\gradlew.bat --stop

# Step 1: Build release distributable (creates Nuvio.exe with native DLLs)
Write-Host "Step 1: Building release distributable..." -ForegroundColor Green
if (Test-Path $VsDevCmd) {
    cmd.exe /c "call `"$VsDevCmd`" -arch=x64 -host_arch=x64 && .\gradlew.bat :composeApp:createReleaseDistributable --no-configuration-cache"
} else {
    .\gradlew.bat :composeApp:createReleaseDistributable --no-configuration-cache
}

# Step 2: Build Inno Setup installer (optional)
if (!$NoInstaller) {
    Write-Host "Step 2: Building Inno installer..." -ForegroundColor Green
    $InnoExitCode = 0
    if (Test-Path $VsDevCmd) {
        cmd.exe /c "call `"$VsDevCmd`" -arch=x64 -host_arch=x64 && .\gradlew.bat :composeApp:packageReleaseInnoExe --no-configuration-cache"
        $InnoExitCode = $LASTEXITCODE
    } else {
        .\gradlew.bat :composeApp:packageReleaseInnoExe --no-configuration-cache
        $InnoExitCode = $LASTEXITCODE
    }
    if ($InnoExitCode -eq 0) {
        Write-Host "Inno installer built successfully!" -ForegroundColor Green
    } else {
        Write-Warning "Inno installer build skipped (Inno Setup may not be installed)."
    }
} else {
    Write-Host "Step 2: Skipping Inno installer (-NoInstaller provided)" -ForegroundColor Yellow
}

# Step 3: Create portable ZIP
Write-Host "Step 3: Creating portable ZIP..." -ForegroundColor Green
$PortableDir = Join-Path $RepoRoot "composeApp\build\compose\binaries\main-release\app\Nuvio"

if (!(Test-Path $PortableDir)) {
    throw "Portable distributable folder not found: $PortableDir"
}

if (Test-Path $PortableZipPath) {
    Write-Host "Removing existing ZIP: $PortableZipPath" -ForegroundColor Yellow
    Remove-Item $PortableZipPath -Force
}

# Build the portable ZIP via Gradle
$GradleBuildOk = $false
if (Test-Path $VsDevCmd) {
    cmd.exe /c "call `"$VsDevCmd`" -arch=x64 -host_arch=x64 && .\gradlew.bat :composeApp:packageReleasePortable --no-configuration-cache"
    $GradleBuildOk = ($LASTEXITCODE -eq 0)
} else {
    .\gradlew.bat :composeApp:packageReleasePortable --no-configuration-cache
    $GradleBuildOk = ($LASTEXITCODE -eq 0)
}

$GradlePortableZip = Join-Path $RepoRoot "composeApp\build\compose\binaries\main-release\portable\Nuvio-$MarketingVersion-x86_64-portable.zip"

if ($GradleBuildOk -and (Test-Path $GradlePortableZip)) {
    Write-Host "Copying portable ZIP from Gradle output to release-assets..." -ForegroundColor Green
    Copy-Item $GradlePortableZip -Destination $PortableZipPath
} else {
    Write-Host "Creating portable ZIP manually..." -ForegroundColor Yellow
    # Create portable marker
    $PortableMarkerPath = Join-Path $PortableDir "Nuvio.portable"
    Set-Content -Path $PortableMarkerPath -Value "" -NoNewline
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($PortableDir, $PortableZipPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)
    Remove-Item $PortableMarkerPath -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "== Build outputs ==" -ForegroundColor Cyan

Write-Host ""
Write-Host "Portable ZIP:" -ForegroundColor Cyan
Get-Item $PortableZipPath | Select-Object FullName, Length, LastWriteTime | Format-List

Write-Host ""
Write-Host "Distributable EXE:" -ForegroundColor Cyan
Get-ChildItem "composeApp\build\compose\binaries\main-release\app\Nuvio" -Recurse -File -Filter "Nuvio.exe" |
    Sort-Object LastWriteTime -Descending |
    Select-Object FullName, Length, LastWriteTime -First 10 |
    Format-Table -AutoSize

if (!$NoInstaller) {
    Write-Host ""
    Write-Host "Installer (Inno/jpackage):" -ForegroundColor Cyan
    Get-ChildItem "composeApp\build\compose\binaries\main-release\inno" -Recurse -File -Filter "*.exe" |
        Sort-Object LastWriteTime -Descending |
        Select-Object FullName, Length, LastWriteTime -First 10 |
        Format-Table -AutoSize
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
