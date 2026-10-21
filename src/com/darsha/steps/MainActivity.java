package com.darsha.steps;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // Palette
    private static final int BG = 0xFF0B0F14;
    private static final int CARD = 0xFF151B23;
    private static final int CARD2 = 0xFF1B232D;
    private static final int TEXT = 0xFFECF2F8;
    private static final int MUTED = 0xFF8FA0B3;
    private static final int ACCENT = 0xFF3DDC97;
    private static final int BLUE = 0xFF4DA3FF;
    private static final int BORDER = 0xFF202A36;
    private static final int BAR_BG = 0xFF243140;
    private static final int DANGER = 0xFFFF6B6B;

    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_EXPORT_JSON = 11;
    private static final int REQ_EXPORT_CSV = 12;
    private static final int REQ_IMPORT = 13;

    private Store store;
    private FrameLayout content;
    private TextView[] tabs;
    private int tabIndex = 0;

    private String pendingExport;
    private int historyRange = 7;
    private String questCategory = QuestEngine.CAT_DAILY;

    // Today page live references
    private TextView tvSteps;
    private TextView tvGoalLabel;
    private TextView tvDist;
    private TextView tvCal;
    private TextView tvStreak;
    private TextView tvStatus;
    private Charts.RingView ring;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (tabIndex == 0) updateToday();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(0xFF0E141B);
        buildShell();
        selectTab(0);
        ensurePermissions();
        Reminders.reschedule(this);
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(0xFF0E141B);
        nav.setPadding(dp(4), dp(4), dp(4), dp(6));

        String[] labels = {"Today", "History", "Quests", "Awards", "Settings"};
        tabs = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView t = tv(labels[i], 12, MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setOnClickListener(v -> selectTab(index));
            nav.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabs[i] = t;
        }

        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void selectTab(int index) {
        tabIndex = index;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == index ? ACCENT : MUTED);
            tabs[i].setTypeface(i == index ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        content.removeAllViews();
        View page;
        switch (index) {
            case 1:
                page = buildHistory();
                break;
            case 2:
                page = buildQuests();
                break;
            case 3:
                page = buildAwards();
                break;
            case 4:
                page = buildSettings();
                break;
            default:
                page = buildToday();
                break;
        }
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /* ------------------------------------------------------------- today */

    private View buildToday() {
        LinearLayout root = pageRoot();

        LinearLayout head = row();
        TextView title = tv("Today", 28, TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(tv(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
                .format(LocalDate.now()), 13, MUTED, false));
        addBlock(root, head, 0, 16);

        if (!hasActivityPermission()) {
            LinearLayout warn = card();
            warn.addView(tv("Step sensor permission needed", 15, TEXT, true));
            TextView d = tv("Stride reads the phone's hardware pedometer so it can count steps. "
                    + "No data leaves this phone.", 13, MUTED, false);
            d.setPadding(0, dp(6), 0, dp(10));
            warn.addView(d);
            warn.addView(button("Grant permission", ACCENT, BG, this::ensurePermissions),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            addCard(root, warn);
        }

        LinearLayout ringCard = card();
        FrameLayout ringWrap = new FrameLayout(this);
        ring = new Charts.RingView(this);
        ringWrap.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout center = column();
        center.setGravity(Gravity.CENTER);
        tvSteps = tv("0", 52, TEXT, true);
        tvSteps.setGravity(Gravity.CENTER);
        tvGoalLabel = tv("of 8,000 steps", 13, MUTED, false);
        tvGoalLabel.setGravity(Gravity.CENTER);
        center.addView(tvSteps);
        center.addView(tvGoalLabel);
        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        centerLp.gravity = Gravity.CENTER;
        ringWrap.addView(center, centerLp);
        ringCard.addView(ringWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        tvStatus = tv("Starting local counter…", 12, MUTED, false);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(6), 0, dp(12));
        ringCard.addView(tvStatus);

        LinearLayout stats = row();
        tvDist = tv("0 km", 17, TEXT, true);
        tvCal = tv("0", 17, TEXT, true);
        tvStreak = tv("0", 17, TEXT, true);
        stats.addView(statColumn("Distance", tvDist), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stats.addView(statColumn("Calories", tvCal), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stats.addView(statColumn("Day streak", tvStreak), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ringCard.addView(stats);
        addCard(root, ringCard);

        LinearLayout quests = card();
        LinearLayout qHead = row();
        qHead.addView(tv("Today's quests", 15, TEXT, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView viewAll = tv("View all ›", 12, ACCENT, true);
        viewAll.setOnClickListener(v -> selectTab(2));
        qHead.addView(viewAll);
        quests.addView(qHead);

        LinearLayout preview = column();
        preview.setPadding(0, dp(8), 0, 0);
        List<QuestEngine.Status> daily = QuestEngine.list(this, QuestEngine.CAT_DAILY);
        if (daily.isEmpty()) {
            preview.addView(tv("No quests configured.", 12, MUTED, false));
        }
        for (QuestEngine.Status s : daily) {
            LinearLayout line = row();
            line.setPadding(0, dp(5), 0, dp(5));
            line.addView(tv(s.quest.title, 13, TEXT, false), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String stateText = "claimed".equals(s.state) ? "✓"
                    : "ready".equals(s.state) ? "Claim" : s.progress + "/" + s.target;
            line.addView(tv(stateText, 12,
                    "claimed".equals(s.state) || "ready".equals(s.state) ? ACCENT : MUTED, true));
            preview.addView(line);
        }
        preview.setOnClickListener(v -> selectTab(2));
        quests.addView(preview);
        addCard(root, quests);

        TextView foot = tv("Private by design · no network permission · no ads · no paywalls",
                11, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(4), 0, dp(10));
        root.addView(foot);

        updateToday();
        return scroll(root);
    }

    private void updateToday() {
        if (tvSteps == null) return;
        int goal = store.goal();
        Store.Day day = store.day(LocalDate.now().toString());
        int steps = day == null ? 0 : day.steps;

        tvSteps.setText(fmt(steps));
        tvGoalLabel.setText("of " + fmt(goal) + " steps");
        ring.setProgress(goal > 0 ? steps / (float) goal : 0f);
        ring.setAccent(steps >= goal ? ACCENT : BLUE);

        tvDist.setText(formatDistance(day == null ? 0 : day.distanceM));
        tvCal.setText(day == null ? "0" : String.valueOf(Math.round(day.calories)));
        tvStreak.setText(String.valueOf(store.streak()));

        boolean perm = hasActivityPermission();
        if (!perm) {
            tvStatus.setText("Sensor permission needed");
            tvStatus.setTextColor(DANGER);
        } else if (StepService.RUNNING) {
            tvStatus.setText("● Counting on-device");
            tvStatus.setTextColor(ACCENT);
        } else {
            tvStatus.setText("○ Counter paused");
            tvStatus.setTextColor(MUTED);
        }
    }

    /* ----------------------------------------------------------- history */

    private View buildHistory() {
        LinearLayout root = pageRoot();
        header(root, "History", "Daily totals from your local database.");

        LinearLayout chips = row();
        int[] ranges = {7, 30, 90, 365};
        String[] names = {"7 days", "30 days", "90 days", "1 year"};
        for (int i = 0; i < ranges.length; i++) {
            final int range = ranges[i];
            TextView c = chip(names[i], historyRange == range, () -> {
                historyRange = range;
                selectTab(1);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chips.addView(c, lp);
        }
        addBlock(root, chips, 0, 14);

        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(historyRange - 1L);
        List<Store.Day> days = store.daysForRange(from, today);

        LinearLayout chartCard = card();
        Charts.BarChartView chart = new Charts.BarChartView(this);
        chart.setData(days, store.goal());
        chartCard.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
        addCard(root, chartCard);

        long total = 0;
        int best = 0;
        for (Store.Day d : days) {
            total += d.steps;
            best = Math.max(best, d.steps);
        }
        int avg = days.isEmpty() ? 0 : (int) (total / days.size());

        LinearLayout stats = row();
        stats.addView(statColumn("Average", tv(fmt(avg), 17, TEXT, true)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stats.addView(statColumn("Best day", tv(fmt(best), 17, TEXT, true)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stats.addView(statColumn("Total", tv(fmt((int) Math.min(total, Integer.MAX_VALUE)), 17, TEXT, true)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout statCard = card();
        statCard.addView(stats);
        addCard(root, statCard);

        LinearLayout listCard = card();
        listCard.addView(tv("Recent days", 15, TEXT, true));
        LinearLayout list = column();
        list.setPadding(0, dp(6), 0, 0);
        List<Store.Day> recent = store.daysDesc(30);
        if (recent.isEmpty()) {
            list.addView(tv("No stored days yet.", 13, MUTED, false));
        }
        DateTimeFormatter fmtDate = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault());
        for (Store.Day d : recent) {
            LinearLayout line = row();
            line.setPadding(0, dp(7), 0, dp(7));
            String dateText;
            try {
                dateText = LocalDate.parse(d.date).format(fmtDate);
            } catch (Exception e) {
                dateText = d.date;
            }
            line.addView(tv(dateText, 13, MUTED, false), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(tv(fmt(d.steps), 14, TEXT, true));
            TextView mark = tv(d.goalReached ? "●" : "○", 12,
                    d.goalReached ? ACCENT : MUTED, true);
            mark.setPadding(dp(10), 0, 0, 0);
            line.addView(mark);
            list.addView(line);
        }
        listCard.addView(list);
        addCard(root, listCard);

        return scroll(root);
    }

    /* ------------------------------------------------------------ quests */

    private View buildQuests() {
        LinearLayout root = pageRoot();
        header(root, "Quests", "Optional goals computed entirely on this phone.");

        int xp = store.xp();
        int level = QuestEngine.level(xp);
        int start = QuestEngine.levelStartXp(level);
        int next = QuestEngine.nextLevelXp(level);
        int need = Math.max(1, next - start);

        LinearLayout xpCard = card();
        LinearLayout top = row();
        top.addView(tv("Level " + level, 22, TEXT, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(tv(fmt(xp) + " XP total", 12, MUTED, false));
        xpCard.addView(top);

        ProgressBar xpBar = progressBar();
        xpBar.setMax(need);
        xpBar.setProgress(Math.min(need, Math.max(0, xp - start)));
        xpBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams xpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        xpLp.topMargin = dp(10);
        xpCard.addView(xpBar, xpLp);
        TextView xpInfo = tv(fmt(Math.max(0, xp - start)) + " / " + fmt(need)
                + " XP to level " + (level + 1), 12, MUTED, false);
        xpInfo.setPadding(0, dp(6), 0, 0);
        xpCard.addView(xpInfo);
        addCard(root, xpCard);

        LinearLayout chips = row();
        String[][] cats = {
                {QuestEngine.CAT_DAILY, "Daily"},
                {QuestEngine.CAT_WEEKLY, "Weekly"},
                {QuestEngine.CAT_MILESTONES, "Milestones"}
        };
        for (String[] cat : cats) {
            TextView c = chip(cat[1], questCategory.equals(cat[0]), () -> {
                questCategory = cat[0];
                selectTab(2);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chips.addView(c, lp);
        }
        addBlock(root, chips, 0, 14);

        List<QuestEngine.Status> quests = QuestEngine.list(this, questCategory);
        for (QuestEngine.Status s : quests) {
            addCard(root, questCard(s));
        }

        TextView note = tv("Claimed quests stay in your history. Daily quests reset at local midnight; "
                + "weekly quests reset on Monday. No timers pressure you — missing a day never deletes past awards.",
                11, MUTED, false);
        note.setPadding(0, dp(4), 0, dp(10));
        root.addView(note);

        return scroll(root);
    }

    private View questCard(QuestEngine.Status s) {
        LinearLayout c = card();
        LinearLayout top = row();
        top.addView(tv(s.quest.title, 16, TEXT, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView xp = tv("+" + s.quest.xp + " XP", 11, ACCENT, true);
        xp.setBackground(rounded(0xFF10281F, 10, 0xFF1E4A38));
        xp.setPadding(dp(8), dp(3), dp(8), dp(3));
        top.addView(xp);
        c.addView(top);

        TextView desc = tv(QuestEngine.describe(s.quest, s.target), 13, MUTED, false);
        desc.setPadding(0, dp(6), 0, dp(10));
        c.addView(desc);

        ProgressBar pb = progressBar();
        pb.setMax(Math.max(1, s.target));
        pb.setProgress(Math.min(s.progress, s.target));
        boolean ready = "ready".equals(s.state);
        pb.setProgressTintList(ColorStateList.valueOf(ready ? ACCENT : BLUE));
        c.addView(pb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        LinearLayout foot = row();
        foot.setPadding(0, dp(10), 0, 0);
        String unit = s.quest.type.contains("days") ? "days" : "steps";
        String progress = fmt(s.progress) + " / " + fmt(s.target) + " " + unit;
        TextView p = tv(progress, 12, ready ? ACCENT : MUTED, false);
        foot.addView(p, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (ready) {
            TextView claim = button("Claim", ACCENT, BG, () -> {
                if (QuestEngine.claim(MainActivity.this, s)) {
                    toast("+" + s.quest.xp + " XP claimed");
                    selectTab(2);
                } else {
                    toast("This quest is not ready yet");
                }
            });
            claim.setPadding(dp(18), dp(7), dp(18), dp(7));
            foot.addView(claim);
        } else if ("claimed".equals(s.state)) {
            foot.addView(tv("✓ Claimed", 12, ACCENT, true));
        } else {
            String reset = QuestEngine.CAT_DAILY.equals(s.quest.category)
                    ? "Resets at midnight" : QuestEngine.CAT_WEEKLY.equals(s.quest.category)
                    ? "Resets Monday" : "Milestone";
            foot.addView(tv(reset, 11, MUTED, false));
        }
        c.addView(foot);
        return c;
    }

    /* ------------------------------------------------------------ awards */

    private View buildAwards() {
        LinearLayout root = pageRoot();
        header(root, "Awards", "Badges are stored locally and never expire.");

        int xp = store.xp();
        int level = QuestEngine.level(xp);
        LinearLayout card = card();
        card.addView(tv("Level " + level, 20, TEXT, true));
        card.addView(tv(fmt(xp) + " total XP", 12, MUTED, false));
        addCard(root, card);

        LinearLayout badges = card();
        badges.addView(tv("Badges", 15, TEXT, true));
        badges.addView(tv("Unlocked by claiming weekly and milestone quests.",
                12, MUTED, false));

        List<QuestEngine.Quest> defs = QuestEngine.definitions(this);
        boolean any = false;
        for (QuestEngine.Quest q : defs) {
            if (q.badge == null || q.badge.isEmpty()) continue;
            any = true;
            boolean unlocked = store.hasAchievement(q.badge);
            LinearLayout r = row();
            r.setPadding(0, dp(10), 0, dp(4));
            TextView star = tv("★", 22, unlocked ? ACCENT : MUTED, false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.rightMargin = dp(12);
            r.addView(star, slp);
            LinearLayout col = column();
            col.addView(tv(q.title, 14, unlocked ? TEXT : MUTED, true));
            col.addView(tv(unlocked ? "Unlocked" : "Locked · " + q.description.replace("{target}",
                    fmt(QuestEngine.targetFor(q, store.goal()))), 11, MUTED, false));
            r.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            badges.addView(r);
        }
        if (!any) badges.addView(tv("No badges configured.", 12, MUTED, false));
        addCard(root, badges);

        return scroll(root);
    }

    /* ---------------------------------------------------------- settings */

    private View buildSettings() {
        LinearLayout root = pageRoot();
        header(root, "Settings", "Everything is stored on this phone only.");

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            LinearLayout debugCard = card();
            debugCard.addView(tv("Debug tools", 15, DANGER, true));
            debugCard.addView(tv("Visible only in debug builds.", 12, MUTED, false));
            TextView seed = button("Seed 14 days of demo data", CARD2, TEXT, () -> {
                store.debugSeedDemo();
                QuestEngine.sync(this);
                selectTab(4);
                toast("Demo data seeded");
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(8);
            debugCard.addView(seed, slp);

            TextView debugExport = button("Write JSON + CSV to app storage", CARD2, TEXT, () -> {
                try {
                    java.io.File dir = getExternalFilesDir(null);
                    if (dir == null) {
                        toast("App storage unavailable");
                        return;
                    }
                    if (!dir.exists()) dir.mkdirs();
                    writeFile(new java.io.File(dir, "stride-debug-backup.json"),
                            buildBackup().toString(2));
                    writeFile(new java.io.File(dir, "stride-debug-export.csv"), store.exportCsv());
                    toast("Wrote debug files to app storage");
                } catch (Exception e) {
                    toast("Debug export failed: " + e.getMessage());
                }
            });
            LinearLayout.LayoutParams delp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            delp.topMargin = dp(8);
            debugCard.addView(debugExport, delp);

            TextView debugImport = button("Import debug JSON (replaces data)", CARD2, TEXT, () -> {
                try {
                    java.io.File dir = getExternalFilesDir(null);
                    java.io.File f = new java.io.File(dir, "stride-debug-backup.json");
                    if (!f.exists()) {
                        toast("No debug backup written yet");
                        return;
                    }
                    JSONObject backup = new JSONObject(readFile(f));
                    int count = store.importJson(backup);
                    Reminders.reschedule(this);
                    QuestEngine.sync(this);
                    selectTab(4);
                    toast("Imported " + count + " day(s) from debug file");
                } catch (Exception e) {
                    toast("Debug import failed: " + e.getMessage());
                }
            });
            LinearLayout.LayoutParams dilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dilp.topMargin = dp(8);
            debugCard.addView(debugImport, dilp);
            addCard(root, debugCard);
        }

        LinearLayout goalCard = card();
        goalCard.addView(tv("Daily step goal", 15, TEXT, true));
        LinearLayout goalRow = row();
        goalRow.setPadding(0, dp(8), 0, 0);
        EditText goalInput = numberField(store.goal());
        goalRow.addView(goalInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView saveGoal = button("Save", ACCENT, BG, () -> {
            try {
                int g = Integer.parseInt(goalInput.getText().toString().trim());
                if (g < 1000 || g > 200000) {
                    toast("Choose a goal between 1,000 and 200,000");
                    return;
                }
                store.setGoal(g);
                QuestEngine.sync(this);
                toast("Goal updated");
                selectTab(4);
            } catch (NumberFormatException e) {
                toast("Enter a number");
            }
        });
        LinearLayout.LayoutParams sgp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sgp.leftMargin = dp(8);
        goalRow.addView(saveGoal, sgp);
        goalCard.addView(goalRow);
        addCard(root, goalCard);

        LinearLayout bodyCard = card();
        bodyCard.addView(tv("Body & units", 15, TEXT, true));

        bodyCard.addView(tv("Stride length (cm)", 12, MUTED, false));
        EditText strideInput = numberField(store.strideCm());
        bodyCard.addView(strideInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView weightLabel = tv("Weight (kg) · used only for the calorie estimate", 12, MUTED, false);
        weightLabel.setPadding(0, dp(10), 0, 0);
        bodyCard.addView(weightLabel);
        EditText weightInput = numberField(store.weightKg());
        bodyCard.addView(weightInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout unitsRow = row();
        unitsRow.setPadding(0, dp(12), 0, 0);
        unitsRow.addView(tv("Units", 13, TEXT, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView km = chip("km", "km".equals(store.units()), () -> {
            store.setMeta(Store.KEY_UNITS, "km");
            selectTab(4);
        });
        TextView mi = chip("mi", "mi".equals(store.units()), () -> {
            store.setMeta(Store.KEY_UNITS, "mi");
            selectTab(4);
        });
        LinearLayout.LayoutParams lpm = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpm.leftMargin = dp(8);
        unitsRow.addView(km);
        unitsRow.addView(mi, lpm);
        bodyCard.addView(unitsRow);

        TextView saveBody = button("Save body settings", CARD2, TEXT, () -> {
            try {
                int stride = Integer.parseInt(strideInput.getText().toString().trim());
                int weight = Integer.parseInt(weightInput.getText().toString().trim());
                if (stride < 30 || stride > 120) {
                    toast("Stride should be 30–120 cm");
                    return;
                }
                if (weight < 25 || weight > 250) {
                    toast("Weight should be 25–250 kg");
                    return;
                }
                store.setMeta(Store.KEY_STRIDE, String.valueOf(stride));
                store.setMeta(Store.KEY_WEIGHT, String.valueOf(weight));
                toast("Saved");
                selectTab(4);
            } catch (NumberFormatException e) {
                toast("Enter numbers for stride and weight");
            }
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbp.topMargin = dp(12);
        bodyCard.addView(saveBody, sbp);
        addCard(root, bodyCard);

        LinearLayout reminderCard = card();
        LinearLayout remRow = row();
        Switch sw = new Switch(this);
        sw.setText("Daily walk reminder");
        sw.setTextColor(TEXT);
        sw.setChecked(store.reminderOn());
        sw.setOnCheckedChangeListener((buttonView, checked) -> {
            store.setMeta(Store.KEY_REMINDER_ON, checked ? "1" : "0");
            Reminders.reschedule(this);
            if (checked && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSIONS);
            }
        });
        remRow.addView(sw, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        reminderCard.addView(remRow);

        int mins = store.reminderMinutes();
        String time = String.format(Locale.getDefault(), "%d:%02d %s",
                (mins / 60) % 24 == 0 ? 12 : ((mins / 60) % 24 > 12 ? (mins / 60) % 24 - 12 : (mins / 60) % 24),
                mins % 60, (mins / 60) % 24 >= 12 ? "PM" : "AM");
        TextView timeBtn = button("Reminder time: " + time, CARD2, TEXT, this::pickReminderTime);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        reminderCard.addView(timeBtn, tlp);
        addCard(root, reminderCard);

        LinearLayout dataCard = card();
        dataCard.addView(tv("Your data", 15, TEXT, true));
        TextView dataNote = tv("Export a versioned JSON backup or a CSV of daily rows. "
                + "Files are written only where you choose.", 12, MUTED, false);
        dataNote.setPadding(0, dp(4), 0, dp(10));
        dataCard.addView(dataNote);

        TextView exportJson = button("Export JSON backup", ACCENT, BG, () -> beginExport(true));
        dataCard.addView(exportJson, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView exportCsv = button("Export CSV", CARD2, TEXT, () -> beginExport(false));
        LinearLayout.LayoutParams csvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        csvLp.topMargin = dp(8);
        dataCard.addView(exportCsv, csvLp);

        TextView importBtn = button("Import JSON backup", CARD2, TEXT, this::beginImport);
        LinearLayout.LayoutParams impLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        impLp.topMargin = dp(8);
        dataCard.addView(importBtn, impLp);
        addCard(root, dataCard);

        LinearLayout sysCard = card();
        sysCard.addView(tv("System", 15, TEXT, true));
        TextView battBtn = button("Battery optimization settings", CARD2, TEXT, () -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        sysCard.addView(battBtn, blp);

        TextView permBtn = button("App permissions", CARD2, TEXT, () -> {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) {
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(8);
        sysCard.addView(permBtn, plp);

        TextView about = tv("Stride 1.0 · schema 1\n\n"
                + "This app has no internet permission, no account, no advertising, no analytics "
                + "and no paywalls. Steps are read from the phone's hardware pedometer and stored "
                + "in a private local database. Distance and calories are estimates.\n\n"
                + "If you export a file, that copy is outside the app's control.",
                11, MUTED, false);
        about.setPadding(0, dp(14), 0, 0);
        sysCard.addView(about);
        addCard(root, sysCard);

        LinearLayout dangerCard = card();
        dangerCard.addView(tv("Danger zone", 15, DANGER, true));
        dangerCard.addView(tv("Deletes every step, quest, award and setting on this phone.",
                12, MUTED, false));
        TextView delete = button("Delete all data", 0xFF3A1D1D, DANGER, this::confirmDelete);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(10);
        dangerCard.addView(delete, dlp);
        addCard(root, dangerCard);

        return scroll(root);
    }

    private void pickReminderTime() {
        int mins = store.reminderMinutes();
        new TimePickerDialog(this, (view, hour, minute) -> {
            store.setMeta(Store.KEY_REMINDER_MIN, String.valueOf(hour * 60 + minute));
            store.setMeta(Store.KEY_REMINDER_ON, "1");
            Reminders.reschedule(this);
            selectTab(4);
        }, mins / 60, mins % 60, false).show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete all data?")
                .setMessage("Every stored day, quest, award and setting will be removed. "
                        + "Exported files are not affected. This cannot be undone.")
                .setPositiveButton("Delete everything", (d, w) -> {
                    store.resetAll();
                    Reminders.reschedule(this);
                    QuestEngine.sync(this);
                    selectTab(0);
                    toast("All local data deleted");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /* ------------------------------------------------------ export/import */

    private void beginExport(boolean json) {
        try {
            pendingExport = json ? buildBackup().toString(2) : store.exportCsv();
        } catch (Exception e) {
            toast("Could not build export");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(json ? "application/json" : "text/csv");
        intent.putExtra(Intent.EXTRA_TITLE,
                "stride-" + LocalDate.now() + (json ? ".json" : ".csv"));
        startActivityForResult(intent, json ? REQ_EXPORT_JSON : REQ_EXPORT_CSV);
    }

    private void beginImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_EXPORT_JSON || requestCode == REQ_EXPORT_CSV) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && pendingExport != null) {
                try {
                    writeText(data.getData(), pendingExport);
                    toast("Backup saved");
                } catch (Exception e) {
                    toast("Could not save the file");
                }
            }
            pendingExport = null;
            return;
        }

        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            try {
                String text = readText(data.getData());
                JSONObject root = new JSONObject(text);
                if (!"Stride".equals(root.optString("app"))) {
                    toast("That file is not a Stride backup");
                    return;
                }
                int days = root.optJSONArray("days") == null ? 0 : root.optJSONArray("days").length();
                new AlertDialog.Builder(this)
                        .setTitle("Import backup?")
                        .setMessage("This replaces all current data with " + days
                                + " stored day(s) from the backup.")
                        .setPositiveButton("Replace", (d, w) -> {
                            try {
                                int count = store.importJson(root);
                                Reminders.reschedule(this);
                                QuestEngine.sync(this);
                                selectTab(0);
                                toast("Imported " + count + " day(s)");
                            } catch (Exception e) {
                                toast("Import failed: " + e.getMessage());
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } catch (Exception e) {
                toast("Could not read that backup");
            }
        }
    }

    private void writeText(Uri uri, String text) throws Exception {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new Exception("No output stream");
            os.write(text.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private JSONObject buildBackup() {
        try {
            JSONObject root = store.exportJson();
            org.json.JSONArray defs = new org.json.JSONArray();
            for (QuestEngine.Quest q : QuestEngine.definitions(this)) {
                JSONObject o = new JSONObject();
                o.put("id", q.id);
                o.put("title", q.title);
                o.put("category", q.category);
                o.put("type", q.type);
                o.put("xp", q.xp);
                o.put("badge", q.badge);
                defs.put(o);
            }
            root.put("definitions", defs);
            return root;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeFile(File file, String text) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readFile(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("No input stream");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    /* --------------------------------------------------------- permissions */

    private boolean hasActivityPermission() {
        return Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissions() {
        List<String> needed = new ArrayList<>();
        if (!hasActivityPermission()) needed.add(Manifest.permission.ACTIVITY_RECOGNITION);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            startStepService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            startStepService();
            selectTab(tabIndex);
        }
    }

    private void startStepService() {
        try {
            Intent intent = new Intent(this, StepService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tabs != null) selectTab(tabIndex);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    /* ------------------------------------------------------------- helpers */

    private LinearLayout pageRoot() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(16), dp(18), dp(30));
        return root;
    }

    private View scroll(LinearLayout root) {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(BG);
        sc.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sc;
    }

    private void header(LinearLayout root, String title, String subtitle) {
        LinearLayout head = column();
        head.addView(tv(title, 28, TEXT, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = tv(subtitle, 13, MUTED, false);
            s.setPadding(0, dp(3), 0, 0);
            head.addView(s);
        }
        addBlock(root, head, 0, 16);
    }

    private void addBlock(LinearLayout parent, View child, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        parent.addView(child, p);
    }

    private void addCard(LinearLayout parent, View child) {
        addBlock(parent, child, 0, 12);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(rounded(CARD, 18, BORDER));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        return c;
    }

    private LinearLayout statColumn(String label, TextView value) {
        LinearLayout l = column();
        l.setGravity(Gravity.CENTER);
        value.setGravity(Gravity.CENTER);
        l.addView(value);
        TextView lab = tv(label, 11, MUTED, false);
        lab.setGravity(Gravity.CENTER);
        lab.setPadding(0, dp(3), 0, 0);
        l.addView(lab);
        return l;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private TextView tv(String text, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String text, int bg, int fg, Runnable action) {
        TextView b = tv(text, 14, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(11), dp(16), dp(11));
        b.setBackground(rounded(bg, 14, bg));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private TextView chip(String text, boolean selected, Runnable action) {
        TextView t = tv(text, 13, selected ? BG : TEXT, selected);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(rounded(selected ? ACCENT : CARD2, 20, selected ? ACCENT : BORDER));
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private EditText numberField(int value) {
        EditText e = new EditText(this);
        e.setText(String.valueOf(value));
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setBackground(rounded(CARD2, 12, BORDER));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setSingleLine(true);
        return e;
    }

    private ProgressBar progressBar() {
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgressTintList(ColorStateList.valueOf(BLUE));
        pb.setProgressBackgroundTintList(ColorStateList.valueOf(BAR_BG));
        return pb;
    }

    private String fmt(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private String formatDistance(double meters) {
        if ("mi".equals(store.units())) {
            return String.format(Locale.US, "%.2f mi", meters / 1609.344);
        }
        return String.format(Locale.US, "%.2f km", meters / 1000.0);
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
