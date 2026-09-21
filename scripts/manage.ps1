param([Parameter(Mandatory=$true)][string]$RequestFile,
 [string]$ProgramsDirectory=[Environment]::GetFolderPath('Programs'))
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtime=Join-Path $root 'build/gradle/runtime'
if(!(Test-Path -LiteralPath $runtime)){throw 'Compilez le Hub avec .\gradlew.bat prepareRuntime avant cette commande.'}
$java=if($env:NRF_JAVA){$env:NRF_JAVA}elseif($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{'java.exe'}
& $java '-Dfile.encoding=UTF-8' '-cp' "$runtime/*" 'fr.nimby.hub.MainKt' '--manage' $RequestFile $ProgramsDirectory
exit $LASTEXITCODE