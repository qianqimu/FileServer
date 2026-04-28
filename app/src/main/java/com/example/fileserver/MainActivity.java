package com.example.fileserver;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "FileServerPrefs";
    private static final String PREF_ROOT_PATH = "root_path";
    private static final String PREF_PORT = "port";

    // UI
    private TextView tvStatus;
    private TextView tvUrl;
    private TextView tvPath;
    private Button btnToggle;
    private Button btnChooseFolder;
    private EditText etPort;
    private View cardRunning;
    private View cardStopped;

    // 服务
    private HttpServerService serverService;
    private boolean serviceBound = false;
    private SharedPreferences prefs;

    // 文件夹选择
    private ActivityResultLauncher<Uri> folderPickerLauncher;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            HttpServerService.LocalBinder binder = (HttpServerService.LocalBinder) service;
            serverService = binder.getService();
            serviceBound = true;
            updateUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        initViews();
        setupFolderPicker();
        requestPermissions();
        bindServerService();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvUrl = findViewById(R.id.tv_url);
        tvPath = findViewById(R.id.tv_path);
        btnToggle = findViewById(R.id.btn_toggle);
        btnChooseFolder = findViewById(R.id.btn_choose_folder);
        etPort = findViewById(R.id.et_port);
        cardRunning = findViewById(R.id.card_running);
        cardStopped = findViewById(R.id.card_stopped);

        // 读取上次配置
        String savedPath = prefs.getString(PREF_ROOT_PATH, getDefaultPath());
        int savedPort = prefs.getInt(PREF_PORT, HttpServerService.DEFAULT_PORT);

        tvPath.setText(savedPath);
        etPort.setText(String.valueOf(savedPort));

        btnToggle.setOnClickListener(v -> toggleServer());
        btnChooseFolder.setOnClickListener(v -> openFolderPicker());

        // 点击URL复制
        tvUrl.setOnClickListener(v -> {
            String url = tvUrl.getText().toString();
            if (!url.isEmpty() && !url.equals("--")) {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("URL", url);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "地址已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupFolderPicker() {
        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri != null) {
                        // 获取持久化权限
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                        // 将 content:// URI 转换为真实文件路径
                        String path = uriToPath(uri);
                        if (path != null) {
                            setPath(path);
                        } else {
                            // SAF URI 无法转为文件路径，使用 MediaStore 方式
                            // 降级提示用户使用自定义路径
                            Toast.makeText(this, "无法获取文件路径，请手动输入", Toast.LENGTH_SHORT).show();
                            showFolderInputDialog();
                        }
                    }
                });
    }

    /**
     * 打开系统文件夹选择器
     */
    private void openFolderPicker() {
        // Android 11+ 优先使用 SAF 目录选择器
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            folderPickerLauncher.launch(null);
        } catch (Exception e) {
            // 降级到自定义对话框
            showFolderInputDialog();
        }
    }

    /**
     * 将 SAF content:// URI 转换为文件系统路径
     */
    private String uriToPath(Uri uri) {
        try {
            // 对于 External Storage 的 URI，尝试直接获取路径
            String docId = DocumentsContract.getTreeDocumentId(uri);
            String[] split = docId.split(":");
            String type = split[0];
            String relativePath = split.length > 1 ? split[1] : "";

            if ("primary".equalsIgnoreCase(type)) {
                // 主存储
                return Environment.getExternalStorageDirectory().getAbsolutePath()
                        + (relativePath.isEmpty() ? "" : "/" + relativePath);
            } else {
                // SD 卡等外部存储
                File[] externalDirs = getExternalFilesDirs(null);
                for (File dir : externalDirs) {
                    if (dir != null && dir.getAbsolutePath().contains("/" + type + "/")) {
                        String basePath = dir.getAbsolutePath();
                        int idx = basePath.indexOf("/Android/");
                        if (idx > 0) {
                            return basePath.substring(0, idx)
                                    + (relativePath.isEmpty() ? "" : "/" + relativePath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "URI to path conversion failed", e);
        }
        return null;
    }

    private void showFolderInputDialog() {
        String currentPath = tvPath.getText().toString();

        // 显示常用路径选择列表
        String[] commonPaths = {
                Environment.getExternalStorageDirectory().getAbsolutePath(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).getAbsolutePath(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath(),
                "自定义路径..."
        };

        String[] labels = {
                "📱 手机存储根目录",
                "📥 下载 (Downloads)",
                "🖼️ 图片 (Pictures)",
                "🎬 视频 (Movies)",
                "🎵 音乐 (Music)",
                "📄 文档 (Documents)",
                "✏️ 自定义路径..."
        };

        new AlertDialog.Builder(this)
                .setTitle("选择共享文件夹")
                .setItems(labels, (dialog, which) -> {
                    if (which == labels.length - 1) {
                        // 自定义路径
                        showCustomPathDialog(currentPath);
                    } else {
                        setPath(commonPaths[which]);
                    }
                })
                .show();
    }

    private void showCustomPathDialog(String currentPath) {
        final EditText input = new EditText(this);
        input.setText(currentPath);
        input.setSelection(currentPath.length());

        new AlertDialog.Builder(this)
                .setTitle("输入文件夹路径")
                .setMessage("请输入要共享的文件夹完整路径")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (!path.isEmpty()) {
                        setPath(path);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setPath(String path) {
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            Toast.makeText(this, "路径无效或不是文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        tvPath.setText(path);
        prefs.edit().putString(PREF_ROOT_PATH, path).apply();

        // 如果服务正在运行，实时更新
        if (serviceBound && serverService != null && serverService.isRunning()) {
            serverService.updateRootPath(path);
            Toast.makeText(this, "共享文件夹已更新", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleServer() {
        if (!serviceBound || serverService == null) return;

        if (serverService.isRunning()) {
            // 停止服务
            serverService.stopServer();
            Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
        } else {
            // 检查权限
            if (!checkStoragePermission()) {
                requestStoragePermission();
                return;
            }

            String path = tvPath.getText().toString();
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                Toast.makeText(this, "所选文件夹不存在，请重新选择", Toast.LENGTH_SHORT).show();
                return;
            }

            int port;
            try {
                port = Integer.parseInt(etPort.getText().toString());
                if (port < 1024 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "端口号无效 (1024-65535)", Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.edit().putInt(PREF_PORT, port).apply();

            boolean success = serverService.startServer(path, port);
            if (success) {
                Toast.makeText(this, "服务已启动！", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "启动失败，端口 " + port + " 可能已被占用", Toast.LENGTH_LONG).show();
            }
        }
        updateUI();
    }

    private void updateUI() {
        if (!serviceBound || serverService == null) return;

        boolean running = serverService.isRunning();

        if (running) {
            cardRunning.setVisibility(View.VISIBLE);
            cardStopped.setVisibility(View.GONE);
            btnToggle.setText("⏹ 停止服务");
            btnToggle.setBackgroundColor(0xFFE53935);

            String ip = getLocalIpAddress();
            int port = serverService.getCurrentPort();
            String url = "http://" + ip + ":" + port;
            tvUrl.setText(url);
            tvStatus.setText("● 运行中");
            tvStatus.setTextColor(0xFF4CAF50);
        } else {
            cardRunning.setVisibility(View.GONE);
            cardStopped.setVisibility(View.VISIBLE);
            btnToggle.setText("▶ 启动服务");
            btnToggle.setBackgroundColor(0xFF667EEA);
            tvStatus.setText("● 已停止");
            tvStatus.setTextColor(0xFF9E9E9E);
        }
    }

    private String getLocalIpAddress() {
        try {
            // 优先获取 WiFi IP
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ipInt = wifiInfo.getIpAddress();
                if (ipInt != 0) {
                    return Formatter.formatIpAddress(ipInt);
                }
            }
            // 遍历网卡
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface iface : interfaces) {
                List<InetAddress> addresses = Collections.list(iface.getInetAddresses());
                for (InetAddress addr : addresses) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().contains(".")) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get IP", e);
        }
        return "127.0.0.1";
    }

    private String getDefaultPath() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    // ========== 权限处理 ==========

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setTitle("需要文件访问权限")
                    .setMessage("请在设置中授予「所有文件访问权限」，以便共享手机上的文件")
                    .setPositiveButton("去设置", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
        }
    }

    private void requestPermissions() {
        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    // ========== 服务绑定 ==========

    private void bindServerService() {
        Intent intent = new Intent(this, HttpServerService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (serviceBound) updateUI();
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }
}
