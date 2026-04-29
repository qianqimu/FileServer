@echo off
chcp 65001 >nul
echo ============================================
echo   Android FileServer - 一键构建脚本
echo ============================================
echo.

REM 检查Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Java，请先安装 JDK 17:
    echo   https://adoptium.net/
    pause
    exit /b 1
)
echo [OK] Java 已安装

REM 下载 gradle-wrapper.jar（如果不存在）
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [INFO] 正在下载 gradle-wrapper.jar...
    powershell -Command "Invoke-WebRequest -Uri 'https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar' -UseBasicParsing"
    if not exist "gradle\wrapper\gradle-wrapper.jar" (
        echo [错误] 下载失败，请手动下载:
        echo   https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar
        echo   保存到: gradle\wrapper\gradle-wrapper.jar
        pause
        exit /b 1
    )
)
echo [OK] gradle-wrapper.jar 就绪

echo.
echo [INFO] 开始编译 Debug APK...
call gradlew.bat assembleDebug --stacktrace

if errorlevel 1 (
    echo.
    echo [错误] 编译失败，请检查错误信息
    pause
    exit /b 1
)

echo.
echo ============================================
echo   编译成功！APK 位置:
echo   app\build\outputs\apk\debug\app-debug.apk
echo ============================================
pause
