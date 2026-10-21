package com.darsha.steps;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * On-device quest engine. Definitions ship in assets/quests.json.
 * Progress is derived from the local activity table, so it is safe to
 * recompute after imports or restarts. No server involved.
 */
public class QuestEngine {

    public static final String CAT_DAILY = "daily";
    public static final String CAT_WEEKLY = "weekly";
    public static final String CAT_MILESTONES = "milestones";

    public static class Quest {
        public String id = "";
        public String title = "";
        public String description = "";
        public String category = "";
        public String type = "";
        public String badge = "";
        public double multiplier;
        public int target;
        public int xp;
    }

    public static class Status {
        public Quest quest;
        public String periodKey = "";
        public String state = "active"; // active | ready | claimed
        public int progress;
        public int target;
    }

    private static List<Quest> definitions;

    public static synchronized List<Quest> definitions(Context context) {
        if (definitions == null) definitions = load(context);
        return definitions;
    }

    private static List<Quest> load(Context context) {
        List<Quest> out = new ArrayList<>();
        try {
            String json = readAsset(context, "quests.json");
            JSONObject root = new JSONObject(json);
            addCategory(out, root.optJSONArray(CAT_DAILY), CAT_DAILY);
            addCategory(out, root.optJSONArray(CAT_WEEKLY), CAT_WEEKLY);
            addCategory(out, root.optJSONArray(CAT_MILESTONES), CAT_MILESTONES);
        } catch (Exception e) {
            // A broken asset should not crash step tracking; fall back to empty list.
            e.printStackTrace();
        }
        return out;
    }

    private static void addCategory(List<Quest> out, JSONArray array, String category) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            Quest q = new Quest();
            q.id = o.optString("id");
            q.title = o.optString("title");
            q.description = o.optString("description");
            q.category = category;
            q.type = o.optString("type");
            q.badge = o.optString("badge", "");
            q.multiplier = o.optDouble("multiplier", 0);
            q.target = o.optInt("target", 0);
            q.xp = o.optInt("xp", 10);
            if (!q.id.isEmpty() && !q.type.isEmpty()) out.add(q);
        }
    }

    private static String readAsset(Context context, String name) throws Exception {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    public static String periodKey(Quest q, LocalDate today) {
        if (CAT_DAILY.equals(q.category)) return today.toString();
        if (CAT_WEEKLY.equals(q.category)) {
            return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
        }
        return "ever";
    }

    public static int targetFor(Quest q, int goal) {
        if (q.multiplier > 0) return (int) Math.round(goal * q.multiplier);
        return q.target;
    }

    public static String describe(Quest q, int target) {
        return q.description.replace("{target}", String.format(java.util.Locale.US, "%,d", target));
    }

    /** Creates/updates this period's quest instances from current activity data. */
    public static void sync(Context context) {
        Store store = Store.get(context);
        int goal = store.goal();
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        for (Quest q : definitions(context)) {
            String key = periodKey(q, today);
            int target = targetFor(q, goal);
            int progress = evaluate(q, store, today, weekStart);

            Store.QuestState existing = store.questState(q.id, key);
            if (existing != null && "claimed".equals(existing.state)) continue;

            String state = progress >= target ? "ready" : "active";
            store.putQuestState(q.id, key, state, Math.min(progress, target), target);
        }
    }

    private static int evaluate(Quest q, Store store, LocalDate today, LocalDate weekStart) {
        switch (q.type) {
            case "daily_steps": {
                Store.Day day = store.day(today.toString());
                return day == null ? 0 : day.steps;
            }
            case "weekly_goal_days":
                return store.countGoalDays(weekStart);
            case "weekly_active_days":
                return store.countActiveDays(weekStart, 1000);
            case "weekly_steps":
                return store.weekSteps(weekStart);
            case "lifetime_steps":
                return (int) Math.min(Integer.MAX_VALUE, store.lifetimeSteps());
            case "best_day":
                return store.bestDaySteps();
            case "streak_days":
                return store.streak();
            default:
                return 0;
        }
    }

    public static List<Status> list(Context context, String category) {
        sync(context);
        Store store = Store.get(context);
        int goal = store.goal();
        LocalDate today = LocalDate.now();

        List<Status> out = new ArrayList<>();
        for (Quest q : definitions(context)) {
            if (!q.category.equals(category)) continue;
            Status s = new Status();
            s.quest = q;
            s.periodKey = periodKey(q, today);
            s.target = targetFor(q, goal);
            Store.QuestState st = store.questState(q.id, s.periodKey);
            if (st != null) {
                s.state = st.state;
                s.progress = st.progress;
                s.target = st.target > 0 ? st.target : s.target;
            }
            out.add(s);
        }
        return out;
    }

    public static boolean claim(Context context, Status s) {
        sync(context);
        Store store = Store.get(context);
        Store.QuestState st = store.questState(s.quest.id, s.periodKey);
        if (st == null || !"ready".equals(st.state)) return false;
        return store.claimQuest(s.quest.id, s.periodKey, s.quest.xp, s.quest.badge);
    }

    /* ------------------------------------------------------- level math */

    public static int level(int xp) {
        int level = 1;
        while (level < 999 && xp >= 100 * level * level) level++;
        return level;
    }

    public static int levelStartXp(int level) {
        int l = Math.max(1, level);
        return 100 * (l - 1) * (l - 1);
    }

    public static int nextLevelXp(int level) {
        int l = Math.max(1, level);
        return 100 * l * l;
    }

    public static String badgeTitle(Context context, String badgeId) {
        for (Quest q : definitions(context)) {
            if (badgeId.equals(q.badge)) return q.title;
        }
        return badgeId;
    }
}
