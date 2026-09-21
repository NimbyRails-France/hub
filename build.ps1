param([switch]$Package)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
 $tasks=@('build','prepareRuntime')
 if($Package){$tasks+=@('createDistributable','collectDependencyNotices')}
 & .\gradlew.bat @tasks '--console=plain'
 if($LASTEXITCODE){throw 'Gradle build failed'}
} finally { Pop-Location }
