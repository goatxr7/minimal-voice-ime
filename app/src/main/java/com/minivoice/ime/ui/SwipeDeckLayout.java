package com.minivoice.ime.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * 设置页两个标签页的跟手翻页容器。两个子页各占满宽、叠放在同一层，同一时间只显示一页。
 * 手指横向拖动时当前页和目标页跟随平移，松手按距离或甩速决定翻页还是回弹。
 * 状态以 voicePage 为唯一来源，动画开始前先取消旧动画，收尾统一复位，避免来回滑动后错乱。
 */
public class SwipeDeckLayout extends FrameLayout {

    public interface Listener {
        /** 翻页落定后回调，toVoice 为 true 表示落在模型设置页。 */
        void onPageChanged(boolean toVoice);
    }

    private View pageVoice;
    private View pageMore;
    private boolean voicePage = true;
    private Listener listener;

    private boolean dragging;
    private boolean swipeActive;
    private boolean animating;
    private float downX;
    private float downY;
    private final int touchSlop;
    private VelocityTracker tracker;

    public SwipeDeckLayout(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public SwipeDeckLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public SwipeDeckLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() >= 2) {
            pageVoice = getChildAt(0);
            pageMore = getChildAt(1);
        }
    }

    /** 直接定位到某一页，不带翻页动画，标签点击和初始化时用。 */
    public void setCurrent(boolean voice) {
        cancelAnimations();
        this.voicePage = voice;
        pageVoice.setVisibility(voice ? VISIBLE : GONE);
        pageMore.setVisibility(voice ? GONE : VISIBLE);
        resetTranslation();
        animating = false;
    }

    private void resetTranslation() {
        if (pageVoice != null) pageVoice.setTranslationX(0);
        if (pageMore != null) pageMore.setTranslationX(0);
    }

    private void cancelAnimations() {
        if (pageVoice != null) {
            pageVoice.animate().setListener(null);
            pageVoice.animate().cancel();
        }
        if (pageMore != null) {
            pageMore.animate().setListener(null);
            pageMore.animate().cancel();
        }
    }

    /** 手动测量子级，不在测量期间改动可见性，避免重入和状态错乱。高度跟随可见页。 */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = getPaddingLeft() + getPaddingRight();
        int visibleHeight = 0;
        int maxHeight = getPaddingTop() + getPaddingBottom();
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int childWidthSpec = getChildMeasureSpec(widthMeasureSpec,
                    getPaddingLeft() + getPaddingRight(), lp.width);
            int childHeightSpec = getChildMeasureSpec(heightMeasureSpec,
                    getPaddingTop() + getPaddingBottom(), lp.height);
            child.measure(childWidthSpec, childHeightSpec);
            maxWidth = Math.max(maxWidth, child.getMeasuredWidth()
                    + getPaddingLeft() + getPaddingRight());
            maxHeight = Math.max(maxHeight, child.getMeasuredHeight()
                    + getPaddingTop() + getPaddingBottom());
            if (child.getVisibility() != GONE) {
                visibleHeight = Math.max(visibleHeight, child.getMeasuredHeight()
                        + getPaddingTop() + getPaddingBottom());
            }
        }
        int height = visibleHeight > 0 ? visibleHeight : maxHeight;
        setMeasuredDimension(resolveSize(maxWidth, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    /** 当前页允许滑向哪个方向：模型页向左滑到其他页，其他页向右滑回模型页。 */
    private boolean canSwipe(float dx) {
        return voicePage ? dx < 0 : dx > 0;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (animating) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                swipeActive = false;
                ensureTracker();
                if (tracker != null) tracker.addMovement(ev);
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    if (canSwipe(dx)) {
                        dragging = true;
                        swipeActive = true;
                        requestDisallowInterceptTouchEvent(true);
                        prepareNext();
                    }
                }
                if (dragging) return true;
                break;
            }
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (animating) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                ensureTracker();
                if (tracker != null) tracker.addMovement(ev);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!swipeActive) return false;
                if (tracker != null) tracker.addMovement(ev);
                applyDrag(ev.getX() - downX);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (tracker != null) {
                    tracker.addMovement(ev);
                    tracker.computeCurrentVelocity(1000);
                }
                float vx = tracker != null ? tracker.getXVelocity() : 0;
                finishDrag(ev.getX() - downX, vx);
                releaseTracker();
                return true;
            }
            default:
                break;
        }
        return super.onTouchEvent(ev);
    }

    private void prepareNext() {
        View next = voicePage ? pageMore : pageVoice;
        if (next == null) return;
        next.setVisibility(VISIBLE);
        int w = getWidth();
        next.setTranslationX(voicePage ? w : -w);
    }

    private void applyDrag(float dx) {
        int w = getWidth();
        dx = Math.max(-w, Math.min(w, dx));
        View current = voicePage ? pageVoice : pageMore;
        View next = voicePage ? pageMore : pageVoice;
        if (current != null) current.setTranslationX(dx);
        if (next != null) next.setTranslationX(voicePage ? w + dx : -w + dx);
    }

    private void finishDrag(float dx, float velocityX) {
        int w = getWidth();
        boolean toNext;
        if (voicePage) {
            toNext = dx < -w * 0.3f || velocityX < -700;
        } else {
            toNext = dx > w * 0.3f || velocityX > 700;
        }
        if (toNext) {
            animateToNext();
        } else {
            animateBack();
        }
    }

    private void animateToNext() {
        animating = true;
        cancelAnimations();
        final View current = voicePage ? pageVoice : pageMore;
        final View next = voicePage ? pageMore : pageVoice;
        int w = getWidth();
        float currentTarget = voicePage ? -w : w;
        if (next != null) next.animate().translationX(0).setDuration(220).start();
        if (current != null) {
            current.animate().translationX(currentTarget).setDuration(220)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            settleAfterNext(current);
                        }
                    }).start();
        } else {
            settleAfterNext(current);
        }
    }

    private void settleAfterNext(View current) {
        resetTranslation();
        animating = false;
        voicePage = !voicePage;
        pageVoice.setVisibility(voicePage ? VISIBLE : GONE);
        pageMore.setVisibility(voicePage ? GONE : VISIBLE);
        if (listener != null) listener.onPageChanged(voicePage);
    }

    private void animateBack() {
        animating = true;
        cancelAnimations();
        final View current = voicePage ? pageVoice : pageMore;
        final View next = voicePage ? pageMore : pageVoice;
        if (current != null) current.animate().translationX(0).setDuration(180).start();
        if (next != null) {
            next.animate().translationX(voicePage ? getWidth() : -getWidth()).setDuration(180)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            settleBack(next);
                        }
                    }).start();
        } else {
            settleBack(next);
        }
    }

    private void settleBack(View next) {
        resetTranslation();
        animating = false;
        pageVoice.setVisibility(voicePage ? VISIBLE : GONE);
        pageMore.setVisibility(voicePage ? GONE : VISIBLE);
    }

    private void ensureTracker() {
        if (tracker == null) tracker = VelocityTracker.obtain();
    }

    private void releaseTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }
}
