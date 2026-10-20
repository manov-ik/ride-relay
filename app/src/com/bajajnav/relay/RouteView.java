package com.bajajnav.relay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The ride's route as a plain line, drawn from the recorded GPS points.
 *
 * There is no map tile here on purpose: a tile layer would need a Maps SDK key,
 * and Ride Relay does not carry one. This is enough to recognise which way the
 * ride went.
 */
final class RouteView extends View {
    private final List<float[]> points = new ArrayList<>();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint end = new Paint(Paint.ANTI_ALIAS_FLAG);

    RouteView(Context c) {
        this(c, null);
    }

    RouteView(Context c, AttributeSet attrs) {
        super(c, attrs);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2.5f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFF3D7BFF);
        end.setStyle(Paint.Style.FILL);
        end.setColor(0xFF3D7BFF);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Parses "lat,lng;lat,lng". Bad pairs are skipped, never fatal. */
    void setPoints(String raw) {
        points.clear();
        if (raw != null && !raw.isEmpty()) {
            for (String pair : raw.split(";")) {
                int comma = pair.indexOf(',');
                if (comma <= 0) {
                    continue;
                }
                try {
                    float lat = Float.parseFloat(pair.substring(0, comma));
                    float lng = Float.parseFloat(pair.substring(comma + 1));
                    points.add(new float[]{lat, lng});
                } catch (NumberFormatException ignored) {
                }
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) {
            return;
        }
        float pad = dp(10);
        float w = Math.max(1, getWidth() - pad * 2);
        float h = Math.max(1, getHeight() - pad * 2);

        float minLat = Float.MAX_VALUE, maxLat = -Float.MAX_VALUE;
        float minLng = Float.MAX_VALUE, maxLng = -Float.MAX_VALUE;
        for (float[] p : points) {
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLng = Math.min(minLng, p[1]);
            maxLng = Math.max(maxLng, p[1]);
        }
        float latSpan = maxLat - minLat;
        float lngSpan = maxLng - minLng;
        if (latSpan <= 0 && lngSpan <= 0) {
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, dp(4), end);
            return;
        }
        // Keep the shape honest: one scale for both axes (longitude shrinks with
        // latitude, which matters little at city scale but keeps the trace from
        // stretching).
        float scale = Math.min(lngSpan > 0 ? w / lngSpan : Float.MAX_VALUE,
                latSpan > 0 ? h / latSpan : Float.MAX_VALUE);
        if (scale == Float.MAX_VALUE) {
            scale = Math.min(w, h);
        }
        float drawW = lngSpan * scale;
        float drawH = latSpan * scale;
        float ox = pad + (w - drawW) / 2f;
        float oy = pad + (h - drawH) / 2f;

        Path path = new Path();
        for (int i = 0; i < points.size(); i++) {
            float[] p = points.get(i);
            float x = ox + (p[1] - minLng) * scale;
            float y = oy + (maxLat - p[0]) * scale;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, line);
        float[] lastPoint = points.get(points.size() - 1);
        canvas.drawCircle(ox + (lastPoint[1] - minLng) * scale,
                oy + (maxLat - lastPoint[0]) * scale, dp(4), end);
    }
}
