param(
    [ValidateSet("amd64", "arm64")]
    [string]$Architecture = "amd64"
)

$ErrorActionPreference = "Stop"

$desktopRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$sourceRoot = Join-Path $desktopRoot "vlm-runtime"
$generatedRoot = Join-Path $desktopRoot "generated\vlm"
$cacheBase = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { [System.IO.Path]::GetTempPath() }
$cacheRoot = if ($env:MURONG_BUILD_CACHE) { $env:MURONG_BUILD_CACHE } else { Join-Path $cacheBase "MurongBuildCache" }
$buildRoot = Join-Path $cacheRoot "desktop-vlm-build\$Architecture"
$downloadRoot = Join-Path $cacheRoot "vlm"

$mnnVersion = "3.5.0"
$mnnArchive = Join-Path $cacheRoot "MNN-$mnnVersion.zip"
$mnnArchiveUrl = "https://github.com/alibaba/MNN/archive/refs/tags/$mnnVersion.zip"
$mnnArchiveSha256 = "A31F4D46417F6AF64E9AF079435E088690423EB2E282DE5DA61F7D082325446C"

$llamaCppBuild = "b10092"
$llamaArchive = Join-Path $downloadRoot "llama-$llamaCppBuild-bin-win-cpu-x64.zip"
$llamaArchiveUrl = "https://github.com/ggml-org/llama.cpp/releases/download/$llamaCppBuild/llama-$llamaCppBuild-bin-win-cpu-x64.zip"
$llamaArchiveSha256 = "C842FA7DC90E32B327C62903F4310EF251A902C90EF5B3A6C01C6B675DCE078E"

$liteRtArtifacts = @(
    @{
        Name = "litertlm-jvm-0.14.0.jar"
        Url = "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-jvm/0.14.0/litertlm-jvm-0.14.0.jar"
        Sha256 = "699047B35137F588EC608160FA2C272A9DC33B9174B164AD12743DAC8835A69B"
    },
    @{
        Name = "gson-2.13.2.jar"
        Url = "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar"
        Sha256 = "DD0CE1B55A3ED2080CB70F9C655850CDA86C206862310009DCB5E5C95265A5E0"
    },
    @{
        Name = "kotlin-reflect-2.2.21.jar"
        Url = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-reflect/2.2.21/kotlin-reflect-2.2.21.jar"
        Sha256 = "44380ABF37D245CE5C0F294F43512D1C39A59642BFA463922C74E96877CF49F8"
    },
    @{
        Name = "kotlin-stdlib-2.2.21.jar"
        Url = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar"
        Sha256 = "6558A3D233DA56A20934B32159F9DB5F86ED5816EF098F78A2C223DC6ABB79DD"
    },
    @{
        Name = "kotlin-stdlib-jdk7-1.8.0.jar"
        Url = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk7/1.8.0/kotlin-stdlib-jdk7-1.8.0.jar"
        Sha256 = "4C889D1D9803F5F2EB6C1592A6B7E62369AC7660C9EEE15ABA16FEC059163666"
    },
    @{
        Name = "kotlin-stdlib-jdk8-1.8.0.jar"
        Url = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.8.0/kotlin-stdlib-jdk8-1.8.0.jar"
        Sha256 = "05B62804441B0C9A1920B6B7D5CF7329A4E24B6258478E32B1F046CA01900946"
    },
    @{
        Name = "kotlinx-coroutines-core-jvm-1.9.0.jar"
        Url = "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar"
        Sha256 = "AD89C2892235E670F222D819CB3D81188143CB19A05B59DF9889AE4269F5C70A"
    },
    @{
        Name = "annotations-23.0.0.jar"
        Url = "https://repo1.maven.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0.jar"
        Sha256 = "7B0F19724082CBFCBC66E5ABEA2B9BC92CF08A1EA11E191933ED43801EB3CD05"
    }
)

function Assert-FileHash([string]$Path, [string]$Expected) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file does not exist: $Path"
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actual -ne $Expected) {
        throw "SHA-256 mismatch for $Path. Expected $Expected, got $actual"
    }
}

function Get-VerifiedDownload([string]$Path, [string]$Url, [string]$Sha256) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        try {
            Assert-FileHash $Path $Sha256
            return
        } catch {
            Remove-Item -LiteralPath $Path -Force
        }
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $partial = "$Path.download"
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $partial
    Assert-FileHash $partial $Sha256
    Move-Item -LiteralPath $partial -Destination $Path -Force
}

function Assert-ChildPath([string]$Root, [string]$Target) {
    $resolvedRoot = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $resolvedTarget = [IO.Path]::GetFullPath($Target)
    if (-not $resolvedTarget.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside $Root`: $Target"
    }
}

function Reset-GeneratedDirectory([string]$Name) {
    $target = Join-Path $generatedRoot $Name
    Assert-ChildPath $generatedRoot $target
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    return $target
}

function Copy-JarNotices([string]$JarPath, [string]$Destination, [string]$Prefix) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -notmatch "(?i)^(META-INF/)?(LICENSE|NOTICE)(\..*)?$") {
                continue
            }
            $safeName = ($entry.FullName -replace "[^A-Za-z0-9._-]", "_")
            $target = Join-Path $Destination "$Prefix-$safeName"
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally {
        $archive.Dispose()
    }
}

function Get-CMakeVisualStudioArguments([string]$TargetArchitecture) {
    $vswhere = Get-Command vswhere.exe -ErrorAction SilentlyContinue
    if (-not $vswhere) {
        $defaultVswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
        if (Test-Path -LiteralPath $defaultVswhere -PathType Leaf) {
            $vswhere = Get-Item -LiteralPath $defaultVswhere
        } else {
            throw "vswhere.exe is required to locate the installed Visual Studio toolchain"
        }
    }
    $vswherePath = if ($vswhere.Source) { $vswhere.Source } else { $vswhere.FullName }
    $installationPath = [string](& $vswherePath -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | Select-Object -First 1)
    $installationVersion = [string](& $vswherePath -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationVersion | Select-Object -First 1)
    $installationPath = $installationPath.Trim()
    $installationVersion = $installationVersion.Trim()
    if (-not $installationPath -or -not $installationVersion) {
        throw "No Visual Studio installation with a C++ toolchain was found"
    }

    $majorVersion = [int]($installationVersion.Split('.')[0])
    $generator = switch ($majorVersion) {
        17 { "Visual Studio 17 2022" }
        18 { "Visual Studio 18 2026" }
        default { throw "Unsupported Visual Studio major version $majorVersion at $installationPath" }
    }
    Write-Host "Using Visual Studio $installationVersion at $installationPath with CMake generator '$generator'."

    $arguments = @("-G", $generator, "-A", $(if ($TargetArchitecture -eq "arm64") { "ARM64" } else { "x64" }))
    if ($TargetArchitecture -eq "arm64") {
        # The wrapper CMake project compiles MNN's preprocessed AArch64 assembly
        # directly with clang-cl because Visual Studio otherwise adds /TP.
        $arguments += @("-T", "ClangCL")
    }
    return $arguments
}

New-Item -ItemType Directory -Force -Path $buildRoot, $generatedRoot, $downloadRoot | Out-Null

Write-Host "Preparing MNN $mnnVersion desktop vision runtime..."
$mnnSourceContainer = Join-Path $buildRoot "mnn-source"
$mnnSource = Join-Path $mnnSourceContainer "MNN-$mnnVersion"
if (-not (Test-Path -LiteralPath (Join-Path $mnnSource "CMakeLists.txt") -PathType Leaf)) {
	Get-VerifiedDownload $mnnArchive $mnnArchiveUrl $mnnArchiveSha256
	Assert-ChildPath $buildRoot $mnnSourceContainer
	if (Test-Path -LiteralPath $mnnSourceContainer) {
		Remove-Item -LiteralPath $mnnSourceContainer -Recurse -Force
	}
	New-Item -ItemType Directory -Force -Path $mnnSourceContainer | Out-Null
	Expand-Archive -LiteralPath $mnnArchive -DestinationPath $mnnSourceContainer
} else {
	Write-Host "Reusing the existing verified MNN source cache."
}

$mnnBuild = Join-Path $buildRoot "mnn-build"
$cmakeVisualStudioArguments = Get-CMakeVisualStudioArguments $Architecture
$mnnOptions = @()
if ($Architecture -eq "arm64") {
    # ARM82 and the optional KleidiAI/SME2 targets contain additional assembly
    # families which are outside the wrapper's verified Windows ARM64 path.
    $mnnOptions += @(
        "-DARCHS=ARM64",
        "-DMNN_ARM82=OFF",
        "-DMNN_KLEIDIAI=OFF",
        "-DMNN_SME2=OFF"
    )
}
Assert-ChildPath $buildRoot $mnnBuild
if (Test-Path -LiteralPath $mnnBuild) {
    Remove-Item -LiteralPath $mnnBuild -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $mnnBuild | Out-Null
& cmake -S $sourceRoot -B $mnnBuild @cmakeVisualStudioArguments "-DMNN_SOURCE_DIR=$mnnSource" @mnnOptions
if ($LASTEXITCODE -ne 0) { throw "MNN CMake configuration failed" }
& cmake --build $mnnBuild --config Release --target murong-mnn-vlm --parallel -- /p:TrackFileAccess=false
if ($LASTEXITCODE -ne 0) { throw "MNN desktop vision runtime build failed" }
$mnnExecutable = Get-ChildItem -LiteralPath $mnnBuild -Filter "murong-mnn-vlm.exe" -Recurse |
    Where-Object { $_.FullName -match "[\\/]Release[\\/]" } |
    Select-Object -First 1
if (-not $mnnExecutable) {
    throw "MNN desktop vision runtime executable was not produced"
}
$generatedMnn = Reset-GeneratedDirectory "mnn"
Copy-Item -LiteralPath $mnnExecutable.FullName -Destination (Join-Path $generatedMnn "murong-mnn-vlm.exe")
Copy-Item -LiteralPath (Join-Path $mnnSource "LICENSE.txt") -Destination (Join-Path $generatedMnn "LICENSE-MNN.txt")

if ($Architecture -eq "arm64") {
	$generatedLlama = Reset-GeneratedDirectory "llama"
	[IO.File]::WriteAllText(
		(Join-Path $generatedLlama "UNAVAILABLE.txt"),
		"The pinned llama.cpp Windows runtime in this release is x64 only. Use a Qwen MNN model or a user API on Windows ARM64."
	)
	$generatedLiteRt = Reset-GeneratedDirectory "litert"
    [IO.File]::WriteAllText(
        (Join-Path $generatedLiteRt "UNAVAILABLE.txt"),
        "LiteRT-LM 0.14 does not publish a Windows ARM64 native runtime. Use a Qwen MNN model or a user API."
    )
    Write-Host "MNN vision runtime is ready; Gemma is disabled because LiteRT-LM has no Windows ARM64 binary."
	return
}

Write-Host "Preparing llama.cpp $llamaCppBuild desktop runtime..."
Get-VerifiedDownload $llamaArchive $llamaArchiveUrl $llamaArchiveSha256
$llamaExtract = Join-Path $buildRoot "llama-cpp"
Assert-ChildPath $buildRoot $llamaExtract
if (Test-Path -LiteralPath $llamaExtract) {
	Remove-Item -LiteralPath $llamaExtract -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $llamaExtract | Out-Null
Expand-Archive -LiteralPath $llamaArchive -DestinationPath $llamaExtract
if (-not (Test-Path -LiteralPath (Join-Path $llamaExtract "llama-server.exe") -PathType Leaf)) {
	throw "llama.cpp Windows server runtime is missing from the verified archive"
}
$generatedLlama = Reset-GeneratedDirectory "llama"
Get-ChildItem -LiteralPath $llamaExtract -File | ForEach-Object {
	Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $generatedLlama $_.Name)
}

Write-Host "Preparing LiteRT-LM 0.14 desktop vision runtime..."
$artifactPaths = @()
foreach ($artifact in $liteRtArtifacts) {
    $path = Join-Path $downloadRoot $artifact.Name
    Get-VerifiedDownload $path $artifact.Url $artifact.Sha256
    $artifactPaths += $path
}
$javaClasses = Join-Path $buildRoot "litert-java-classes"
Assert-ChildPath $buildRoot $javaClasses
if (Test-Path -LiteralPath $javaClasses) {
    Remove-Item -LiteralPath $javaClasses -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $javaClasses | Out-Null
$classpath = $artifactPaths -join [IO.Path]::PathSeparator
$javaSource = Join-Path $sourceRoot "java\com\murong\agent\vlm\LiteRtVisionMain.java"
& javac --release 21 -encoding UTF-8 -cp $classpath -d $javaClasses $javaSource
if ($LASTEXITCODE -ne 0) { throw "LiteRT-LM Java helper compilation failed" }
$helperJar = Join-Path $buildRoot "murong-litert-vlm.jar"
& jar --create --file $helperJar -C $javaClasses .
if ($LASTEXITCODE -ne 0) { throw "LiteRT-LM Java helper packaging failed" }

$generatedLiteRt = Reset-GeneratedDirectory "litert"
$generatedLiteRtLib = Join-Path $generatedLiteRt "lib"
$generatedLiteRtLicenses = Join-Path $generatedLiteRt "licenses"
New-Item -ItemType Directory -Force -Path $generatedLiteRtLib, $generatedLiteRtLicenses | Out-Null
Copy-Item -LiteralPath $helperJar -Destination $generatedLiteRtLib
foreach ($path in $artifactPaths) {
    Copy-Item -LiteralPath $path -Destination $generatedLiteRtLib
    Copy-JarNotices $path $generatedLiteRtLicenses ([IO.Path]::GetFileNameWithoutExtension($path))
}

$runtimeImage = Join-Path $generatedLiteRt "runtime"
$jlinkHelp = (& jlink --help 2>&1 | Out-String)
$jlinkCompression = if ($jlinkHelp -match "zip-\{?0-9") { "zip-6" } else { "2" }
& jlink `
    --add-modules "java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.sql,jdk.unsupported" `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    "--compress=$jlinkCompression" `
    --output $runtimeImage
if ($LASTEXITCODE -ne 0) { throw "LiteRT-LM minimal Java runtime creation failed" }

Write-Host "Desktop MNN, llama.cpp and LiteRT-LM vision runtimes are ready in $generatedRoot"
