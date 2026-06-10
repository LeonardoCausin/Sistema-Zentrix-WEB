@echo off
cd /d "%~dp0"
"C:\Program Files\NetBeans-25\netbeans\java\maven\bin\mvn.cmd" spring-boot:run > backend.log 2> backend-error.log
