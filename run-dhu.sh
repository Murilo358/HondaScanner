#!/usr/bin/env bash
# Abre o Desktop Head Unit (emulador da multimidia do Android Auto) e conecta no celular.
#
# ANTES de rodar:
#  1) Instale as libs (uma vez):   sudo apt install -y libc++1 libc++abi1
#  2) No celular, no app Android Auto:
#       - Config. -> role ate "Versao" e toque ~10x -> ativa "Modo desenvolvedor"
#       - Menu (3 pontinhos) -> "Iniciar servidor da unidade principal"
#  3) Celular plugado no USB com depuracao ativada.
#
# Uso:   ./run-dhu.sh [perfil]
#   perfil (opcional): custom_628.ini (padrao, 1280x480 ~ sua multimidia),
#                      default_6in.ini (800x480), default_720p.ini (1280x720),
#                      default_1080p.ini (1920x1080)
set -e
DHU="/home/murilo/Desktop/hondash/hondash-aa/.tools/sdk/extras/google/auto"
PROFILE="${1:-custom_628.ini}"

if [ ! -f "$DHU/config/$PROFILE" ]; then
  echo "Perfil nao encontrado: $DHU/config/$PROFILE"; exit 1
fi
cp "$DHU/config/$PROFILE" "$DHU/config/default.ini"

echo ">> adb forward tcp:5277"
adb forward tcp:5277 tcp:5277

echo ">> abrindo DHU com perfil $PROFILE (feche a janela para encerrar)"
cd "$DHU"
LD_LIBRARY_PATH="$DHU" exec ./desktop-head-unit
