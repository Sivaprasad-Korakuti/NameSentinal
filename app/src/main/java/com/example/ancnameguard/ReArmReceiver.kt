package com.example.ancnameguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat

/** After a reboot, or when earbuds connect while the guard was killed by the system, offer a one-tap resume. */
class ReArmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs.of(ctx).getBoolean(Prefs.ARMED, false) || GuardState.running) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rearm", "Resume guard", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java).putExtra("arm", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(3, NotificationCompat.Builder(ctx, "rearm")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Name Sentinel is asleep")
            .setContentText("Tap to resume guarding your name.")
            .setContentIntent(pi).setAutoCancel(true).build())
    }
}
