package com.despejo.gravador;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Desenha o mapa mental: tronco no meio, galhos para os dois lados, folhas em caixinhas,
 * linhas curvas da cor de cada galho e setas tracejadas nas ligações entre ideias.
 * Arrasta com um dedo, zoom com dois, toque num galho abre ou fecha, toque numa folha mostra o detalhe.
 */
public class MindMapView extends View {

    interface OnNote {
        void show(MindMap.Node n);
    }

    static final int[] BRANCH = {
            Color.parseColor("#4F9DFF"), Color.parseColor("#3ECF8E"), Color.parseColor("#FFB020"),
            Color.parseColor("#FF6B9A"), Color.parseColor("#A78BFA"), Color.parseColor("#2DD4BF"),
            Color.parseColor("#FF8A4C"),
    };
    private static final int ROOT = Color.parseColor("#7C5CFF");
    private static final int SURFACE = Color.parseColor("#1B212A");
    private static final int FG = Color.parseColor("#E8ECF1");
    private static final int DARK = Color.parseColor("#12161C");
    private static final int LINK = Color.parseColor("#FFD166");

    private MindMap map;
    private OnNote onNote;
    private final float d;   // densidade (px por dp)

    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Matrix view = new Matrix();
    private final Matrix inverse = new Matrix();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private boolean fitted;
    private final List<MindMap.Node> visible = new ArrayList<>();

    public MindMapView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * d);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(2 * d);
        dash.setColor(LINK);
        dash.setPathEffect(new DashPathEffect(new float[]{8 * d, 6 * d}, 0));
        arrow.setColor(LINK);
        small.setColor(LINK);
        small.setTextSize(11 * d * c.getResources().getConfiguration().fontScale);
        setBackgroundColor(DARK);

        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector g) {
                float s = currentScale();
                float f = Math.max(0.25f / s, Math.min(3f / s, g.getScaleFactor()));
                view.postScale(f, f, g.getFocusX(), g.getFocusY());
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                view.postTranslate(-dx, -dy);
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                MindMap.Node n = hit(e.getX(), e.getY());
                if (n == null) return false;
                if (!n.kids.isEmpty() && n.parent != null) {
                    toggle(n);
                } else if (onNote != null && !n.note.isEmpty()) {
                    onNote.show(n);
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                MindMap.Node n = hit(e.getX(), e.getY());
                if (n != null && onNote != null) onNote.show(n);
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                view.postScale(1.6f, 1.6f, e.getX(), e.getY());
                invalidate();
                return true;
            }
        });
    }

    void setMap(MindMap m, OnNote onNote) {
        this.map = m;
        this.onNote = onNote;
        prepare(m.root, 0, 0, 1);
        // Começa mostrando tronco, galhos e o primeiro nível de folhas; o resto abre com um toque.
        collapseFrom(m.root, 2);
        relayout();
        fitted = false;
        invalidate();
    }

    void fit() {
        fitted = false;
        invalidate();
    }

    // ---------- Montagem ----------

    private void prepare(MindMap.Node n, int depth, int colorIndex, int side) {
        n.depth = depth;
        n.color = depth == 0 ? ROOT : BRANCH[colorIndex % BRANCH.length];
        String label = n.label;
        if (label.length() > 70) {
            if (n.note.isEmpty()) n.note = label;
            label = label.substring(0, 66).trim() + "…";
        }
        String shown = n.icon.isEmpty() ? label : n.icon + "  " + label;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        float sp = getResources().getConfiguration().fontScale * d;
        tp.setTextSize((depth == 0 ? 20 : depth == 1 ? 16 : 14) * sp);
        tp.setTypeface(depth <= 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tp.setColor(depth <= 1 ? Color.WHITE : FG);
        float maxW = (depth == 0 ? 210 : 170) * d;
        int w = (int) Math.min(maxW, Math.ceil(tp.measureText(shown)) + 1);
        n.text = StaticLayout.Builder.obtain(shown, 0, shown.length(), tp, Math.max(w, (int) (40 * d)))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0, 1.05f)
                .setMaxLines(depth == 0 ? 3 : 4)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build();
        float padX = (depth == 0 ? 18 : 12) * d, padY = (depth == 0 ? 14 : 8) * d;
        n.w = n.text.getWidth() + 2 * padX;
        n.h = n.text.getHeight() + 2 * padY;
        for (int i = 0; i < n.kids.size(); i++) {
            prepare(n.kids.get(i), depth + 1, depth == 0 ? i : colorIndex, side);
        }
    }

    private void collapseFrom(MindMap.Node n, int depth) {
        n.collapsed = n.depth >= depth && !n.kids.isEmpty();
        for (MindMap.Node k : n.kids) collapseFrom(k, depth);
    }

    private void toggle(MindMap.Node n) {
        // Mantém a caixa tocada no mesmo lugar da tela depois de reorganizar.
        float[] before = {n.x, n.y};
        view.mapPoints(before);
        n.collapsed = !n.collapsed;
        relayout();
        float[] after = {n.x, n.y};
        view.mapPoints(after);
        view.postTranslate(before[0] - after[0], before[1] - after[1]);
        invalidate();
    }

    private float gapY() { return 12 * d; }

    private float gapX() { return 46 * d; }

    private float height(MindMap.Node n) {
        if (n.collapsed || n.kids.isEmpty()) return n.h;
        float sum = 0;
        for (MindMap.Node k : n.kids) sum += height(k);
        sum += gapY() * (n.kids.size() - 1);
        return Math.max(n.h, sum);
    }

    private void relayout() {
        MindMap.Node r = map.root;
        r.x = 0;
        r.y = 0;
        // Divide os galhos entre direita e esquerda, equilibrando a altura dos dois lados.
        List<MindMap.Node> right = new ArrayList<>(), left = new ArrayList<>();
        float hr = 0, hl = 0;
        for (MindMap.Node k : r.kids) {
            float h = height(k);
            if (hr <= hl) { right.add(k); hr += h + gapY() * 2; }
            else { left.add(k); hl += h + gapY() * 2; }
        }
        placeColumn(r, right, 1);
        placeColumn(r, left, -1);
    }

    private void placeColumn(MindMap.Node parent, List<MindMap.Node> kids, int side) {
        if (kids.isEmpty()) return;
        float gap = parent.depth == 0 ? gapY() * 2 : gapY();
        float total = gap * (kids.size() - 1);
        for (MindMap.Node k : kids) total += height(k);
        float y = parent.y - total / 2;
        for (MindMap.Node k : kids) {
            float h = height(k);
            k.side = side;
            k.x = parent.x + side * (parent.w / 2 + gapX() + k.w / 2);
            k.y = y + h / 2;
            if (!k.collapsed) placeColumn(k, k.kids, side);
            y += h + gap;
        }
    }

    // ---------- Desenho ----------

    @Override
    protected void onDraw(Canvas c) {
        if (map == null) return;
        if (!fitted && getWidth() > 0) fitToScreen();
        c.save();
        c.concat(view);
        visible.clear();
        collect(map.root);
        for (MindMap.Node n : visible) if (n.parent != null) drawEdge(c, n.parent, n);
        drawLinks(c);
        for (MindMap.Node n : visible) drawNode(c, n);
        c.restore();
    }

    private void collect(MindMap.Node n) {
        visible.add(n);
        if (!n.collapsed) for (MindMap.Node k : n.kids) collect(k);
    }

    private void drawEdge(Canvas c, MindMap.Node p, MindMap.Node k) {
        float x1 = p.x + k.side * p.w / 2, y1 = p.y;
        float x2 = k.x - k.side * k.w / 2, y2 = k.y;
        if (p.depth == 0) { x1 = p.x; }
        Path path = new Path();
        path.moveTo(x1, y1);
        float mx = (x1 + x2) / 2;
        path.cubicTo(mx, y1, mx, y2, x2, y2);
        edge.setColor(k.color);
        edge.setStrokeWidth(Math.max(2, 7 - 2 * k.depth) * d);
        c.drawPath(path, edge);
    }

    private void drawNode(Canvas c, MindMap.Node n) {
        RectF r = new RectF(n.x - n.w / 2, n.y - n.h / 2, n.x + n.w / 2, n.y + n.h / 2);
        float radius = n.depth == 0 ? n.h / 2 : 12 * d;
        if (n.depth <= 1) {
            box.setColor(n.color);
            c.drawRoundRect(r, radius, radius, box);
        } else {
            box.setColor(SURFACE);
            c.drawRoundRect(r, radius, radius, box);
            stroke.setColor(n.color);
            c.drawRoundRect(r, radius, radius, stroke);
        }
        c.save();
        c.translate(n.x - n.text.getWidth() / 2f, n.y - n.text.getHeight() / 2f);
        n.text.draw(c);
        c.restore();

        // Galho fechado: bolinha com quantas ideias tem dentro.
        if (n.collapsed && !n.kids.isEmpty()) {
            String s = "+" + n.countAll();
            float bx = n.x + n.side * (n.w / 2 + 2 * d), by = n.y;
            float br = 12 * d;
            box.setColor(n.color);
            c.drawCircle(bx, by, br, box);
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(DARK);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setTextSize((s.length() > 3 ? 9 : 11) * d);
            tp.setTextAlign(Paint.Align.CENTER);
            c.drawText(s, bx, by + tp.getTextSize() / 3, tp);
        }
        // Folha com detalhe: pontinho no canto.
        if (!n.note.isEmpty() && n.depth > 0) {
            box.setColor(LINK);
            c.drawCircle(r.right - 6 * d, r.top + 6 * d, 3 * d, box);
        }
    }

    /** Setas tracejadas entre ideias de galhos diferentes. */
    private void drawLinks(Canvas c) {
        for (MindMap.Link l : map.links) {
            MindMap.Node a = shown(map.byId.get(l.from)), b = shown(map.byId.get(l.to));
            if (a == null || b == null || a == b) continue;
            float bend = 60 * d;
            float mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2 - bend;
            float bx = b.x, by = b.y - b.h / 2;
            float ax = a.x, ay = a.y - a.h / 2;
            Path p = new Path();
            p.moveTo(ax, ay);
            p.quadTo(mx, my, bx, by);
            c.drawPath(p, dash);
            // Ponta da seta, apontando na direção da curva no fim.
            double ang = Math.atan2(by - my, bx - mx);
            float s = 10 * d;
            Path head = new Path();
            head.moveTo(bx, by);
            head.lineTo((float) (bx - s * Math.cos(ang - 0.45)), (float) (by - s * Math.sin(ang - 0.45)));
            head.lineTo((float) (bx - s * Math.cos(ang + 0.45)), (float) (by - s * Math.sin(ang + 0.45)));
            head.close();
            c.drawPath(head, arrow);
            if (!l.label.isEmpty()) {
                small.setTextAlign(Paint.Align.CENTER);
                c.drawText(l.label, mx, (ay + by) / 2 - bend / 2 - 4 * d, small);
            }
        }
    }

    /** Se a ideia está dentro de um galho fechado, a seta sai do galho. */
    private MindMap.Node shown(MindMap.Node n) {
        if (n == null) return null;
        MindMap.Node best = n;
        for (MindMap.Node p = n.parent; p != null; p = p.parent) if (p.collapsed) best = p;
        return best;
    }

    private void fitToScreen() {
        fitted = true;
        visible.clear();
        collect(map.root);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (MindMap.Node n : visible) {
            minX = Math.min(minX, n.x - n.w / 2 - 16 * d);
            maxX = Math.max(maxX, n.x + n.w / 2 + 16 * d);
            minY = Math.min(minY, n.y - n.h / 2);
            maxY = Math.max(maxY, n.y + n.h / 2);
        }
        float pad = 16 * d;
        float s = Math.min((getWidth() - 2 * pad) / (maxX - minX), (getHeight() - 2 * pad) / (maxY - minY));
        s = Math.max(0.45f, Math.min(1.2f, s));   // não deixa a letra pequena demais para ler
        view.reset();
        view.postScale(s, s);
        // Se couber, centraliza tudo; se não, centraliza no tronco.
        boolean fits = (maxX - minX) * s <= getWidth();
        float cx = fits ? (minX + maxX) / 2 : 0;
        float cy = (maxY - minY) * s <= getHeight() ? (minY + maxY) / 2 : 0;
        view.postTranslate(getWidth() / 2f - cx * s, getHeight() / 2f - cy * s);
    }

    private float currentScale() {
        float[] v = new float[9];
        view.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    private MindMap.Node hit(float sx, float sy) {
        view.invert(inverse);
        float[] p = {sx, sy};
        inverse.mapPoints(p);
        for (int i = visible.size() - 1; i >= 0; i--) {
            MindMap.Node n = visible.get(i);
            float slack = n.collapsed ? 16 * d : 0;   // a bolinha "+N" também conta
            if (Math.abs(p[0] - n.x) <= n.w / 2 + slack && Math.abs(p[1] - n.y) <= n.h / 2) return n;
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean a = scaler.onTouchEvent(e);
        boolean b = scaler.isInProgress() || gestures.onTouchEvent(e);
        return a || b;
    }
}
