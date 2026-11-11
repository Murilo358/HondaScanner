# Scanner — painel Honda OBD‑1

App Android que conecta num **scanner OBD‑1 Honda por Bluetooth** e vira um painel de instrumentos
completo: RPM, velocidade, temperatura, consumo, registro de viagens com mapa, diagnóstico de falhas,
avisos de radar e mais. Sem conta, sem nuvem: os dados ficam só no aparelho.

Funciona **no celular** — e, se você quiser, também na **tela da multimídia** (Android Auto).

---

## 📲 Instalar no celular

1. Baixe o **`Scanner-vX.apk`** mais novo em **[Releases](https://github.com/Murilo358/HondaScanner/releases)**.
2. Abra o arquivo baixado e confirme a instalação
   (se o Android pedir, permita "instalar apps desconhecidos"; se o Play Protect reclamar, toque em **"Instalar mesmo assim"**).
3. Pronto — abra o app **Scanner**.

### Usar

- **Com o scanner:** pareie ele no Bluetooth do celular (costuma se chamar **OBD**, PIN **1234**).
  O app conecta sozinho ao abrir; o indicador no topo mostra o status.
- **Sem o carro por perto:** menu ☰ → **Simular dados** mostra o painel funcionando com dados de exemplo.
- **Atualizar:** o próprio app avisa quando sai versão nova e baixa pra você — é só confirmar.

É isso. O que vem abaixo só interessa a quem quer o painel **na tela do carro**.

---

## 🚗 Usar na multimídia (Android Auto) — opcional

O Android Auto só mostra apps que ele acredita terem vindo da Google Play, então colocar o Scanner lá
pede um passo extra — que **depende da versão do Android** do aparelho onde o Scanner está instalado:

### Android 11 ou mais antigo (ex.: centrais com Android)

Use o **`Scanner-Instalador-vX.apk`** (também está em Releases) — ele registra a origem certa sozinho:

1. Instale o `Scanner-Instalador-vX.apk` (mesmo jeito do passo a passo acima).
2. Abra **"Scanner — Android Auto"** → **Instalar para Android Auto** → confirme.
3. Reconecte ao carro. Se o app não aparecer: **Ajustes → Apps → Android Auto → Forçar parada** e reconecte.

**Atualizar depois:** normal — o próprio Scanner baixa e instala a versão nova.

### Android 12 ou mais novo (celulares atuais)

Nessas versões nenhum app consegue se registrar como Play por conta própria — nem o instalador acima,
nem a opção "Fontes desconhecidas" do Android Auto (testado: não basta). A saída é o
**Shizuku + KingInstaller**, uma configuração que se faz **uma vez só**:

1. **Shizuku** — instale o [Shizuku](https://github.com/RikkaApps/Shizuku) e inicie-o por
   "pareamento por depuração sem fio" (precisa de Wi‑Fi):
   Opções do desenvolvedor → **Depuração por Wi‑Fi** → **Parear dispositivo com código** →
   digite o código de 6 dígitos na notificação do Shizuku → toque **Iniciar** até dizer
   "Shizuku está em execução".
   *O Shizuku para quando o celular reinicia — abra e toque Iniciar de novo.*
2. **KingInstaller** — instale o [KingInstaller](https://github.com/fcaronte/KingInstaller/releases),
   abra e conceda o acesso ao Shizuku (**Permitir sempre**).
3. Baixe o **`Scanner-vX.apk`** e **instale-o pelo KingInstaller** (o botão de instalar dele já
   registra a origem certa).
4. Reconecte ao carro → o Scanner aparece na tela. ✅ Confirmado no Moto G77 / Android 16.

**Atualizar depois:** o Scanner avisa da versão nova, baixa e **chama o KingInstaller pra você** —
só confirme (com o Shizuku rodando).

> A lista de apps do Android Auto só existe **com o celular conectado no carro** — não procure uma
> "lista do Android Auto" na tela do celular.

### Central NWD (multimídia que roda o próprio app)

Algumas centrais (ex.: NWD K2401) não expõem o Bluetooth padrão do Android — quem manda é um serviço
do fabricante. O Scanner detecta isso sozinho e usa o Bluetooth da central direto (mesmo APK): ao abrir,
ele oferece o seletor próprio pra achar e parear o scanner (nome OBD/VHM/CAR, PIN 1234).

---

<details>
<summary><b>🛠️ Para desenvolvedores</b> (compilar, estrutura, gotchas)</summary>

### Compilar

**Requisitos:** **JDK 17** (obrigatório — mais novo quebra o build), Android SDK (`compileSdk 35`),
`adb` no PATH. Caminho do SDK em `local.properties` (`sdk.dir=...`, não versionado).
`minSdk 23`, `targetSdk 34`.

```bash
# o app (gera app/build/outputs/apk/debug/app-debug.apk)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug

# o instalador com o app embutido (Android ≤ 11)
./build-installer.sh

# compila e joga o APK no Downloads do celular (p/ KingInstaller, Android 12+)
./push-app.sh
```

**Release:** empurrar uma tag `vX` dispara o CI (`.github/workflows/release.yml`), que grava a versão
da tag no build, assina com a keystore do secret `DEBUG_KEYSTORE` (mesma assinatura em todo build) e
publica os dois APKs em Releases:
```bash
git push origin main && git tag v0.40 && git push origin v0.40
```

**Android Auto no PC (sem carro):** `./run-dhu.sh` abre o Desktop Head Unit. Precisa do modo
desenvolvedor do AA e, no celular, ⋮ → "Iniciar servidor da unidade principal".
Log: `adb logcat | grep GH.`

**Backup/restore dos dados** (build debug, então `run-as` funciona):
```bash
adb exec-out run-as net.murilo.scanner tar c -C /data/data/net.murilo.scanner shared_prefs files > bkp.tar
adb shell am force-stop net.murilo.scanner
adb shell "run-as net.murilo.scanner tar x -C /data/data/net.murilo.scanner" < bkp.tar
```

### Estrutura

```
AplicativoScanner/
├─ app/                      módulo principal (o app)
│  └─ src/main/
│     ├─ assets/radares_brasil.csv   base nacional de radares embutida (projeto Pardal)
│     ├─ res/                drawables, xml (automotive_app_desc = "template")
│     └─ java/net/murilo/scanner/   todo o código Kotlin
├─ installer/               módulo do instalador p/ Android Auto (embute app/.../Scanner.apk)
├─ build-installer.sh       gera o instalador com o app atual (Android ≤ 11)
├─ push-app.sh              compila e joga o APK no Downloads do celular (Android 12+)
├─ run-dhu.sh               Desktop Head Unit (teste do Android Auto no PC)
└─ .github/workflows/       CI: publica os APKs no Releases ao enviar uma tag vX
```

**Painel (desenho):** `DashboardView.kt` (View principal) · `DashboardRenderer.kt` /
`ClusterRenderer.kt` / `GaugeRenderer.kt` (Canvas, compartilhado celular + AA) · `Theme.kt` cores ·
`Feat.kt` elementos/limiares · `Layout.kt` / `CustomStore.kt` layouts · `BgTex.kt` fundo ·
`Widgets.kt`, `ColorSettings.kt`.

**Telas:** `MainActivity.kt` (principal + menu) · `DatalogActivity.kt`/`DatalogView.kt` (linha do
tempo da sessão) · `Trip.kt`/`TripLogger.kt`/`TripsActivity.kt` (registro automático) ·
`TripImage.kt` (imagem de compartilhar, tiles OSM direto) · `RouteMapActivity.kt` (trajeto no mapa +
"Onde estacionei") · `DtcActivity.kt`/`DtcReader.kt` (diagnóstico) · `PerfActivity.kt`/`PerfMeter.kt`
(0‑100, dyno) · `ReportActivity.kt` (saúde) · `Maint.kt`/`MaintActivity.kt` · `SettingsActivity.kt` ·
`RadarMapActivity.kt`/`RadarDb.kt`/`Speaker.kt`/`GpsProvider.kt` (radares, voz, GPS) ·
`UpdateCheck.kt` (atualização pelo Releases).

**Carro / comunicação:** `CarApp.kt` (Android Auto) · `ScannerClient.kt`/`ScannerConnector.kt`/
`Protocol.kt` (Bluetooth + protocolo OBD‑1) · `NwdSpp.kt` + `com/bt/BTDevice.kt` (Bluetooth da
central NWD: `com.bt.ACTION_START_SERVICE` → binder `com.bt.BTFeature`, SPP cru) · `Dash.kt` (estado) ·
`GearDetector.kt` (marcha) · `FuelCalc.kt` (combustível por injeção) · `Simulator.kt` (dados falsos).

**Instalador:** `InstallerActivity.java` (tela Instalar/Corrigir) · `ApkProvider.java` (serve o APK
embutido) · `assets/Scanner.apk` (cópia do app, reembutida a cada build).

**Protocolo OBD‑1 (resumo):** Bluetooth SPP/RFCOMM. Leitura `20 05 <addr> <len> <ck>`, resposta
`00 <L> <dados> <ck>`; limpar DTC `21 04 01 <ck>`.

### Gotchas

- **JDK 17, nunca mais novo** — com Java 22 o build quebra.
- **Android Auto:** ≤ 11 = origem Play via instalador do projeto; 12+ = **só KingInstaller + Shizuku**
  ("Fontes desconhecidas" do AA e `adb install -i` não bastam — o AA exige *requested by* =
  instalador do sistema **e** *installed by* = `com.android.vending`). Com **root + LSPosed**, o módulo
  [Android‑Auto‑XLauncher‑Unlocked](https://github.com/Rikj000/Android-Auto-XLauncher-Unlocked) faz o
  AA ignorar a checagem de origem.
- **Assinatura:** o CI assina com a keystore do secret `DEBUG_KEYSTORE` — toda release tem a mesma
  assinatura. Aparelhos instalados de releases antigas (v0.29–v0.35, chaves aleatórias) precisam
  desinstalar e instalar de novo uma única vez.
- **Play Protect / Xiaomi** podem bloquear a instalação de forma inconsistente — "Instalar mesmo assim".
- `app/src/main/res/xml/automotive_app_desc.xml` **tem que ser** `<uses name="template"/>`, senão o
  AA não lista o app.
- Radares: base nacional embutida; "Atualizar" baixa do GitHub (projeto Pardal). INTERNET só pra
  isso, pros tiles de mapa e pra checar atualização.

</details>
