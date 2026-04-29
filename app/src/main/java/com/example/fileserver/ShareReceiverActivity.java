package com.example.fileserver;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;

/**
 * 接收其他应用分享的文件，保存到共享文件夹
 */
public class ShareReceiverActivity extends AppCompatActivity {

    private static final String TAG = "ShareReceiver";
    private HttpServerService serverService;
    private boolean serviceBound = false;
    private Uri pendingUri;
    private String pendingName;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            HttpServerService.LocalBinder binder = (HttpServerService.LocalBinder) service;
            serverService = binder.getService();
            serviceBound = true;
            handleSharedFile();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (!Intent.ACTION_SEND.equals(action) || type == null) {
            finish();
            return;
        }

        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            Toast.makeText(this, "未获取到文件", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        pendingUri = uri;

        // 尝试从 ContentResolver 获取文件名
        String fileName = getFileNameFromUri(uri);
        pendingName = fileName != null ? fileName : "shared_file";

        // 绑定服务获取当前共享路径
        Intent serviceIntent = new Intent(this, HttpServerService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private String getFileNameFromUri(Uri uri) {
        String name = null;
        try {
            android.database.Cursor cursor = getContentResolver().query(
                    uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
                cursor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get file name from uri", e);
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    private void handleSharedFile() {
        if (serverService == null) {
            Toast.makeText(this, "服务未就绪", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String rootPath = serverService.getCurrentRootPath();
        boolean isRunning = serverService.isRunning();

        if (!isRunning || rootPath == null || rootPath.isEmpty()) {
            // 服务未运行，提示用户并引导启动
            unbindService(serviceConnection);
            serviceBound = false;

            new AlertDialog.Builder(this)
                    .setTitle("服务未启动")
                    .setMessage("请先在APP中启动文件服务，然后重新分享文件")
                    .setPositiveButton("去启动", (d, w) -> {
                        Intent mainIntent = new Intent(this, MainActivity.class);
                        mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(mainIntent);
                        finish();
                    })
                    .setNegativeButton("取消", (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
            return;
        }

        // 保存文件到共享文件夹
        File destDir = new File(rootPath);
        if (!destDir.exists() || !destDir.isDirectory()) {
            Toast.makeText(this, "共享文件夹无效: " + rootPath, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 处理文件名冲突
        File destFile = new File(destDir, pendingName);
        if (destFile.exists()) {
            String baseName = pendingName;
            String ext = "";
            int dotIdx = pendingName.lastIndexOf('.');
            if (dotIdx > 0) {
                baseName = pendingName.substring(0, dotIdx);
                ext = pendingName.substring(dotIdx);
            }
            int i = 1;
            while (destFile.exists()) {
                destFile = new File(destDir, baseName + " (" + i + ")" + ext);
                i++;
            }
        }

        // 复制文件
        try (InputStream is = getContentResolver().openInputStream(pendingUri);
             OutputStream os = new FileOutputStream(destFile)) {
            if (is == null) {
                Toast.makeText(this, "无法读取源文件", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            byte[] buffer = new byte[8192];
            int len;
            long total = 0;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
                total += len;
            }

            String sizeStr = formatSize(total);
            Log.d(TAG, "File saved: " + destFile.getAbsolutePath() + " (" + sizeStr + ")");
            Toast.makeText(this, "已保存到共享文件夹: " + destFile.getName() + " (" + sizeStr + ")",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save file", e);
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        unbindService(serviceConnection);
        serviceBound = false;
        finish();
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            try {
                unbindService(serviceConnection);
            } catch (Exception e) {
                // ignore
            }
            serviceBound = false;
        }
        super.onDestroy();
    }
}
