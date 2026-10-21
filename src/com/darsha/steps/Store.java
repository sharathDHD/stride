package com.darsha.steps;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Local-only storage. Everything lives in a private SQLite database on the phone.
 * No network, no accounts, no analytics.
 */
public class Store {

    public static final String KEY_GOAL = "goal_steps";
    public static final String KEY_STRIDE = "stride_cm";
    public static final String KEY_WEIGHT = "weight_kg";
    public static final String KEY_SENSOR_LAST = "sensor_last";
    public static final String KEY_XP = "xp";
    public static final String KEY_REMINDER_ON = "reminder_on";
    public static final String KEY_REMINDER_MIN = "reminder_min";
    public static final String KEY_UNITS = "units";           // "km" or "mi"
    public static final String KEY_FIRST_DAY = "first_day";

    public static final int DEFAULT_GOAL = 8000;
    public static final int DEFAULT_STRIDE_CM = 72;
    public static final int DEFAULT_WEIGHT_KG = 70;

    private static Store instance;

    public static synchronized Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }

    private final DB db;

    private Store(Context context) {
        db = new DB(context);
    }

    /* ---------------------------------------------------------------- meta */

    public synchronized int getInt(String key, int def) {
        return (int) getLong(key, def);
    }

    public synchronized long getLong(String key, long def) {
        try (Cursor c = db.getReadableDatabase().query("meta", new String[]{"v"},
                "k=?", new String[]{key}, null, null, null)) {
            if (c.moveToFirst()) {
                try {
                    return Long.parseLong(c.getString(0));
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
        }
        return def;
    }

    public synchronized String getString(String key, String def) {
        try (Cursor c = db.getReadableDatabase().query("meta", new String[]{"v"},
                "k=?", new String[]{key}, null, null, null)) {
            if (c.moveToFirst()) return c.getString(0);
        }
        return def;
    }

    public synchronized void setMeta(String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("k", key);
        cv.put("v", value);
        db.getWritableDatabase().insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized int goal() {
        return Math.max(1000, getInt(KEY_GOAL, DEFAULT_GOAL));
    }

    public synchronized int strideCm() {
        return Math.max(30, getInt(KEY_STRIDE, DEFAULT_STRIDE_CM));
    }

    public synchronized int weightKg() {
        return Math.max(25, getInt(KEY_WEIGHT, DEFAULT_WEIGHT_KG));
    }

    public synchronized String units() {
        return getString(KEY_UNITS, "km");
    }

    public synchronized boolean reminderOn() {
        return getInt(KEY_REMINDER_ON, 0) == 1;
    }

    public synchronized int reminderMinutes() {
        return Math.max(0, Math.min(1439, getInt(KEY_REMINDER_MIN, 20 * 60)));
    }

    public synchronized int xp() {
        return Math.max(0, getInt(KEY_XP, 0));
    }

    public synchronized void setGoal(int goal) {
        setMeta(KEY_GOAL, String.valueOf(Math.max(1000, goal)));
        String today = LocalDate.now().toString();
        db.getWritableDatabase().execSQL(
                "UPDATE days SET goal=?, goal_reached=CASE WHEN steps>=? THEN 1 ELSE 0 END WHERE date=?",
                new Object[]{Math.max(1000, goal), Math.max(1000, goal), today});
    }

    /* --------------------------------------------------------------- steps */

    public static class Day {
        public String date = "";
        public int steps;
        public double distanceM;
        public double calories;
        public int goal;
        public boolean goalReached;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("date", date);
            o.put("steps", steps);
            o.put("distance_m", distanceM);
            o.put("calories", calories);
            o.put("goal", goal);
            o.put("goal_reached", goalReached);
            return o;
        }
    }

    public static class ApplyResult {
        public long delta;
        public int daySteps;
        public int goal;
        public boolean goalReachedNow;
    }

    /** Applies a hardware step-counter value (steps since boot). Handles reboots. */
    public synchronized ApplyResult applyCounter(long raw) {
        int goal = goal();
        int stride = strideCm();
        int weight = weightKg();

        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            long last = metaGetLong(d, KEY_SENSOR_LAST, -1);
            long delta;
            if (last < 0) {
                delta = 0;
            } else {
                delta = raw - last;
                if (delta < 0) delta = raw; // device rebooted; counter restarted at 0
            }
            metaSet(d, KEY_SENSOR_LAST, String.valueOf(raw));

            ApplyResult r;
            if (delta > 0) {
                r = addToToday(d, (int) Math.min(delta, 250000), goal, stride, weight);
            } else {
                r = new ApplyResult();
                r.delta = 0;
                Day t = dayInternal(d, LocalDate.now().toString());
                r.daySteps = t == null ? 0 : t.steps;
                r.goal = goal;
                r.goalReachedNow = t != null && t.goalReached;
            }
            d.setTransactionSuccessful();
            return r;
        } finally {
            d.endTransaction();
        }
    }

    /** Fallback path when only a step-detector is available: one step per event. */
    public synchronized ApplyResult applyDetectedStep() {
        int goal = goal();
        int stride = strideCm();
        int weight = weightKg();
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            ApplyResult r = addToToday(d, 1, goal, stride, weight);
            d.setTransactionSuccessful();
            return r;
        } finally {
            d.endTransaction();
        }
    }

    private ApplyResult addToToday(SQLiteDatabase d, int delta, int goal, int strideCm, int weightKg) {
        String today = LocalDate.now().toString();
        Day existing = dayInternal(d, today);

        int steps;
        double dist;
        double cal;
        int goalSnapshot;
        if (existing == null) {
            steps = 0;
            dist = 0;
            cal = 0;
            goalSnapshot = goal;
        } else {
            steps = existing.steps;
            dist = existing.distanceM;
            cal = existing.calories;
            goalSnapshot = existing.goal > 0 ? existing.goal : goal;
        }

        boolean hadGoal = existing != null && existing.goalReached;
        steps += delta;
        dist += delta * strideCm / 100.0;
        cal += delta * 0.04 * (weightKg / 70.0);
        boolean reached = steps >= goalSnapshot;

        ContentValues cv = new ContentValues();
        cv.put("date", today);
        cv.put("steps", steps);
        cv.put("distance_m", dist);
        cv.put("calories", cal);
        cv.put("goal", goalSnapshot);
        cv.put("goal_reached", reached ? 1 : 0);
        cv.put("updated_at", System.currentTimeMillis());
        d.insertWithOnConflict("days", null, cv, SQLiteDatabase.CONFLICT_REPLACE);

        ApplyResult r = new ApplyResult();
        r.delta = delta;
        r.daySteps = steps;
        r.goal = goalSnapshot;
        r.goalReachedNow = reached && !hadGoal;
        return r;
    }

    private Day dayInternal(SQLiteDatabase d, String date) {
        try (Cursor c = d.query("days", null, "date=?", new String[]{date},
                null, null, null)) {
            if (c.moveToFirst()) return readDay(c);
        }
        return null;
    }

    public synchronized Day day(String date) {
        return dayInternal(db.getReadableDatabase(), date);
    }

    private Day readDay(Cursor c) {
        Day day = new Day();
        day.date = c.getString(c.getColumnIndexOrThrow("date"));
        day.steps = c.getInt(c.getColumnIndexOrThrow("steps"));
        day.distanceM = c.getDouble(c.getColumnIndexOrThrow("distance_m"));
        day.calories = c.getDouble(c.getColumnIndexOrThrow("calories"));
        day.goal = c.getInt(c.getColumnIndexOrThrow("goal"));
        day.goalReached = c.getInt(c.getColumnIndexOrThrow("goal_reached")) == 1;
        return day;
    }

    /** Newest first, for lists. */
    public synchronized List<Day> daysDesc(int limit) {
        List<Day> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().query("days", null, null, null,
                null, null, "date DESC", String.valueOf(limit))) {
            while (c.moveToNext()) out.add(readDay(c));
        }
        return out;
    }

    /** Oldest first, with zero-filled gaps, for charts. */
    public synchronized List<Day> daysForRange(LocalDate from, LocalDate to) {
        List<Day> out = new ArrayList<>();
        int goal = goal();
        java.util.Map<String, Day> map = new java.util.HashMap<>();
        try (Cursor c = db.getReadableDatabase().query("days", null, "date>=? AND date<=?",
                new String[]{from.toString(), to.toString()}, null, null, "date ASC")) {
            while (c.moveToNext()) {
                Day d = readDay(c);
                map.put(d.date, d);
            }
        }
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Day day = map.get(d.toString());
            if (day == null) {
                day = new Day();
                day.date = d.toString();
                day.goal = goal;
            }
            out.add(day);
        }
        return out;
    }

    public synchronized long lifetimeSteps() {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT COALESCE(SUM(steps),0) FROM days", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    public synchronized int bestDaySteps() {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT COALESCE(MAX(steps),0) FROM days", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized int streak() {
        LocalDate d = LocalDate.now();
        Day today = day(d.toString());
        if (today == null || !today.goalReached) d = d.minusDays(1);
        int n = 0;
        while (n < 3650) {
            Day row = day(d.toString());
            if (row != null && row.goalReached) {
                n++;
                d = d.minusDays(1);
            } else {
                break;
            }
        }
        return n;
    }

    public synchronized int weekSteps(LocalDate weekStart) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(steps),0) FROM days WHERE date>=? AND date<=?",
                new String[]{weekStart.toString(), LocalDate.now().toString()})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized int countGoalDays(LocalDate weekStart) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM days WHERE date>=? AND date<=? AND goal_reached=1",
                new String[]{weekStart.toString(), LocalDate.now().toString()})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized int countActiveDays(LocalDate weekStart, int minSteps) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM days WHERE date>=? AND date<=? AND steps>=?",
                new String[]{weekStart.toString(), LocalDate.now().toString(), String.valueOf(minSteps)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /* -------------------------------------------------------------- quests */

    public static class QuestState {
        public String defId = "";
        public String periodKey = "";
        public String state = "active";
        public int progress;
        public int target;
        public long claimedAt;
    }

    public synchronized QuestState questState(String defId, String periodKey) {
        try (Cursor c = db.getReadableDatabase().query("quests", null,
                "def_id=? AND period_key=?", new String[]{defId, periodKey}, null, null, null)) {
            if (!c.moveToFirst()) return null;
            QuestState s = new QuestState();
            s.defId = defId;
            s.periodKey = periodKey;
            s.state = c.getString(c.getColumnIndexOrThrow("state"));
            s.progress = c.getInt(c.getColumnIndexOrThrow("progress"));
            s.target = c.getInt(c.getColumnIndexOrThrow("target"));
            s.claimedAt = c.getLong(c.getColumnIndexOrThrow("claimed_at"));
            return s;
        }
    }

    public synchronized void putQuestState(String defId, String periodKey, String state,
                                           int progress, int target) {
        QuestState existing = questState(defId, periodKey);
        if (existing != null && "claimed".equals(existing.state)) return;

        ContentValues cv = new ContentValues();
        cv.put("def_id", defId);
        cv.put("period_key", periodKey);
        cv.put("state", state);
        cv.put("progress", progress);
        cv.put("target", target);
        cv.put("updated_at", System.currentTimeMillis());
        db.getWritableDatabase().insertWithOnConflict("quests", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized boolean claimQuest(String defId, String periodKey, int xp, String badge) {
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            QuestState st;
            try (Cursor c = d.query("quests", null, "def_id=? AND period_key=?",
                    new String[]{defId, periodKey}, null, null, null)) {
                if (!c.moveToFirst()) {
                    d.setTransactionSuccessful();
                    return false;
                }
                st = new QuestState();
                st.state = c.getString(c.getColumnIndexOrThrow("state"));
            }
            if (!"ready".equals(st.state)) {
                d.setTransactionSuccessful();
                return false;
            }

            ContentValues up = new ContentValues();
            up.put("state", "claimed");
            up.put("claimed_at", System.currentTimeMillis());
            d.update("quests", up, "def_id=? AND period_key=?", new String[]{defId, periodKey});

            ContentValues rv = new ContentValues();
            String key = defId + "|" + periodKey;
            rv.put("reward_key", key);
            rv.put("def_id", defId);
            rv.put("period_key", periodKey);
            rv.put("kind", "xp");
            rv.put("payload", String.valueOf(xp));
            rv.put("granted_at", System.currentTimeMillis());
            long id = d.insertWithOnConflict("rewards", null, rv, SQLiteDatabase.CONFLICT_IGNORE);

            boolean granted = id != -1;
            if (granted) {
                int total = metaGetInt(d, KEY_XP, 0) + Math.max(0, xp);
                metaSet(d, KEY_XP, String.valueOf(total));
                if (badge != null && !badge.isEmpty()) {
                    ContentValues av = new ContentValues();
                    av.put("id", badge);
                    av.put("unlocked_at", System.currentTimeMillis());
                    d.insertWithOnConflict("achievements", null, av, SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            d.setTransactionSuccessful();
            return granted;
        } finally {
            d.endTransaction();
        }
    }

    public synchronized List<QuestState> allQuestStates() {
        List<QuestState> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().query("quests", null, null, null,
                null, null, "period_key DESC")) {
            while (c.moveToNext()) {
                QuestState s = new QuestState();
                s.defId = c.getString(c.getColumnIndexOrThrow("def_id"));
                s.periodKey = c.getString(c.getColumnIndexOrThrow("period_key"));
                s.state = c.getString(c.getColumnIndexOrThrow("state"));
                s.progress = c.getInt(c.getColumnIndexOrThrow("progress"));
                s.target = c.getInt(c.getColumnIndexOrThrow("target"));
                s.claimedAt = c.getLong(c.getColumnIndexOrThrow("claimed_at"));
                out.add(s);
            }
        }
        return out;
    }

    public synchronized boolean hasAchievement(String id) {
        try (Cursor c = db.getReadableDatabase().query("achievements", new String[]{"id"},
                "id=?", new String[]{id}, null, null, null)) {
            return c.moveToFirst();
        }
    }

    public synchronized List<String> achievements() {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().query("achievements", new String[]{"id"},
                null, null, null, null, "unlocked_at ASC")) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /* ------------------------------------------------------ export / import */

    public synchronized JSONObject exportJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "Stride");
            root.put("schemaVersion", 1);
            root.put("exportedAt", Instant.now().toString());
            root.put("xp", xp());

            JSONObject settings = new JSONObject();
            settings.put("goal", goal());
            settings.put("stride_cm", strideCm());
            settings.put("weight_kg", weightKg());
            settings.put("units", units());
            settings.put("reminder_on", reminderOn());
            settings.put("reminder_minutes", reminderMinutes());
            root.put("settings", settings);

            JSONArray days = new JSONArray();
            for (Day d : daysDesc(100000)) days.put(d.toJson());
            root.put("days", days);

            JSONArray quests = new JSONArray();
            for (QuestState s : allQuestStates()) {
                JSONObject q = new JSONObject();
                q.put("id", s.defId);
                q.put("period", s.periodKey);
                q.put("state", s.state);
                q.put("progress", s.progress);
                q.put("target", s.target);
                q.put("claimed_at", s.claimedAt);
                quests.put(q);
            }
            root.put("quests", quests);

            JSONArray achievements = new JSONArray();
            for (String id : achievements()) achievements.put(id);
            root.put("achievements", achievements);

        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return root;
    }

    public synchronized String exportCsv() {
        StringBuilder sb = new StringBuilder("date,steps,distance_m,calories,goal,goal_reached\n");
        for (Day d : daysDesc(100000)) {
            sb.append(d.date).append(',')
                    .append(d.steps).append(',')
                    .append(String.format(java.util.Locale.US, "%.1f", d.distanceM)).append(',')
                    .append(String.format(java.util.Locale.US, "%.1f", d.calories)).append(',')
                    .append(d.goal).append(',')
                    .append(d.goalReached ? 1 : 0).append('\n');
        }
        return sb.toString();
    }

    /** Restores a Stride JSON backup. Returns number of imported days. */
    public synchronized int importJson(JSONObject root) throws JSONException {
        if (!"Stride".equals(root.optString("app"))) {
            throw new JSONException("Not a Stride backup file.");
        }
        int version = root.optInt("schemaVersion", 0);
        if (version < 1 || version > 1) {
            throw new JSONException("Unsupported backup version: " + version);
        }

        JSONObject settings = root.optJSONObject("settings");
        JSONArray days = root.optJSONArray("days");
        JSONArray quests = root.optJSONArray("quests");
        JSONArray achievements = root.optJSONArray("achievements");

        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            d.delete("days", null, null);
            d.delete("quests", null, null);
            d.delete("rewards", null, null);
            d.delete("achievements", null, null);

            if (settings != null) {
                setMeta(KEY_GOAL, String.valueOf(settings.optInt("goal", DEFAULT_GOAL)));
                setMeta(KEY_STRIDE, String.valueOf(settings.optInt("stride_cm", DEFAULT_STRIDE_CM)));
                setMeta(KEY_WEIGHT, String.valueOf(settings.optInt("weight_kg", DEFAULT_WEIGHT_KG)));
                setMeta(KEY_UNITS, settings.optString("units", "km"));
                setMeta(KEY_REMINDER_ON, settings.optBoolean("reminder_on", false) ? "1" : "0");
                setMeta(KEY_REMINDER_MIN, String.valueOf(settings.optInt("reminder_minutes", 1200)));
            }
            setMeta(KEY_XP, String.valueOf(root.optInt("xp", 0)));
            setMeta(KEY_SENSOR_LAST, "-1"); // never trust another device's sensor baseline

            int count = 0;
            if (days != null) {
                for (int i = 0; i < days.length(); i++) {
                    JSONObject o = days.getJSONObject(i);
                    ContentValues cv = new ContentValues();
                    cv.put("date", o.getString("date"));
                    cv.put("steps", o.optInt("steps", 0));
                    cv.put("distance_m", o.optDouble("distance_m", 0));
                    cv.put("calories", o.optDouble("calories", 0));
                    cv.put("goal", o.optInt("goal", goal()));
                    cv.put("goal_reached", o.optBoolean("goal_reached", false) ? 1 : 0);
                    cv.put("updated_at", System.currentTimeMillis());
                    d.insertWithOnConflict("days", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    count++;
                }
            }
            if (quests != null) {
                for (int i = 0; i < quests.length(); i++) {
                    JSONObject o = quests.getJSONObject(i);
                    ContentValues cv = new ContentValues();
                    cv.put("def_id", o.getString("id"));
                    cv.put("period_key", o.getString("period"));
                    cv.put("state", o.optString("state", "active"));
                    cv.put("progress", o.optInt("progress", 0));
                    cv.put("target", o.optInt("target", 0));
                    cv.put("claimed_at", o.optLong("claimed_at", 0));
                    d.insertWithOnConflict("quests", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            if (achievements != null) {
                for (int i = 0; i < achievements.length(); i++) {
                    ContentValues cv = new ContentValues();
                    cv.put("id", achievements.getString(i));
                    cv.put("unlocked_at", System.currentTimeMillis());
                    d.insertWithOnConflict("achievements", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                }
            }

            d.setTransactionSuccessful();
            return count;
        } finally {
            d.endTransaction();
        }
    }

    public synchronized void resetAll() {
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            d.delete("days", null, null);
            d.delete("quests", null, null);
            d.delete("rewards", null, null);
            d.delete("achievements", null, null);
            d.delete("meta", null, null);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** Debug builds only: seeds two weeks of plausible data for UI testing. */
    public synchronized void debugSeedDemo() {
        SQLiteDatabase d = db.getWritableDatabase();
        int goal = goal();
        int stride = strideCm();
        int weight = weightKg();
        java.util.Random random = new java.util.Random(42);
        d.beginTransaction();
        try {
            for (int i = 13; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                int steps = i == 0
                        ? Math.max(1200, goal / 2)
                        : (int) Math.round(goal * (0.45 + random.nextDouble() * 0.85));
                ContentValues cv = new ContentValues();
                cv.put("date", date.toString());
                cv.put("steps", steps);
                cv.put("distance_m", steps * stride / 100.0);
                cv.put("calories", steps * 0.04 * (weight / 70.0));
                cv.put("goal", goal);
                cv.put("goal_reached", steps >= goal ? 1 : 0);
                cv.put("updated_at", System.currentTimeMillis());
                d.insertWithOnConflict("days", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /* --------------------------------------------------------------- helpers */

    private long metaGetLong(SQLiteDatabase d, String key, long def) {
        try (Cursor c = d.query("meta", new String[]{"v"}, "k=?", new String[]{key},
                null, null, null)) {
            if (c.moveToFirst()) {
                try {
                    return Long.parseLong(c.getString(0));
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
        }
        return def;
    }

    private int metaGetInt(SQLiteDatabase d, String key, int def) {
        return (int) metaGetLong(d, key, def);
    }

    private void metaSet(SQLiteDatabase d, String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("k", key);
        cv.put("v", value);
        d.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static class DB extends SQLiteOpenHelper {
        DB(Context context) {
            super(context, "stride.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            d.execSQL("CREATE TABLE days(" +
                    "date TEXT PRIMARY KEY," +
                    "steps INTEGER NOT NULL DEFAULT 0," +
                    "distance_m REAL NOT NULL DEFAULT 0," +
                    "calories REAL NOT NULL DEFAULT 0," +
                    "goal INTEGER NOT NULL DEFAULT 0," +
                    "goal_reached INTEGER NOT NULL DEFAULT 0," +
                    "updated_at INTEGER NOT NULL DEFAULT 0)");
            d.execSQL("CREATE TABLE quests(" +
                    "def_id TEXT NOT NULL," +
                    "period_key TEXT NOT NULL," +
                    "state TEXT NOT NULL," +
                    "progress INTEGER NOT NULL DEFAULT 0," +
                    "target INTEGER NOT NULL DEFAULT 0," +
                    "updated_at INTEGER NOT NULL DEFAULT 0," +
                    "claimed_at INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(def_id, period_key))");
            d.execSQL("CREATE TABLE rewards(" +
                    "reward_key TEXT PRIMARY KEY," +
                    "def_id TEXT," +
                    "period_key TEXT," +
                    "kind TEXT," +
                    "payload TEXT," +
                    "granted_at INTEGER NOT NULL DEFAULT 0)");
            d.execSQL("CREATE TABLE achievements(" +
                    "id TEXT PRIMARY KEY," +
                    "unlocked_at INTEGER NOT NULL DEFAULT 0)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
            // v1: nothing yet
        }
    }
}
