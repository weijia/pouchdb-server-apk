package net.weijia.pouchdbserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * 开机 / 应用更新后自动拉起前台服务，使服务与通知独立于 Activity 生命周期
 * （参照 go-stock-server 的 BootReceiver）。
 *
 * 注意：Android 3.1+ 对已安装但未首次启动的 App 不投递 BOOT_COMPLETED，
 * 因此本 Receiver 在用户首次打开 App 之后才会真正生效——这与预期一致。
 *
 * 守护：Android 13+ 必须已获 POST_NOTIFICATIONS 才能 startForeground，否则 Android 14 会直接强杀进程。
 * 未授权时跳过自启，交由用户打开 App 点“启动服务”时引导授权。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Log.d(TAG, "收到 action=" + action);

        // Android 13+ 先确认通知权限，避免无权限起前台服务导致秒退
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "未获通知权限，跳过自启（请打开 App 点“启动服务”授权）");
            return;
        }

        Intent svc = new Intent(context, NodeService.class);
        svc.setAction(NodeService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务失败", e);
        }
    }
}
