#!/bin/bash
# Build do app no WSL (usa SDK do Windows em /mnt/c/...)
# Uso: ./scripts/build-wsl.sh [assembleDebug|installDebug]
set -e
cd "$(dirname "$0")/.."
export ANDROID_HOME="/mnt/c/Users/julio/AppData/Local/Android/Sdk"
if [ ! -d "$ANDROID_HOME" ]; then
  echo "Erro: SDK não encontrado em $ANDROID_HOME"
  exit 1
fi
TASK="${1:-assembleDebug}"
./gradlew ":app:$TASK"
