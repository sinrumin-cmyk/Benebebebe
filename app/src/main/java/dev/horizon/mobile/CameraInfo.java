package dev.horizon.mobile;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Range;
import android.util.SizeF;

import java.util.Set;

/** What cameras the app is given, to see whether the ultra-wide one can be used for hand tracking. */
final class CameraInfo {
    private CameraInfo() {}

    static String dump(Context c) {
        StringBuilder sb = new StringBuilder();
        String bestId = null;
        double bestFov = 0;
        String zoomId = null;
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            sb.append("Камер видно приложению: ").append(ids.length).append('\n');
            for (String id : ids) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                boolean back = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
                sb.append("\nid ").append(id).append(back ? " (задняя)" : facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? " (передняя)" : "");
                float[] focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (focal != null && focal.length > 0 && size != null) {
                    double fov = Math.toDegrees(2 * Math.atan(size.getWidth() / (2 * focal[0])));
                    sb.append("\n  фокус ").append(focal[0]).append(" мм, угол ~").append(Math.round(fov)).append("°");
                    if (back && fov > bestFov) { bestFov = fov; bestId = id; }
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    Range<Float> z = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                    if (z != null) {
                        sb.append("\n  зум ").append(z.getLower()).append("–").append(z.getUpper());
                        if (back && z.getLower() <= 0.65f && zoomId == null) zoomId = id;
                    }
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    Set<String> phys = ch.getPhysicalCameraIds();
                    if (!phys.isEmpty()) sb.append("\n  физические камеры: ").append(phys);
                }
            }
        } catch (Throwable e) {
            sb.append("Не удалось прочитать камеры: ").append(e);
        }
        sb.append("\n\n");
        if (bestFov >= 100) sb.append("Ультраширик отдан приложению: id ").append(bestId).append(", угол ~").append(Math.round(bestFov)).append("°.");
        else if (zoomId != null) sb.append("Широкий угол доступен зумом 0.6x на id ").append(zoomId).append('.');
        else sb.append("Ультраширик приложению не отдан: остаётся основная камера (узкий угол).");
        return sb.toString();
    }
}
