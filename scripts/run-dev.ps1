# Builds the webview and the plugin, then starts the dev product (Eclipse Platform + Kiro ACP Chat).
#   scripts\run-dev.ps1 [-SkipBuild]
# JDKs: the build needs 21+ (KIROACP_BUILD_JDK), the product runs on 17+ (KIROACP_RUN_JDK).
param([switch]$SkipBuild)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$buildJdk = if ($env:KIROACP_BUILD_JDK) { $env:KIROACP_BUILD_JDK } else { $env:JAVA_HOME }
$runJdk = if ($env:KIROACP_RUN_JDK) { $env:KIROACP_RUN_JDK } else { $buildJdk }

if (-not $SkipBuild) {
  Push-Location (Join-Path $root "webview")
  try {
    if (-not (Test-Path node_modules)) { npm ci; if ($LASTEXITCODE) { throw "npm ci failed" } }
    npm run build; if ($LASTEXITCODE) { throw "webview build failed" }
  } finally { Pop-Location }
  Push-Location $root
  try {
    $env:JAVA_HOME = $buildJdk
    mvn -B -ntp verify; if ($LASTEXITCODE) { throw "maven build failed" }
  } finally { Pop-Location }
}

$product = Join-Path $root "releng\io.github.liaoalia.kiroacp.product\target\products\io.github.liaoalia.kiroacp.dev\win32\win32\x86_64"
$workspace = Join-Path $root ".dev-workspace"
New-Item -ItemType Directory -Force $workspace, (Join-Path $product "configuration\.settings") | Out-Null
# No welcome page and no Microsoft Defender exclusion prompt in the throwaway dev instance.
$custom = Join-Path $workspace "customization.ini"
Set-Content $custom "org.eclipse.ui/showIntro=false"
Set-Content (Join-Path $product "configuration\.settings\org.eclipse.ui.workbench.prefs") "eclipse.preferences.version=1`nwindows.defender.startup.check.skip=true"

& (Join-Path $product "eclipse.exe") -vm (Join-Path $runJdk "bin") -data $workspace -pluginCustomization $custom -nosplash
