@echo off
rem cursus: runs the fat jar (cursus-<version>-all.jar) next to this script, in the build output
rem of a source checkout, or at %CURSUS_JAR%.
setlocal
set "JAR=%CURSUS_JAR%"
if "%JAR%"=="" (
  for %%F in ("%~dp0cursus-*-all.jar" "%~dp0..\shiguchi-cursus\shiguchi-cursus-cli\build\libs\cursus-*-all.jar") do if exist "%%~F" set "JAR=%%~F"
)
if "%JAR%"=="" (
  echo cursus: no cursus-*-all.jar found; set CURSUS_JAR or run gradlew :shiguchi-cursus-cli:shadowJar 1>&2
  exit /b 1
)
if "%CURSUS_JAVA_OPTS%"=="" set "CURSUS_JAVA_OPTS=-Xshare:auto -XX:TieredStopAtLevel=1"
java %CURSUS_JAVA_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%
