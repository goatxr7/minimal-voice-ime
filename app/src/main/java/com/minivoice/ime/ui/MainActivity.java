package com.minivoice.ime.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.minivoice.ime.R;
import com.minivoice.ime.service.KeyInterceptorService;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvState;
    private Button btnEnable;
    private Button btnSelect;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 应用一打开就在后台把 Rime 部署好，避免第一次唤起键盘时还在建索引、打字没候选
        com.minivoice.ime.input.RimeEngine.initAsync(getApplicationContext());

        tvState = findViewById(R.id.tv_state);

        btnEnable = findViewById(R.id.btn_enable);
        btnSelect = findViewById(R.id.btn_select);
        Button btnSettings = findViewById(R.id.btn_settings);

        btnEnable.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            scheduleRefresh();
        });

        btnSelect.setOnClickListener(v -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
            scheduleRefresh();
        });

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        btnSettings.setOnLongClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateState();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateState();
    }

    /** 系统输入法设置页或选择器返回后，状态可能异步生效，延后多刷几次保证对勾及时出现。 */
    private void scheduleRefresh() {
        long[] delays = {300L, 800L, 1500L, 2500L};
        for (long d : delays) {
            mainHandler.postDelayed(this::updateState, d);
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void updateState() {
        boolean enabled = isImeEnabled();
        boolean current = isImeCurrent();
        boolean intercept = KeyInterceptorService.isEnabled();

        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendRow(sb, enabled, "已启用输入法", "还没启用输入法");
        appendRow(sb, current, "当前就是极简语音", "还不是默认输入法");
        appendRow(sb, intercept, "按键拦截已开启", "按键拦截未开启（可选）");
        tvState.setText(sb);

        if (enabled) {
            btnEnable.setBackgroundResource(R.drawable.bg_btn_success);
            btnEnable.setText("✓ 已启用输入法");
            btnEnable.setTextColor(getColor(R.color.on_accent));
        } else {
            btnEnable.setBackgroundResource(R.drawable.bg_btn_secondary);
            btnEnable.setText(R.string.action_enable_ime);
            btnEnable.setTextColor(getColor(R.color.text_primary));
        }

        if (current) {
            btnSelect.setBackgroundResource(R.drawable.bg_btn_success);
            btnSelect.setText("✓ 已设为默认");
            btnSelect.setTextColor(getColor(R.color.on_accent));
        } else {
            btnSelect.setBackgroundResource(R.drawable.bg_btn_secondary);
            btnSelect.setText(R.string.action_choose_ime);
            btnSelect.setTextColor(getColor(R.color.text_primary));
        }
    }

    private void appendRow(SpannableStringBuilder sb, boolean done, String okText, String noText) {
        if (sb.length() > 0) sb.append("\n");
        String mark = done ? "✓ " : "○ ";
        String text = done ? okText : noText;
        int color = done ? getColor(R.color.success) : getColor(R.color.text_hint);
        int start = sb.length();
        sb.append(mark).append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private boolean isImeEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        List<InputMethodInfo> list = imm.getEnabledInputMethodList();
        for (InputMethodInfo info : list) {
            if (info.getPackageName().equals(getPackageName())) return true;
        }
        return false;
    }

    private boolean isImeCurrent() {
        String current = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.startsWith(getPackageName());
    }
}
