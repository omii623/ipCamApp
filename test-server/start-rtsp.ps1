# RTSP teszt kamera: MediaMTX (RTSP szerver) + ffmpeg tesztkép
#
# Első futáskor letölti a MediaMTX-et ebbe a mappába (bin\), utána csak indít.
# Stream:  rtsp://<PC-IP>:8554/stream1   (az appban: RTSP, port 8554, útvonal /stream1)
#
# Futtatás:  powershell -ExecutionPolicy Bypass -File .\start-rtsp.ps1
#            powershell -ExecutionPolicy Bypass -File .\start-rtsp.ps1 -Cameras 3   (stream1..stream3)

param(
    [int]$Cameras = 1,
    [string]$MediaMtxVersion = "latest"
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$bin = Join-Path $here "bin"
$exe = Join-Path $bin "mediamtx.exe"

if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    Write-Host "Hiba: ffmpeg nem található a PATH-ban." -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $exe)) {
    New-Item -ItemType Directory -Force $bin | Out-Null
    $zip = Join-Path $bin "mediamtx.zip"
    $api = if ($MediaMtxVersion -eq "latest") {
        "https://api.github.com/repos/bluenviron/mediamtx/releases/latest"
    } else {
        "https://api.github.com/repos/bluenviron/mediamtx/releases/tags/$MediaMtxVersion"
    }
    $rel = Invoke-RestMethod -Uri $api -Headers @{ "User-Agent" = "ipcam-test" }
    $asset = $rel.assets | Where-Object { $_.name -like "*windows_amd64.zip" } | Select-Object -First 1
    if (-not $asset) { Write-Host "Nem találom a Windows-os MediaMTX csomagot." -ForegroundColor Red; exit 1 }
    Write-Host "MediaMTX letöltése ($($rel.tag_name))..."
    $url = $asset.browser_download_url
    Invoke-WebRequest -Uri $url -OutFile $zip
    Expand-Archive $zip -DestinationPath $bin -Force
    Remove-Item $zip
}

Write-Host "MediaMTX indítása..."
$mtx = Start-Process -FilePath $exe -WorkingDirectory $bin -PassThru -WindowStyle Minimized
Start-Sleep -Seconds 2

$ffmpegs = @()
$colors = @("cyan", "red", "lime", "yellow", "violet", "orange")
for ($i = 1; $i -le $Cameras; $i++) {
    $c = $colors[($i - 1) % $colors.Count]
    $vf = "drawbox=x=0:y=0:w=200:h=60:color=black@0.6:t=fill,drawtext=text='CAM $i  %{localtime\:%H\\\:%M\\\:%S}':x=12:y=16:fontsize=28:fontcolor=$c"
    $ffArgs = @(
        "-hide_banner", "-loglevel", "error", "-re",
        "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25",
        "-vf", $vf,
        "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
        "-g", "50", "-pix_fmt", "yuv420p",
        "-f", "rtsp", "-rtsp_transport", "tcp",
        "rtsp://127.0.0.1:8554/stream$i"
    )
    $ffmpegs += Start-Process -FilePath "ffmpeg" -ArgumentList $ffArgs -PassThru -NoNewWindow
}

$ips = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
    Select-Object -ExpandProperty IPAddress

Write-Host ""
Write-Host "============================================================"
Write-Host " RTSP teszt kamera fut"
Write-Host "============================================================"
foreach ($ip in $ips) { Write-Host "  rtsp://${ip}:8554/stream1" }
Write-Host ""
Write-Host "  Az appban: Típus = RTSP, Port = 8554, Útvonal = /stream1"
if ($Cameras -gt 1) { Write-Host "  További kamerák: /stream2 ... /stream$Cameras" }
Write-Host "  Leállítás: Enter"
Write-Host "============================================================"
[void](Read-Host)

$ffmpegs | ForEach-Object { if (-not $_.HasExited) { Stop-Process -Id $_.Id -Force } }
if (-not $mtx.HasExited) { Stop-Process -Id $mtx.Id -Force }
Write-Host "Leállítva."
