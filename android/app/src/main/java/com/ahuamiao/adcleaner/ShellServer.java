package com.ahuamiao.adcleaner;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * shell 身份的命令服务（#622）：经内置无线调试用一条常驻的 `exec:` 流启动
 * （`CLASSPATH=本 APK app_process / com.ahuamiao.adcleaner.ShellServer`），之后 App 的每条命令都走这条流。
 *
 * 不给每条命令单开一条 adb 流：libadb 在对端关流时有竞态，模拟器上 getprop 这类小命令会丢掉全部输出和退出码。
 * 一条流从头用到尾、只开关一次，就碰不到它。Shizuku 也是这个结构（一个 shell 身份的服务进程）。
 *
 * 协议（大端，经 stdin / stdout，stderr 不用）：
 *   请求  int id, byte 类型, int 长度, 内容
 *         类型 1 执行：内容 = int 命令长度, 命令 UTF-8, int stdin 长度, stdin
 *         类型 2 起常驻进程：内容 = int 名字长度, 名字, 命令 UTF-8（输出读掉丢弃）
 *         类型 3 停常驻进程：内容 = 名字
 *         类型 4 读屏：内容 = 屏号（-1 = 所有屏），回 stdout = 与 uiautomator 同格式的 XML（节点带 display-id）
 *         类型 5 屏幕尺寸：内容 = 屏号，回 stdout = 「宽 高」（随旋转）
 *   回应  int id, int 退出码, int stdout 长度, stdout, int stderr 长度, stderr（类型 2 / 3 回 0 与空）
 */
@SuppressLint({"NewApi", "PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi"})
public final class ShellServer {
    private static DataOutputStream out;
    private static final Map<String, Process> daemons = new HashMap<>();

    public static void main(String[] args) throws IOException {
        // UiAutomation 的回调走主线程的 Looper：读请求放到另一个线程，主线程只转 Looper
        Looper.prepareMainLooper();
        try {
            // systemMain 要在有 Looper 的线程上调：放在主线程起服务前（读屏第一次用到时才连 UiAutomation）
            Class<?> at = Class.forName("android.app.ActivityThread");
            system = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        } catch (Throwable ignored) {
        }
        Thread worker = new Thread(ShellServer::serve, "serve");
        worker.start();
        Looper.loop();
    }

    private static void serve() {
        try {
            loop();
        } catch (IOException ignored) {
        }
        System.exit(0);
    }

    private static void loop() throws IOException {
        DataInputStream in = new DataInputStream(System.in);
        out = new DataOutputStream(System.out);
        ExecutorService pool = Executors.newCachedThreadPool();
        reply(0, 0, new byte[0], "ready".getBytes("UTF-8"));        // 起来了：App 等到这一条才发命令
        while (true) {
            int id;
            try {
                id = in.readInt();
            } catch (IOException eof) {
                break;                                                // App 那头关了流：退出，常驻进程一并结束
            }
            byte type = in.readByte();
            byte[] body = new byte[in.readInt()];
            in.readFully(body);
            pool.execute(() -> handle(id, type, body));
        }
        synchronized (daemons) {
            for (Process p : daemons.values()) p.destroy();
        }
    }

    private static void handle(int id, byte type, byte[] body) {
        try {
            DataInputStream b = new DataInputStream(new java.io.ByteArrayInputStream(body));
            if (type == 1) {
                String cmd = new String(read(b), "UTF-8");
                byte[] stdin = read(b);
                run(id, cmd, stdin);
            } else if (type == 2) {
                String name = new String(read(b), "UTF-8");
                byte[] rest = new byte[b.available()];
                b.readFully(rest);
                daemon(name, new String(rest, "UTF-8"));
                reply(id, 0, new byte[0], new byte[0]);
            } else if (type == 4) {
                reply(id, 0, dump(Integer.parseInt(new String(body, "UTF-8").trim())).getBytes("UTF-8"), new byte[0]);
            } else if (type == 5) {
                reply(id, 0, size(Integer.parseInt(new String(body, "UTF-8").trim())).getBytes("UTF-8"), new byte[0]);
            } else if (type == 3) {
                Process p;
                synchronized (daemons) {
                    p = daemons.remove(new String(body, "UTF-8"));
                }
                if (p != null) p.destroy();
                reply(id, 0, new byte[0], new byte[0]);
            }
        } catch (Throwable t) {
            // 反射调用的异常包了一层：拆到根因再回，排查时看得懂
            StringBuilder why = new StringBuilder(String.valueOf(t));
            for (Throwable c = t.getCause(); c != null && why.length() < 2000; c = c.getCause()) why.append(" ← ").append(c);
            try {
                reply(id, 255, new byte[0], why.toString().getBytes("UTF-8"));
            } catch (IOException ignored) {
            }
        }
    }

    // ---------- 读屏：自己连 UiAutomation，不开端口（#623 第 1 项：u2 的读屏服务在 9008 端口对局域网开放）----------
    private static UiAutomation uia;
    private static Context system;

    private static synchronized UiAutomation uia() throws Exception {
        if (uia != null) return uia;
        if (system == null) throw new IllegalStateException("拿不到系统 Context");
        HandlerThread handler = new HandlerThread("uia");
        handler.start();
        Object connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
        Class<?> iface = Class.forName("android.app.IUiAutomationConnection");
        UiAutomation u = null;
        // 构造函数各版本不同：安卓 14 起是 (Context, 连接)，之前是 (Looper, 连接)
        for (Constructor<?> c : UiAutomation.class.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[1] == iface) {
                c.setAccessible(true);
                u = (UiAutomation) c.newInstance(p[0] == Looper.class ? handler.getLooper() : system, connection);
                break;
            }
        }
        if (u == null) throw new IllegalStateException("找不到 UiAutomation 构造函数");
        Method connect;
        try {
            connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
            connect.setAccessible(true);
            connect.invoke(u, 1);          // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES：不踢掉用户开着的无障碍服务
        } catch (NoSuchMethodException e) {
            connect = UiAutomation.class.getDeclaredMethod("connect");
            connect.setAccessible(true);
            connect.invoke(u);
        }
        AccessibilityServiceInfo info = u.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        u.setServiceInfo(info);
        // 刚连上时窗口列表还是空的（模拟器实测第一次读 0 个节点）：等它看见窗口，最多 3 秒
        for (int i = 0; i < 30; i++) {
            SparseArray<java.util.List<AccessibilityWindowInfo>> all = u.getWindowsOnAllDisplays();
            boolean seen = false;
            for (int k = 0; k < all.size() && !seen; k++)
                for (AccessibilityWindowInfo w : all.valueAt(k))
                    if (w.getRoot() != null) { seen = true; break; }
            if (seen) break;
            Thread.sleep(100);
        }
        uia = u;
        return u;
    }

    /** 与 uiautomator 的 dump 同格式（引擎按这些属性读）；每个节点带 display-id，后台屏按它过滤。 */
    private static synchronized String dump(int display) throws Exception {
        UiAutomation u = uia();
        StringBuilder sb = new StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">");
        SparseArray<java.util.List<AccessibilityWindowInfo>> all = u.getWindowsOnAllDisplays();
        for (int i = 0; i < all.size(); i++) {
            int id = all.keyAt(i);
            if (display >= 0 && id != display) continue;
            for (AccessibilityWindowInfo w : all.valueAt(i)) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) node(sb, root, 0, id, 0);
            }
        }
        return sb.append("</hierarchy>").toString();
    }

    private static void node(StringBuilder sb, AccessibilityNodeInfo n, int index, int display, int depth) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        sb.append("<node index=\"").append(index).append('"');
        attr(sb, "text", n.getText());
        attr(sb, "resource-id", n.getViewIdResourceName());
        attr(sb, "class", n.getClassName());
        attr(sb, "package", n.getPackageName());
        attr(sb, "content-desc", n.getContentDescription());
        flag(sb, "checkable", n.isCheckable());
        flag(sb, "checked", n.isChecked());
        flag(sb, "clickable", n.isClickable());
        flag(sb, "enabled", n.isEnabled());
        flag(sb, "focusable", n.isFocusable());
        flag(sb, "focused", n.isFocused());
        flag(sb, "scrollable", n.isScrollable());
        flag(sb, "long-clickable", n.isLongClickable());
        flag(sb, "password", n.isPassword());
        flag(sb, "selected", n.isSelected());
        flag(sb, "visible-to-user", n.isVisibleToUser());
        sb.append(" bounds=\"[").append(r.left).append(',').append(r.top).append("][")
                .append(r.right).append(',').append(r.bottom).append("]\" display-id=\"").append(display).append('"');
        int count = depth < 50 ? n.getChildCount() : 0;
        if (count == 0) {
            sb.append(" />");
            return;
        }
        sb.append('>');
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) node(sb, c, i, display, depth + 1);
        }
        sb.append("</node>");
    }

    private static void attr(StringBuilder sb, String name, CharSequence value) {
        sb.append(' ').append(name).append("=\"");
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                    case '&': sb.append("&amp;"); break;
                    case '<': sb.append("&lt;"); break;
                    case '>': sb.append("&gt;"); break;
                    case '"': sb.append("&quot;"); break;
                    case '\n': sb.append("&#10;"); break;
                    case '\r': sb.append("&#13;"); break;
                    default:
                        if (ch >= 0x20 || ch == '\t') sb.append(ch);   // XML 1.0 不允许的控制字符丢掉
                }
            }
        }
        sb.append('"');
    }

    private static void flag(StringBuilder sb, String name, boolean value) {
        sb.append(' ').append(name).append("=\"").append(value).append('"');
    }

    /** 某块屏的逻辑尺寸（随旋转）：DisplayManagerGlobal.getDisplayInfo(屏号).logicalWidth / logicalHeight。 */
    private static String size(int display) throws Exception {
        Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, display);
        int w = info.getClass().getField("logicalWidth").getInt(info);
        int h = info.getClass().getField("logicalHeight").getInt(info);
        return w + " " + h;
    }

    private static byte[] read(DataInputStream b) throws IOException {
        byte[] data = new byte[b.readInt()];
        b.readFully(data);
        return data;
    }

    private static void run(int id, String cmd, byte[] stdin) throws Exception {
        Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd).start();
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        Thread to = drain(p.getInputStream(), o), te = drain(p.getErrorStream(), e);
        try (OutputStream os = p.getOutputStream()) {
            if (stdin.length > 0) os.write(stdin);
        } catch (IOException ignored) {
        }
        int code = p.waitFor();
        to.join(5000);
        te.join(5000);
        reply(id, code, o.toByteArray(), e.toByteArray());
    }

    private static void daemon(String name, String cmd) throws IOException {
        synchronized (daemons) {
            Process old = daemons.get(name);
            if (old != null && old.isAlive()) return;
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd).start();
            drain(p.getInputStream(), null);
            drain(p.getErrorStream(), null);
            p.getOutputStream().close();
            daemons.put(name, p);
        }
    }

    private static Thread drain(InputStream s, ByteArrayOutputStream into) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = s.read(buf)) > 0) {
                    if (into != null) into.write(buf, 0, n);
                }
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void reply(int id, int code, byte[] o, byte[] e) throws IOException {
        synchronized (ShellServer.class) {
            out.writeInt(id);
            out.writeInt(code);
            out.writeInt(o.length);
            out.write(o);
            out.writeInt(e.length);
            out.write(e);
            out.flush();
        }
    }
}
