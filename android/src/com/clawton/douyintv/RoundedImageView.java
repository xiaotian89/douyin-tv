package com.clawton.douyintv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.ImageView;

/**
 * 圆角图片控件。
 *
 * 为什么不用 setClipToOutline：Android 7.1 上对 ImageView 子类经常不生效
 * （outline 在 0×0 时算过一次就不刷新），封面四角始终是直角。
 * 这里直接在 onDraw 里用 Path 裁一刀，稳。
 */
public class RoundedImageView extends ImageView {

    private final float radius;
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public RoundedImageView(Context c, float radiusPx) {
        super(c);
        this.radius = radiusPx;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (radius <= 0) {
            super.onDraw(canvas);
            return;
        }
        rect.set(0, 0, getWidth(), getHeight());
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(path);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }
}
