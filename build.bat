@echo off
echo Building YaMusicLsposed module...
call gradlew assembleDebug
if %errorlevel% neq 0 (
    echo.
    echo Build failed! Please check the errors above.
    pause
    exit /b %errorlevel%
)
echo.
echo Build successful!
echo Your APK is located at:
echo app\build\outputs\apk\debug\app-debug.apk
pause
