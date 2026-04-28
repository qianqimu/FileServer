package com.example.fileserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;

/**
 * HTTP 服务器前台服务
 * 确保 App 切到后台时服务不被杀死
 */
public class HttpServerService extends Service {

    private static final String TAG = "HttpServerService";
    private static final String CHANNEL_ID = "FileServerChannel";
    private static final int NOTIFICATION_ID = 1001;
    public static final int DEFAULT_PORT = 8080;

    private FileHttpServer httpServer;
    private final IBinder binder = new LocalBinder();
    private boolean isRunning = false;
    private int currentPort = DEFAULT_PORT;
    private String currentRootPath = "";

    public class LocalBinder extends Binder {
        HttpServerService getService() {
            return HttpServerService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getStringExtra("action");
            if ("stop".equals(action)) {
                stopServer();
                stopSelf();
            }
        }
        return START_STICKY;
    }

    /**
     * 启动 HTTP 服务器
     */
    public boolean startServer(String rootPath, int port) {
        if (isRunning) {
            stopServer();
        }
        currentRootPath = rootPath;
        currentPort = port;
        try {
            httpServer = new FileHttpServer(port, rootPath);
            httpServer.start();
            isRunning = true;
            Log.d(TAG, "Server started on port " + port + " serving: " + rootPath);
            startForeground(NOTIFICATION_ID, buildNotification(port));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start server", e);
            isRunning = false;
            return false;
        }
    }

    /**
     * 停止 HTTP 服务器
     */
    public void stopServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        isRunning = false;
        stopForeground(true);
        Log.d(TAG, "Server stopped");
    }

    /**
     * 修改共享文件夹（无需重启服务）
     */
    public void updateRootPath(String newPath) {
        currentRootPath = newPath;
        if (httpServer != null) {
            httpServer.setRootPath(newPath);
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getCurrentPort() {
        return currentPort;
    }

    public String getCurrentRootPath() {
        return currentRootPath;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "文件服务器", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("HTTP 文件共享服务正在运行");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(int port) {
        Intent stopIntent = new Intent(this, HttpServerService.class);
        stopIntent.putExtra("action", "stop");
        PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("文件服务器运行中")
                .setContentText("端口: " + port + "  点击查看访问地址")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止服务", stopPending)
                .setOngoing(true)
                .build();
    }
}
