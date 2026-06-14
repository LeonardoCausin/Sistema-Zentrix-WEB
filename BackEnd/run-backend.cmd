@echo off
setlocal
cd /d "%~dp0"
if exist ".env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"
)
if not exist "target\zentrix-web-api-0.1.0-SNAPSHOT.jar" (
  "C:\Program Files\NetBeans-25\netbeans\java\maven\bin\mvn.cmd" -q -DskipTests package
)
java -jar "target\zentrix-web-api-0.1.0-SNAPSHOT.jar" > backend.log 2> backend-error.log
