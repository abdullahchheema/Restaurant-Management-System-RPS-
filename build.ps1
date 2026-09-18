$ErrorActionPreference = "Stop"
$javac = "javac"
if (Test-Path "C:\Program Files\Java\jdk-25.0.4.1\bin\javac.exe") {
    $javac = "C:\Program Files\Java\jdk-25.0.4.1\bin\javac.exe"
}

New-Item -ItemType Directory -Force -Path bin | Out-Null

$sources = Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sourcesFile = New-TemporaryFile
$sources | Set-Content -Path $sourcesFile

# -encoding is pinned, not left to the platform default: the UI and receipts contain
# characters like — · … × that would compile to mojibake under a Windows-1252 default.
& $javac -encoding UTF-8 -d bin -cp "lib/*" "@$sourcesFile"
$exitCode = $LASTEXITCODE

Remove-Item $sourcesFile -Force

if ($exitCode -eq 0) {
    Write-Host "Build succeeded." -ForegroundColor Green
} else {
    Write-Host "Build failed." -ForegroundColor Red
}
exit $exitCode
