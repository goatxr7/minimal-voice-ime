package com.minivoice.ime.data;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 记录最近复制过的文本，最多留 10 条，最新的排在最前面。
 * 数据躺在 filesDir/clip_history.json，不联网。
 */
public final class ClipHistoryStore {

    private static final Gson GSON = new Gson();
    private static final int MAX = 10;
    private static final int MAX_LEN = 500;
    private static List<String> cache;

    private ClipHistoryStore() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "clip_history.json");
    }

    private static synchronized List<String> all(Context ctx) {
        if (cache != null) return cache;
        cache = new ArrayList<>();
        try {
            File f = file(ctx);
            if (f.exists()) {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                List<String> m = GSON.fromJson(s, new TypeToken<List<String>>() {
                }.getType());
                if (m != null) cache = m;
            }
        } catch (Exception ignore) {
            // 文件坏了就当空的，别让输入法起不来
        }
        return cache;
    }

    /** 记一条复制内容，重复的会挪到最前面，超出 10 条丢最旧的。 */
    public static synchronized void add(Context ctx, String text) {
        if (text == null) return;
        String s = text.trim();
        if (s.isEmpty()) return;
        if (s.length() > MAX_LEN) s = s.substring(0, MAX_LEN);
        List<String> all = all(ctx);
        all.remove(s);
        all.add(0, s);
        while (all.size() > MAX) all.remove(all.size() - 1);
        write(ctx);
    }

    /** 取历史记录，最新的在前，没有就返回空表。 */
    public static synchronized List<String> list(Context ctx) {
        return new ArrayList<>(all(ctx));
    }

    private static void write(Context ctx) {
        try {
            Files.write(file(ctx).toPath(), GSON.toJson(cache).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {
        }
    }
}
