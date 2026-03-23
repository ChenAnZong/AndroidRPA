@echo off
chcp 65001 >nul
echo ========================================
echo   Yyds.Auto VSCode 插件发布工具
echo ========================================
echo.

REM 检查是否安装了 vsce
where vsce >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未找到 vsce 工具
    echo 请先安装: npm install -g @vscode/vsce
    pause
    exit /b 1
)

echo [1/4] 编译插件...
call npm run package
if %errorlevel% neq 0 (
    echo [错误] 编译失败
    pause
    exit /b 1
)

echo.
echo [2/4] 打包 VSIX...
call vsce package --no-dependencies
if %errorlevel% neq 0 (
    echo [错误] 打包失败
    pause
    exit /b 1
)

echo.
echo [3/4] 准备发布...
echo.
echo 请选择操作:
echo   1. 发布到 VSCode 市场
echo   2. 仅本地安装测试
echo   3. 退出
echo.
set /p choice="请输入选项 (1-3): "

if "%choice%"=="1" goto publish
if "%choice%"=="2" goto install
if "%choice%"=="3" goto end

:publish
echo.
echo [4/4] 发布到市场...
echo.
echo 提示: 如果是首次发布，请先运行: vsce login ChenAnzong
echo.
set /p confirm="确认发布到 VSCode 市场? (y/n): "
if /i not "%confirm%"=="y" goto end

call vsce publish
if %errorlevel% neq 0 (
    echo [错误] 发布失败
    echo 请检查:
    echo   1. 是否已登录: vsce login ChenAnzong
    echo   2. Personal Access Token 是否有效
    echo   3. package.json 中的 publisher 字段是否正确
    pause
    exit /b 1
)

echo.
echo ========================================
echo   发布成功! 🎉
echo ========================================
echo.
echo 插件页面: https://marketplace.visualstudio.com/items?itemName=ChenAnzong.yyds-auto-dev-plugin
echo.
goto end

:install
echo.
echo [4/4] 安装到本地 VS Code...
for %%f in (yyds-auto-dev-plugin-*.vsix) do (
    echo 安装: %%f
    code --install-extension "%%f" --force
)
echo.
echo ========================================
echo   安装完成! 请重启 VS Code
echo ========================================
echo.
goto end

:end
pause
