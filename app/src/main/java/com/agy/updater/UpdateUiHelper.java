package com.agy.updater;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Shared UI helper for startup update prompt, manual update check,
 * selective component checkboxes (hiding components with no update),
 * and rollback confirmation.
 */
public final class UpdateUiHelper {

    private UpdateUiHelper() {}

    public static void checkAtStartup(Activity activity) {
        if (UpdatePrefs.isDoNotRemind(activity)) return;
        new Thread(() -> {
            UpstreamChecker.CheckResult res = UpstreamChecker.check(activity);
            if (!res.hasAnyUpdate() || res.isSkippedByUser(activity)) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    showUpdateDialog(activity, res, true);
                }
            });
        }, "StartupUpdateCheck").start();
    }

    public static void checkManual(Activity activity, Runnable onStatusChanged) {
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage("Проверка обновлений Ядра и Обхода...");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            UpstreamChecker.CheckResult res = UpstreamChecker.check(activity);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (pd.isShowing()) pd.dismiss();
                if (activity.isFinishing() || activity.isDestroyed()) return;

                if (onStatusChanged != null) onStatusChanged.run();

                if (!res.hasAnyUpdate()) {
                    String msg = "Установлены актуальные версии:\n• Ядро: " + res.installedCoreVersion;
                    if (!UpdatePrefs.isVanillaBuild()) {
                        msg += "\n• Обход: " + res.installedBypassVersion;
                    }
                    if (res.coreError != null || res.bypassError != null) {
                        msg += "\n\nОшибки проверки:";
                        if (res.coreError != null) msg += "\n- Ядро: " + res.coreError;
                        if (res.bypassError != null) msg += "\n- Обход: " + res.bypassError;
                    }
                    new AlertDialog.Builder(activity)
                            .setTitle("Обновлений нет")
                            .setMessage(msg)
                            .setPositiveButton("ОК", null)
                            .show();
                    return;
                }
                showUpdateDialog(activity, res, false);
            });
        }, "ManualUpdateCheck").start();
    }

    public static void showUpdateDialog(Activity activity, UpstreamChecker.CheckResult res, boolean fromStartup) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        TextView info = new TextView(activity);
        StringBuilder sb = new StringBuilder("Обнаружены обновления:\n");
        if (res.hasCoreUpdate) {
            sb.append("• Ядро: ").append(res.installedCoreVersion)
                    .append(" → ").append(res.latestCoreVersion).append("\n");
        }
        if (res.hasBypassUpdate) {
            sb.append("• Обход: ").append(res.installedBypassVersion)
                    .append(" → ").append(res.latestBypassVersion).append("\n");
            if (res.bypassRequiresManualCodeUpdate) {
                sb.append("⚠ Внимание: в upstream обнаружен новый протокол (AGU3), может потребоваться обновление приложения.\n");
            }
        }
        if (res.hasDriveUpdate) {
            sb.append("• Приложение (Google Диск): ").append(res.installedCoreVersion)
                    .append(" → ").append(res.latestDriveVersion)
                    .append(" (").append(res.latestDriveApkName).append(")\n");
        }
        info.setText(sb.toString().trim());
        box.addView(info);

        // Only offer components that actually have an update!
        final CheckBox cbCore = res.hasCoreUpdate ? new CheckBox(activity) : null;
        if (cbCore != null) {
            cbCore.setText("Обновить Ядро (" + res.latestCoreVersion + ", ~226 МБ)");
            cbCore.setChecked(true);
            box.addView(cbCore);
        }

        final CheckBox cbBypass = res.hasBypassUpdate ? new CheckBox(activity) : null;
        if (cbBypass != null) {
            cbBypass.setText("Обновить Обход (" + res.latestBypassVersion + ")");
            cbBypass.setChecked(true);
            box.addView(cbBypass);
        }

        final CheckBox cbDrive = res.hasDriveUpdate ? new CheckBox(activity) : null;
        if (cbDrive != null) {
            cbDrive.setText("Скачать готовый APK с Google Диска (" + res.latestDriveVersion + ", уже со всеми компонентами)");
            boolean preferDrive = !res.hasCoreUpdate && !res.hasBypassUpdate;
            cbDrive.setChecked(preferDrive);
            box.addView(cbDrive);
            cbDrive.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) {
                    if (cbCore != null) cbCore.setChecked(false);
                    if (cbBypass != null) cbBypass.setChecked(false);
                }
            });
        }
        if (cbCore != null) {
            cbCore.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked && cbDrive != null) cbDrive.setChecked(false);
            });
        }
        if (cbBypass != null) {
            cbBypass.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked && cbDrive != null) cbDrive.setChecked(false);
            });
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Доступно обновление")
                .setView(box)
                .setPositiveButton("Скачать", (d, w) -> {
                    boolean doDrive = cbDrive != null && cbDrive.isChecked();
                    boolean doCore = cbCore != null && cbCore.isChecked();
                    boolean doBypass = cbBypass != null && cbBypass.isChecked();
                    if (doDrive) {
                        startDriveInstall(activity, res);
                        return;
                    }
                    if (!doCore && !doBypass) {
                        Toast.makeText(activity, "Выберите хотя бы один компонент", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startRebuildAndInstall(activity, res, doCore, doBypass);
                })
                .setNegativeButton(fromStartup ? "Обновить позже" : "Отмена", null);

        final AlertDialog dialog = builder.create();
        if (fromStartup) {
            android.widget.Button btnSkip = new android.widget.Button(activity);
            btnSkip.setText("Пропустить версию");
            btnSkip.setOnClickListener(v -> {
                UpdatePrefs.setSkippedVersions(
                        activity,
                        res.hasCoreUpdate ? res.latestCoreVersion : null,
                        res.hasBypassUpdate ? res.latestBypassVersion : null,
                        res.hasDriveUpdate ? res.latestDriveVersion : null);
                Toast.makeText(activity, "Версия пропущена", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            box.addView(btnSkip);

            android.widget.Button btnNever = new android.widget.Button(activity);
            btnNever.setText("Не напоминать больше");
            btnNever.setOnClickListener(v -> {
                UpdatePrefs.setDoNotRemind(activity, true);
                Toast.makeText(activity, "Напоминания отключены (можно включить в Настройках)", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            box.addView(btnNever);
        }
        dialog.show();
    }

    private static void startDriveInstall(Activity activity, UpstreamChecker.CheckResult res) {
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setTitle("Загрузка с Google Диска");
        pd.setMessage("Подготовка...");
        pd.setCancelable(false);
        pd.show();

        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File apk = ApkRepackagerAndSigner.downloadDriveApk(
                        activity, res.latestDriveDownloadUrl,
                        msg -> main.post(() -> {
                            if (pd.isShowing()) pd.setMessage(msg);
                        }));
                main.post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        ApkRepackagerAndSigner.launchInstaller(activity, apk);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Ошибка загрузки")
                                .setMessage(e.getMessage())
                                .setPositiveButton("ОК", null)
                                .show();
                    }
                });
            }
        }, "DriveApkDownload").start();
    }

    private static void startRebuildAndInstall(Activity activity, UpstreamChecker.CheckResult res,
                                               boolean doCore, boolean doBypass) {
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setTitle("Пересборка APK на устройстве");
        pd.setMessage("Подготовка...");
        pd.setCancelable(false);
        pd.show();

        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File apk = ApkRepackagerAndSigner.rebuildAndSignUpdate(
                        activity, res, doCore, doBypass,
                        msg -> main.post(() -> {
                            if (pd.isShowing()) pd.setMessage(msg);
                        }));
                main.post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        ApkRepackagerAndSigner.launchInstaller(activity, apk);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Ошибка обновления")
                                .setMessage(e.getMessage())
                                .setPositiveButton("ОК", null)
                                .show();
                    }
                });
            }
        }, "ApkRebuild").start();
    }

    public static void confirmAndRollback(Activity activity) {
        if (!UpdatePrefs.hasBackup(activity)) {
            Toast.makeText(activity, "Нет сохранённой резервной копии для отката", Toast.LENGTH_SHORT).show();
            return;
        }
        String summary = UpdatePrefs.getBackupSummary(activity);
        new AlertDialog.Builder(activity)
                .setTitle("Откатить обновление?")
                .setMessage("Вернуться к предыдущей версии:\n" + summary
                        + "\n\nПри наличии Root/Shizuku будет выполнен прямой откат Android (pm install -r -d), иначе — перепаковка с versionCode+1.")
                .setPositiveButton("Откатить", (d, w) -> {
                    ProgressDialog pd = new ProgressDialog(activity);
                    pd.setTitle("Откат обновления");
                    pd.setMessage("Подготовка отката...");
                    pd.setCancelable(false);
                    pd.show();

                    Handler main = new Handler(Looper.getMainLooper());
                    RollbackManagerCompat.performRollback(activity, new RollbackManagerCompat.Callback() {
                        @Override
                        public void onProgress(String msg) {
                            main.post(() -> {
                                if (pd.isShowing()) pd.setMessage(msg);
                            });
                        }

                        @Override
                        public void onDone(boolean launchedInstaller, String message) {
                            main.post(() -> {
                                if (pd.isShowing()) pd.dismiss();
                                if (!launchedInstaller) {
                                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                                }
                            });
                        }

                        @Override
                        public void onError(String err) {
                            main.post(() -> {
                                if (pd.isShowing()) pd.dismiss();
                                Toast.makeText(activity, err, Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}
