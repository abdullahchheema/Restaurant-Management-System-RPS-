$ErrorActionPreference = "Stop"
$java = "java"
if (Test-Path "C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe") {
    $java = "C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe"
}

# FlatLaf loads a native library for window decorations. Since JDK 24 that prints a
# restricted-method warning, and a future JDK will BLOCK it outright -- which would break
# the look-and-feel on the client's machine after a runtime upgrade. Declaring the access
# up front silences the warning today and keeps the app working when the block lands.
& $java --enable-native-access=ALL-UNNAMED -cp "bin;lib/*" rps.app.App
