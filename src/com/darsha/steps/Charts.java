package com.darsha.steps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small self-contained canvas charts. No libraries, no network. */
public class Charts {

    public static class RingView extends View {
        private float progress;
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final float stroke;

        public RingView(Context context) {
            super(context);
            stroke = dp(14);
            bgPaint.setStyle(Paint.Style.STROKE);
            bgPaint.setStrokeWidth(stroke);
            bgPaint.setStrokeCap(Paint.Cap.ROUND);
            bgPaint.setColor(0xFF1E2732);
            fgPaint.setStyle(Paint.Style.STROKE);
            fgPaint.setStrokeWidth(stroke);
            fgPaint.setStrokeCap(Paint.Cap.ROUND);
            fgPaint.setColor(0xFF3DDC97);
        }

        public void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            invalidate();
        }

        public void setAccent(int color) {
            fgPaint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = size / 2f - stroke / 2f - dp(2);
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.drawArc(oval, -90, 360, false, bgPaint);
            if (progress > 0f) {
                canvas.drawArc(oval, -90, 360 * progress, false, fgPaint);
            }
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }

    public static class BarChartView extends View {
        private List<Store.Day> days = new ArrayList<>();
        private int goal;
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint goalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public BarChartView(Context context) {
            super(context);
            barPaint.setColor(0xFF35506B);
            goalPaint.setColor(0xFF8FA0B3);
            goalPaint.setStyle(Paint.Style.STROKE);
            goalPaint.setStrokeWidth(dp(1.2f));
            goalPaint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(5)}, 0));
            labelPaint.setColor(0xFF8FA0B3);
            labelPaint.setTextSize(dp(10));
            axisPaint.setColor(0xFF202A36);
            axisPaint.setStrokeWidth(dp(1));
        }

        public void setData(List<Store.Day> data, int goalSteps) {
            days = data == null ? new ArrayList<>() : data;
            goal = goalSteps;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int n = days.size();
            if (n == 0) {
                labelPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("No activity yet", getWidth() / 2f, getHeight() / 2f, labelPaint);
                return;
            }

            float left = dp(8);
            float right = getWidth() - dp(8);
            float top = dp(22);
            float bottom = getHeight() - dp(24);
            float chartH = bottom - top;
            float chartW = right - left;

            int maxSteps = 0;
            for (Store.Day d : days) maxSteps = Math.max(maxSteps, d.steps);
            float max = Math.max(Math.max(maxSteps, goal), 1) * 1.12f;

            // baseline
            canvas.drawLine(left, bottom, right, bottom, axisPaint);

            float slot = chartW / n;
            float barW = Math.max(dp(1), slot * 0.62f);

            // goal line
            if (goal > 0) {
                float gy = bottom - (goal / max) * chartH;
                canvas.drawLine(left, gy, right, gy, goalPaint);
                labelPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText("goal", right, gy - dp(4), labelPaint);
            }

            barPaint.setColor(0xFF35506B);
            for (int i = 0; i < n; i++) {
                Store.Day d = days.get(i);
                if (d.steps <= 0) continue;
                float h = Math.max(dp(1.5f), (d.steps / max) * chartH);
                float l = left + i * slot + (slot - barW) / 2f;
                float r = l + barW;
                float t = bottom - h;
                if (d.goalReached) barPaint.setColor(0xFF3DDC97);
                else barPaint.setColor(0xFF35506B);
                canvas.drawRoundRect(l, t, r, bottom, dp(2), dp(2), barPaint);
            }

            // date labels: up to four evenly spaced
            labelPaint.setTextAlign(Paint.Align.CENTER);
            int labels = Math.min(4, n);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(n > 60 ? "MMM" : "d MMM", Locale.getDefault());
            for (int i = 0; i < labels; i++) {
                int idx = labels == 1 ? 0 : Math.round(i * (n - 1f) / (labels - 1));
                String text;
                try {
                    text = LocalDate.parse(days.get(idx).date).format(fmt);
                } catch (Exception e) {
                    text = days.get(idx).date;
                }
                float x = left + idx * slot + slot / 2f;
                canvas.drawText(text, x, getHeight() - dp(6), labelPaint);
            }
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }
}
