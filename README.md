# Scanner — painel Honda OBD‑1

App Android (Kotlin) que liga num **scanner OBD‑1 Honda por Bluetooth** e mostra um painel de
instrumentos ao vivo — no **celular** e no **Android Auto** da multimídia. Sem conta, sem nuvem, sem
chave de API: os dados ficam só no aparelho.

---

## 📲 Como instalar

Você tem dois jeitos: **usar no carro (Android Auto)** ou **só testar as telas no celular**.
Os APKs prontos ficam em **[Releases](https://github.com/Murilo358/HondaScanner/releases)** do repositório:

| Arquivo | O que é |
|---|---|
| `Scanner-vX.apk` | o app em si |
| `Scanner-Instalador-vX.apk` | um instalador que já traz o app embutido (atalho; ver o fim da seção) |

### A) Usar no carro (Android Auto)

Para o app aparecer no Android Auto, **duas coisas** precisam estar certas: o app instalado **e** o
Android Auto configurado para aceitar apps de fora da Play. É rápido:

**Passo 1 — Instalar o app**
1. Baixe o **`Scanner-vX.apk`** (mais recente) em [Releases](https://github.com/Murilo358/HondaScanner/releases).
2. No celular, abra **Arquivos → Downloads → `Scanner-vX.apk`** e toque em **Instalar**.
   - Se pedir, **permita instalar apps desconhecidos** para o app de Arquivos (ou navegador).
   - Se o **Play Protect** avisar "app nocivo": **Mais detalhes → Instalar mesmo assim**. É trava
     genérica do Google para app de fora da Play, não é problema do app.

**Passo 2 — Ligar "Fontes desconhecidas" no Android Auto** (uma vez só)

> Por que: o Android Auto esconde apps que não vieram da Play Store. Essa opção manda ele aceitar.
> **É o passo que todo mundo esquece.**

1. Abra as configurações do Android Auto: **Ajustes do celular → Dispositivos conectados →
   Preferências de conexão → Android Auto** (ou busque **"Android Auto"** nos Ajustes — ele não tem
   ícone na gaveta de apps).
2. Role até o fim, em **"Versão e informações de permissão"**, e **toque ~10 vezes** até aparecer
   "modo desenvolvedor ativado".
3. Toque no menu **⋮** (canto superior direito) → **Configurações do desenvolvedor**.
4. Ligue **"Fontes desconhecidas"** (*Unknown sources*).

**Passo 3 — Conectar no carro**

Conecte o celular na multimídia como você já faz (cabo ou sem fio). O **Scanner** aparece na grade de
apps do Android Auto. Abra e pronto.

- Não apareceu de primeira? **Ajustes → Apps → Android Auto → Forçar parada** e reconecte.
- A lista de apps do AA **só existe durante a projeção** (conectado no carro). Não adianta procurar
  uma "lista do Android Auto" na tela do celular — ela não existe mais.

### B) Só testar as telas no celular (sem carro)

Instale o `Scanner-vX.apk` igual ao Passo 1 acima e **abra o app "Scanner"** direto. Ele funciona
sozinho: dá pra ver todas as telas, usar **"Simular dados"** (no menu) ou conectar no scanner por
Bluetooth. Não precisa de nada do Android Auto.

### Atualizar para uma versão nova

Baixe o `Scanner-vX.apk` novo e instale por cima — **mantém seus dados**. (Se der erro de assinatura,
é porque a versão nova foi assinada com outra chave; aí desinstale a antiga e instale a nova.)

### Instalador alternativo (`Scanner-Instalador.apk`)

É um app com um botão "Instalar para Android Auto" que **já traz o Scanner embutido** — útil se você
não quer baixar o APK do app à parte, ou em **Android 10/11**, onde ele consegue registrar a Google
Play como origem (e aí nem precisa das "Fontes desconhecidas"). **No Android 12+ ele não consegue mais
forjar a origem Play** — então nessas versões o caminho continua sendo o **Passo 2** acima (Fontes
desconhecidas). Use: instale o `Scanner-Instalador.apk`, abra **"Scanner — Android Auto"** e siga as
instruções na tela.

---

## 🔌 Conexão com o scanner

O app acha o transporte certo sozinho (**mesmo APK no celular e na central**):

- **Celular / tablet:** Bluetooth normal (RFCOMM/SPP) — pareie o scanner no Bluetooth do Android antes.
- **Central NWD (multimídia):** usa o Bluetooth do fabricante — ver [Bluetooth da central](#bluetooth-da-central-multimídia-nwd).

**Reconexão automática** vem ligada: a tela principal mostra no topo a contagem até a próxima tentativa
e vira "Conectando…" enquanto tenta; toque no indicador para tentar na hora. Para ligar/desligar:
**Menu → Painel → Conexão automática**. O scanner costuma se chamar **OBD** e usar **PIN 1234**.

---

## 🛠️ Compilar do código (desenvolvedor)

**Requisitos:** **JDK 17** (obrigatório, não use versão mais nova), Android SDK (`compileSdk 35`), e
`adb` no PATH. O Gradle vem pelo wrapper (`./gradlew`). O caminho do SDK fica em `local.properties`
(`sdk.dir=...`, não versionado). SDK alvo: `minSdk 23`, `targetSdk 34`.

```bash
cd ~/Desktop/AplicativoScanner

# o app (gera app/build/outputs/apk/debug/app-debug.apk)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug

# o instalador com o app embutido (faz tudo: compila, embute, gera o installer-debug.apk)
./build-installer.sh

# limpar
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew clean
```

**Release no GitHub:** empurrar uma tag `vX` dispara o workflow (`.github/workflows/release.yml`), que
compila e publica os dois APKs em Releases:
```bash
git push origin main && git tag v0.30 && git push origin v0.30
```

**Testar o Android Auto no PC (sem carro):** `./run-dhu.sh` abre o Desktop Head Unit (emulador do AA).
Precisa do modo desenvolvedor do AA ligado e, no celular, **⋮ → "Iniciar servidor da unidade
principal"**. Útil pra ver a grade de apps do AA e ler o log (`adb logcat | grep GH.`).

**Backup/restore dos dados** (app é debug, então `run-as` funciona):
```bash
# backup
adb exec-out run-as net.murilo.scanner tar c -C /data/data/net.murilo.scanner shared_prefs files > bkp.tar
# restaurar (com o app fechado)
adb shell am force-stop net.murilo.scanner
adb shell "run-as net.murilo.scanner tar x -C /data/data/net.murilo.scanner" < bkp.tar
```

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
├─ .github/workflows/       CI: gera os APKs e publica no Releases ao enviar uma tag vX
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

### Protocolo OBD‑1 (resumo)
Bluetooth SPP/RFCOMM. Leitura `20 05 <addr> <len> <ck>`, resposta `00 <L> <dados> <ck>`;
limpar DTC `21 04 01 <ck>`.

---

## Gotchas (coisas que já morderam)

- **JDK 17, nunca mais novo** — com Java 22 o build quebra.
- **Android Auto = "Fontes desconhecidas" (Android 12+) ou origem Play (Android ≤ 11).** No Android 12+
  (Moto G77/Android 16, etc.) o truque de origem Play do instalador não funciona mais; ligue "Fontes
  desconhecidas" no modo desenvolvedor do Android Auto (ver "Como instalar → Passo 2"). A lista de apps
  do AA só aparece durante a projeção, nunca na tela do celular.
- **Play Protect / Xiaomi** podem bloquear a instalação ("app nocivo") de forma inconsistente —
  desligar na hora de instalar.
- `app/src/main/res/xml/automotive_app_desc.xml` **tem que ser** `<uses name="template"/>` (não
  "navigation"), senão o AA não lista o app.
- Radares: a base nacional já vem embutida (`assets/radares_brasil.csv`); "Atualizar" baixa do
  GitHub (projeto Pardal). Precisa de INTERNET só pra atualizar e pros tiles do mapa.
