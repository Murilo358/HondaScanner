#!/usr/bin/env bash
# Compila o app e joga o APK no Downloads do celular, pronto pro KingInstaller.
# (Android 12+: o KingInstaller + Shizuku instalam "como Play Store"; ver README.)
# Uso: ./push-app.sh
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

echo "==> compilando o app"
./gradlew assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk

if adb devices | sed 1d | grep -q "device$"; then
  adb push "$APK" /sdcard/Download/Scanner.apk
  echo "    pronto: no celular, abra o KingInstaller e escolha Downloads/Scanner.apk"
else
  echo "    celular desconectado — copie $APK pro aparelho e use no KingInstaller."
fi
