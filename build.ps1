#requires -Version 5.1
<#
.SYNOPSIS
    Builds the Horizon Android APK locally: Java app + Rust native library.

.DESCRIPTION
    The script is branch-agnostic. It reads the Horizon ref from the
    checked-out branch's .github/workflows/build.yml (same value CI uses),
    syncs and patches a local horizon checkout, builds libhorizon_client.so
    with cargo-ndk, then runs the Gradle build.

    Optional hand tracking model (tools/fetch_hand_model.sh, used by v15+)
    is downloaded automatically if the script is present and the model is missing.

.PARAMETER AppBranch
    Check out this git branch/tag in the app repository before building.
    Default: build whatever is currently checked out.

.PARAMETER HorizonRef
    Horizon branch, tag or commit. Default: horizon_ref from build.yml.

.PARAMETER Task
    Gradle task. Default: assembleDebug.

.PARAMETER SkipNative
    Do not rebuild libhorizon_client.so; reuse whatever is in app/src/main/jniLibs.

.PARAMETER RebuildNative
    Force a clean horizon sync/patch and a full native rebuild.

.EXAMPLE
    .\build.ps1

.EXAMPLE
    .\build.ps1 -AppBranch main

.EXAMPLE
    .\build.ps1 -HorizonRef 33e35d37c0362a0580e4927bfc12d0b15a3dc5a7 -RebuildNative

.EXAMPLE
    .\build.ps1 -SkipNative -Task assembleDebug
#>
[CmdletBinding()]
param(
    [string]$AppBranch,
    [string]$HorizonRef,
    [string]$Task = "assembleDebug",
    [switch]$SkipNative,
    [switch]$RebuildNative
)

$ErrorActionPreference = "Continue"
$Root = $PSScriptRoot

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $Root
    )
    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $File @Arguments 2>&1 | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { Write-Host $_.Exception.Message }
            else { Write-Host $_ }
        }
        $code = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($code -ne 0) {
        throw "Command failed with exit code $code`: $File $($Arguments -join ' ')"
    }
}

function Resolve-Tool {
    param([string]$Name, [string]$Fallback)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) { return $cmd.Source }
    if ($Fallback -and (Test-Path -LiteralPath $Fallback)) { return $Fallback }
    return $null
}

function Resolve-Bash {
    $candidates = @()
    if ($env:ProgramFiles) { $candidates += (Join-Path $env:ProgramFiles "Git\bin\bash.exe") }
    if (${env:ProgramFiles(x86)}) { $candidates += (Join-Path ${env:ProgramFiles(x86)} "Git\bin\bash.exe") }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Programs\Git\bin\bash.exe") }
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath $c) { return $c }
    }
    $cmd = Get-Command bash.exe -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source -and $cmd.Source -notmatch '\\WindowsApps\\') { return $cmd.Source }
    return $null
}

function Get-JavaMajor {
    param([string]$JavaExe)
    $out = & $JavaExe -version 2>&1
    $text = ($out | Out-String)
    $m = [regex]::Match($text, 'version "(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return 0
}

function Resolve-JavaHome {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    foreach ($base in @("C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft")) {
        if (Test-Path -LiteralPath $base) {
            Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    $best = $null
    $bestMajor = 0
    foreach ($dir in $candidates) {
        $java = Join-Path $dir "bin\java.exe"
        if (-not (Test-Path -LiteralPath $java)) { continue }
        $major = Get-JavaMajor $java
        if ($major -ge 17 -and $major -gt $bestMajor) {
            $bestMajor = $major
            $best = $dir
        }
    }
    return $best
}

function Resolve-Sdk {
    $localProps = Join-Path $Root "local.properties"
    if (Test-Path -LiteralPath $localProps) {
        $m = [regex]::Match((Get-Content -LiteralPath $localProps -Raw), '(?m)^sdk\.dir=(.+)$')
        if ($m.Success) {
            $p = $m.Groups[1].Value.Trim().Replace('\\', '\').Replace('\:', ':')
            if (Test-Path -LiteralPath $p) { return $p }
        }
    }
    foreach ($p in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk"))) {
        if ($p -and (Test-Path -LiteralPath $p)) { return $p }
    }
    throw "Android SDK not found. Install it or set ANDROID_HOME / sdk.dir in local.properties."
}

function Resolve-Ndk {
    param([string]$Sdk)
    if ($env:ANDROID_NDK_HOME -and (Test-Path -LiteralPath (Join-Path $env:ANDROID_NDK_HOME "source.properties"))) {
        return $env:ANDROID_NDK_HOME
    }
    $best = $null
    $bestRev = [version]"0.0"
    $ndkRoot = Join-Path $Sdk "ndk"
    if (Test-Path -LiteralPath $ndkRoot) {
        foreach ($dir in (Get-ChildItem -LiteralPath $ndkRoot -Directory -ErrorAction SilentlyContinue)) {
            $props = Join-Path $dir.FullName "source.properties"
            if (-not (Test-Path -LiteralPath $props)) { continue }
            $m = [regex]::Match((Get-Content -LiteralPath $props -Raw), 'Pkg\.Revision\s*=\s*([0-9.]+)')
            if ($m.Success) {
                $rev = [version]$m.Groups[1].Value
                if ($rev -gt $bestRev) {
                    $bestRev = $rev
                    $best = $dir.FullName
                }
            }
        }
    }
    if (-not $best) {
        throw "Android NDK not found under $ndkRoot. Install an NDK or set ANDROID_NDK_HOME."
    }
    return $best
}

function Resolve-Gradle {
    $wrapper = Join-Path $Root "gradlew.bat"
    if (Test-Path -LiteralPath $wrapper) { return $wrapper }

    $cmd = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if (-not $cmd) { $cmd = Get-Command gradle -ErrorAction SilentlyContinue }
    if ($cmd -and $cmd.Source -and $cmd.Source -match '\.(bat|cmd|exe)$') { return $cmd.Source }

    $dists = Join-Path $env:USERPROFILE ".gradle\wrapper\dists"
    $found = @()
    if (Test-Path -LiteralPath $dists) {
        $found = Get-ChildItem -LiteralPath $dists -Recurse -Filter "gradle.bat" -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\gradle-([0-9.]+)\\bin\\gradle\.bat$' } |
            ForEach-Object {
                $m = [regex]::Match($_.FullName, '\\gradle-([0-9.]+)\\bin\\gradle\.bat$')
                [pscustomobject]@{ Path = $_.FullName; Version = [version]$m.Groups[1].Value }
            }
    }
    if ($found.Count -eq 0) {
        throw "Gradle not found. Add a gradlew wrapper, install Gradle, or download a distribution."
    }
    return ($found | Sort-Object Version -Descending | Select-Object -First 1).Path
}

function Get-HorizonRefFromWorkflow {
    $workflow = Join-Path $Root ".github\workflows\build.yml"
    if (Test-Path -LiteralPath $workflow) {
        $m = [regex]::Match((Get-Content -LiteralPath $workflow -Raw), 'horizon_ref:[\s\S]*?default:\s*"([^"]+)"')
        if ($m.Success) { return $m.Groups[1].Value }
    }
    return "main"
}

function Resolve-HorizonCommit {
    param([string]$Dir, [string]$Ref)
    $commit = & git -C $Dir rev-parse --verify "$Ref^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $commit) {
        $commit = & git -C $Dir rev-parse --verify "origin/$Ref^{commit}" 2>$null
    }
    if ($LASTEXITCODE -ne 0 -or -not $commit) {
        throw "Horizon ref '$Ref' was not found in $Dir (after git fetch)."
    }
    return $commit.Trim()
}

if (-not $Root) { throw "Cannot determine script directory (PSScriptRoot)." }

if ($AppBranch) {
    Write-Host "==> Checking out app branch '$AppBranch'"
    Invoke-Native git @("checkout", $AppBranch)
}

$JavaHome = Resolve-JavaHome
if (-not $JavaHome) {
    throw "JDK 17+ not found. Install a JDK and/or set JAVA_HOME."
}
$env:JAVA_HOME = $JavaHome

$Sdk = Resolve-Sdk
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk

$localProps = Join-Path $Root "local.properties"
$desired = "sdk.dir=" + ($Sdk -replace '\\', '/')
$needsWrite = -not (Test-Path -LiteralPath $localProps)
if (-not $needsWrite) {
    $current = (Get-Content -LiteralPath $localProps -Raw).Trim()
    if ($current.Length -eq 0) { $needsWrite = $true }
}
if ($needsWrite) {
    Set-Content -LiteralPath $localProps -Value $desired -Encoding ASCII
}

$Gradle = Resolve-Gradle
$Cargo = Resolve-Tool "cargo.exe" (Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe")
$Rustup = Resolve-Tool "rustup.exe" (Join-Path $env:USERPROFILE ".cargo\bin\rustup.exe")
$Python = Resolve-Tool "python.exe"
if (-not $Python) { $Python = Resolve-Tool "python3.exe" }

$HorizonDir = Join-Path $Root "horizon"
if (-not $HorizonRef) { $HorizonRef = Get-HorizonRefFromWorkflow }

$appHead = (& git -C $Root rev-parse --short HEAD 2>$null)
Write-Host "==> Repository : $Root"
Write-Host "==> App commit : $appHead"
Write-Host "==> Horizon ref: $HorizonRef"
Write-Host "==> Java       : $JavaHome"
Write-Host "==> SDK        : $Sdk"
Write-Host "==> Gradle     : $Gradle"

if (-not $SkipNative) {
    if (-not $Cargo) {
        throw "cargo not found. Install Rust: https://rustup.rs"
    }
    if (-not $Python) {
        throw "python not found (needed to apply the Horizon patch)."
    }

    $Ndk = Resolve-Ndk $Sdk
    $env:ANDROID_NDK_HOME = $Ndk
    $env:ANDROID_NDK_ROOT = $Ndk
    Write-Host "==> NDK        : $Ndk"

    if ($Rustup) {
        $targets = & $Rustup target list --installed 2>$null
        if (-not ($targets -match 'aarch64-linux-android')) {
            Write-Host "==> Adding Rust target aarch64-linux-android"
            Invoke-Native $Rustup @("target", "add", "aarch64-linux-android")
        }
    }

    & $Cargo ndk --version *> $null 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "cargo-ndk is not installed. Run: cargo install cargo-ndk --locked"
    }

    Write-Host "==> Syncing horizon"
    if (-not (Test-Path -LiteralPath $HorizonDir)) {
        Invoke-Native git @("clone", "https://github.com/ludovic111/horizon", $HorizonDir)
    }
    Invoke-Native git @("-C", $HorizonDir, "fetch", "--force", "--tags", "--prune", "origin")

    $target = Resolve-HorizonCommit -Dir $HorizonDir -Ref $HorizonRef
    $head = (& git -C $HorizonDir rev-parse HEAD 2>$null).Trim()
    $stamp = Join-Path $HorizonDir ".horizon-patch-ok"

    $needReset = $RebuildNative -or ($head -ne $target) -or (-not (Test-Path -LiteralPath $stamp))
    if (-not $needReset) {
        $stampTime = (Get-Item -LiteralPath $stamp).LastWriteTimeUtc
        $patchNewer = Get-ChildItem -LiteralPath (Join-Path $Root "rust") -File -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTimeUtc -gt $stampTime }
        if ($patchNewer) { $needReset = $true }
    }

    if ($needReset) {
        Write-Host "==> Resetting horizon to $target"
        Invoke-Native git @("-C", $HorizonDir, "reset", "--hard", "HEAD")
        Invoke-Native git @("-C", $HorizonDir, "clean", "-fd")
        Invoke-Native git @("-C", $HorizonDir, "checkout", "--detach", $target)
    }

    if (-not (Test-Path -LiteralPath $stamp)) {
        Write-Host "==> Applying Android patch"
        $env:PYTHONUTF8 = "1"
        try {
            Invoke-Native $Python @((Join-Path $Root "rust\apply_patch.py"), $HorizonDir)
        }
        catch {
            throw "Could not patch horizon at '$HorizonRef'. If this is a moving branch (for example main), upstream has drifted: pass -HorizonRef <known-good-commit>. $($_.Exception.Message)"
        }
        Set-Content -LiteralPath $stamp -Value (Get-Date -Format o) -Encoding ASCII
    }

    $JniDir = Join-Path $Root "app\src\main\jniLibs"
    New-Item -ItemType Directory -Force -Path $JniDir | Out-Null

    Write-Host "==> Building libhorizon_client.so (arm64-v8a)"
    $cargoArgs = @(
        "ndk", "-t", "arm64-v8a", "-P", "26", "-o", $JniDir,
        "build", "--release", "--lib", "-p", "horizon-client",
        "--no-default-features", "--features", "audio"
    )
    Invoke-Native $Cargo $cargoArgs $HorizonDir

    $libCpp = Get-ChildItem -Path (Join-Path $Ndk "toolchains\llvm\prebuilt\*\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so") -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $libCpp) {
        throw "libc++_shared.so not found inside $Ndk"
    }
    Copy-Item -LiteralPath $libCpp.FullName -Destination (Join-Path $JniDir "arm64-v8a\libc++_shared.so") -Force
}
else {
    Write-Host "==> Skipping native rebuild (-SkipNative)"
}

$modelScript = Join-Path $Root "tools\fetch_hand_model.sh"
$modelFile = Join-Path $Root "app\src\main\assets\hand_landmarker.task"
if ((Test-Path -LiteralPath $modelScript) -and -not (Test-Path -LiteralPath $modelFile)) {
    $bash = Resolve-Bash
    if ($bash) {
        Write-Host "==> Fetching MediaPipe hand model"
        Invoke-Native $bash @(($modelScript -replace '\\', '/'))
    }
    else {
        $url = [regex]::Match((Get-Content -LiteralPath $modelScript -Raw), 'https?://[^\s''"]+')
        $curl = Resolve-Tool "curl.exe" (Join-Path $env:SystemRoot "System32\curl.exe")
        if ($url.Success -and $curl) {
            Write-Host "==> Fetching MediaPipe hand model (curl fallback)"
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $modelFile) | Out-Null
            Invoke-Native $curl @("--silent", "--show-error", "-L", "--fail", "--retry", "3", "-o", $modelFile, $url.Value)
            if ((Get-Item -LiteralPath $modelFile).Length -le 1000000) {
                throw "Downloaded hand model looks too small."
            }
        }
        else {
            Write-Warning "Neither Git Bash nor curl found; skipping the hand model (hand tracking will not work)."
        }
    }
}

Write-Host "==> Running gradle $Task"
Push-Location -LiteralPath $Root
try {
    & $Gradle $Task --no-daemon --console=plain 2>&1 | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) { Write-Host $_.Exception.Message }
        else { Write-Host $_ }
    }
    $gradleCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
if ($gradleCode -ne 0) {
    throw "Gradle $Task failed with exit code $gradleCode."
}

$apk = Get-ChildItem -Path (Join-Path $Root "app\build\outputs\apk") -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($apk) {
    $sizeMb = [math]::Round($apk.Length / 1MB, 2)
    Write-Host ""
    Write-Host "APK: $($apk.FullName)"
    Write-Host "Size: $sizeMb MB"
}
else {
    Write-Warning "Build finished but no APK was found under app\build\outputs\apk."
}