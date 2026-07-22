@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
if not exist ".env" (
  copy ".env.example" ".env" >nul
  echo.
  echo O arquivo .env foi criado a partir do exemplo.
  echo Configure os dados do banco em BackEnd\.env antes de iniciar o Zentrix Web.
  echo.
  pause
  exit /b 1
)

for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"

if "%WEB_DB_PASSWORD%"=="sua_senha_aqui" (
  echo.
  echo Configure WEB_DB_PASSWORD em BackEnd\.env. A senha ainda esta com o valor de exemplo.
  echo.
  pause
  exit /b 1
)

if "%WEB_DB_NAME%"=="" set "WEB_DB_NAME=zentrix_web"
if "%WEB_DB_HOST%"=="" set "WEB_DB_HOST=localhost"
if "%WEB_DB_PORT%"=="" set "WEB_DB_PORT=3306"
if "%WEB_DB_USER%"=="" set "WEB_DB_USER=root"

if "%JAVA_HOME%"=="" (
  for /f "delims=" %%J in ('dir /b /ad "C:\Program Files\Java\jdk-*" 2^>nul ^| sort /r') do (
    if "!JAVA_HOME!"=="" set "JAVA_HOME=C:\Program Files\Java\%%J"
  )
)

if not "%JAVA_HOME%"=="" (
  if not exist "%JAVA_HOME%\bin\java.exe" (
    echo.
    echo JAVA_HOME aponta para uma pasta invalida: %JAVA_HOME%
    echo Configure JAVA_HOME para um JDK valido antes de iniciar o Zentrix Web.
    echo.
    pause
    exit /b 1
  )
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)

where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo Java nao encontrado. Instale um JDK 17 ou superior, ou configure JAVA_HOME.
  echo.
  pause
  exit /b 1
)

if not exist "target\zentrix-web-api-0.1.0-SNAPSHOT.jar" (
  call ".\mvnw.cmd" -q -DskipTests package
  if errorlevel 1 (
    echo.
    echo Nao foi possivel compilar o backend.
    echo.
    pause
    exit /b 1
  )
)

echo.
echo Iniciando Zentrix Web em %WEB_DB_HOST%:%WEB_DB_PORT%/%WEB_DB_NAME%...
echo Se o banco nao estiver pronto, esta janela vai mostrar o erro e a API nao ficara aberta pela metade.
echo.
java -jar "target\zentrix-web-api-0.1.0-SNAPSHOT.jar"
