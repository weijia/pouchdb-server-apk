package net.weijia.pouchdbserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机 / 应用更新后自动拉起前台服务，使服务与通知独立于 Activity 生命周期
 * （参照 go-stock-server 的 BootReceiver）。
 *
 * 注意：Android 3.1+ 对已安装但未首次启动的 App 不投递 BOOT_COMPLETED，
 * 因此本 Receiver 在用户首次打开 App 之后才会真正生效——这与预期一致。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Log.d(TAG, "收到 action=" + action);

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
