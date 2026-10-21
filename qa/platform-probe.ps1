# Creates one disposable PRIVATE test file, checks the contract and removes it.
param([switch]$Image)
$ErrorActionPreference = 'Stop'
$configValues = @{}
Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\.env') | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { $configValues[$Matches[1]] = $Matches[2].Trim().Trim([char]39).Trim([char]34) }
}
$baseUrl = if ($configValues['PLATFORM_URL']) { $configValues['PLATFORM_URL'].TrimEnd('/') } else { 'https://platform.shnea.kr' }
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)
function Send-ProbeRequest([string]$method, [string]$path, $content, [bool]$authenticated = $true, [hashtable]$headers = @{}) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($method), $baseUrl + $path)
    if ($authenticated) { $request.Headers.Add('X-Platform-Key', $configValues['PLATFORM_API_KEY']) }
    foreach ($header in $headers.Keys) { $request.Headers.Add($header, [string]$headers[$header]) }
    if ($null -ne $content) { $request.Content = $content }
    return $client.SendAsync($request).GetAwaiter().GetResult()
}
function Read-ProbeJson($response) {
    if (-not $response.IsSuccessStatusCode) { throw ('Platform HTTP ' + [int]$response.StatusCode) }
    return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
}
$uploadId = $null
$fileId = $null
try {
    $context = Read-ProbeJson (Send-ProbeRequest 'GET' '/api/v1/integration/context' $null)
    if ($context.projectId -ne $configValues['PLATFORM_PROJECT_ID'] -or $context.environmentId -ne $configValues['PLATFORM_ENVIRONMENT_ID'] -or $context.issuer -ne $configValues['PLATFORM_OIDC_ISSUER']) { throw 'Platform scope mismatch' }
    $payload = if ($Image) { [IO.File]::ReadAllBytes((Join-Path $PSScriptRoot '../web/public/assets-wordmark.png')) } else { [Text.Encoding]::UTF8.GetBytes('Assets disposable integration verification') }
    $originalName = if ($Image) { 'assets-disposable-image-check.png' } else { 'assets-connection-check.txt' }
    $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($payload)).ToLowerInvariant()
    $inputJson = @{requestId=[guid]::NewGuid();originalName=$originalName;size=$payload.Length;sha256=$digest;visibility='PRIVATE';retentionCode='영구'} | ConvertTo-Json -Compress
    $body = [System.Net.Http.StringContent]::new($inputJson,[Text.Encoding]::UTF8,'application/json')
    $created = Read-ProbeJson (Send-ProbeRequest 'POST' '/api/v1/files/uploads' $body)
    $uploadId = [string]$created.uploadId
    $chunk = [System.Net.Http.ByteArrayContent]::new($payload)
    $chunk.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream')
    $received = Read-ProbeJson (Send-ProbeRequest 'PATCH' ('/api/v1/files/uploads/'+$uploadId) $chunk $true @{'Upload-Offset'='0';'X-Chunk-SHA256'=$digest})
    if ($received.receivedBytes -ne $payload.Length) { throw 'Upload offset mismatch' }
    $completed = Read-ProbeJson (Send-ProbeRequest 'POST' ('/api/v1/files/uploads/'+$uploadId+'/complete') $null)
    $fileId = [string]$completed.fileId
    if ($completed.visibility -ne 'PRIVATE' -or $completed.retentionCode -ne '영구' -or $completed.sha256 -ne $digest -or $completed.size -ne $payload.Length) { throw 'File contract mismatch' }
    $anonymous = Send-ProbeRequest 'GET' ('/api/v1/files/'+$fileId+'/download') $null $false
    if ([int]$anonymous.StatusCode -ne 404) { throw 'PRIVATE file was visible anonymously' }
    $download = Send-ProbeRequest 'GET' ('/api/v1/files/'+$fileId+'/download') $null
    if (-not $download.IsSuccessStatusCode) { throw 'Download failed' }
    $downloaded = $download.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($downloaded)).ToLowerInvariant() -ne $digest) { throw 'Download checksum mismatch' }
    if ($Image) {
        for ($attempt=0; $attempt -lt 45; $attempt++) {
            $views = Read-ProbeJson (Send-ProbeRequest 'POST' ('/api/v1/files/'+$fileId+'/view-ticket') $null)
            if ($views.state -eq 'READY' -and $views.thumbnailUrl -and $views.previewUrl) { break }
            if ($attempt -eq 0) { Write-Output 'Waiting for platform image derivatives' }
            Start-Sleep -Seconds 1
        }
        if ($views.fileId -ne $fileId -or $views.kind -ne 'IMAGE' -or $views.state -ne 'READY' -or -not $views.thumbnailUrl -or -not $views.previewUrl) { throw 'Image derivatives did not become ready' }
        if ($views.thumbnailUrl -eq $views.originalUrl -or $views.previewUrl -eq $views.originalUrl) { throw 'Original and derivative URLs must be distinct' }
        $imageResults = @{}
        foreach ($variant in @('thumbnailUrl','previewUrl','originalUrl')) {
            $uri = [uri]::new([uri]$baseUrl,[string]$views.$variant)
            if ($uri.GetLeftPart([System.UriPartial]::Authority) -ne ([uri]$baseUrl).GetLeftPart([System.UriPartial]::Authority)) { throw 'Unexpected image origin' }
            $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get,$uri)
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            if (-not $response.IsSuccessStatusCode -or -not $response.Content.Headers.ContentType.MediaType.StartsWith('image/')) { throw 'Image view failed' }
            $data = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            if ($data.Length -eq 0) { throw 'Empty image response' }
            $checksum = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($data)).ToLowerInvariant()
            if ($variant -eq 'originalUrl') { if ($checksum -ne $digest) { throw 'Image original mismatch' } }
            elseif ($checksum -eq $digest) { throw 'Image derivative unexpectedly aliases the original' }
            $imageResults[$variant] = @{bytes=$data.Length;contentType=$response.Content.Headers.ContentType.MediaType}
        }
        Write-Output ('PASS: real platform private image thumbnail/preview derivatives and original ticket views '+($imageResults | ConvertTo-Json -Compress))
    }
    Write-Output 'PASS: platform scope, PRIVATE permanent upload, chunk/full hash, anonymous denial and authenticated download'
} finally {
    if ($fileId) { $cleanup = Send-ProbeRequest 'DELETE' ('/api/v1/files/'+$fileId) $null }
    elseif ($uploadId) { $cleanup = Send-ProbeRequest 'DELETE' ('/api/v1/files/uploads/'+$uploadId) $null }
    if ($cleanup) {
        if (-not $cleanup.IsSuccessStatusCode) { Write-Error ('Disposable test-file cleanup failed: HTTP '+[int]$cleanup.StatusCode) }
        else { Write-Output 'PASS: disposable test file removed' }
    }
    $client.Dispose()
}
