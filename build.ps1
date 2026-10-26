# Builds TVLauncher.apk without Gradle: aapt2 -> javac -> R8 (or d8) -> zipalign -> apksigner.
# -Plain: plain d8 instead of R8, no shrinking. Same app, APK about 12 KB larger.
param([switch]$Plain)
$ErrorActionPreference = 'Stop'
$sdk = 'C:\Android'
$bt = "$sdk\build-tools\34.0.0"
$jar = "$sdk\platforms\android-34\android.jar"
$here = $PSScriptRoot
$out = Join-Path $here 'build'

function Run($exe) {
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE)" }
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

# Icon and TV banner. The real ones are cut from art\logo-source.png and kept in res;
# this only draws simple placeholders if they are missing.
$draw = Join-Path $here 'res\drawable-nodpi'
if (-not (Test-Path "$draw\banner.*")) {
    Add-Type -AssemblyName System.Drawing
    New-Item -ItemType Directory -Force $draw | Out-Null
    # Dark background, a soft projector beam from the top right corner, the name in warm amber.
    function Img($w, $h, $text, $size, $path) {
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'
        $g.Clear([System.Drawing.Color]::FromArgb(255, 10, 11, 14))
        $beam = New-Object System.Drawing.Drawing2D.GraphicsPath
        $beam.AddPolygon([System.Drawing.PointF[]]@(
            (New-Object System.Drawing.PointF ($w * 1.0), 0),
            (New-Object System.Drawing.PointF 0, ($h * 0.62)),
            (New-Object System.Drawing.PointF ($w * 0.30), ($h * 1.0))))
        # The gradient must reach the farthest corner: past its end GDI+ repeats it instead of clamping.
        $light = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            (New-Object System.Drawing.PointF $w, 0), (New-Object System.Drawing.PointF -1, ($h + 1)),
            [System.Drawing.Color]::FromArgb(150, 255, 236, 200), [System.Drawing.Color]::FromArgb(0, 255, 236, 200))
        $fade = New-Object System.Drawing.Drawing2D.Blend 3
        $fade.Factors = [single[]]@(0, 0.75, 1)
        $fade.Positions = [single[]]@(0, 0.45, 1)
        $light.Blend = $fade
        $g.FillPath($light, $beam)
        $font = New-Object System.Drawing.Font 'Segoe UI Light', $size
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 227, 180, 100))
        $fmt = New-Object System.Drawing.StringFormat
        $fmt.Alignment = 'Center'; $fmt.LineAlignment = 'Center'
        $g.DrawString($text, $font, $brush, (New-Object System.Drawing.RectangleF 0, ($h * 0.04), $w, $h), $fmt)
        $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $bmp.Dispose()
    }
    Img 320 180 'Luch' 40 "$draw\banner.png"
    Img 192 192 'L' 84 "$draw\icon.png"
}

Run "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
$assets = @(); if (Test-Path "$here\assets") { $assets = @('-A', "$here\assets") }
Run "$bt\aapt2.exe" link -o "$out\unsigned.apk" -I $jar --manifest "$here\AndroidManifest.xml" @assets `
    --java "$out\gen" --min-sdk-version 24 --target-sdk-version 29 "$out\res.zip"

$sources = @(Get-ChildItem -Recurse "$here\src", "$out\gen" -Filter *.java | ForEach-Object FullName)
Run javac -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath $jar -d "$out\classes" @sources

# One jar instead of a list of .class files: the list no longer fits on a Windows command line.
Run jar cf "$out\classes.jar" -C "$out\classes" .
# R8 (bundled in d8.jar) instead of plain d8: drops unused code and shortens names.
# Classes named in the manifest are kept by r8-rules.pro.
# mapping.txt turns the shortened names in a user's crash report back into real ones;
# keep it with each release, do not publish it.
if ($Plain) {
    Run java -cp "$bt\lib\d8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib $jar `
        --output "$out\dex" "$out\classes.jar"
} else {
    Run java -cp "$bt\lib\d8.jar" com.android.tools.r8.R8 --release --min-api 24 --lib $jar `
        --pg-conf "$here\r8-rules.pro" --pg-map-output "$here\mapping.txt" --output "$out\dex" "$out\classes.jar"
}

Push-Location "$out\dex"
try { Run "$bt\aapt.exe" add "$out\unsigned.apk" classes.dex } finally { Pop-Location }

Run "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"

# Keep this key: updates of the installed app must be signed with the same one.
$ks = Join-Path $here 'tvlauncher.jks'
if (-not (Test-Path $ks)) {
    Run keytool -genkeypair -keystore $ks -storepass tvlauncher -keypass tvlauncher -alias tvlauncher `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TV Launcher'
}
Run "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:tvlauncher --key-pass pass:tvlauncher `
    --out "$here\TVLauncher.apk" "$out\aligned.apk"

Write-Host "Готово: $here\TVLauncher.apk"
