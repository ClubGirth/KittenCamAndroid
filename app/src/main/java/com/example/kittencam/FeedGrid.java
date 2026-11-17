package com.example.kittencam;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays out attached decoder surfaces without reparenting them when the grid changes. */
final class FeedGrid extends ViewGroup {
  boolean grid = true;

  FeedGrid(Context context) {
    super(context);
    setPadding(12, 8, 12, 8);
  }

  private int visibleCount() {
    int n = 0;
    for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) n++;
    return n;
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
    setMeasuredDimension(width, height);
    int count = visibleCount(),
        columns = grid && count > 1 ? 2 : 1,
        rows = Math.max(1, (count + columns - 1) / columns);
    int cellW = Math.max(0, (width - getPaddingLeft() - getPaddingRight()) / columns),
        cellH = Math.max(0, (height - getPaddingTop() - getPaddingBottom()) / rows);
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() != GONE)
        child.measure(
            MeasureSpec.makeMeasureSpec(Math.max(0, cellW - 8), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(Math.max(0, cellH - 8), MeasureSpec.EXACTLY));
    }
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    int count = visibleCount(),
        columns = grid && count > 1 ? 2 : 1,
        rows = Math.max(1, (count + columns - 1) / columns);
    int cellW = (getWidth() - getPaddingLeft() - getPaddingRight()) / columns,
        cellH = (getHeight() - getPaddingTop() - getPaddingBottom()) / rows,
        index = 0;
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() == GONE) continue;
      int x = getPaddingLeft() + (index % columns) * cellW,
          y = getPaddingTop() + (index / columns) * cellH;
      child.layout(x, y, x + Math.max(0, cellW - 8), y + Math.max(0, cellH - 8));
      index++;
    }
  }

  @Override
  protected LayoutParams generateDefaultLayoutParams() {
    return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
  }
}
