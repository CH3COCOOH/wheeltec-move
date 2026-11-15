package com.github.ch3cocooh.wheeltecmove;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SshControlService extends Service {

    // 固定连接参数
    private static final String SSH_USER = "wheeltec";
    private static final String SSH_PASS = "dongguan";
    private static final int SSH_PORT = 22;

    private final IBinder binder = new LocalBinder();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Session session;
    private ChannelShell channel;
    private OutputStream shellOut;

    private MainActivity activity;

    public class LocalBinder extends Binder {
        public SshControlService getService() {
            return SshControlService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setActivity(MainActivity activity) {
        this.activity = activity;
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    /**
     * 连接 SSH 并启动对应的 ROS keyboard_teleop 节点
     *
     * @param host   小车 IP
     * @param isRos2 true 为 ROS2，false 为 ROS1
     */
    public void connectAndStartTeleop(String host, boolean isRos2) {
        executor.execute(() -> {
            notifyStatus(false, "正在连接 " + host + " ...");

            try {
                JSch jsch = new JSch();
                session = jsch.getSession(SSH_USER, host, SSH_PORT);
                session.setPassword(SSH_PASS);

                // 跳过首次连接的 HostKey 确认
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "no");
                session.setConfig(config);
                session.setTimeout(8000);
                session.connect();

                // 必须使用 shell 通道并开启 PTY 伪终端，否则 keyboard_teleop 无法读取按键
                channel = (ChannelShell) session.openChannel("shell");
                channel.setPty(true);
                channel.setPtyType("vt100");

                InputStream shellIn = channel.getInputStream();
                shellOut = channel.getOutputStream();
                channel.connect(5000);

                notifyStatus(true, "SSH 已连接，正在启动 " + (isRos2 ? "ROS2" : "ROS1") + " 控制节点...");

                // 启动后台线程持续消耗终端输出，防止缓冲区满导致阻塞
                startOutputDrainThread(shellIn);

                // 等待 shell 初始化完成
                Thread.sleep(600);

                // 加载环境变量并启动对应版本的 launch 文件
                String launchCmd = isRos2
                        ? "ros2 launch wheeltec_robot_rc keyboard_teleop.launch\n"
                        : "roslaunch wheeltec_robot_rc keyboard_teleop.launch\n";

                sendRawBytes(launchCmd.getBytes(StandardCharsets.UTF_8));
                notifyStatus(true, "可使用九宫格控制");

            } catch (Exception e) {
                e.printStackTrace();
                disconnect();
            }
        });
    }

    /**
     * 发送单个按键字符控制无人车
     * 注意：keyboard_teleop 直接读取单字符，不需要追加换行符 \n
     */
    public void sendControlKey(char key) {
        executor.execute(() -> {
            if (!isConnected()) {
                return;
            }
            try {
                byte[] data = new byte[] { (byte)key };
                sendRawBytes(data);
            } catch (IOException e) {
                e.printStackTrace();
                disconnect();
            }
        });
    }

    private synchronized void sendRawBytes(byte[] data) throws IOException {
        shellOut.write(data);
        shellOut.flush();
    }

    private void startOutputDrainThread(InputStream in) {
        executor.execute(() -> {
            byte[] buffer = new byte[1024];
            try {
                while (in.read(buffer) != -1) {
                }
            } catch (IOException e) {
                disconnect();
                e.printStackTrace();
            }
        });
    }

    public void disconnect() {
        executor.execute(() -> {
            if (!isConnected()) {
                return;
            }
            try {
                shellOut.close();
                shellOut = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
            channel.disconnect();
            channel = null;
            session.disconnect();
            session = null;
            notifyStatus(false, "已主动断开连接");
        });
    }

    public void notifyStatus(boolean connected, String msg) {
        mainHandler.post(() -> {
            activity.updateUi(connected, msg);
        });
    }

    @Override
    public void onDestroy() {
        disconnect();
        executor.shutdownNow();
        super.onDestroy();
    }
}
