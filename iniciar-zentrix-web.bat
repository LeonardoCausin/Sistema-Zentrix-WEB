@echo off
setlocal
cd /d "%~dp0"

echo Iniciando Zentrix Web...

sc query MySQL80 | find "RUNNING" >nul
if errorlevel 1 (
  echo Iniciando MySQL...
  net start MySQL80
)

start "Zentrix Web API" /D "%~dp0BackEnd" cmd /k run-backend.cmd

echo Aguardando o sistema preparar o acesso...
for /l %%I in (1,1,30) do (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $r = Invoke-RestMethod -Uri 'http://localhost:8080/api/health' -TimeoutSec 2; if ($r.status -eq 'UP' -and $r.database -eq 'UP') { exit 0 } } catch { }; exit 1" >nul 2>nul
  if not errorlevel 1 goto abrir_site
  timeout /t 2 /nobreak >nul
)

echo.
echo O Zentrix Web nao ficou pronto.
echo Confira a janela "Zentrix Web API" para ver o erro do banco.
echo.
pause
exit /b 1

:abrir_site
start "" "%~dp0index.html"

echo Zentrix Web iniciado.
