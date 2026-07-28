param(
    [ValidateSet("amd64", "arm64")]
    [string]$Architecture = "amd64"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$dist = Join-Path $root "dist"
$codexVersion = "0.144.6"
$platforms = @{
    amd64 = @{
        NpmPlatform = "win32-x64"
        Sha256 = "E04AFBE9841BE306455D075AD414993A946C94A399E55D7F9EC223F734CD4101"
    }
    arm64 = @{
        NpmPlatform = "win32-arm64"
        Sha256 = "92774896D0D293DB1C1808E77085B5A068E5AA70825D0D656E29BD4CA276E651"
    }
}
$platform = $platforms[$Architecture]
$codexArchiveName = "codex-npm-$($platform.NpmPlatform)-$codexVersion.tgz"
$codexArchiveUrl = "https://github.com/openai/codex/releases/download/rust-v$codexVersion/$codexArchiveName"
$cacheBase = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { [System.IO.Path]::GetTempPath() }
$cacheRoot = if ($env:MURONG_BUILD_CACHE) { $env:MURONG_BUILD_CACHE } else { Join-Path $cacheBase "MurongBuildCache" }
$cachedCodexArchive = if ($env:MURONG_CODEX_ARCHIVE) { $env:MURONG_CODEX_ARCHIVE } else { Join-Path $cacheRoot $codexArchiveName }
$codexRuntimePackageName = "murong-runtime-codex-windows-$Architecture-$codexVersion.tgz"
$visionRuntimeVersion = "builtin-vlm-2026-07-v1"
$outputName = "murong-desktop-agent-windows-$Architecture.exe"
$outputPath = Join-Path $dist $outputName

function Assert-CodexArchive([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Codex archive does not exist: $Path"
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actual -ne $platform.Sha256) {
        throw "Codex archive SHA-256 mismatch. Expected $($platform.Sha256), got $actual"
    }
}

Push-Location $root
try {
    & (Join-Path $root "scripts\build-vlm-runtimes.ps1") -Architecture $Architecture
    if ($LASTEXITCODE -ne 0) { throw "Desktop built-in vision runtimes failed" }

    npm ci --prefix frontend --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw "Desktop workbench dependencies failed" }
    npm run build --prefix frontend
    if ($LASTEXITCODE -ne 0) { throw "Desktop workbench build failed" }
    node --check frontend/dist/app.js
    if ($LASTEXITCODE -ne 0) { throw "Desktop frontend syntax check failed" }

    go test -count=1 .
    if ($LASTEXITCODE -ne 0) { throw "Desktop Agent tests failed" }
    go vet .
    if ($LASTEXITCODE -ne 0) { throw "Desktop Agent vet failed" }

    New-Item -ItemType Directory -Force $dist | Out-Null
    if (-not $env:MURONG_CODEX_ARCHIVE -and -not (Test-Path -LiteralPath $cachedCodexArchive -PathType Leaf)) {
        New-Item -ItemType Directory -Force $cacheRoot | Out-Null
        $download = "$cachedCodexArchive.download"
        Invoke-WebRequest -UseBasicParsing -Uri $codexArchiveUrl -OutFile $download
        Assert-CodexArchive $download
        Move-Item -LiteralPath $download -Destination $cachedCodexArchive -Force
    }
    Assert-CodexArchive $cachedCodexArchive
    Copy-Item -LiteralPath $cachedCodexArchive -Destination (Join-Path $dist $codexRuntimePackageName) -Force
    $runtimeComponents = @(
        @{ Name = "mnn"; Required = "generated\vlm\mnn\murong-mnn-vlm.exe" },
        @{ Name = "llama"; Required = "generated\vlm\llama\llama-server.exe" },
        @{ Name = "litert"; Required = "generated\vlm\litert\runtime\bin\java.exe" }
    )
    foreach ($component in $runtimeComponents) {
        if (-not (Test-Path -LiteralPath $component.Required -PathType Leaf)) { continue }
        $packageName = "murong-runtime-$($component.Name)-windows-$Architecture-$visionRuntimeVersion.tgz"
        go run ./cmd/runtime-packager -source "generated/vlm/$($component.Name)" -output (Join-Path $dist $packageName)
        if ($LASTEXITCODE -ne 0) { throw "Packaging $($component.Name) runtime failed" }
    }

    go run github.com/wailsapp/wails/v2/cmd/wails@v2.13.0 build `
        -clean -m -s -skipbindings -skipembedcreate `
        -platform "windows/$Architecture" -trimpath -o $outputName
    if ($LASTEXITCODE -ne 0) { throw "Desktop Agent Wails build failed" }
    $builtPath = Join-Path $root "build\bin\$outputName"
    if (-not (Test-Path -LiteralPath $builtPath -PathType Leaf)) {
        throw "Desktop Agent Wails output is missing: $builtPath"
    }
    $env:MURONG_BUILT_EXE = $builtPath
    try {
        go test -count=1 -run TestBuiltWindowsApplicationResources .
        if ($LASTEXITCODE -ne 0) { throw "Packaged Windows resource verification failed" }
    } finally {
        Remove-Item Env:MURONG_BUILT_EXE -ErrorAction SilentlyContinue
    }
    try {
        Copy-Item -LiteralPath $builtPath -Destination $outputPath -Force
    } catch {
        $pendingName = "murong-desktop-agent-windows-$Architecture.next.exe"
        $pendingPath = Join-Path $dist $pendingName
        Copy-Item -LiteralPath $builtPath -Destination $pendingPath -Force
        Write-Warning "The installed desktop executable is currently in use. The new verified build was staged as $pendingPath; exit Murong from the system tray before replacing the installed executable."
        $outputPath = $pendingPath
    }

    Get-FileHash -Algorithm SHA256 $outputPath
} finally {
    Pop-Location
}
