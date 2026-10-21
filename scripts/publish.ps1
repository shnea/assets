param(
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$')]
    [string]$Tag
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $repoRoot
$previousTag = $env:IMAGE_TAG
try {
    $env:IMAGE_TAG = $Tag
    docker compose -f compose.yml build api web nginx
    if ($LASTEXITCODE -ne 0) { throw 'Image build failed; no images were pushed.' }
    docker compose -f compose.yml push api web nginx
    if ($LASTEXITCODE -ne 0) { throw 'Image push did not complete. Check registry access before deploying.' }
    Write-Output ('Published registry.shnea.kr/assets-{api,web,nginx}:'+$Tag)
} finally {
    if ($null -eq $previousTag) { Remove-Item Env:IMAGE_TAG -ErrorAction SilentlyContinue }
    else { $env:IMAGE_TAG = $previousTag }
    Pop-Location
}
