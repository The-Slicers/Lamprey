@echo off
rem Rebuilds Plugins\lamprey-plugins.jar from Plugins\src (Java 8 bytecode, so
rem it runs on whatever Java the Quorum runtime ships with). Run from anywhere:
rem     Plugins\build.bat
setlocal EnableDelayedExpansion
cd /d "%~dp0" || exit /b 1

if exist classes rmdir /s /q classes
mkdir classes || goto :fail

rem cmd has no equivalent of $(find ...), so collect the sources into a javac
rem argument file. Paths are quoted and use forward slashes so spaces and
rem backslashes survive javac's argfile parsing.
if exist sources.txt del sources.txt
for /r src %%f in (*.java) do (
    set "p=%%f"
    >>sources.txt echo "!p:\=/!"
)
if not exist sources.txt (
    echo No .java files found under src 1>&2
    goto :fail
)

javac --release 8 -d classes @sources.txt || goto :fail
jar --create --file lamprey-plugins.jar -C classes . || goto :fail

rmdir /s /q classes
del sources.txt
echo Built %CD%\lamprey-plugins.jar
exit /b 0

:fail
if exist classes rmdir /s /q classes
if exist sources.txt del sources.txt
echo Build failed. 1>&2
exit /b 1
