@echo off
setlocal

if "%JAVA_HOME%"=="" (
    echo ERROR: Set JAVA_HOME to a JDK installation.
    exit /b 1
)
set "JBIN=%JAVA_HOME%\bin"

if exist build rmdir /s /q build
mkdir build

echo === Compiling for Java 8 ===
"%JBIN%\javac.exe" -encoding UTF-8 --release 8 -d build ArcaneMaze.java
if errorlevel 1 exit /b 1

echo Main-Class: ArcaneMaze> build\MANIFEST.MF
"%JBIN%\jar.exe" cfm build\ArcaneMaze.jar build\MANIFEST.MF -C build .
if errorlevel 1 exit /b 1
echo Created build\ArcaneMaze.jar

if /I not "%1"=="exe" goto :done

echo === Creating standalone Windows app image ===
if exist dist rmdir /s /q dist
"%JBIN%\jpackage.exe" --type app-image --name ArcaneMaze ^
    --input build --main-jar ArcaneMaze.jar --main-class ArcaneMaze ^
    --dest dist --app-version 1.0 --vendor "helmetsoft.com" ^
    --description "Arcane Maze: Rift Hunt - fantasy FPS"
if errorlevel 1 exit /b 1
echo Created dist\ArcaneMaze\ArcaneMaze.exe

:done
endlocal
