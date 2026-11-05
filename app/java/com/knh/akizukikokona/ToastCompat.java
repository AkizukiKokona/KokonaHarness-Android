package com.knh.akizukikokona;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Toast that is safe to call from the JS bridge threads. */
final class ToastCompat {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ToastCompat() {
    }

    static void show(final Context context, final String message) {
        if (context == null || message == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
