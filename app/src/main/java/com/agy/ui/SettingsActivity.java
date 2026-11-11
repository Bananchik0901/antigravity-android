package com.agy.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.agy.BuildConfig;
import com.agy.shell.RootDetector;
import com.agy.shell.ShizukuBridge;

/**
 * Settings UI for the Clean / Vanilla Antigravity port on Android.
 * Manages Shell privileges (Root & Shizuku) and displays Core server state.
 * Contains no proxy bypass logic or external traffic interception.
 */
public class SettingsActivity extends Activity {

    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_PERM = "moe.shizuku.manager.permission.API_V23";
    private static final int REQ_SHIZUKU = 101;

    private TextView tvShizukuStatus;
    private TextView tvRootStatus;
    private TextView tvServerInfo;

    private ShizukuBridge shizukuBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Настройки Antigravity (Vanilla)");

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
        tvTitle.setPadding(0, 0, 0, dp(4));
        root.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("Чистый ванильный порт (Android 16, без обхода блокировок)");
        tvSubtitle.setTextSize(12);
        tvSubtitle.setTextColor(Color.parseColor("#888888"));
        tvSubtitle.setPadding(0, 0, 0, dp(14));
        root.addView(tvSubtitle);

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
            tvRootStatus.setTypeface(Typeface.MONOSPACE);
            tvRootStatus.setPadding(0, dp(6), 0, 0);
            bannerRoot.addView(tvRootStatus);

            root.addView(bannerRoot);
        }

        // --- SECTION 1: SHELL PRIVILEGES (SHIZUKU & ROOT) ---
        addSectionHeader(root, "1. Привилегии Shell (Shizuku & Root)");

        TextView tvShizukuHelp = new TextView(this);
        tvShizukuHelp.setText("Служба Shizuku позволяет Antigravity запускать терминал и системные команды с повышенными правами без Root и без подключения к ПК.");
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

        // --- SECTION 2: NETWORK & CORE STATUS ---
        addSectionHeader(root, "2. Сеть и подключение к API");

        TextView tvNetHelp = new TextView(this);
        tvNetHelp.setText("Режим: Прямое подключение (Vanilla / Direct)\n\n"
                + "Ядро Antigravity подключается напрямую к серверам Google API без локального прокси и без перехвата трафика.\n\n"
                + "• Если сервисы Google доступны в вашей сети, приложение работает максимально быстро с минимальной задержкой.\n"
                + "• Если доступ к Google API в вашей стране заблокирован, используйте системный VPN (на уровне Android) или установите сборку Antigravity Mobile с обходом (Bypass Edition).");
        tvNetHelp.setTextColor(Color.parseColor("#CCCCCC"));
        tvNetHelp.setTextSize(13);
        tvNetHelp.setPadding(0, 0, 0, dp(12));
        root.addView(tvNetHelp);

        tvServerInfo = new TextView(this);
        tvServerInfo.setText("Шлюз: 127.0.0.1:" + BuildConfig.GATEWAY_PORT + "\nЯдро: Google Language Server (официальное)\nСтатус: Готово к работе");
        tvServerInfo.setTextColor(Color.parseColor("#81C784"));
        tvServerInfo.setTextSize(12);
        tvServerInfo.setTypeface(Typeface.MONOSPACE);
        tvServerInfo.setPadding(dp(10), dp(10), dp(10), dp(10));
        tvServerInfo.setBackgroundColor(Color.parseColor("#1E2522"));
        root.addView(tvServerInfo);

        // --- CLOSE BUTTON ---
        Button btnClose = createButton("✓ Закрыть настройки");
        btnClose.setBackgroundColor(Color.parseColor("#455A64"));
        btnClose.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpClose.setMargins(0, dp(20), 0, 0);
        btnClose.setLayoutParams(lpClose);
        root.addView(btnClose);

        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShizukuStatusUi();
    }

    private void addSectionHeader(LinearLayout root, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(Color.parseColor("#64B5F6"));
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(10), 0, dp(6));
        root.addView(tv);
    }

    private Button createButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        btn.setLayoutParams(lp);
        return btn;
    }

    private void requestShizukuPermission() {
        try {
            Intent reqIntent = new Intent("moe.shizuku.privileged.api.intent.action.REQUEST_PERMISSION");
            reqIntent.setPackage(SHIZUKU_PKG);
            startActivityForResult(reqIntent, REQ_SHIZUKU);
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
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + SHIZUKU_PKG)));
            } catch (Exception ignored) {
            }
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
        if (tvShizukuStatus == null) return;
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
