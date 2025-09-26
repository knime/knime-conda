#!/usr/bin/env pwsh
param(
    [Parameter(Mandatory=$true)]
    [string]$KnimeRoot
)

# Validate the KNIME installation path
if (-not (Test-Path $KnimeRoot)) {
    Write-Error "Error: Path to the KNIME installation '$KnimeRoot' does not exist."
    exit 1
}

Write-Host "Using KNIME installation at: $KnimeRoot"

# Build the project
Write-Host "Building the project..."
mvn clean package
if ($LASTEXITCODE -ne 0) {
    Write-Error "Maven build failed"
    exit 1
}

# Create a temporary directory for the Eclipse installation
$DEST = New-TemporaryFile | ForEach-Object { Remove-Item $_; New-Item -ItemType Directory -Path $_ }
Write-Host "Created temporary directory: $DEST"

# Find the Eclipse launcher jar
$LauncherJar = Get-ChildItem -Path "$KnimeRoot\plugins" -Name "org.eclipse.equinox.launcher_*.jar" | Select-Object -First 1
if (-not $LauncherJar) {
    Write-Error "Could not find Eclipse launcher jar in $KnimeRoot\plugins"
    exit 1
}
$LauncherPath = Join-Path "$KnimeRoot\plugins" $LauncherJar

Write-Host "Using launcher: $LauncherPath"

# Use the p2 director to create a minimal Eclipse installation
Write-Host "Creating minimal Eclipse installation..."
java -jar "$LauncherPath" `
    -application org.eclipse.equinox.p2.director `
    -nosplash -consolelog `
    -repository 'https://jenkins.devops.knime.com/p2/knime/composites/master' `
    -installIU org.knime.minimal.product `
    -destination "$DEST"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to create minimal Eclipse installation"
    Remove-Item -Recurse -Force $DEST
    exit 1
}

# Find the launcher in the destination
$DestLauncherJar = Get-ChildItem -Path "$DEST\plugins" -Name "org.eclipse.equinox.launcher_*.jar" | Select-Object -First 1
if (-not $DestLauncherJar) {
    Write-Error "Could not find Eclipse launcher jar in destination"
    Remove-Item -Recurse -Force $DEST
    exit 1
}
$DestLauncherPath = Join-Path "$DEST\plugins" $DestLauncherJar

# Get current directory for the repository URL
$CurrentDir = Get-Location
$RepoPath = Join-Path $CurrentDir "org.knime.update.conda\target\repository"

# Install the test extension from the local build
Write-Host "Installing test extension..."
java -jar "$DestLauncherPath" `
    -application org.eclipse.equinox.p2.director `
    -nosplash -consolelog `
    -repository "file:///$($RepoPath -replace '\\', '/'),https://jenkins.devops.knime.com/p2-browse/knime/composites/master" `
    -installIU org.knime.features.conda.envinstall.testext.feature.group,org.knime.features.python3.scripting.feature.group

if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to install test extension"
    Remove-Item -Recurse -Force $DEST
    exit 1
}

Write-Host "Installation completed successfully!"

# Ask if the user wants to delete the temporary directory
$answer = Read-Host "Do you want to delete the temporary directory $DEST? (y/n)"
if ($answer -eq "y" -or $answer -eq "Y") {
    Remove-Item -Recurse -Force $DEST
    Write-Host "Temporary directory $DEST deleted."
} else {
    Write-Host "Temporary directory $DEST not deleted."
}