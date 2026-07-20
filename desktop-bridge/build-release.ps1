$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$env:CGO_ENABLED = "0"
$env:GOOS = "windows"
$env:GOARCH = "amd64"

Push-Location $root
try {
    go test ./...
    if ($LASTEXITCODE -ne 0) { throw "Windows Node tests failed" }
    go vet ./...
    if ($LASTEXITCODE -ne 0) { throw "Windows Node vet failed" }
    go build -tags windowsgui -trimpath -ldflags "-H windowsgui -s -w" -o (Join-Path $dist "murong-windows-node-amd64.exe") .\cmd\murong-windows-node
    if ($LASTEXITCODE -ne 0) { throw "Windows Node GUI build failed" }
    go build -trimpath -ldflags "-s -w" -o (Join-Path $dist "murong-windows-node-cli-amd64.exe") .\cmd\murong-windows-node-cli
    if ($LASTEXITCODE -ne 0) { throw "Windows Node CLI build failed" }
    Write-Host "Built $dist\murong-windows-node-amd64.exe (GUI)"
    Write-Host "Built $dist\murong-windows-node-cli-amd64.exe (CLI)"
} finally {
    Pop-Location
}
