package net.murilo.scanner.installer;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Instala o app Scanner pelo instalador do sistema declarando o Google Play como origem.
 * O Android Auto so lista apps da Car App Library cuja instalacao veio da Play; o instalador
 * do sistema (diferente do adb ou de instaladores de terceiros) consegue registrar isso no
 * Android 13 e abaixo. Mesma abordagem do AAAD / KingInstaller / hondash-aa.
 */
public final class InstallerActivity extends Activity {

    private static final String TARGET = "net.murilo.scanner";
    private static final String PLAY_STORE = "com.android.vending";
    private static final String ASSET = "Scanner.apk";
    private static final int REQ_INSTALL = 1;
    private static final int REQ_UNINSTALL = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button button;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Scanner — Android Auto");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        button = new Button(this);
        button.setText("Instalar para Android Auto");
        button.setOnClickListener(v -> start());
        root.addView(button);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        PackageInfo info = installedInfo();
        if (info == null) {
            status.setText("O Scanner nao esta instalado.");
            button.setText("Instalar para Android Auto");
        } else if (installedViaPlay()) {
            status.setText("Scanner " + info.versionName + " instalado do jeito certo.\n\n"
                    + "Se ainda nao aparece no Android Auto, force a parada do Android Auto "
                    + "(Config. -> Apps -> Android Auto -> Forcar parada) ou reinicie o celular.");
            button.setText("Reinstalar / atualizar");
        } else {
            // Android 12+ ignora o truque de origem Play; a instalacao fica como "packageinstaller".
            // Nessas versoes quem faz o Android Auto listar o app e a opcao "Fontes desconhecidas".
            status.setText("Scanner " + info.versionName + " instalado.\n\n"
                    + "Neste Android a origem Google Play nao pode ser forcada (normal no Android 12+),"
                    + " entao o Android Auto so lista o app com as FONTES DESCONHECIDAS ligadas:\n\n"
                    + "1. Abra o Android Auto (Ajustes -> Dispositivos conectados -> Android Auto).\n"
                    + "2. Role ate \"Versao\" e toque ~10x -> ativa o modo desenvolvedor.\n"
                    + "3. Menu (3 pontos) -> Config. do desenvolvedor -> ligue \"Fontes desconhecidas\".\n"
                    + "4. Reconecte ao carro (a lista de apps so aparece na projecao).\n\n"
                    + "Origem atual: " + sourceInfo());
            button.setText("Reinstalar / atualizar");
        }
    }

    private void start() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            status.setText("Primeiro permita que ESTE app (Scanner — Android Auto) instale apps."
                    + "\n\nAbrindo os ajustes…");
            if (!open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())))
                    && !open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                    && !open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())))) {
                status.setText("Nao consegui abrir os ajustes. Va em Config. -> Apps -> "
                        + "Scanner — Android Auto -> Instalar apps desconhecidos, ligue e volte.");
            }
            return;
        }
        // Android < 12: da pra converter a origem p/ Play reinstalando, entao desinstala antes.
        // Android 12+: a origem nao pode ser forcada; so reinstala por cima (mantem dados) e o AA
        //              lista via "Fontes desconhecidas" (ver refreshStatus) — nao desinstala a toa.
        if (Build.VERSION.SDK_INT < 31 && installedInfo() != null && !installedViaPlay()) {
            status.setText("Desinstalando a versao antiga — confirme na tela do sistema.");
            Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                    Uri.parse("package:" + TARGET));
            uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            try {
                startActivityForResult(uninstall, REQ_UNINSTALL);
            } catch (Exception e) {
                try {
                    startActivityForResult(new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + TARGET)), REQ_UNINSTALL);
                } catch (Exception e2) {
                    status.setText("Nao consegui abrir a desinstalacao. Desinstale o \"Scanner\" "
                            + "na mao (Config. -> Apps -> Scanner -> Desinstalar) e toque aqui de novo.");
                }
            }
            return;
        }
        install();
    }

    private boolean open(Intent i) {
        try { startActivity(i); return true; } catch (Exception e) { return false; }
    }

    private String sourceInfo() {
        try {
            if (Build.VERSION.SDK_INT < 30) {
                return String.valueOf(getPackageManager().getInstallerPackageName(TARGET));
            }
            InstallSourceInfo s = getPackageManager().getInstallSourceInfo(TARGET);
            return s.getInstallingPackageName()
                    + (Build.VERSION.SDK_INT >= 33 ? " (src=" + s.getPackageSource() + ")" : "");
        } catch (Exception e) {
            return "?";
        }
    }

    private void install() {
        button.setEnabled(false);
        status.setText("Preparando…");
        new Thread(() -> {
            try {
                File apk = extractApk();
                main.post(() -> launchInstaller(apk));
            } catch (IOException e) {
                main.post(() -> {
                    button.setEnabled(true);
                    status.setText("Erro ao preparar o APK: " + e.getMessage());
                });
            }
        }).start();
    }

    private File extractApk() throws IOException {
        File out = new File(getCacheDir(), ASSET);
        try (InputStream in = getAssets().open(ASSET);
                OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private void launchInstaller(File apk) {
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(ApkProvider.uriFor(apk), ApkProvider.MIME);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, PLAY_STORE);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        status.setText("Confirme a instalacao na tela do sistema.\n\nSe aparecer aviso do "
                + "Play Protect, toque em \"Mais detalhes\" -> \"Instalar mesmo assim\".");
        try {
            startActivityForResult(intent, REQ_INSTALL);
        } catch (Exception e) {
            button.setEnabled(true);
            status.setText("Nao consegui abrir o instalador do sistema: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        button.setEnabled(true);
        if (requestCode == REQ_UNINSTALL) {
            if (installedInfo() == null) {
                install();
            } else {
                status.setText("A desinstalacao foi cancelada.");
            }
        } else if (requestCode == REQ_INSTALL) {
            refreshStatus();
            if (resultCode != RESULT_OK && installedInfo() == null) {
                status.setText("A instalacao foi cancelada ou falhou.");
            }
        }
    }

    private PackageInfo installedInfo() {
        try {
            return getPackageManager().getPackageInfo(TARGET, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private boolean installedViaPlay() {
        PackageManager pm = getPackageManager();
        try {
            if (Build.VERSION.SDK_INT < 30) {
                return PLAY_STORE.equals(pm.getInstallerPackageName(TARGET));
            }
            InstallSourceInfo src = pm.getInstallSourceInfo(TARGET);
            if (!PLAY_STORE.equals(src.getInstallingPackageName())) {
                return false;
            }
            return Build.VERSION.SDK_INT < 33
                    || src.getPackageSource() != PackageInstaller.PACKAGE_SOURCE_OTHER;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
            return false;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
