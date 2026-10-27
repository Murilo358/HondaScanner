#!/usr/bin/env bash
# Gera o instalador do Android Auto com o APK atual do app embutido.
# Uso: ./build-installer.sh
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

echo "==> 1/4 compilando o app"
./gradlew assembleDebug

echo "==> 2/4 embutindo app no instalador"
cp app/build/outputs/apk/debug/app-debug.apk installer/src/main/assets/Scanner.apk

echo "==> 3/4 compilando o instalador"
./gradlew :installer:assembleDebug
APK=installer/build/outputs/apk/debug/installer-debug.apk
echo "    instalador: $APK ($(du -h "$APK" | cut -f1))"

echo "==> 4/4 enviando pro celular (Downloads)"
if adb devices | sed 1d | grep -q "device$"; then
  adb push "$APK" /sdcard/Download/Scanner-Instalador.apk
  echo "    pronto: abra Downloads > Scanner-Instalador.apk e rode 'Corrigir instalacao'."
else
  echo "    celular desconectado — copie manualmente $APK pro aparelho."
fi
