package com.bajajnav.relay;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Programmatic-view helpers shared by the two screens.
 *
 * The app uses the platform theme rather than a custom one, so every colour is
 * resolved from a theme attribute and follows light/dark mode automatically.
 */
final class Ui {
    private Ui() {
    }

    static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static int color(Activity a, int attr) {
        TypedValue tv = new TypedValue();
        if (a.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                return a.getResources().getColor(tv.resourceId, a.getTheme());
            }
            return tv.data;
        }
        return 0xFF000000;
    }

    static int primary(Activity a) {
        return color(a, android.R.attr.textColorPrimary);
    }

    static int secondary(Activity a) {
        return color(a, android.R.attr.textColorSecondary);
    }

    static int divider(Activity a) {
        return (secondary(a) & 0x00FFFFFF) | 0x33000000;
    }

    /** Section header: small, upper-case, quiet. */
    static TextView section(Activity a, String title) {
        TextView t = new TextView(a);
        t.setText(title.toUpperCase(Locale.US));
        t.setTextSize(12);
        t.setLetterSpacing(0.1f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(secondary(a));
        t.setPadding(0, dp(a, 16), 0, dp(a, 2));
        return t;
    }

    static View rule(Activity a) {
        View v = new View(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 0.5f)));
        lp.topMargin = dp(a, 18);
        v.setLayoutParams(lp);
        v.setBackgroundColor(divider(a));
        return v;
    }

    /** One "label … value" line. Returns the value view so it can be updated. */
    static TextView row(Activity a, LinearLayout parent, String label) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(a, 5), 0, dp(a, 5));

        TextView l = new TextView(a);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(secondary(a));
        l.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(a);
        value.setTextSize(14);
        value.setTextColor(primary(a));
        value.setGravity(Gravity.END);

        r.addView(l);
        r.addView(value);
        parent.addView(r);
        return value;
    }

    static View fullWidth(View child) {
        child.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return child;
    }

    static LinearLayout buttonRow(Activity a, Button... buttons) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < buttons.length; i++) {
            Button b = buttons[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.leftMargin = dp(a, 8);
            }
            b.setLayoutParams(lp);
            row.addView(b);
        }
        return row;
    }

    /** Plain system button, mixed case (the default theme forces all-caps). */
    static Button button(Activity a, String text) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }
}
