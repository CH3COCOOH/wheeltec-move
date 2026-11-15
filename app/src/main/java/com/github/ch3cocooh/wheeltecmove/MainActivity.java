package com.github.ch3cocooh.wheeltecmove;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText etIp;
    private RadioButton rbRos1, rbRos2;
    private Button btnConnect, btnDisconnect;
    private TextView tvStatus;

    private SshControlService sshService;

    private SharedPreferences prefs;
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private Runnable repeatRunnable;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SshControlService.LocalBinder binder = (SshControlService.LocalBinder) service;
            sshService = binder.getService();
            sshService.setActivity(MainActivity.this);
            updateUi(sshService.isConnected(), "初始化完成");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sshService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("wheeltec_prefs", Context.MODE_PRIVATE);
        initViews();
        loadSavedConfig();
        setupControlButtons();

        // 启动并绑定后台 SSH 服务
        Intent serviceIntent = new Intent(this, SshControlService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void initViews() {
        etIp = findViewById(R.id.et_ip);
        rbRos1 = findViewById(R.id.rb_ros1);
        rbRos2 = findViewById(R.id.rb_ros2);
        btnConnect = findViewById(R.id.btn_connect);
        btnDisconnect = findViewById(R.id.btn_disconnect);
        tvStatus = findViewById(R.id.tv_status);

        btnConnect.setOnClickListener(v -> {
            String ip = etIp.getText().toString().trim();
            if (TextUtils.isEmpty(ip)) {
                Toast.makeText(this, "请输入无人车 IP 地址", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean isRos2 = rbRos2.isChecked();
            saveConfig(ip, isRos2);
            sshService.connectAndStartTeleop(ip, isRos2);
        });

        btnDisconnect.setOnClickListener(v -> {
            sshService.disconnect();
        });
    }

    private void setupControlButtons() {
        bindKeyButton(R.id.btn_u, 'u');
        bindKeyButton(R.id.btn_i, 'i');
        bindKeyButton(R.id.btn_o, 'o');
        bindKeyButton(R.id.btn_j, 'j');
        bindKeyButton(R.id.btn_k, 'k');
        bindKeyButton(R.id.btn_l, 'l');
        bindKeyButton(R.id.btn_m, 'm');
        bindKeyButton(R.id.btn_comma, ',');
        bindKeyButton(R.id.btn_dot, '.');
    }

    /**
     * 绑定九宫格按键：支持单次点击与按住连续触发
     */
    @SuppressLint("ClickableViewAccessibility")
    private void bindKeyButton(int buttonId, char keyChar) {
        Button button = findViewById(buttonId);
        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    sshService.sendControlKey(keyChar);
                    startRepeatSend(keyChar);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    stopRepeatSend();
                    return true;
            }
            return false;
        });
    }

    private void startRepeatSend(char keyChar) {
        stopRepeatSend();
        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                sshService.sendControlKey(keyChar);
                repeatHandler.postDelayed(this, 150); // 每 150ms 模拟一次键盘长按重复输入
            }
        };
        repeatHandler.postDelayed(repeatRunnable, 300);
    }

    private void stopRepeatSend() {
        if (repeatRunnable != null) {
            repeatHandler.removeCallbacks(repeatRunnable);
            repeatRunnable = null;
        }
    }

    private void loadSavedConfig() {
        String savedIp = prefs.getString("ip", "192.168.0.100");
        boolean isRos2 = prefs.getBoolean("is_ros2", false);
        etIp.setText(savedIp);
        if (isRos2) {
            rbRos2.setChecked(true);
        } else {
            rbRos1.setChecked(true);
        }
    }

    private void saveConfig(String ip, boolean isRos2) {
        prefs.edit()
                .putString("ip", ip)
                .putBoolean("is_ros2", isRos2)
                .apply();
    }

    public void updateUi(boolean connected, String message) {
        tvStatus.setText("状态：" + message);
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        etIp.setEnabled(!connected);
        rbRos1.setEnabled(!connected);
        rbRos2.setEnabled(!connected);
    }

    @Override
    protected void onDestroy() {
        stopRepeatSend();
        sshService.setActivity(null);
        unbindService(serviceConnection);
        stopService(new Intent(this, SshControlService.class));
        super.onDestroy();
    }
}
