package de.tu_darmstadt.seemoo.nfcgate.gui.component;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Random;

import de.tu_darmstadt.seemoo.nfcgate.R;

public class MatrixRainView extends View {
    private static final String CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final Random mRandom = new Random();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClearPaint = new Paint();

    private boolean mRunning = false;
    private long mLastFrameMs = 0L;

    private float mTextSizePx;
    private float mCharWidth;
    private float mCharHeight;

    private int mColumns = 0;
    private float[] mDropY;
    private float[] mSpeedPxPerSec;
    private int[] mTailLength;

    public MatrixRainView(Context context) {
        super(context);
        init(context);
    }

    public MatrixRainView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MatrixRainView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);

        mTextSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, context.getResources().getDisplayMetrics());
        mPaint.setTextSize(mTextSizePx);
        mPaint.setColor(ContextCompat.getColor(context, R.color.matrix_green));

        mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        // We clear with Xfermode, so ensure we have a layer.
        setLayerType(LAYER_TYPE_HARDWARE, null);

        // This is a background effect only.
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setRunning(boolean running) {
        if (mRunning == running) {
            return;
        }

        mRunning = running;
        mLastFrameMs = 0L;

        // Force redraw to either start animation or clear any last frame.
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        mCharWidth = Math.max(1f, mPaint.measureText("W"));
        mCharHeight = Math.max(1f, mTextSizePx * 1.1f);

        mColumns = Math.max(1, (int) Math.floor(w / mCharWidth));
        mDropY = new float[mColumns];
        mSpeedPxPerSec = new float[mColumns];
        mTailLength = new int[mColumns];

        for (int i = 0; i < mColumns; i++) {
            resetColumn(i, h);
            // Spread out initial Y positions so it's not synchronized.
            mDropY[i] = mRandom.nextFloat() * h - h;
        }
    }

    private void resetColumn(int i, int height) {
        if (mDropY == null || mSpeedPxPerSec == null || mTailLength == null) {
            return;
        }

        mDropY[i] = -mRandom.nextFloat() * height;
        mSpeedPxPerSec[i] = 120f + mRandom.nextFloat() * 280f; // 120..400 px/s
        mTailLength[i] = 10 + mRandom.nextInt(16); // 10..25
    }

    private char randomChar() {
        return CHARSET.charAt(mRandom.nextInt(CHARSET.length()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Clear previous contents (view is transparent, so we need to clear explicitly).
        canvas.drawPaint(mClearPaint);

        if (!mRunning || mDropY == null || mColumns <= 0) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (mLastFrameMs == 0L) {
            mLastFrameMs = now;
        }

        float dt = (now - mLastFrameMs) / 1000f;
        mLastFrameMs = now;

        int height = getHeight();

        for (int i = 0; i < mColumns; i++) {
            float x = i * mCharWidth;
            float y = mDropY[i];
            int tail = mTailLength[i];

            // Draw tail (fading).
            for (int j = 0; j < tail; j++) {
                float yy = y - (j * mCharHeight);
                if (yy < -mCharHeight || yy > height + mCharHeight) {
                    continue;
                }

                int alpha = (int) (255f * (1f - (j / (float) tail)));
                alpha = Math.max(0, Math.min(255, alpha));
                mPaint.setAlpha(alpha);
                canvas.drawText(String.valueOf(randomChar()), x, yy, mPaint);
            }

            mDropY[i] = y + (mSpeedPxPerSec[i] * dt);
            if (mDropY[i] > height + (tail * mCharHeight)) {
                resetColumn(i, height);
            }
        }

        postInvalidateOnAnimation();
    }
}
