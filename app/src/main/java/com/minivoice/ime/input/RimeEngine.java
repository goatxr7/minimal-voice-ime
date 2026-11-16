package com.minivoice.ime.input;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import icu.astronot233.rime.RimeApi;
import icu.astronot233.rime.RimeCandidate;

/**
 * 对 librime 的一层薄封装。所有 native 调用都必须加锁，
 * 因为 Rime 只有一个全局会话，不能并发读写。
 */
public final class RimeEngine {

    private static final String TAG = "RimeEngine";
    private static final Object LOCK = new Object();
    private static final AtomicBoolean ready = new AtomicBoolean(false);
    private static final AtomicBoolean deploying = new AtomicBoolean(false);

    private static Set<String> syllables = new HashSet<>();
    private static boolean syllablesLoaded = false;

    /** 一个候选，index >= 0 表示来自当前会话的第几号候选，否则直接上屏。 */
    public static final class Cand {
        public final String text;
        public final int index;
        public Cand(String text, int index) {
            this.text = text;
            this.index = index;
        }
    }

    private RimeEngine() {}

    public static boolean isReady() {
        return ready.get();
    }

    public static void initAsync(final Context app) {
        if (deploying.getAndSet(true)) return;
        Thread t = new Thread(() -> {
            try {
                File root = new File(app.getFilesDir(), "rime");
                File shared = new File(root, "shared");
                File user = new File(root, "user");
                copyAssets(app, "rime", shared);
                if (!user.exists()) user.mkdirs();
                synchronized (LOCK) {
                    boolean ok = RimeApi.initialize(shared.getAbsolutePath(),
                            user.getAbsolutePath(), "com.minivoice.ime");
                    if (!ok) {
                        Log.e(TAG, "rime initialize failed");
                        return;
                    }
                    RimeApi.startup(true);
                    RimeApi.joinMaintenance();
                }
                ready.set(true);
                Log.i(TAG, "rime ready");
            } catch (Throwable e) {
                Log.e(TAG, "rime init error", e);
            } finally {
                deploying.set(false);
            }
        }, "rime-init");
        t.start();
    }

    private static void copyAssets(Context app, String assetDir, File outDir) {
        if (!outDir.exists()) outDir.mkdirs();
        try {
            String[] names = app.getAssets().list(assetDir);
            if (names == null) return;
            for (String name : names) {
                String child = assetDir + "/" + name;
                File dest = new File(outDir, name);
                String[] sub = app.getAssets().list(child);
                if (sub != null && sub.length > 0) {
                    copyAssets(app, child, dest);
                } else {
                    if (dest.exists() && dest.length() > 0) continue;
                    try (InputStream in = app.getAssets().open(child);
                         OutputStream out = new FileOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "copy assets error", e);
        }
    }

    private static void loadSyllables(Context app) {
        if (syllablesLoaded) return;
        syllablesLoaded = true;
        try (InputStream in = app.getAssets().open("pinyin_chars.txt")) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            if (n <= 0) return;
            String text = new String(buf, 0, n, "UTF-8");
            for (String line : text.split("\n")) {
                int tab = line.indexOf('\t');
                String syl = tab > 0 ? line.substring(0, tab).trim() : line.trim();
                if (!syl.isEmpty()) syllables.add(syl);
            }
        } catch (Exception e) {
            Log.e(TAG, "load syllables error", e);
        }
    }

    public static List<Cand> candidates(Context app, String letters) {
        if (letters == null || letters.isEmpty()) return new ArrayList<>();
        // 首次部署可能还在后台建索引，这里短暂等一下，避免刚唤起键盘就打字拿到空候选
        long deadline = System.currentTimeMillis() + 1200;
        while (!ready.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                break;
            }
        }
        // 上次初始化若异常结束，部署线程已退出，这里再拉起一次并继续等一段，避免永久空候选
        if (!ready.get() && !deploying.get()) {
            initAsync(app);
            long deadline2 = System.currentTimeMillis() + 1500;
            while (!ready.get() && System.currentTimeMillis() < deadline2) {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        if (!ready.get()) return new ArrayList<>();
        loadSyllables(app);
        String low = letters.toLowerCase();
        List<Cand> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        synchronized (LOCK) {
            try {
                RimeApi.clearComposition();
                RimeApi.simulateKeySequence(low);
                for (RimeCandidate c : RimeApi.getCandidates()) {
                    if (c == null || c.getText() == null || c.getText().isEmpty()) continue;
                    if (seen.add(c.getText())) out.add(new Cand(c.getText(), out.size()));
                }
            } catch (Throwable e) {
                Log.e(TAG, "candidates error", e);
            }

            // 主串拼不出合法音节时，做相邻键纠错，生成少量候选补在后面
            if (out.isEmpty() || splitSyllables(low) == null) {
                List<String> fixed = typoVariants(low);
                for (String v : fixed) {
                    try {
                        RimeApi.clearComposition();
                        RimeApi.simulateKeySequence(v);
                        int added = 0;
                        for (RimeCandidate c : RimeApi.getCandidates()) {
                            if (c == null || c.getText() == null || c.getText().isEmpty()) continue;
                            if (seen.add(c.getText())) {
                                out.add(new Cand(c.getText(), -1));
                                added++;
                            }
                            if (added >= 3) break;
                        }
                        if (added > 0 && out.size() >= 16) break;
                    } catch (Throwable ignored) {
                    }
                }
                // 恢复主串，保证主候选的 index 仍然有效
                try {
                    RimeApi.clearComposition();
                    RimeApi.simulateKeySequence(low);
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    public static String commit(int index) {
        synchronized (LOCK) {
            try {
                if (index >= 0 && RimeApi.selectCandidate(index)) {
                    String commit = RimeApi.getCommit();
                    RimeApi.clearComposition();
                    return (commit != null && !commit.isEmpty()) ? commit : null;
                }
            } catch (Throwable e) {
                Log.e(TAG, "commit error", e);
            }
        }
        return null;
    }

    public static void reset() {
        synchronized (LOCK) {
            try {
                RimeApi.clearComposition();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            try {
                RimeApi.shutdown();
            } catch (Throwable ignored) {
            }
        }
        ready.set(false);
    }

    private static final Map<Character, String> NEIGHBORS = buildNeighbors();

    private static Map<Character, String> buildNeighbors() {
        Map<Character, String> m = new HashMap<>();
        m.put('q', "was");
        m.put('w', "qeasd");
        m.put('e', "wrdsf");
        m.put('r', "etdfg");
        m.put('t', "ryfgh");
        m.put('y', "tughj");
        m.put('u', "yihjk");
        m.put('i', "uojkl");
        m.put('o', "ipkl");
        m.put('p', "ol");
        m.put('a', "qwszx");
        m.put('s', "weadzx");
        m.put('d', "erfcxs");
        m.put('f', "rtgvcd");
        m.put('g', "tyhbvf");
        m.put('h', "yujnbg");
        m.put('j', "uikmnh");
        m.put('k', "iolmj");
        m.put('l', "opk");
        m.put('z', "asx");
        m.put('x', "zsdc");
        m.put('c', "xdfv");
        m.put('v', "cfgb");
        m.put('b', "vghn");
        m.put('n', "bhjm");
        m.put('m', "njk");
        return m;
    }

    /** 生成单键相邻替换/增删后的合法拼音串，最多返回 4 个。 */
    private static List<String> typoVariants(String s) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        char[] cs = s.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            String nb = NEIGHBORS.get(cs[i]);
            if (nb != null) {
                for (int k = 0; k < nb.length(); k++) {
                    char[] t = cs.clone();
                    t[i] = nb.charAt(k);
                    String v = new String(t);
                    if (splitSyllables(v) != null && seen.add(v)) out.add(v);
                }
            }
            // 删除一位
            StringBuilder del = new StringBuilder(s).deleteCharAt(i);
            String dv = del.toString();
            if (!dv.isEmpty() && splitSyllables(dv) != null && seen.add(dv)) out.add(dv);
        }
        // 交换相邻两位
        for (int i = 0; i + 1 < cs.length; i++) {
            char[] t = cs.clone();
            char tmp = t[i]; t[i] = t[i + 1]; t[i + 1] = tmp;
            String v = new String(t);
            if (splitSyllables(v) != null && seen.add(v)) out.add(v);
        }
        if (out.size() > 4) return out.subList(0, 4);
        return out;
    }

    /** 用字典音节做 DP 分词，能全部分完就返回非空列表，否则返回 null。 */
    private static List<String> splitSyllables(String s) {
        if (s == null || s.isEmpty()) return new ArrayList<>();
        int n = s.length();
        boolean[] ok = new boolean[n + 1];
        ok[0] = true;
        for (int i = 1; i <= n; i++) {
            for (int j = 0; j < i; j++) {
                if (ok[j] && syllables.contains(s.substring(j, i))) {
                    ok[i] = true;
                    break;
                }
            }
        }
        if (!ok[n]) return null;
        List<String> res = new ArrayList<>();
        int end = n;
        while (end > 0) {
            for (int j = end - 1; j >= 0; j--) {
                if (ok[j] && syllables.contains(s.substring(j, end))) {
                    res.add(0, s.substring(j, end));
                    end = j;
                    break;
                }
            }
        }
        return res;
    }
}
