package com.minivoice.ime.data;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 记录每个拼音串下被手动点过的候选词频次。
 * 下次打同样拼音时，点得多的词排到前面，没点过的保持词典原顺序。
 * 数据躺在 filesDir/user_freq.json，不联网。
 */
public final class UserFreqStore {

    private static final Gson GSON = new Gson();
    private static Map<String, Map<String, Integer>> cache;

    private UserFreqStore() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "user_freq.json");
    }

    private static synchronized Map<String, Map<String, Integer>> all(Context ctx) {
        if (cache != null) return cache;
        cache = new HashMap<>();
        try {
            File f = file(ctx);
            if (f.exists()) {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                Map<String, Map<String, Integer>> m = GSON.fromJson(s,
                        new TypeToken<Map<String, Map<String, Integer>>>() {
                        }.getType());
                if (m != null) cache = m;
            }
        } catch (Exception ignore) {
            // 文件坏了就当空的，别让输入法起不来
        }
        return cache;
    }

    /** 记录一次上屏：当前拼音串下该词频次加一。 */
    public static synchronized void record(Context ctx, String letters, String word) {
        if (letters == null || letters.isEmpty() || word == null || word.isEmpty()) return;
        Map<String, Map<String, Integer>> all = all(ctx);
        Map<String, Integer> by = all.computeIfAbsent(letters, k -> new HashMap<>());
        by.merge(word, 1, Integer::sum);
        write(ctx);
    }

    /** 取当前拼音串下的词频表，没有就返回空表。 */
    public static synchronized Map<String, Integer> freqOf(Context ctx, String letters) {
        if (letters == null || letters.isEmpty()) return new HashMap<>();
        Map<String, Integer> m = all(ctx).get(letters);
        return m == null ? new HashMap<>() : new HashMap<>(m);
    }

    private static void write(Context ctx) {
        try {
            Files.write(file(ctx).toPath(), GSON.toJson(cache).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {
        }
    }
}
