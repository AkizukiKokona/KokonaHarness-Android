package com.kokonaharness.android.akizuki;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Modal in the runtime's own visual language: white card, black text, one black
 * primary button with white label, everything else as plain text buttons.
 *
 * <p>The DSH UI already shows its confirmations this way (the permission-preset
 * prompt on first launch: "确认启用完全权限？" / "我已了解风险，并愿意继续"). A stock
 * Android AlertDialog sitting next to that reads as a different app, which is
 * exactly why the app stopped using it.</p>
 *
 * <p>Kept as a plain Dialog rather than an AlertDialog subclass so the card can
 * own its padding and corner radius without fighting the platform theme.</p>
 */
final class KhaDialog {

    private static final int BLACK = 0xff111111;
    private static final int WHITE = 0xffffffff;
    private static final int BODY = 0xff333333;
    private static final int MUTED = 0xff666666;

    private final Context context;
    private final String title;
    private final String message;
    private String primaryLabel;
    private Runnable primaryAction;
    private final List<String> extraLabels = new ArrayList<String>();
    private final List<Runnable> extraActions = new ArrayList<Runnable>();
    private boolean cancelable = true;
    private boolean actionsVertical = true;

    private KhaDialog(Context context, String title, String message) {
        this.context = context;
        this.title = title;
        this.message = message;
    }

    static KhaDialog create(Context context, String title, String message) {
        return new KhaDialog(context, title, message);
    }

    KhaDialog primary(String label, Runnable action) {
        this.primaryLabel = label;
        this.primaryAction = action;
        return this;
    }

    KhaDialog extra(String label, Runnable action) {
        if (TextUtils.isEmpty(label)) return this;
        extraLabels.add(label);
        extraActions.add(action);
        return this;
    }

    KhaDialog cancelable(boolean value) {
        this.cancelable = value;
        return this;
    }

    /** Lays the secondary actions out side by side instead of stacked. */
    KhaDialog actionsInline(boolean value) {
        this.actionsVertical = !value;
        return this;
    }

    Dialog show() {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setDimAmount(0.45f);
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(WHITE);
        background.setCornerRadius(dp(16));
        card.setBackground(background);

        if (!TextUtils.isEmpty(title)) {
            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(BLACK);
            titleView.setTextSize(17);
            titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
            titleView.setLineSpacing(dp(4), 1f);
            card.addView(titleView, wrap());
        }

        if (!TextUtils.isEmpty(message)) {
            ScrollView scroller = new ScrollView(context);
            TextView body = new TextView(context);
            body.setText(message);
            body.setTextColor(BODY);
            body.setTextSize(13);
            body.setLineSpacing(dp(5), 1f);
            body.setTextIsSelectable(true);
            int pad = dp(12);
            scroller.setPadding(0, pad, 0, pad);
            scroller.addView(body, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(scroller, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(actionsVertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        if (actionsVertical) {
            actions.setPadding(0, dp(6), 0, 0);
        } else {
            actions.setGravity(Gravity.CENTER_VERTICAL);
        }

        if (!TextUtils.isEmpty(primaryLabel)) {
            Button primary = new Button(context);
            primary.setAllCaps(false);
            primary.setText(primaryLabel);
            primary.setTextColor(WHITE);
            primary.setTextSize(14);
            GradientDrawable primaryBg = new GradientDrawable();
            primaryBg.setColor(BLACK);
            primaryBg.setCornerRadius(dp(10));
            primary.setBackground(primaryBg);
            primary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (primaryAction != null) primaryAction.run();
                }
            });
            actions.addView(primary, buttonParams());
        }

        for (int i = 0; i < extraLabels.size(); i++) {
            final String label = extraLabels.get(i);
            final Runnable action = extraActions.get(i);
            Button secondary = new Button(context);
            secondary.setAllCaps(false);
            secondary.setText(label);
            secondary.setTextColor(BLACK);
            secondary.setTextSize(14);
            secondary.setBackgroundColor(Color.TRANSPARENT);
            secondary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (action != null) action.run();
                }
            });
            actions.addView(secondary, buttonParams());
        }
        card.addView(actions, wrap());

        dialog.setContentView(card);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            int width = Math.min((int) (context.getResources().getDisplayMetrics().widthPixels * 0.9f),
                    dp(420));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                actionsVertical ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                dp(44), actionsVertical ? 1f : 0f);
        params.topMargin = dp(6);
        if (!actionsVertical) {
            params.weight = 1f;
            params.setMargins(dp(4), dp(6), dp(4), 0);
        }
        return params;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    static int mutedColor() {
        return MUTED;
    }
}
