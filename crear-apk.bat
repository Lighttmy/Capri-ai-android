@echo off
rem Sube el proyecto a un repositorio PRIVADO tuyo, compila el APK en GitHub Actions y lo descarga.
rem Requiere git y GitHub CLI (gh) con la sesion iniciada: gh auth login
gh auth status >nul 2>&1 || (echo Primero ejecuta: gh auth login & exit /b 1)
if not exist .git (git init -q -b main & git add -A & git commit -qm "Asistente IA movil")
gh repo view asistente-ia-movil >nul 2>&1 || gh repo create asistente-ia-movil --private --source . --push
git push -u origin main
gh workflow run apk.yml
timeout /t 15 >nul
for /f %%i in ('gh run list --workflow apk.yml --limit 1 --json databaseId -q ".[0].databaseId"') do set ID=%%i
gh run watch %ID% --exit-status
gh run download %ID% -n AsistenteIA-debug-apk -D .
echo Listo: app-debug.apk
