@echo off
rem Builds voronoi.dll from jni\voronoi_bridge.cpp.
rem Needs JAVA_HOME (for the JNI headers) and BOOST_INCLUDE (the folder containing boost\).
setlocal
cd /d "%~dp0" || exit /b 1

if "%JAVA_HOME%"=="" (
    echo JAVA_HOME is not set 1>&2
    goto :fail
)
if "%BOOST_INCLUDE%"=="" (
    echo BOOST_INCLUDE is not set 1>&2
    goto :fail
)

g++ -std=c++17 -shared -O2 -static -static-libgcc -static-libstdc++ ^
    -I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32" ^
    -I"%BOOST_INCLUDE%" -I"native" ^
    jni\voronoi_bridge.cpp -o voronoi.dll || goto :fail

echo Built %CD%\voronoi.dll
exit /b 0

:fail
echo Native build failed. 1>&2
exit /b 1