@echo off
chcp 65001 >nul
echo ========================================
echo Building Vue project and deploying to Spring Boot...
echo ========================================

echo.
echo [1/3] Cleaning old build files...
if exist "..\src\main\resources\static" (
    rmdir /s /q "..\src\main\resources\static"
    echo Old files cleaned.
)

echo.
echo [2/3] Building Vue project...
call npm run build

if %errorlevel% neq 0 (
    echo.
    echo [FAIL] Build failed!
    pause
    exit /b %errorlevel%
)

echo.
echo [3/3] Verifying build output...
if exist "..\src\main\resources\static\index.html" (
    echo [OK] Build succeeded!
    echo.
    echo Files deployed to: src/main/resources/static/
    echo.
    echo You can now start the Spring Boot app at http://localhost:8080
) else (
    echo [FAIL] Build output not found!
)

echo.
echo ========================================
echo Build finished.
echo ========================================
pause
