package net.murilo.scanner.installer;

import android.app.Activity;
import android.app.PendingIntent;
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
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Instala o app Scanner declarando o Google Play como origem — o Android Auto so lista apps da
 * Car App Library cuja instalacao veio da Play.
 *
 * Dois caminhos, porque o jeito antigo deixou de funcionar no meio do caminho:
 *  - Android <= 11 (ex.: a central NWD/Android 10): intent legado ACTION_INSTALL_PACKAGE +
 *    EXTRA_INSTALLER_PACKAGE_NAME — COMPROVADO, marca a origem como Play.
 *  - Android 12+ (ex.: Moto G77/Android 16): o extra acima e IGNORADO, entao usa
 *    PackageInstaller.Session com setInstallerPackageName(Play) e, no 13+,
 *    setPackageSource(PACKAGE_SOURCE_STORE). Obs.: app comum nao consegue cravar o nome
 *    "com.android.vending" (precisa de permissao de sistema) — a tela mostra a origem que ficou;
 *    se nao ficar Play, use tambem "Fontes desconhecidas" no modo dev do Android Auto.
 */
public final class InstallerActivity extends Activity {

    private static final String TARGET = "net.murilo.scanner";
    private static final String PLAY_STORE = "com.android.vending";
    private static final String ASSET = "Scanner.apk";
    private static final String ACTION_RESULT = "net.murilo.scanner.installer.INSTALL_RESULT";
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
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        button = new Button(this);
        button.setOnClickListener(v -> start());
        root.addView(button);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        handleSessionResult(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSessionResult(intent);
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
            status.setText("Scanner " + info.versionName + " instalado com origem Play.\n\n"
                    + "Origem: " + sourceInfo() + "\n\n"
                    + "Se ainda nao aparecer no Android Auto, force a parada do Android Auto "
                    + "(Config. -> Apps -> Android Auto -> Forcar parada) e reconecte.");
            button.setText("Reinstalar / atualizar");
        } else {
            status.setText("Scanner " + info.versionName + " instalado, mas a origem NAO ficou como Play.\n\n"
                    + "Origem atual: " + sourceInfo() + "\n\n"
                    + "Toque abaixo para reinstalar declarando a Play. Se mesmo assim nao ficar "
                    + "(limitacao do Android 12+), ligue \"Fontes desconhecidas\" no modo "
                    + "desenvolvedor do Android Auto.");
            button.setText("Corrigir instalacao");
        }
        button.setEnabled(true);
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
        if (Build.VERSION.SDK_INT < 31) {
            // Android <= 11 (central NWD/Android 10): metodo legado, que marca a origem Play.
            // Para trocar a origem e preciso reinstalar do zero, entao desinstala a versao atual antes.
            if (installedInfo() != null && !installedViaPlay()) {
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
                        status.setText("Desinstale o \"Scanner\" na mao (Config. -> Apps -> Scanner "
                                + "-> Desinstalar) e toque aqui de novo.");
                    }
                }
                return;
            }
            installLegacy();
        } else {
            installSession();
        }
    }

    private boolean open(Intent i) {
        try { startActivity(i); return true; } catch (Exception e) { return false; }
    }

    // --- Android <= 11: intent legado (ACTION_INSTALL_PACKAGE), via FileProvider ---

    private void installLegacy() {
        button.setEnabled(false);
        status.setText("Preparando…");
        new Thread(() -> {
            try {
                File apk = extractApk();
                main.post(() -> launchLegacy(apk));
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
    private void launchLegacy(File apk) {
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
                installLegacy();
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

    // --- Android 12+: PackageInstaller.Session ---

    private void installSession() {
        button.setEnabled(false);
        status.setText("Instalando…");
        new Thread(() -> {
            try {
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                // NAO dá pra fingir ser a Play: o sistema rejeita com SecurityException
                // ("com.android.vending does not belong to <uid>"). O máximo possível é declarar
                // que veio de uma loja (source=STORE) — isso um app comum pode.
                if (Build.VERSION.SDK_INT >= 33) {
                    try { params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE); } catch (Throwable ignore) {}
                }
                int sid = pi.createSession(params);
                try (PackageInstaller.Session session = pi.openSession(sid)) {
                    try (InputStream in = getAssets().open(ASSET);
                            OutputStream out = session.openWrite("apk", 0, -1)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        session.fsync(out);
                    }
                    Intent cb = new Intent(this, InstallerActivity.class).setAction(ACTION_RESULT);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pending = PendingIntent.getActivity(this, 0, cb, flags);
                    session.commit(pending.getIntentSender());
                }
                main.post(() -> status.setText("Confirme a instalacao na tela do sistema.\n\nSe o "
                        + "Play Protect avisar, toque em \"Mais detalhes\" -> \"Instalar mesmo assim\"."));
            } catch (Exception e) {
                main.post(() -> {
                    button.setEnabled(true);
                    status.setText("Erro ao instalar: " + e.getMessage());
                });
            }
        }).start();
    }

    /** Retorno do PackageInstaller.Session: pede confirmacao do sistema, ou mostra sucesso/falha. */
    private void handleSessionResult(Intent intent) {
        if (intent == null || !ACTION_RESULT.equals(intent.getAction())) {
            return;
        }
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    startActivity(confirm);
                } catch (Exception e) {
                    button.setEnabled(true);
                    status.setText("Nao consegui abrir a confirmacao: " + e.getMessage());
                }
            }
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            main.post(this::refreshStatus);
        } else if (st != -999) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            button.setEnabled(true);
            status.setText("A instalacao falhou" + (msg != null ? ": " + msg : "") + "."
                    + "\n\nSe falou de assinatura, desinstale o \"Scanner\" antigo "
                    + "(Config. -> Apps -> Scanner -> Desinstalar) e tente de novo.");
        }
    }

    // --- comum ---

    private String sourceInfo() {
        try {
            if (Build.VERSION.SDK_INT < 30) {
                return String.valueOf(getPackageManager().getInstallerPackageName(TARGET));
            }
            InstallSourceInfo s = getPackageManager().getInstallSourceInfo(TARGET);
            return s.getInstallingPackageName()
                    + (Build.VERSION.SDK_INT >= 33 ? " (source=" + s.getPackageSource() + ")" : "");
        } catch (Exception e) {
            return "?";
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
            if (PLAY_STORE.equals(src.getInstallingPackageName())) {
                return true;
            }
            return Build.VERSION.SDK_INT >= 33
                    && src.getPackageSource() == PackageInstaller.PACKAGE_SOURCE_STORE;
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
