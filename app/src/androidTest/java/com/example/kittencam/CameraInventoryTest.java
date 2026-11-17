package com.example.kittencam;

import android.content.Context;
import android.hardware.camera2.*;
import android.os.Build;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CameraInventoryTest {
  @Test
  public void reportAccessibleCameraLenses() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    StringBuilder result = new StringBuilder();
    for (String id : manager.getCameraIdList()) {
      CameraCharacteristics c = manager.getCameraCharacteristics(id);
      result
          .append("camera=")
          .append(id)
          .append(" facing=")
          .append(c.get(CameraCharacteristics.LENS_FACING))
          .append(" focal=")
          .append(Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
          .append(" sensorSize=")
          .append(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
          .append(" digitalMax=")
          .append(c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM));
      if (Build.VERSION.SDK_INT >= 30)
        result.append(" zoomRatio=").append(c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE));
      if (Build.VERSION.SDK_INT >= 28)
        result.append(" physicalIds=").append(c.getPhysicalCameraIds());
      result.append('\n');
      if (Build.VERSION.SDK_INT >= 28)
        for (String physical : c.getPhysicalCameraIds()) {
          var p = manager.getCameraCharacteristics(physical);
          result
              .append(" physical=")
              .append(physical)
              .append(" focal=")
              .append(
                  Arrays.toString(p.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
              .append(" sensorSize=")
              .append(p.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
              .append('\n');
        }
    }
    java.io.File report = new java.io.File(context.getFilesDir(), "camera-inventory.txt");
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(report)) {
      out.write(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    Log.i("KittenCamLenses", result.toString());
  }
}
