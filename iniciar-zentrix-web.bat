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
timeout /t 8 /nobreak >nul

start "" "%~dp0FrontEnd\index.html"

echo Zentrix Web iniciado.
