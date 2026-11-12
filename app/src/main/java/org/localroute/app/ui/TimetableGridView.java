package org.localroute.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;
import org.localroute.app.study.StudySchedule;
import org.localroute.app.study.TimetableCourse;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 周课表网格：左侧节次/时间，右侧周一至周日；课程按节次纵向跨行绘制。
 *
 * <p>完全自绘，避免 8×12 个 View 带来的层级与测量开销；放在
 * HorizontalScrollView 里横向滚动，纵向随页面滚动。
 */
public class TimetableGridView extends View {

    private static final int DAYS = 7;
    private static final String[] DAY_LABELS = {
            "周一", "周二", "周三", "周四", "周五", "周六", "周日"
    };

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detailPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final int timeColumnWidth;
    private final int dayColumnWidth;
    private final int headerHeight;
    private final int rowHeight;

    private final int[] blockFills;
    private final int[] blockTexts;
    private final int colorInk;
    private final int colorMuted;
    private final int colorMintDark;
    private final int colorWeekend;
    private final int colorToday;

    private final List<Block> blocks = new ArrayList<>();
    private final int touchSlop;

    private StudySchedule schedule = StudySchedule.winterDefault();
    private int displayPeriods = 10;
    private OnEmptySlotClickListener emptySlotListener;
    private OnCourseClickListener courseListener;
    private float downX;
    private float downY;
    private boolean trackingTap;

    /** 点击没有课程的格子时回调，用于在空白处自定义课程或事件。 */
    public interface OnEmptySlotClickListener {
        void onEmptySlot(int weekday, int period);
    }

    /** 点击课程块时回调，用于直接修改或删除这门课。 */
    public interface OnCourseClickListener {
        void onCourse(@NonNull TimetableCourse course);
    }

    public TimetableGridView(Context context) {
        this(context, null);
    }

    public TimetableGridView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        timeColumnWidth = Math.round(46 * density);
        dayColumnWidth = Math.round(80 * density);
        headerHeight = Math.round(38 * density);
        rowHeight = Math.round(60 * density);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Math.max(1f, density));
        gridPaint.setColor(ContextCompat.getColor(context, R.color.campus_card_stroke));

        labelPaint.setTextAlign(Paint.Align.CENTER);

        titlePaint.setFakeBoldText(true);
        titlePaint.setTextSize(sp(10.5f));
        detailPaint.setTextSize(sp(8.5f));

        blockFills = new int[]{
                ContextCompat.getColor(context, R.color.study_block_mint_fill),
                ContextCompat.getColor(context, R.color.study_block_blue_fill),
                ContextCompat.getColor(context, R.color.study_block_orange_fill),
                ContextCompat.getColor(context, R.color.study_block_violet_fill),
                ContextCompat.getColor(context, R.color.study_block_rose_fill)
        };
        blockTexts = new int[]{
                ContextCompat.getColor(context, R.color.study_block_mint_text),
                ContextCompat.getColor(context, R.color.study_block_blue_text),
                ContextCompat.getColor(context, R.color.study_block_orange_text),
                ContextCompat.getColor(context, R.color.study_block_violet_text),
                ContextCompat.getColor(context, R.color.study_block_rose_text)
        };
        colorInk = ContextCompat.getColor(context, R.color.campus_ink);
        colorMuted = ContextCompat.getColor(context, R.color.campus_muted);
        colorMintDark = ContextCompat.getColor(context, R.color.campus_mint_dark);
        colorWeekend = ContextCompat.getColor(context, R.color.study_weekend_bg);
        colorToday = ContextCompat.getColor(context, R.color.study_today_bg);
    }

    public void setOnEmptySlotClickListener(@Nullable OnEmptySlotClickListener listener) {
        emptySlotListener = listener;
        setClickable(listener != null || courseListener != null);
    }

    public void setOnCourseClickListener(@Nullable OnCourseClickListener listener) {
        courseListener = listener;
        setClickable(emptySlotListener != null || listener != null);
    }

    public void setData(@NonNull List<TimetableCourse> courses, @NonNull StudySchedule schedule) {
        this.schedule = schedule;
        int maxEnd = 0;
        for (TimetableCourse course : courses) {
            maxEnd = Math.max(maxEnd, course.endPeriod());
        }
        displayPeriods = Math.max(8, Math.min(TimetableCourse.MAX_PERIOD, maxEnd));
        if (courses.isEmpty()) {
            displayPeriods = 10;
        }
        rebuildBlocks(courses);
        setContentDescription(String.format(Locale.CHINA, "周课表，共 %d 门课", courses.size()));
        requestLayout();
        invalidate();
    }

    private void rebuildBlocks(@NonNull List<TimetableCourse> courses) {
        blocks.clear();
        int textWidth = dayColumnWidth - Math.round(12 * getResources()
                .getDisplayMetrics().density);
        for (TimetableCourse course : courses) {
            int index = Math.floorMod(course.id().hashCode(), blockFills.length);
            int span = Math.max(1, course.endPeriod() - course.startPeriod() + 1);
            TextPaint titlePaint = new TextPaint(this.titlePaint);
            titlePaint.setColor(blockTexts[index]);
            TextPaint detailPaint = new TextPaint(this.detailPaint);
            detailPaint.setColor(blockTexts[index]);
            detailPaint.setAlpha(220);
            String name = course.name();
            StaticLayout title = layout(name, titlePaint, textWidth,
                    span >= 2 ? 2 : 1);
            StringBuilder detail = new StringBuilder();
            if (!course.location().isEmpty()) {
                detail.append(course.location());
            } else if (!course.teacher().isEmpty()) {
                detail.append(course.teacher());
            }
            if (detail.length() > 0) {
                detail.append('\n');
            }
            detail.append(course.weeks()).append("周");
            StaticLayout sub = layout(detail.toString(), detailPaint, textWidth,
                    span >= 2 ? 2 : 1);
            blocks.add(new Block(course, title, sub, blockFills[index]));
        }
    }

    private StaticLayout layout(String text, TextPaint paint, int width, int maxLines) {
        String value = text == null || text.isEmpty() ? " " : text;
        return StaticLayout.Builder.obtain(value, 0, value.length(), paint, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getPaddingLeft() + timeColumnWidth + DAYS * dayColumnWidth
                + getPaddingRight();
        int height = getPaddingTop() + headerHeight + displayPeriods * rowHeight
                + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());

        int gridWidth = timeColumnWidth + DAYS * dayColumnWidth;
        int gridHeight = headerHeight + displayPeriods * rowHeight;
        int today = todayIndex();

        // 周末与今天列的浅色底
        fillPaint.setStyle(Paint.Style.FILL);
        for (int day = 1; day <= DAYS; day++) {
            int left = timeColumnWidth + (day - 1) * dayColumnWidth;
            if (day >= 6) {
                fillPaint.setColor(colorWeekend);
                canvas.drawRect(left, headerHeight, left + dayColumnWidth, gridHeight,
                        fillPaint);
            }
            if (day == today) {
                fillPaint.setColor(colorToday);
                canvas.drawRect(left, headerHeight, left + dayColumnWidth, gridHeight,
                        fillPaint);
            }
        }

        // 表头
        labelPaint.setTextSize(sp(10));
        for (int day = 1; day <= DAYS; day++) {
            boolean isToday = day == today;
            labelPaint.setColor(isToday ? colorMintDark : colorMuted);
            labelPaint.setFakeBoldText(isToday);
            float x = timeColumnWidth + (day - 1) * dayColumnWidth + dayColumnWidth / 2f;
            canvas.drawText(DAY_LABELS[day - 1], x, headerHeight / 2f
                    + labelPaint.getTextSize() / 3f, labelPaint);
        }

        // 左侧节次与时间
        for (int period = 1; period <= displayPeriods; period++) {
            int top = headerHeight + (period - 1) * rowHeight;
            labelPaint.setFakeBoldText(true);
            labelPaint.setColor(colorInk);
            labelPaint.setTextSize(sp(10));
            canvas.drawText(String.valueOf(period), timeColumnWidth / 2f,
                    top + rowHeight * 0.38f, labelPaint);

            labelPaint.setFakeBoldText(false);
            labelPaint.setColor(colorMuted);
            labelPaint.setTextSize(sp(8));
            canvas.drawText(schedule.startLabel(period),
                    timeColumnWidth / 2f, top + rowHeight * 0.7f, labelPaint);
        }

        // 网格线
        canvas.drawLine(0, headerHeight, gridWidth, headerHeight, gridPaint);
        for (int period = 1; period <= displayPeriods; period++) {
            float y = headerHeight + period * rowHeight;
            canvas.drawLine(0, y, gridWidth, y, gridPaint);
        }
        for (int day = 0; day <= DAYS; day++) {
            float x = timeColumnWidth + day * dayColumnWidth;
            canvas.drawLine(x, 0, x, gridHeight, gridPaint);
        }
        canvas.drawLine(0, 0, 0, gridHeight, gridPaint);

        // 课程块
        RectF rect = new RectF();
        for (Block block : blocks) {
            TimetableCourse course = block.course;
            float left = timeColumnWidth + (course.weekday() - 1) * dayColumnWidth
                    + 2 * density;
            float top = headerHeight + (course.startPeriod() - 1) * rowHeight + 2 * density;
            float right = timeColumnWidth + course.weekday() * dayColumnWidth - 2 * density;
            float bottom = headerHeight + course.endPeriod() * rowHeight - 2 * density;
            rect.set(left, top, right, bottom);

            fillPaint.setColor(block.fillColor);
            canvas.drawRoundRect(rect, 8 * density, 8 * density, fillPaint);

            canvas.save();
            canvas.clipRect(rect);
            float textLeft = left + 5 * density;
            float textTop = top + 5 * density;
            canvas.translate(textLeft, textTop);
            block.title.draw(canvas);
            canvas.translate(0, block.title.getHeight() + 1 * density);
            block.sub.draw(canvas);
            canvas.restore();
        }

        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (emptySlotListener == null && courseListener == null) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                trackingTap = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (trackingTap && (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop)) {
                    trackingTap = false;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (trackingTap) {
                    performClick();
                    handleTap(event.getX(), event.getY());
                }
                trackingTap = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                trackingTap = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void handleTap(float touchX, float touchY) {
        float x = touchX - getPaddingLeft();
        float y = touchY - getPaddingTop();
        if (x < timeColumnWidth || y < headerHeight) {
            return;
        }
        int weekday = (int) ((x - timeColumnWidth) / dayColumnWidth) + 1;
        int period = (int) ((y - headerHeight) / rowHeight) + 1;
        if (weekday < 1 || weekday > DAYS || period < 1 || period > displayPeriods) {
            return;
        }
        for (Block block : blocks) {
            TimetableCourse course = block.course;
            if (course.weekday() == weekday
                    && period >= course.startPeriod() && period <= course.endPeriod()) {
                if (courseListener != null) {
                    courseListener.onCourse(course);
                }
                return;
            }
        }
        if (emptySlotListener != null) {
            emptySlotListener.onEmptySlot(weekday, period);
        }
    }

    private static int todayIndex() {
        int calendarDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return calendarDay == Calendar.SUNDAY ? 7 : calendarDay - 1;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    private static final class Block {
        final TimetableCourse course;
        final StaticLayout title;
        final StaticLayout sub;
        final int fillColor;

        Block(TimetableCourse course, StaticLayout title, StaticLayout sub, int fillColor) {
            this.course = course;
            this.title = title;
            this.sub = sub;
            this.fillColor = fillColor;
        }
    }
}
