package com.agy.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.agy.proxy.BypassProxyServer;
import com.agy.shell.RootDetector;
import com.agy.shell.ShizukuBridge;

/**
 * Settings activity to view device bot command, update proxy credentials,
 * detect Root access, and manage Shizuku permissions.
 */
public class ProxySettingsActivity extends Activity {

    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_PERM = "moe.shizuku.manager.permission.API_V23";
    private static final int REQ_SHIZUKU = 2001;

    private TextView tvProxyStatus;
    private TextView tvShizukuStatus;
    private TextView tvRootStatus;
    private EditText etProxyKey;
    private ShizukuBridge shizukuBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Настройки Antigravity");

        shizukuBridge = new ShizukuBridge(this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrollView.setBackgroundColor(Color.parseColor("#121214"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root);

        // Header Title
        TextView tvTitle = new TextView(this);
        tvTitle.setText("Настройки Antigravity");
        tvTitle.setTextSize(20);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, dp(12));
        root.addView(tvTitle);

        // --- ROOT NOTICE BANNER (if root detected) ---
        boolean isRoot = RootDetector.isRootAvailable();
        if (isRoot) {
            LinearLayout bannerRoot = new LinearLayout(this);
            bannerRoot.setOrientation(LinearLayout.VERTICAL);
            bannerRoot.setBackgroundColor(Color.parseColor("#1B2E1E"));
            bannerRoot.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lpBanner = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpBanner.setMargins(0, 0, 0, dp(14));
            bannerRoot.setLayoutParams(lpBanner);

            TextView tvBannerTitle = new TextView(this);
            tvBannerTitle.setText("👑 Обнаружен Root-доступ");
            tvBannerTitle.setTextColor(Color.parseColor("#81C784"));
            tvBannerTitle.setTextSize(14);
            tvBannerTitle.setTypeface(null, Typeface.BOLD);
            bannerRoot.addView(tvBannerTitle);

            TextView tvBannerDesc = new TextView(this);
            tvBannerDesc.setText("На устройстве есть Root (su). Из-за наличия Root служба Shizuku не обязательна — системные команды можно выполнять напрямую. Однако использование Shizuku по-прежнему полностью разрешено и доступно ниже.");
            tvBannerDesc.setTextColor(Color.parseColor("#C8E6C9"));
            tvBannerDesc.setTextSize(12);
            tvBannerDesc.setPadding(0, dp(4), 0, dp(8));
            bannerRoot.addView(tvBannerDesc);

            Button btnTestSu = createButton("👑 Проверить Root (su -c id)");
            btnTestSu.setBackgroundColor(Color.parseColor("#2E7D32"));
            btnTestSu.setOnClickListener(v -> testSuShell());
            bannerRoot.addView(btnTestSu);

            tvRootStatus = new TextView(this);
            tvRootStatus.setTextSize(12);
            tvRootStatus.setTextColor(Color.parseColor("#A5D6A7"));
            tvRootStatus.setTypeface(Typeface.MONOSPACE);
            tvRootStatus.setPadding(0, dp(4), 0, dp(4));
            bannerRoot.addView(tvRootStatus);

            root.addView(bannerRoot);
        }

        // --- SECTION 1: PROXY & BOT COMMAND ---
        addSectionHeader(root, "1. Обход блокировок");

        // Switch toggle
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, dp(4), 0, dp(10));

        TextView tvSwitchLabel = new TextView(this);
        tvSwitchLabel.setText("Обход блокировок");
        tvSwitchLabel.setTextColor(Color.WHITE);
        tvSwitchLabel.setTextSize(15);
        tvSwitchLabel.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tvSwitchLabel.setLayoutParams(lpLabel);
        switchRow.addView(tvSwitchLabel);

        Switch swBypass = new Switch(this);
        swBypass.setChecked(BypassProxyServer.isProxyEnabled(this));
        swBypass.setOnCheckedChangeListener((buttonView, isChecked) -> {
            BypassProxyServer.setProxyEnabled(this, isChecked);
            updateProxyStatusUi();
            Toast.makeText(this, isChecked ? "Обход блокировок включен" : "Обход блокировок выключен", Toast.LENGTH_SHORT).show();
        });
        switchRow.addView(swBypass);
        root.addView(switchRow);

        final String botCmd = BypassProxyServer.getBotCommand(this);
        TextView tvCmdLabel = new TextView(this);
        tvCmdLabel.setText("Ваша команда для группы Telegram (@nova_txt):");
        tvCmdLabel.setTextColor(Color.parseColor("#AAAAAA"));
        tvCmdLabel.setTextSize(12);
        root.addView(tvCmdLabel);

        final TextView tvCmd = new TextView(this);
        tvCmd.setText(botCmd);
        tvCmd.setTextColor(Color.parseColor("#4CAF50"));
        tvCmd.setTextSize(16);
        tvCmd.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvCmd.setPadding(0, dp(2), 0, dp(4));
        tvCmd.setTextIsSelectable(true);
        root.addView(tvCmd);

        Button btnCopyCmd = createButton("📋 Скопировать команду");
        btnCopyCmd.setOnClickListener(v -> copyText(botCmd, "Команда"));
        root.addView(btnCopyCmd);

        TextView tvKeyLabel = new TextView(this);
        tvKeyLabel.setText("Ключ доступа из группы (agk1...):");
        tvKeyLabel.setTextColor(Color.parseColor("#AAAAAA"));
        tvKeyLabel.setTextSize(13);
        tvKeyLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(tvKeyLabel);

        etProxyKey = createEditText("Вставьте ключ agk1... сюда");
        root.addView(etProxyKey);

        Button btnSaveKey = createButton("💾 Сохранить и активировать ключ");
        btnSaveKey.setOnClickListener(v -> saveProxyKey());
        root.addView(btnSaveKey);

        tvProxyStatus = new TextView(this);
        tvProxyStatus.setTextSize(14);
        tvProxyStatus.setTypeface(null, Typeface.BOLD);
        tvProxyStatus.setPadding(0, dp(4), 0, dp(14));
        root.addView(tvProxyStatus);
        updateProxyStatusUi();

        // --- SECTION 2: SHIZUKU INTEGRATION ---
        addSectionHeader(root, "2. Привилегии Shell (Shizuku)");

        TextView tvShizukuHelp = new TextView(this);
        tvShizukuHelp.setText("Служба Shizuku позволяет запускать Shell и привилегированные инструменты без Root и без подключения к ПК.");
        tvShizukuHelp.setTextColor(Color.parseColor("#AAAAAA"));
        tvShizukuHelp.setTextSize(12);
        tvShizukuHelp.setPadding(0, 0, 0, dp(8));
        root.addView(tvShizukuHelp);

        Button btnRequestShizuku = createButton("⚡ Запросить разрешение Shizuku");
        btnRequestShizuku.setBackgroundColor(Color.parseColor("#43A047"));
        btnRequestShizuku.setOnClickListener(v -> requestShizukuPermission());
        root.addView(btnRequestShizuku);

        Button btnTestShizuku = createButton("🧪 Проверить Shell (whoami / id)");
        btnTestShizuku.setBackgroundColor(Color.parseColor("#1E88E5"));
        btnTestShizuku.setOnClickListener(v -> testShizukuShell());
        root.addView(btnTestShizuku);

        Button btnOpenShizukuApp = createButton("📲 Открыть приложение Shizuku");
        btnOpenShizukuApp.setBackgroundColor(Color.parseColor("#37474F"));
        btnOpenShizukuApp.setOnClickListener(v -> openShizukuApp());
        root.addView(btnOpenShizukuApp);

        tvShizukuStatus = new TextView(this);
        tvShizukuStatus.setTextSize(12);
        tvShizukuStatus.setTypeface(Typeface.MONOSPACE);
        tvShizukuStatus.setPadding(0, dp(6), 0, dp(16));
        root.addView(tvShizukuStatus);

        updateShizukuStatusUi();

        // --- SECTION 3: AUTO-UPDATE & ROLLBACK ---
        addSectionHeader(root, "3. Обновления и откат (Ядро / Обход)");

        TextView tvUpdateInfo = new TextView(this);
        tvUpdateInfo.setTextColor(Color.parseColor("#CCCCCC"));
        tvUpdateInfo.setTextSize(12);
        tvUpdateInfo.setTypeface(Typeface.MONOSPACE);
        tvUpdateInfo.setPadding(0, 0, 0, dp(8));
        String updateStatus = "Версия APK: " + com.agy.updater.UpdatePrefs.getCurrentVersionName(this)
                + " (code " + com.agy.updater.UpdatePrefs.getCurrentVersionCode(this) + ")"
                + "\nЯдро: " + com.agy.updater.UpdatePrefs.getInstalledCoreVersion(this)
                + "\nОбход: " + com.agy.updater.UpdatePrefs.getInstalledBypassVersion(this);
        if (com.agy.updater.UpdatePrefs.hasBackup(this)) {
            updateStatus += "\nБэкап: " + com.agy.updater.UpdatePrefs.getBackupSummary(this);
        }
        tvUpdateInfo.setText(updateStatus);
        root.addView(tvUpdateInfo);

        Button btnCheckUpdates = createButton("🔄 Проверить обновления");
        btnCheckUpdates.setBackgroundColor(Color.parseColor("#1976D2"));
        btnCheckUpdates.setOnClickListener(v -> {
            com.agy.updater.UpdatePrefs.clearSkippedAndRemind(this);
            com.agy.updater.UpdateUiHelper.checkManual(this, null);
        });
        root.addView(btnCheckUpdates);

        if (com.agy.updater.UpdatePrefs.hasBackup(this)) {
            Button btnRollback = createButton("⏪ Откатить обновление");
            btnRollback.setBackgroundColor(Color.parseColor("#D84315"));
            btnRollback.setOnClickListener(v -> com.agy.updater.UpdateUiHelper.confirmAndRollback(this));
            root.addView(btnRollback);
        }

        // Close button
        Button btnClose = createButton("Закрыть");
        btnClose.setBackgroundColor(Color.parseColor("#2A2A2E"));
        btnClose.setOnClickListener(v -> finish());
        root.addView(btnClose);

        TextView tvAuthor = new TextView(this);
        tvAuthor.setText("сделано @bananchik0901 обход @nova_txt");
        tvAuthor.setTextSize(14);
        tvAuthor.setTypeface(null, Typeface.BOLD);
        tvAuthor.setTextColor(Color.parseColor("#B0BEC5"));
        tvAuthor.setPadding(0, dp(14), 0, dp(4));
        root.addView(tvAuthor);

        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateProxyStatusUi();
        updateShizukuStatusUi();
    }

    private void updateProxyStatusUi() {
        boolean enabled = BypassProxyServer.isProxyEnabled(this);
        BypassProxyServer server = BypassProxyServer.getInstance();
        boolean hasKey = server != null && server.hasValidKey();

        if (!enabled) {
            tvProxyStatus.setText("🔴 Отключено");
            tvProxyStatus.setTextColor(Color.parseColor("#E57373"));
        } else if (hasKey) {
            tvProxyStatus.setText("🟢 Активно");
            tvProxyStatus.setTextColor(Color.parseColor("#81C784"));
        } else {
            tvProxyStatus.setText("⚪ Ключ не установлен");
            tvProxyStatus.setTextColor(Color.parseColor("#B0BEC5"));
        }
    }

    private void addSectionHeader(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(Color.parseColor("#64B5F6"));
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(8), 0, dp(6));
        parent.addView(tv);
    }

    private void copyText(String text, String label) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("AG Command", text));
            Toast.makeText(this, label + " скопирована: " + text, Toast.LENGTH_SHORT).show();
        }
    }

    private Button createButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.parseColor("#1E88E5"));
        btn.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        btn.setLayoutParams(lp);
        return btn;
    }

    private EditText createEditText(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#666666"));
        et.setTextColor(Color.WHITE);
        et.setBackgroundColor(Color.parseColor("#1E1E22"));
        et.setTextSize(14);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        et.setLayoutParams(lp);
        return et;
    }

    private void saveProxyKey() {
        String key = etProxyKey.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Введите ключ доступа!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg = BypassProxyServer.updateCredentials(this, key);
            updateProxyStatusUi();
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            etProxyKey.setText("");
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestShizukuPermission() {
        if (checkCallingOrSelfPermission(SHIZUKU_PERM) == PackageManager.PERMISSION_GRANTED) {
            updateShizukuStatusUi();
            testShizukuShell();
            return;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                requestPermissions(new String[]{SHIZUKU_PERM}, REQ_SHIZUKU);
            } else {
                openShizukuApp();
            }
        } catch (Exception e) {
            openShizukuApp();
        }
    }

    private void openShizukuApp() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        if (launch != null) {
            startActivity(launch);
        } else {
            Toast.makeText(this, "Приложение Shizuku не установлено", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_SHIZUKU) {
            updateShizukuStatusUi();
            testShizukuShell();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SHIZUKU) {
            updateShizukuStatusUi();
            testShizukuShell();
        }
    }

    private void updateShizukuStatusUi() {
        boolean hasPerm = checkCallingOrSelfPermission(SHIZUKU_PERM) == PackageManager.PERMISSION_GRANTED;
        if (hasPerm) {
            tvShizukuStatus.setText("🟢 Shizuku: Разрешение активно (Shell доступен)");
            tvShizukuStatus.setTextColor(Color.parseColor("#81C784"));
        } else {
            tvShizukuStatus.setText("⚪ Shizuku: Ожидает разрешения (нажмите кнопку выше)");
            tvShizukuStatus.setTextColor(Color.parseColor("#FFB74D"));
        }
    }

    private void testShizukuShell() {
        tvShizukuStatus.setText("Проверка выполнения через Shizuku rish...");
        tvShizukuStatus.setTextColor(Color.parseColor("#64B5F6"));
        new Thread(() -> {
            final String res = shizukuBridge.exec("id");
            runOnUiThread(() -> {
                if (res != null && (res.contains("uid=2000") || res.contains("uid=0"))) {
                    tvShizukuStatus.setText("🟢 Shizuku OK: " + res);
                    tvShizukuStatus.setTextColor(Color.parseColor("#81C784"));
                    Toast.makeText(this, "Shell успешно работает через Shizuku!", Toast.LENGTH_LONG).show();
                } else {
                    tvShizukuStatus.setText("Статус Shizuku: " + res);
                    tvShizukuStatus.setTextColor(Color.parseColor("#FFB74D"));
                }
            });
        }).start();
    }

    private void testSuShell() {
        if (tvRootStatus != null) {
            tvRootStatus.setText("Проверка su...");
        }
        new Thread(() -> {
            final String res = RootDetector.testSu();
            runOnUiThread(() -> {
                if (tvRootStatus != null) {
                    tvRootStatus.setText(res);
                }
                Toast.makeText(this, res, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
