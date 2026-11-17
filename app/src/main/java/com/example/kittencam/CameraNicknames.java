package com.example.kittencam;

import android.content.Context;
import android.content.SharedPreferences;

/** Local labels keyed by discovery identity, independent of changing network addresses. */
final class CameraNicknames {
  final SharedPreferences preferences;

  CameraNicknames(Context context) {
    preferences = context.getSharedPreferences("camera_nicknames", Context.MODE_PRIVATE);
  }

  String name(String identity) {
    String nickname = preferences.getString(identity, "");
    return nickname == null || nickname.isEmpty() ? identity : nickname;
  }

  void save(String identity, String nickname) {
    String cleaned = nickname.trim();
    if (cleaned.isEmpty() || cleaned.equals(identity)) preferences.edit().remove(identity).apply();
    else preferences.edit().putString(identity, cleaned).apply();
  }
}
