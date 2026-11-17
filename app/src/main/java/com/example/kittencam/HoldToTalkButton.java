package com.example.kittencam;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.*;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.TooltipCompat;
import java.util.function.BooleanSupplier;

/** Touch holds survive ScrollView motion; accessibility/keyboard clicks toggle start/stop. */
final class HoldToTalkButton extends AppCompatImageButton {
  final Feed feed;
  final BooleanSupplier allowed;
  boolean touchActive, latched, touchClick;

  HoldToTalkButton(Context context, Feed feed, BooleanSupplier allowed) {
    super(
        new android.view.ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat));
    this.feed = feed;
    this.allowed = allowed;
    int padding = Math.round(12 * getResources().getDisplayMetrics().density);
    setPadding(padding, padding, padding, padding);
    setBackgroundResource(R.drawable.control_icon_background);
    setOnClickListener(v -> {
      if (touchClick) return;
      if (latched || feed.talking) stop();
      else if (begin()) latched = true;
      refresh();
    });
    refresh();
  }

  boolean begin() {
    if (!allowed.getAsBoolean()) return false;
    feed.talk(true);
    refresh();
    return true;
  }

  void stop() {
    if (touchActive || latched || feed.talking) feed.talk(false);
    touchActive = false;
    latched = false;
    setPressed(false);
    refresh();
  }

  void refresh() {
    boolean available = !feed.closed && feed.device.endpoints[2] != null;
    setEnabled(available || feed.talking);
    setSelected(feed.talking);
    setImageResource(feed.talking ? R.drawable.ic_mic : R.drawable.ic_mic_off);
    setImageTintList(ColorStateList.valueOf(feed.talking ? 0xffff7777 : MainActivity.WHITE));
    String description =
        feed.talking ? "Stop talking" : available ? "Hold to talk" : "Talkback unavailable";
    setContentDescription(description);
    TooltipCompat.setTooltipText(this, description);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!isEnabled()) return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        touchActive = begin();
        setPressed(touchActive);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
      case MotionEvent.ACTION_MOVE:
        return true;
      case MotionEvent.ACTION_UP:
        stop();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        touchClick = true;
        performClick();
        touchClick = false;
        return true;
      case MotionEvent.ACTION_CANCEL:
        stop();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      default:
        return true;
    }
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }

  @Override
  protected void onDetachedFromWindow() {
    if (touchActive || latched) stop();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onVisibilityChanged(View changed, int visibility) {
    super.onVisibilityChanged(changed, visibility);
    if (feed != null && visibility != VISIBLE && (touchActive || latched)) stop();
  }
}
