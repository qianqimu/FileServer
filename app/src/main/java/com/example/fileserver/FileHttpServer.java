package com.example.fileserver;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 基于 NanoHTTPD 的 HTTP 文件服务器
 * 支持目录浏览、文件下载
 */
public class FileHttpServer extends NanoHTTPD {

    private static final String TAG = "FileHttpServer";
    private String rootPath;

    public FileHttpServer(int port, String rootPath) {
        super(port);
        this.rootPath = rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        try {
            uri = URLDecoder.decode(uri, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "URL decode error", e);
        }

        Log.d(TAG, "Request: " + uri);

        // 处理下载请求：/download/路径/文件名 （路径式，浏览器从URL获取文件名）
        if (uri.startsWith("/download/")) {
            String filePathParam = uri.substring("/download".length()); // 去掉 /download 前缀，保留 /路径/文件名
            return serveDownload(filePathParam);
        }

        // 兼容旧的查询参数方式：/download?file=/path/to/file
        if (uri.equals("/download")) {
            String query = session.getQueryParameterString();
            String filePathParam = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2 && "file".equals(kv[0])) {
                        try {
                            filePathParam = URLDecoder.decode(kv[1], "UTF-8");
                        } catch (Exception e) {
                            filePathParam = kv[1];
                        }
                        break;
                    }
                }
            }
            if (filePathParam != null) {
                return serveDownload(filePathParam);
            }
        }

        // 防止路径穿越攻击
        if (uri.contains("..")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
        }

        // 获取实际文件路径
        String filePath = rootPath + uri;
        File file = new File(filePath);

        // 规范化路径，确保在根目录内
        try {
            String canonicalRoot = new File(rootPath).getCanonicalPath();
            String canonicalFile = file.getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalRoot)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
            }
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error");
        }

        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html; charset=utf-8",
                    buildErrorPage("404 - 文件未找到", uri));
        }

        if (file.isDirectory()) {
            // 返回目录列表页面
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                    buildDirectoryPage(file, uri));
        } else {
            // 返回文件内容（预览模式）
            return serveFile(file);
        }
    }

    /**
     * 处理文件下载请求（强制下载）
     */
    private Response serveDownload(String filePathParam) {
        if (filePathParam.contains("..")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
        }
        String filePath = rootPath + filePathParam;
        File file = new File(filePath);
        try {
            String canonicalRoot = new File(rootPath).getCanonicalPath();
            String canonicalFile = file.getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalRoot)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
            }
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error");
        }
        if (!file.exists() || file.isDirectory()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found");
        }
        try {
            String mimeType = getMimeType(file.getName());
            InputStream is = new FileInputStream(file);
            Response response = newFixedLengthResponse(Response.Status.OK, mimeType, is, file.length());
            // 生成 ASCII 安全的文件名，用于不支持 UTF-8 filename 的浏览器
            String asciiName = toAsciiFileName(file.getName());
            String encodedName = URLEncoder.encode(file.getName(), "UTF-8").replace("+", "%20");
            response.addHeader("Content-Disposition",
                    "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName);
            return response;
        } catch (IOException e) {
            Log.e(TAG, "File read error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Cannot read file");
        }
    }

    /**
     * 提供文件下载
     */
    private Response serveFile(File file) {
        try {
            String mimeType = getMimeType(file.getName());
            InputStream is = new FileInputStream(file);
            Response response = newFixedLengthResponse(Response.Status.OK, mimeType, is, file.length());
            // 对非媒体文件强制下载
            if (!isPreviewable(mimeType)) {
                response.addHeader("Content-Disposition",
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(file.getName(), "UTF-8").replace("+", "%20"));
            }
            return response;
        } catch (IOException e) {
            Log.e(TAG, "File read error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Cannot read file");
        }
    }

    /**
     * 构建目录浏览页面（现代美观风格）
     */
    private String buildDirectoryPage(File dir, String uri) {
        File[] files = dir.listFiles();
        if (files == null) files = new File[0];

        // 排序：目录优先，然后按名称
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='zh-CN'><head>");
        sb.append("<meta charset='UTF-8'>");
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        sb.append("<title>📁 ").append(escapeHtml(dir.getName())).append("</title>");
        sb.append("<style>");
        sb.append("* { box-sizing: border-box; margin: 0; padding: 0; }");
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;");
        sb.append("  background: #f0f2f5; min-height: 100vh; color: #333; }");
        sb.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);");
        sb.append("  color: white; padding: 20px 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.2); }");
        sb.append(".header h1 { font-size: 20px; font-weight: 600; }");
        sb.append(".breadcrumb { font-size: 13px; opacity: 0.85; margin-top: 6px; word-break: break-all; }");
        sb.append(".breadcrumb a { color: rgba(255,255,255,0.9); text-decoration: none; }");
        sb.append(".breadcrumb a:hover { text-decoration: underline; }");
        sb.append(".container { max-width: 960px; margin: 24px auto; padding: 0 16px; }");
        sb.append(".card { background: white; border-radius: 12px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); overflow: hidden; }");
        sb.append(".card-header { padding: 16px 20px; border-bottom: 1px solid #f0f0f0;");
        sb.append("  display: flex; align-items: center; justify-content: space-between; }");
        sb.append(".card-header span { color: #888; font-size: 13px; }");
        sb.append(".file-list { list-style: none; }");
        sb.append(".file-item { display: flex; align-items: center; padding: 12px 20px;");
        sb.append("  border-bottom: 1px solid #f5f5f5; transition: background 0.15s; cursor: pointer; }");
        sb.append(".file-item:last-child { border-bottom: none; }");
        sb.append(".file-item:hover { background: #f8f9ff; }");
        sb.append(".file-icon { font-size: 22px; width: 36px; flex-shrink: 0; text-align: center; }");
        sb.append(".file-info { flex: 1; min-width: 0; margin-left: 12px; }");
        sb.append(".file-name { font-size: 14px; font-weight: 500; white-space: nowrap;");
        sb.append("  overflow: hidden; text-overflow: ellipsis; }");
        sb.append(".file-name a { color: #333; text-decoration: none; }");
        sb.append(".file-name a:hover { color: #667eea; }");
        sb.append(".file-meta { font-size: 12px; color: #999; margin-top: 2px; }");
        sb.append(".file-size { font-size: 12px; color: #aaa; margin-left: 16px; flex-shrink: 0; }");
        sb.append(".back-item { background: #fafbff; }");
        sb.append(".btn-download { display: inline-block; padding: 5px 12px; margin-left: 10px;");
        sb.append("  background: #667eea; color: white !important; border-radius: 6px;");
        sb.append("  font-size: 12px; text-decoration: none !important; white-space: nowrap;");
        sb.append("  flex-shrink: 0; transition: background 0.2s; line-height: 1.4; }");
        sb.append(".btn-download:hover { background: #5a6fd6; }");
        sb.append(".empty { padding: 48px; text-align: center; color: #bbb; font-size: 15px; }");
        sb.append(".footer { text-align: center; padding: 20px; font-size: 12px; color: #bbb; }");
        sb.append("</style></head><body>");

        // 头部
        sb.append("<div class='header'>");
        sb.append("<h1>📁 文件服务器</h1>");
        sb.append("<div class='breadcrumb'>").append(buildBreadcrumb(uri)).append("</div>");
        sb.append("</div>");

        sb.append("<div class='container'><div class='card'>");
        sb.append("<div class='card-header'>");
        sb.append("<strong>").append(escapeHtml(dir.getName())).append("</strong>");
        sb.append("<span>共 ").append(files.length).append(" 个项目</span>");
        sb.append("</div>");

        sb.append("<ul class='file-list'>");

        // 返回上级目录
        if (!uri.equals("/")) {
            String parentUri = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
            int lastSlash = parentUri.lastIndexOf('/');
            String parentPath = lastSlash > 0 ? parentUri.substring(0, lastSlash) : "/";
            sb.append("<li class='file-item back-item'>");
            sb.append("<div class='file-icon'>⬆️</div>");
            sb.append("<div class='file-info'><div class='file-name'>");
            sb.append("<a href='").append(parentPath).append("'>返回上级目录</a>");
            sb.append("</div></div></li>");
        }

        if (files.length == 0) {
            sb.append("</ul><div class='empty'>🗂️ 此文件夹为空</div>");
        } else {
            for (File f : files) {
                String encodedName;
                try {
                    encodedName = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
                } catch (Exception e) {
                    encodedName = f.getName();
                }

                String href = uri.endsWith("/") ? uri + encodedName : uri + "/" + encodedName;
                String icon = f.isDirectory() ? "📁" : getFileIcon(f.getName());
                String size = f.isDirectory() ? "" : formatSize(f.length());
                String date = sdf.format(new Date(f.lastModified()));

                sb.append("<li class='file-item'>");
                sb.append("<div class='file-icon'>").append(icon).append("</div>");
                sb.append("<div class='file-info'>");
                sb.append("<div class='file-name'><a href='").append(href).append("'>")
                        .append(escapeHtml(f.getName())).append("</a></div>");
                sb.append("<div class='file-meta'>").append(date).append("</div>");
                sb.append("</div>");
                if (!size.isEmpty()) {
                    sb.append("<div class='file-size'>").append(size).append("</div>");
                }
                if (!f.isDirectory()) {
                    // 用 JavaScript fetch+Blob 触发下载，完全控制文件名，兼容所有浏览器
                    String downloadFileParam;
                    try {
                        downloadFileParam = URLEncoder.encode(uri + "/" + f.getName(), "UTF-8").replace("+", "%20");
                    } catch (Exception e) {
                        downloadFileParam = uri + "/" + f.getName();
                    }
                    String safeFileName = escapeHtml(f.getName()).replace("'", "\\'");
                    sb.append("<a class='btn-download' href='#' onclick=\"dlFile('")
                            .append(downloadFileParam).append("','").append(safeFileName).append("');return false;\">⬇ 下载</a>");
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }

        sb.append("</div>");
        sb.append("<div class='footer'>FileServer · 局域网文件共享</div>");
        sb.append("</div>");

        // JavaScript 下载函数：通过 fetch + Blob + a.click() 控制文件名
        // 解决闪电浏览器等无法从 URL/Content-Disposition 正确获取文件名的问题
        sb.append("<script>");
        sb.append("function dlFile(fileParam,fileName){");
        sb.append("var x=new XMLHttpRequest();");
        sb.append("x.open('GET','/download?file='+encodeURIComponent(fileParam),true);");
        sb.append("x.responseType='blob';");
        sb.append("x.onload=function(){");
        sb.append("  if(x.status===200){");
        sb.append("    var b=new Blob([x.response]);");
        sb.append("    var u=URL.createObjectURL(b);");
        sb.append("    var a=document.createElement('a');");
        sb.append("    a.href=u;");
        sb.append("    a.download=fileName;");
        sb.append("    document.body.appendChild(a);");
        sb.append("    a.click();");
        sb.append("    document.body.removeChild(a);");
        sb.append("    URL.revokeObjectURL(u);");
        sb.append("  }else{alert('下载失败:'+x.status);}");
        sb.append("};");
        sb.append("x.send();");
        sb.append("}");
        sb.append("</script>");

        sb.append("</body></html>");

        return sb.toString();
    }

    /**
     * 构建面包屑导航
     */
    private String buildBreadcrumb(String uri) {
        StringBuilder sb = new StringBuilder("<a href='/'>🏠 根目录</a>");
        if (!uri.equals("/")) {
            String[] parts = uri.split("/");
            StringBuilder path = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                path.append("/").append(part);
                sb.append(" / <a href='").append(path).append("'>")
                        .append(escapeHtml(part)).append("</a>");
            }
        }
        return sb.toString();
    }

    /**
     * 构建错误页面
     */
    private String buildErrorPage(String title, String path) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>" + title + "</title>"
                + "<style>body{font-family:sans-serif;text-align:center;padding:80px;background:#f0f2f5;}"
                + "h1{font-size:48px;color:#667eea;margin-bottom:16px;}"
                + "p{color:#888;font-size:16px;}</style></head><body>"
                + "<h1>😕</h1><h2>" + escapeHtml(title) + "</h2>"
                + "<p>路径: " + escapeHtml(path) + "</p>"
                + "<p><a href='/'>返回根目录</a></p></body></html>";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024));
        return String.format(Locale.US, "%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String getFileIcon(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp"))
            return "🖼️";
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".wmv") || lower.endsWith(".flv"))
            return "🎬";
        if (lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".aac")
                || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".m4a"))
            return "🎵";
        if (lower.endsWith(".pdf")) return "📄";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "📝";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "📊";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "📋";
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")
                || lower.endsWith(".tar") || lower.endsWith(".gz"))
            return "🗜️";
        if (lower.endsWith(".apk")) return "📦";
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md")) return "📃";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "🌐";
        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".py")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".cpp"))
            return "💻";
        return "📄";
    }

    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log"))
            return "text/plain; charset=utf-8";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".xml")) return "application/xml; charset=utf-8";
        return "application/octet-stream";
    }

    private boolean isPreviewable(String mimeType) {
        return mimeType.startsWith("text/") || mimeType.startsWith("image/")
                || mimeType.startsWith("video/") || mimeType.startsWith("audio/")
                || mimeType.equals("application/pdf");
    }

    /**
     * 将文件名转为 ASCII 安全名称（保留扩展名）
     * 非ASCII字符替换为下划线，确保旧浏览器也能正确获取扩展名
     */
    private String toAsciiFileName(String fileName) {
        String name = fileName;
        String ext = "";
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) {
            ext = name.substring(dotIdx); // 包含点号，如 ".pdf"
            name = name.substring(0, dotIdx);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString() + ext;
    }
}
