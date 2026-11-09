# AplicativoScanner

App Android (Kotlin) que conversa com um **scanner OBD‑1 Honda por Bluetooth** e mostra um painel
de instrumentos ao vivo — no **celular** e no **Android Auto** da multimídia.

- Pacote: `net.murilo.scanner`
- Comunicação: Bluetooth SPP/RFCOMM. Leitura `20 05 <addr> <len> <ck>`, resposta `00 <L> <dados> <ck>`; limpar DTC `21 04 01 <ck>`.
- Conecta pelo Bluetooth normal (celular) **ou** pelo Bluetooth da própria multimídia (central NWD),
  escolhido automaticamente. Reconexão automática com indicador na tela principal. Veja abaixo.
- Sem conta, sem nuvem, sem chave de API. Dados ficam só no aparelho.

---

## Requisitos

- **JDK 17** (obrigatório — **não** use Java 22). Neste PC: `/usr/lib/jvm/java-17-openjdk-amd64`.
- **Android SDK** (compileSdk 35, build-tools). Caminho em `local.properties` (`sdk.dir=...`).
- `adb` no PATH (platform-tools) para instalar/depurar.
- Gradle vem pelo wrapper (`./gradlew`) — não precisa instalar.

SDK alvo: `minSdk 23`, `targetSdk 34`, `compileSdk 35`. Java/Kotlin 17.

---

## Como compilar

Sempre apontando o JDK 17:

```bash
cd ~/Desktop/AplicativoScanner
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
```

Saída: `app/build/outputs/apk/debug/app-debug.apk`

Outros:
```bash
# limpar
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew clean
# só o instalador
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :installer:assembleDebug
```

---

## Como instalar

### a) No celular, só pra testar a interface
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Funciona pra ver as telas na mão. **Mas o Android Auto não aceita** um app instalado assim
(a origem fica "adb"). Para o AA, use o instalador (abaixo).

### b) Para o Android Auto (multimídia) — via instalador
O Android Auto só lista apps cuja **origem de instalação é a Google Play**. O módulo `installer`
embute o APK do app e o instala pelo instalador do sistema **declarando a Play como origem**.

1. Gere o instalador com o app novo embutido:
   ```bash
   ./build-installer.sh            # faz tudo: compila app, embute, compila installer, (push se conectado)
   ```
   ou manualmente:
   ```bash
   cp app/build/outputs/apk/debug/app-debug.apk installer/src/main/assets/Scanner.apk
   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :installer:assembleDebug
   adb push installer/build/outputs/apk/debug/installer-debug.apk /sdcard/Download/Scanner-Instalador.apk
   ```
2. No celular: **Arquivos → Downloads → Scanner-Instalador.apk** → instale (permita instalar apps
   desconhecidos para o app de Arquivos).
3. Abra **"Scanner — Android Auto"** → **Instalar / Corrigir instalação** → confirme.
   - Se o **Play Protect / Xiaomi** bloquear ("app nocivo"): toque em **Mais detalhes → Instalar
     mesmo assim**, ou desligue o Play Protect só nessa hora (Play Store → Play Protect → desligar
     "Verificar apps"). É trava do Google/Xiaomi, não do app.
4. Confira na tela do instalador a linha **"Origem atual"**: tem que ficar `com.android.vending`.
5. Se não aparecer no carro: Config. → Apps → Android Auto → Forçar parada, reconecte.

> **Por que esse rodeio:** `adb install` marca origem = adb, e o AA recusa. O instalador registra a
> Play como origem (único jeito que a multimídia aceita). Confirmado no Redmi (HyperOS, Android 13).

### Atualizar o app depois (sem perder dados)
Com a origem já como Play, o botão vira **"Reinstalar/atualizar"** (instala por cima, **mantém os
dados**). Só volta a pedir "Corrigir" se a origem sair da Play. Para backup/restore manual dos dados
(app é debug, então `run-as` funciona):
```bash
# backup
adb exec-out run-as net.murilo.scanner tar c -C /data/data/net.murilo.scanner shared_prefs files > bkp.tar
# restaurar (com o app fechado)
adb shell am force-stop net.murilo.scanner
adb shell "run-as net.murilo.scanner tar x -C /data/data/net.murilo.scanner" < bkp.tar
```

### Android Auto no PC (sem o carro)
`./run-dhu.sh` sobe o Desktop Head Unit (emulador do AA) pra testar as telas do carro.

---

## Bluetooth da central (multimídia NWD)

Algumas multimídias (ex.: **central NWD K2401**, a que roda o Android Auto deste projeto) **não
expõem a pilha Bluetooth padrão do Android** — apps comuns não enxergam o rádio. Quem controla o
Bluetooth é um serviço do fabricante (`com.bt.*`), o mesmo que o app "Ferramentas → Conectar OBD"
da central usa.

O Scanner detecta isso em tempo de execução e escolhe o transporte sozinho (**mesmo APK nos dois**):

- **Celular / tablet:** Bluetooth normal (RFCOMM/SPP no aparelho pareado) — `SocketTransport`.
- **Central NWD:** fala com o serviço do fabricante (`com.bt.ACTION_START_SERVICE` → binder
  `com.bt.BTFeature`, SPP cru) — `NwdSppTransport` (`NwdSpp.kt`). O app nem pede permissão de BT
  (o serviço da central faz o trabalho). O scanner precisa se chamar OBD/VHM/CAR e usar PIN `1234`.

**Reconexão automática** vem **ligada**: a tela principal mostra no topo a contagem até a próxima
tentativa ("Tentando conectar ao scanner em Ns") e vira "Conectando…" enquanto tenta; toque para
tentar na hora. Liga/desliga em **Menu → Painel → Conexão automática**.

> Status: o caminho da central foi reconstruído por engenharia reversa do firmware oficial e
> **ainda precisa ser validado no hardware** (no celular o app cai no Bluetooth normal). Os códigos
> de transação do binder têm que bater com a versão do serviço instalada na central.

---

## Estrutura do projeto

```
AplicativoScanner/
├─ app/                      módulo principal (o app)
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ assets/radares_brasil.csv   base nacional de radares embutida (projeto Pardal)
│     ├─ res/                drawables, xml (automotive_app_desc = "template")
│     └─ java/net/murilo/scanner/   todo o código Kotlin
├─ installer/               módulo do instalador p/ Android Auto (embute app/.../Scanner.apk)
├─ build-installer.sh       atalho p/ gerar o instalador com o app atual
├─ run-dhu.sh               Desktop Head Unit (teste do Android Auto no PC)
├─ build.gradle, settings.gradle, gradle/, gradlew
└─ local.properties         caminho do SDK (não versionar)
```

### Código (`app/src/main/java/net/murilo/scanner/`)

**Painel (desenho)**
- `DashboardView.kt` — a View principal (celular). `DashboardRenderer.kt` / `ClusterRenderer.kt` /
  `GaugeRenderer.kt` — desenham o painel/ponteiros no Canvas (compartilhado celular + Android Auto).
- `Theme.kt` cores · `Feat.kt` liga/desliga de elementos + limiares · `Layout.kt` / `CustomStore.kt`
  layouts · `BgTex.kt` textura de fundo · `Widgets.kt`, `ColorSettings.kt`.

**Telas (Activities)**
- `MainActivity.kt` — tela principal + menu (overlay). `Shared` (objeto global: `dash`, `client`).
- `DatalogActivity.kt` + `DatalogView.kt` — **Registro** (datalog: gráficos roláveis, cursor na
  timeline, pinça p/ esticar). `Trip.kt` / `TripLogger.kt` (grava automático) / `TripsActivity.kt`
  (lista) / `TripImage.kt` (compartilhar imagem) / `RouteMapActivity.kt` (trajeto no mapa).
- `DtcActivity.kt` + `DtcReader.kt` — Diagnóstico (ler/limpar códigos).
- `PerfActivity.kt` + `PerfMeter.kt` — Desempenho (0‑100, dyno).
- `ReportActivity.kt` — Relatório/Saúde. `Stats.kt` (vitalícios) · `Maint.kt`/`MaintActivity.kt`.
- `SettingsActivity.kt` — Ajustes (combustível, temperatura, bateria, motor).
- `RadarMapActivity.kt` — radares no mapa (osmdroid). `RadarDb.kt` — base (asset embutido + Pardal
  online). `Speaker.kt` — avisos por voz (TTS). `GpsProvider.kt` — GPS + aviso de radar.

**Carro / comunicação / lógica**
- `CarApp.kt` — entrada do Android Auto (`DashCarAppService`, telas do AA, `HostValidator` ALLOW_ALL).
- `ScannerClient.kt` / `ScannerConnector.kt` / `Protocol.kt` — Bluetooth e protocolo OBD‑1.
  `NwdSpp.kt` (+ `com/bt/BTDevice.kt`) — transporte pelo Bluetooth da central NWD (ver seção acima).
- `Dash.kt` — estado do carro (todos os sensores). `GearDetector.kt` — detecção de marcha.
- `FuelCalc.kt` — combustível/autonomia (ECU não manda nível; estima pela injeção).
- `Simulator.kt` — dados falsos p/ testar sem o carro. `DemoTrip.kt` — semeia 1 viagem de exemplo.

### Instalador (`installer/src/main/`)
- `java/.../installer/InstallerActivity.java` — a tela "Scanner — Android Auto" (Instalar/Corrigir).
- `java/.../installer/ApkProvider.java` — serve o APK embutido pro instalador do sistema.
- `assets/Scanner.apk` — **cópia do app-debug.apk** (reembutida a cada atualização).
- `AndroidManifest.xml` — `REQUEST_INSTALL_PACKAGES` + `REQUEST_DELETE_PACKAGES`.

---

## Gotchas (coisas que já morderam)

- **JDK 17, nunca 22** — com Java 22 o build quebra.
- **Android Auto = origem Play.** Só pelo instalador (ou modo desenvolvedor do AA, que falhou nessa
  multimídia). `adb install` não serve pro carro, só pra testar no celular.
- **Play Protect / Xiaomi** podem bloquear a instalação ("app nocivo") de forma inconsistente —
  desligar na hora de instalar.
- `app/src/main/res/xml/automotive_app_desc.xml` **tem que ser** `<uses name="template"/>` (não
  "navigation"), senão o AA não lista mesmo com origem Play.
- Radares: a base nacional já vem embutida (`assets/radares_brasil.csv`); "Atualizar" rebaixa do
  GitHub (projeto Pardal). Precisa de INTERNET só pra atualizar e pros tiles do mapa.
```
