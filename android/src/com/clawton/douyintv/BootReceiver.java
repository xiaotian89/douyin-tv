package com.clawton.douyintv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：系统启动完成后直接把直播列表拉起来。
 * 兼容部分盒子发的是 QUICKBOOT_POWERON 而不是标准 BOOT_COMPLETED。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent it) {
        String a = it == null ? "" : String.valueOf(it.getAction());
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (!boot) return;
        try {
            Intent i = new Intent(c, LiveGridActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(c, HomeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }
}
