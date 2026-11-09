package com.kokonaharness.android.akizuki;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * A compact terminal sheet implemented entirely on the Android side.
 *
 * It keeps one long-lived bash process from the bundled runtime, so shell state
 * (cwd, exports, aliases) survives between commands, and streams stdout/stderr
 * into a monospace view. The sheet is dismissed by a left swipe or the back
 * key. Nothing in the dsh front-end is modified to make this work.
 */
final class TerminalOverlay {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context context;
    private final FrameLayout host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final View sheet;
    private final TextView output;
    private final ScrollView scroller;
    private final EditText input;

    private Process process;
    private BufferedWriter writer;
    private Thread reader;
    private boolean shown;

    TerminalOverlay(Context context, FrameLayout host) {
        this.context = context;
        this.host = host;

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xfff7f8fa);

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xff232833);
        bar.setPadding(dp(16), dp(10), dp(8), dp(10));

        TextView title = new TextView(context);
        title.setText("KHA 终端");
        title.setTextColor(0xfff2f4f8);
        title.setTextSize(15f);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        TextView hint = new TextView(context);
        hint.setText("左滑 / 返回键退出");
        hint.setTextColor(0xff9aa4b8);
        hint.setTextSize(11f);
        bar.addView(hint);

        Button close = new Button(context);
        close.setText("×");
        close.setTextSize(18f);
        close.setTextColor(0xfff2f4f8);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(close);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });
        panel.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        output = new TextView(context);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(12f);
        output.setTextColor(0xffe8ecf4);
        output.setPadding(dp(12), dp(10), dp(12), dp(10));
        scroller = new ScrollView(context);
        scroller.setBackgroundColor(0xff141821);
        scroller.addView(output, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xff1b202b);

        input = new EditText(context);
        input.setHint("输入命令，回车执行");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(13f);
        input.setTextColor(0xfff2f4f8);
        input.setHintTextColor(0xff7b8699);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(12), dp(8), dp(4), dp(8));
        row.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button run = new Button(context);
        run.setText("运行");
        run.setAllCaps(false);
        run.setTextSize(12f);
        run.setTextColor(0xfff2f4f8);
        run.setBackgroundColor(Color.TRANSPARENT);
        row.addView(run, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        run.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                    submit();
                    return true;
                }
                return false;
            }
        });

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xfff7f8fa);
        shape.setCornerRadius(dp(16));
        panel.setBackground(shape);

        sheet = panel;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.BOTTOM);
        lp.topMargin = dp(72);
        sheet.setVisibility(View.GONE);
        host.addView(sheet, lp);

        sheet.setOnTouchListener(new View.OnTouchListener() {
            private float x0;
            private float y0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    x0 = event.getX();
                    y0 = event.getY();
                    return false;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    float dx = event.getX() - x0;
                    float dy = event.getY() - y0;
                    if (dx < -dp(70) && Math.abs(dy) < dp(60)) {
                        hide();
                        return true;
                    }
                }
                return false;
            }
        });
    }

    boolean isShown() {
        return shown;
    }

    void show() {
        if (shown) return;
        shown = true;
        sheet.setVisibility(View.VISIBLE);
        output.setText("");
        append("[KHA 终端] 已连接内置运行时（bionic 原生）。\n"
                + "输入命令后回车执行；左滑或按返回键退出本界面。\n"
                + "可直接运行 dsh、python3、git、pnpm 等内置工具。\n\n");
        startShell();
    }

    void hide() {
        if (!shown) return;
        shown = false;
        sheet.setVisibility(View.GONE);
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
            writer = null;
        }
        if (process != null) {
            process.destroy();
            process = null;
        }
        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
    }

    private void startShell() {
        try {
            File runtime = RuntimeInstaller.runtimeDir(context);
            String rt = runtime.getAbsolutePath();
            File home = new File(context.getFilesDir(), "home");
            File tmp = new File(context.getCacheDir(), "tmp");
            if (!home.isDirectory()) home.mkdirs();
            if (!tmp.isDirectory()) tmp.mkdirs();

            ProcessBuilder builder = new ProcessBuilder(
                    rt + "/bin/bash", "--noprofile", "--norc");
            Map<String, String> env = builder.environment();
            env.put("HOME", home.getAbsolutePath());
            env.put("DSH_HOME", new File(home, ".dsh").getAbsolutePath());
            env.put("PREFIX", rt);
            env.put("KOKONA_RUNTIME", rt);
            env.put("TERMUX__PREFIX", rt);
            env.put("PATH", rt + "/bin:/system/bin:/system/xbin");
            env.put("LD_LIBRARY_PATH", rt + "/lib");
            env.put("TMPDIR", tmp.getAbsolutePath());
            env.put("TERM", "dumb");
            env.put("LANG", "en_US.UTF-8");
            env.put("LC_ALL", "en_US.UTF-8");
            env.put("SHELL", rt + "/bin/bash");
            env.put("SSL_CERT_FILE", rt + "/etc/tls/cert.pem");
            env.put("OPENSSL_CONF", rt + "/etc/tls/openssl.cnf");
            env.put("SSL_CERT_DIR", rt + "/etc/tls");
            env.put("npm_config_registry", "https://registry.npmmirror.com");
            builder.directory(home);
            builder.redirectErrorStream(true);
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), UTF8));

            final Process current = process;
            reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(current.getInputStream(), UTF8));
                        String line;
                        while (!Thread.currentThread().isInterrupted()
                                && (line = in.readLine()) != null) {
                            append(line + "\n");
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "kha-terminal-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception error) {
            append("终端启动失败：" + error.getMessage() + "\n");
        }
    }

    private void submit() {
        String command = input.getText().toString().trim();
        input.setText("");
        if (command.length() == 0) return;
        append("> " + command + "\n");
        if ("exit".equals(command) || "quit".equals(command)) {
            hide();
            return;
        }
        send(command + "\n");
    }

    private void send(String text) {
        BufferedWriter target = writer;
        if (target == null) return;
        try {
            target.write(text);
            target.flush();
        } catch (Exception error) {
            append("[写入失败] " + error.getMessage() + "\n");
        }
    }

    private void append(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                output.append(text);
                scroller.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
