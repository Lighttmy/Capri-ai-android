#!/usr/bin/env bash
# Sube el proyecto a un repositorio PRIVADO tuyo, compila el APK en GitHub Actions y lo descarga.
set -e
gh auth status >/dev/null || { echo "Primero: gh auth login"; exit 1; }
NOMBRE=asistente-ia-movil
[ -d .git ] || { git init -q -b main; git add -A; git commit -qm "Asistente IA móvil"; }
gh repo view "$NOMBRE" >/dev/null 2>&1 || gh repo create "$NOMBRE" --private --source . --push
git push -u origin main 2>/dev/null || true
gh workflow run apk.yml
sleep 15
ID=$(gh run list --workflow apk.yml --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$ID" --exit-status
gh run download "$ID" -n AsistenteIA-debug-apk -D .
echo "Listo: app-debug.apk"
