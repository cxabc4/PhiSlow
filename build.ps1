param(
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = 'C:\Program Files\Microsoft\jdk-17.0.14.7-hotspot',
    [switch]$WithTests,
    [ValidateSet('Public', 'Full', 'Update', 'Both')]
    [string]$Variant = 'Public'
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$buildRoot = Join-Path $projectRoot 'build'
$buildTools = Join-Path $SdkRoot 'build-tools\36.1.0'
$androidJar = Join-Path $SdkRoot 'platforms\android-36.1\android.jar'
$java = Join-Path $JavaHome 'bin\java.exe'
$javac = Join-Path $JavaHome 'bin\javac.exe'
$jar = Join-Path $JavaHome 'bin\jar.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$aapt = Join-Path $buildTools 'aapt.exe'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$d8Jar = Join-Path $buildTools 'lib\d8.jar'
$apksignerJar = Join-Path $buildTools 'lib\apksigner.jar'
$keystore = Join-Path $buildRoot 'debug.keystore'
. (Join-Path $projectRoot 'tools\normalize_zip_paths.ps1')

foreach ($path in @($androidJar, $java, $javac, $jar, $keytool, $aapt2, $aapt, $zipalign, $d8Jar, $apksignerJar)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required build tool is missing: $path"
    }
}

function Invoke-Tool {
    param([string]$Executable, [string[]]$Arguments)
    # A tool writing to stderr is not by itself a failure: javac notes deprecated API use there on
    # a perfectly clean compile, and this script would otherwise abort a build that had succeeded.
    # The exit code is the only thing that says whether the step worked.
    $ErrorActionPreference = 'Continue'
    try {
        & $Executable @Arguments
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = 'Stop'
    }
    if ($code -ne 0) {
        throw "Build tool failed with exit code ${code}: $Executable"
    }
}

New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $keystore)) {
    Invoke-Tool $keytool @(
        '-genkeypair', '-keystore', $keystore,
        '-storepass', 'android', '-keypass', 'android',
        '-alias', 'phislow-debug', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10000', '-dname', 'CN=PhiSlow Debug,O=PhiSlow,C=CN'
    )
}

function Build-Apk {
    param(
        [string]$Name,
        [string]$SourceDirectory,
        [string]$Manifest,
        [string]$Destination,
        [string]$ResourcesDirectory = '',
        [string]$AssetsDirectory = '',
        [string]$AdditionalAssetsDirectory = '',
        [string]$AdditionalClassPath = ''
    )

    if (-not (Test-Path -LiteralPath $Manifest -PathType Leaf)) {
        throw "Android manifest is missing: $Manifest"
    }
    $sourceFiles = @(Get-ChildItem -LiteralPath $SourceDirectory -Filter '*.java' -File -Recurse |
        Sort-Object FullName | Select-Object -ExpandProperty FullName)
    if ($sourceFiles.Count -eq 0) {
        throw "No Java sources found in: $SourceDirectory"
    }

    $workDirectory = [System.IO.Path]::GetFullPath((Join-Path $buildRoot $Name))
    $allowedPrefix = [System.IO.Path]::GetFullPath($buildRoot) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $workDirectory.StartsWith($allowedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Build directory must be inside: $buildRoot"
    }
    if (Test-Path -LiteralPath $workDirectory) {
        Remove-Item -LiteralPath $workDirectory -Recurse -Force
    }
    $generatedDirectory = Join-Path $workDirectory 'generated'
    $classesDirectory = Join-Path $workDirectory 'classes'
    $dexDirectory = Join-Path $workDirectory 'dex'
    foreach ($directory in @($generatedDirectory, $classesDirectory, $dexDirectory)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }

    Write-Host "Building $Name..."
    $unsignedApk = Join-Path $workDirectory 'unsigned.apk'
    $linkArguments = @('link', '-I', $androidJar, '--manifest', $Manifest,
        '--java', $generatedDirectory, '-o', $unsignedApk)
    if ($ResourcesDirectory) {
        $resourcesZip = Join-Path $workDirectory 'resources.zip'
        Invoke-Tool $aapt2 @('compile', '--dir', $ResourcesDirectory, '-o', $resourcesZip)
        $linkArguments += @($resourcesZip)
    }
    if ($AssetsDirectory) {
        $linkArguments += @('-A', $AssetsDirectory, '-0', 'wav', '-0', 'ogg')
    }
    if ($AdditionalAssetsDirectory) {
        $linkArguments += @('-A', $AdditionalAssetsDirectory)
    }
    Invoke-Tool $aapt2 $linkArguments
    Normalize-ZipPaths $unsignedApk

    $sourceFiles += @(Get-ChildItem -LiteralPath $generatedDirectory -Filter '*.java' -File -Recurse |
        Sort-Object FullName | Select-Object -ExpandProperty FullName)
    $compileClassPath = $androidJar
    if ($AdditionalClassPath) {
        $compileClassPath += ';' + $AdditionalClassPath
    }
    $compileArguments = @('--release', '8', '-encoding', 'UTF-8',
        '-classpath', $compileClassPath, '-d', $classesDirectory) + $sourceFiles
    Invoke-Tool $javac $compileArguments

    $classesJar = Join-Path $workDirectory 'classes.jar'
    Invoke-Tool $jar @('cf', $classesJar, '-C', $classesDirectory, '.')
    $dexArguments = @('-cp', $d8Jar, 'com.android.tools.r8.D8',
        '--lib', $androidJar, '--min-api', '26', '--output', $dexDirectory)
    if ($AdditionalClassPath) {
        $dexArguments += @('--classpath', $AdditionalClassPath)
    }
    $dexArguments += $classesJar
    Invoke-Tool $java $dexArguments

    $dexFiles = @(Get-ChildItem -LiteralPath $dexDirectory -Filter '*.dex' -File |
        Sort-Object Name | Select-Object -ExpandProperty Name)
    if ($dexFiles.Count -eq 0) {
        throw 'D8 did not produce a dex file.'
    }
    Push-Location -LiteralPath $dexDirectory
    try {
        Invoke-Tool $aapt (@('add', $unsignedApk) + $dexFiles)
    }
    finally {
        Pop-Location
    }

    $alignedApk = Join-Path $workDirectory 'aligned.apk'
    Invoke-Tool $zipalign @('-f', '4', $unsignedApk, $alignedApk)
    Invoke-Tool $java @('-jar', $apksignerJar, 'sign',
        '--ks', $keystore, '--ks-key-alias', 'phislow-debug',
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
        '--out', $Destination, $alignedApk)
    Invoke-Tool $java @('-jar', $apksignerJar, 'verify', '--verbose', $Destination)
    Invoke-Tool $zipalign @('-c', '4', $Destination)
    Write-Host "APK ready: $Destination"
}

function Write-LibraryManifest {
    param([string]$LibraryDirectory, [string]$Destination)
    $libraryPrefix = [System.IO.Path]::GetFullPath($LibraryDirectory).TrimEnd('\', '/') + '\'
    $relativeFiles = New-Object 'System.Collections.Generic.List[string]'
    foreach ($file in Get-ChildItem -LiteralPath $LibraryDirectory -File -Recurse) {
        $relativeFiles.Add($file.FullName.Substring($libraryPrefix.Length).Replace('\', '/'))
    }
    if (-not $relativeFiles.Contains('index.json')) {
        throw "Library index is missing: $LibraryDirectory"
    }
    $relativeFiles.Sort([System.StringComparer]::Ordinal)
    $records = New-Object 'System.Collections.Generic.List[object]'
    [long]$totalBytes = 0
    foreach ($relative in $relativeFiles) {
        $source = Join-Path $LibraryDirectory $relative
        [long]$size = (Get-Item -LiteralPath $source).Length
        $hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        $records.Add([ordered]@{ path = $relative; size = $size; sha256 = $hash })
        $totalBytes += $size
    }
    # Canonical files JSON: ordinal paths, fixed field order, compact UTF-8 without BOM.
    $fileList = ConvertTo-Json -InputObject @($records.ToArray()) -Depth 4 -Compress
    $encoding = New-Object System.Text.UTF8Encoding($false)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $generation = [System.BitConverter]::ToString($sha.ComputeHash($encoding.GetBytes($fileList))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
    $manifest = [ordered]@{ generation = $generation; bytes = $totalBytes; files = @($records.ToArray()) }
    [System.IO.File]::WriteAllText($Destination, (ConvertTo-Json -InputObject $manifest -Depth 5 -Compress), $encoding)
    Write-Host "Library manifest: $($records.Count) files, $totalBytes bytes, generation $generation"
}

function Reset-AssetStaging {
    param([string]$Directory)
    $resolved = [System.IO.Path]::GetFullPath($Directory)
    $allowedPrefix = [System.IO.Path]::GetFullPath($buildRoot) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($allowedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Asset staging directory must be inside: $buildRoot"
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    New-Item -ItemType Directory -Path $resolved -Force | Out-Null
}

$assetsRoot = Join-Path $projectRoot 'app\src\main\assets'
$metadataRoot = Join-Path $buildRoot 'library-metadata'
$libraryManifest = Join-Path $metadataRoot 'library-manifest.json'
$libraryRoot = Join-Path $assetsRoot 'library'
$sourceManifest = Join-Path $projectRoot 'distribution\library-manifest.json'
if ($Variant -ne 'Public') {
    Reset-AssetStaging $metadataRoot
    if (Test-Path -LiteralPath $libraryRoot -PathType Container) {
        Write-LibraryManifest $libraryRoot $libraryManifest
    } elseif ($Variant -eq 'Update' -and (Test-Path -LiteralPath $sourceManifest -PathType Leaf)) {
        Copy-Item -LiteralPath $sourceManifest -Destination $libraryManifest
    } else {
        throw 'Provide the local library for Full, or build Update using the distributed library manifest.'
    }
}

$appArguments = @{
    SourceDirectory = Join-Path $projectRoot 'app\src\main\java'
    Manifest = Join-Path $projectRoot 'app\src\main\AndroidManifest.xml'
    ResourcesDirectory = Join-Path $projectRoot 'app\src\main\res'
}
if ($Variant -eq 'Public') {
    $publicAssets = Join-Path $buildRoot 'public-assets'
    Reset-AssetStaging $publicAssets
    Copy-Item -LiteralPath (Join-Path $assetsRoot 'noteskin') -Destination $publicAssets -Recurse
    Copy-Item -LiteralPath (Join-Path $assetsRoot 'licenses') -Destination $publicAssets -Recurse
    Build-Apk @appArguments -Name 'app' -AssetsDirectory $publicAssets `
        -Destination (Join-Path $projectRoot 'PhiSlow.apk')
}
if ($Variant -in @('Full', 'Both')) {
    $fullApk = Join-Path $projectRoot 'PhiSlow-Full.apk'
    Build-Apk @appArguments -Name 'app' -AssetsDirectory $assetsRoot `
        -AdditionalAssetsDirectory $metadataRoot -Destination $fullApk
    Copy-Item -LiteralPath $fullApk -Destination (Join-Path $projectRoot 'PhiSlow.apk') -Force
}
if ($Variant -in @('Update', 'Both')) {
    $updateAssets = Join-Path $buildRoot 'update-assets'
    Reset-AssetStaging $updateAssets
    Copy-Item -LiteralPath (Join-Path $assetsRoot 'practice-demo.wav') -Destination $updateAssets
    Copy-Item -LiteralPath (Join-Path $assetsRoot 'noteskin') -Destination $updateAssets -Recurse
    Copy-Item -LiteralPath (Join-Path $assetsRoot 'licenses') -Destination $updateAssets -Recurse
    Copy-Item -LiteralPath $libraryManifest -Destination $updateAssets
    # Keep app/classes.jar available to the test build when only the update is requested.
    $updateName = if ($Variant -eq 'Update') { 'app' } else { 'update' }
    Build-Apk @appArguments -Name $updateName -AssetsDirectory $updateAssets `
        -Destination (Join-Path $projectRoot 'PhiSlow-Update.apk')
}

if ($WithTests) {
    Build-Apk -Name 'tests' `
        -SourceDirectory (Join-Path $projectRoot 'tests\java') `
        -Manifest (Join-Path $projectRoot 'tests\AndroidManifest.xml') `
        -AdditionalClassPath (Join-Path $buildRoot 'app\classes.jar') `
        -Destination (Join-Path $buildRoot 'PhiSlow-tests.apk')
}
