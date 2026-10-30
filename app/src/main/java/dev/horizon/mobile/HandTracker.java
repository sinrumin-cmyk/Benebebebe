package dev.horizon.mobile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hand tracking from the phone's back camera (MediaPipe Hand Landmarker), turned into the same gamepad calls the
 * on-screen controls make (HorizonInput.nativeButton / nativeStick). Nothing in the game's native code knows about it.
 *
 * <p>The camera looks away from the face, so in a viewer with a camera opening it sees the player's hands held in
 * front of them. The picture is not mirrored: the right of the image is the player's right. Hands are told apart by
 * position (the one further left is the left hand), not by MediaPipe's handedness label, which assumes a mirrored selfie.
 *
 * <p>Left hand = walking: where the palm is, relative to where it was when it appeared, is the stick (up = forward).
 * Pushed far forward = sprint. A fist = jump.
 * <p>Right hand = actions: pinch (thumb to index) = attack / break (held), fist = use / place, a quick sweep sideways
 * = previous / next hotbar slot.
 */
final class HandTracker {
    private static final String TAG = "HorizonHands";
    private static final String MODEL = "hand_landmarker.task";

    // Landmark indices (MediaPipe hand model).
    private static final int WRIST = 0, THUMB_TIP = 4, INDEX_MCP = 5, MIDDLE_MCP = 9, RING_MCP = 13, PINKY_MCP = 17, INDEX_TIP = 8;
    private static final int[] TIPS = {8, 12, 16, 20};
    private static final int[] PIPS = {6, 10, 14, 18};

    private static final int OPEN = 0, PINCH = 1, FIST = 2;

    /** Pinch distance as a fraction of the palm length: closes below ON, opens above OFF. */
    private static final float PINCH_ON = 0.28f, PINCH_OFF = 0.45f;
    /** How far the left palm travels (as a fraction of the picture's width) for the stick to be fully over. */
    private static final float STICK_RANGE = 0.13f;
    private static final float DEAD_ZONE = 0.18f;
    /** A hand not seen for this long lets go of everything. */
    private static final long LOST_MS = 350;
    /** A hand gone for this long is "new" when it comes back: the stick's neutral is taken again. */
    private static final long RECENTER_MS = 600;
    private static final long SWEEP_WINDOW_MS = 300, SWEEP_COOLDOWN_MS = 450;
    private static final float SWEEP_DISTANCE = 0.22f;

    /** Where the gestures go: gamepad calls in the game, a cursor and clicks in the VR menu. */
    interface Output {
        void button(int index, boolean down);
        /** The left stick, up positive. */
        void stick(float x, float y);
        /** The right hand's palm in the picture (0..1, not mirrored), or not there. */
        void pointer(boolean present, float x, float y);
        /** Every camera frame: both hands' landmarks, for drawing them. */
        default void frame(HandFrame f) {}
    }

    private final Activity activity;
    private final Output out;

    // ---- what is going on, for the hand test screen
    volatile String stage = "не запущен";
    volatile String lastError = "";
    volatile String cameraName = "";
    volatile int framesSeen, framesWithHands;
    private volatile boolean wantPreview;
    private int gen;   // each start() is a new generation: callbacks of an older camera open must not touch the new one

    /** A camera the user can pick for hand tracking. */
    static final class CameraOption {
        final String id, label;
        CameraOption(String id, String label) { this.id = id; this.label = label; }
    }

    /** Every back camera the phone lets apps open, with its field of view: the widest one is usually the best for hands. */
    static java.util.List<CameraOption> cameras(Context c) {
        java.util.List<CameraOption> list = new ArrayList<>();
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                list.add(new CameraOption(id, "Задняя " + id + fovText(ch)));
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private static String fovText(CameraCharacteristics ch) {
        float[] focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focal == null || focal.length == 0 || size == null) return "";
        return " · " + Math.round(Math.toDegrees(2 * Math.atan(size.getWidth() / (2 * focal[0])))) + "°";
    }

    /** The camera's picture goes into every HandFrame (for the test screen). Costs a copy per frame: off by default. */
    void setPreview(boolean on) { wantPreview = on; }

    /** One line per fact, for the test screen. */
    String report() {
        return "Шаг: " + stage + "\nКамера: " + (cameraName.isEmpty() ? "—" : cameraName)
                + "\nКадров с камеры: " + framesSeen + ", с руками: " + framesWithHands
                + (lastError.isEmpty() ? "" : "\nОшибка: " + lastError);
    }
    private final HandlerThread thread = new HandlerThread("horizon-hands");
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandLandmarker landmarker;
    private boolean running;

    private int sensorOrientation;
    private Bitmap bitmap;
    private int[] pixels;
    private long lastTimestamp;

    // ---- gesture state (touched only on the hands thread)
    private final boolean[] down = new boolean[16];
    private long leftSeen, rightSeen;
    private boolean leftActive;
    private float neutralX, neutralY;
    private float stickX, stickY;
    private boolean sprint;
    private final Gesture left = new Gesture(), right = new Gesture();
    private long lastSweep;
    private final ArrayDeque<double[]> sweep = new ArrayDeque<>(); // {time, x}

    HandTracker(Activity activity, Output out) {
        this.activity = activity;
        this.out = out;
    }

    /** Opens the camera and the model. Needs the CAMERA permission. Safe to call again after stop(). */
    synchronized void start() {
        if (running) return;
        gen++;
        framesSeen = 0;
        framesWithHands = 0;
        lastError = "";
        stage = "загрузка модели";
        try {
            if (!thread.isAlive()) thread.start();
            if (handler == null) handler = new Handler(thread.getLooper());
            landmarker = HandLandmarker.createFromOptions(activity, HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build());
            stage = "модель загружена, открываю камеру";
            running = true;
            openCamera();
        } catch (Throwable e) {
            running = false;
            fail("Трекинг рук не запустился: " + e);
            closeAll();
        }
    }

    synchronized void stop() {
        running = false;
        closeAll();
        if (handler != null) handler.post(this::releaseAll);
    }

    // ------------------------------------------------------------------ camera

    private void openCamera() throws CameraAccessException {
        CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        String best = null;
        double bestFov = 0;
        final String wanted = Settings.handCamera(activity);
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            if (!wanted.isEmpty() && id.equals(wanted)) { best = id; break; }   // the user's pick wins
            float[] focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            double fov = focal != null && focal.length > 0 && size != null
                    ? Math.toDegrees(2 * Math.atan(size.getWidth() / (2 * focal[0]))) : 1;
            if (best == null || fov > bestFov) { best = id; bestFov = fov; }  // the widest lens: more room for the hands
        }
        if (best == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "нет задней камеры");
        final String id = best;
        final int myGen = gen;
        final CameraCharacteristics ch = cm.getCameraCharacteristics(id);
        cameraName = "Задняя " + id + fovText(ch) + (wanted.equals(id) ? " (выбрана)" : " (авто)");
        Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = so == null ? 90 : so;

        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "нет YUV-потока");
        Size pick = sizes[0];
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {   // close to 640x480: enough for a hand at arm's length, cheap to convert
            long diff = Math.abs((long) s.getWidth() * s.getHeight() - 640L * 480L);
            if (diff < bestDiff && s.getWidth() % 4 == 0 && s.getHeight() % 4 == 0) { bestDiff = diff; pick = s; }
        }
        reader = ImageReader.newInstance(pick.getWidth(), pick.getHeight(), ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(r -> onFrame(r), handler);

        final float zoom = wideZoom(ch);
        // Permission is checked by the caller (HorizonActivity) before start().
        @SuppressWarnings("MissingPermission")
        Runnable open = () -> {
            try {
                cm.openCamera(id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice device) {
                        synchronized (HandTracker.this) {
                            if (!running || myGen != gen) { device.close(); return; }
                            camera = device;
                        }
                        stage = "камера открыта, настраиваю поток";
                        createSession(device, zoom);
                    }
                    @Override public void onDisconnected(CameraDevice device) { device.close(); }
                    @Override public void onError(CameraDevice device, int error) {
                        device.close();
                        fail("Камера для рук: ошибка " + error);
                    }
                }, handler);
            } catch (Throwable e) {
                fail("Камера для рук не открылась: " + e);
            }
        };
        open.run();
    }

    /** Zoom ratio 0.6x where the main camera offers it (some phones give the ultra-wide that way), else 0 = leave. */
    private static float wideZoom(CameraCharacteristics ch) {
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> z = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (z != null && z.getLower() < 0.99f) return z.getLower();
        }
        return 0f;
    }

    @SuppressWarnings("deprecation")
    private void createSession(CameraDevice device, float zoom) {
        final int sessionGen = gen;
        try {
            final Surface surface = reader.getSurface();
            final CaptureRequest.Builder rb = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(surface);
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            if (Build.VERSION.SDK_INT >= 30 && zoom > 0f) rb.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            device.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    synchronized (HandTracker.this) {
                        if (!running || sessionGen != gen) { s.close(); return; }
                        session = s;
                    }
                    try {
                        s.setRepeatingRequest(rb.build(), null, handler);
                        stage = "камера идёт";
                        Log.i(TAG, "camera running");
                    } catch (Throwable e) {
                        fail("Камера для рук: " + e);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { fail("Камера для рук не настроилась"); }
            }, handler);
        } catch (Throwable e) {
            fail("Камера для рук: " + e);
        }
    }

    private synchronized void closeAll() {
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        try { if (camera != null) camera.close(); } catch (Throwable ignored) {}
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        try { if (landmarker != null) landmarker.close(); } catch (Throwable ignored) {}
        session = null; camera = null; reader = null; landmarker = null;
    }

    private void fail(String message) {
        lastError = message;
        stage = "ошибка";
        Log.e(TAG, message);
        activity.runOnUiThread(() -> android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------------ frames

    private void onFrame(ImageReader r) {
        Image image = null;
        try {
            image = r.acquireLatestImage();   // older frames are dropped: always the newest picture
            if (image == null) return;
            HandLandmarker lm;
            synchronized (this) {
                if (!running || landmarker == null) return;
                lm = landmarker;
            }
            framesSeen++;
            toBitmap(image);
            image.close();
            image = null;
            long ts = SystemClock.uptimeMillis();
            if (ts <= lastTimestamp) ts = lastTimestamp + 1;
            lastTimestamp = ts;
            // MPImage.close() recycles the bitmap it wraps, so the model gets a copy: ours is reused for the next frame.
            MPImage mp = new BitmapImageBuilder(bitmap.copy(Bitmap.Config.ARGB_8888, false)).build();
            HandLandmarkerResult result;
            try {
                result = lm.detectForVideo(mp, ts);
            } finally {
                mp.close();
            }
            process(result, bitmap.getWidth(), bitmap.getHeight(), SystemClock.uptimeMillis());
        } catch (Throwable e) {
            lastError = "кадр: " + e;
            Log.w(TAG, "frame: " + e);
        } finally {
            if (image != null) image.close();
        }
    }

    /**
     * YUV_420_888 to an upright ARGB bitmap at half the camera's resolution. Upright means turned clockwise by
     * (sensor orientation - display rotation), which for the usual back camera in landscape is 0 or 180 degrees.
     */
    private void toBitmap(Image image) {
        final int sw = image.getWidth() / 2, sh = image.getHeight() / 2;
        int deg = displayDegrees();
        final int k = ((sensorOrientation - deg + 90 * Settings.handRotation(activity)) % 360 + 360) % 360;
        final boolean swap = k == 90 || k == 270;
        final int ow = swap ? sh : sw, oh = swap ? sw : sh;
        if (bitmap == null || bitmap.getWidth() != ow || bitmap.getHeight() != oh) {
            bitmap = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            pixels = new int[ow * oh];
        }
        Image.Plane[] p = image.getPlanes();
        final ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        final int yr = p[0].getRowStride(), yp = p[0].getPixelStride();
        final int ur = p[1].getRowStride(), up = p[1].getPixelStride();
        final int vr = p[2].getRowStride(), vp = p[2].getPixelStride();
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int sx, sy;                                   // where this output pixel comes from
                switch (k) {
                    case 90:  sx = y;           sy = sh - 1 - x; break;
                    case 180: sx = sw - 1 - x;  sy = sh - 1 - y; break;
                    case 270: sx = sw - 1 - y;  sy = x;          break;
                    default:  sx = x;           sy = y;          break;
                }
                int yy = yb.get(2 * sy * yr + 2 * sx * yp) & 0xff;
                int u = (ub.get(sy * ur + sx * up) & 0xff) - 128;
                int v = (vb.get(sy * vr + sx * vp) & 0xff) - 128;
                int r = yy + ((91881 * v) >> 16);
                int g = yy - ((22554 * u + 46802 * v) >> 16);
                int b = yy + ((116130 * u) >> 16);
                r = r < 0 ? 0 : Math.min(r, 255);
                g = g < 0 ? 0 : Math.min(g, 255);
                b = b < 0 ? 0 : Math.min(b, 255);
                pixels[y * ow + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        bitmap.setPixels(pixels, 0, ow, 0, 0, ow, oh);
    }

    @SuppressWarnings("deprecation")
    private int displayDegrees() {
        switch (activity.getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    // ------------------------------------------------------------------ gestures

    /** One hand reduced to what the gestures need. Coordinates are normalized (0..1), distances in pixels. */
    private static final class Hand {
        float cx, cy;        // palm centre
        int raw;             // OPEN, PINCH or FIST
        float pinchRatio;
        final float[] pts = new float[63];   // x y z of the 21 landmarks
    }

    private Hand analyse(List<NormalizedLandmark> l, int w, int h, boolean wasPinch) {
        Hand hand = new Hand();
        int[] palm = {WRIST, INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP};
        for (int i : palm) { hand.cx += l.get(i).x(); hand.cy += l.get(i).y(); }
        for (int i = 0; i < 21; i++) {
            hand.pts[3 * i] = l.get(i).x();
            hand.pts[3 * i + 1] = l.get(i).y();
            hand.pts[3 * i + 2] = l.get(i).z();
        }
        hand.cx /= palm.length;
        hand.cy /= palm.length;
        float size = dist(l, WRIST, MIDDLE_MCP, w, h);
        if (size < 1f) { hand.raw = OPEN; return hand; }
        int curled = 0;
        for (int i = 0; i < TIPS.length; i++) {
            if (dist(l, TIPS[i], WRIST, w, h) < dist(l, PIPS[i], WRIST, w, h)) curled++;
        }
        hand.pinchRatio = dist(l, THUMB_TIP, INDEX_TIP, w, h) / size;
        if (curled >= 3) hand.raw = FIST;
        else if (hand.pinchRatio < (wasPinch ? PINCH_OFF : PINCH_ON)) hand.raw = PINCH;
        else hand.raw = OPEN;
        return hand;
    }

    private static float dist(List<NormalizedLandmark> l, int a, int b, int w, int h) {
        return (float) Math.hypot((l.get(a).x() - l.get(b).x()) * w, (l.get(a).y() - l.get(b).y()) * h);
    }

    private void process(HandLandmarkerResult result, int w, int h, long now) {
        Hand lh = null, rh = null;
        List<List<NormalizedLandmark>> hands = result.landmarks();
        List<Hand> found = new ArrayList<>();
        for (List<NormalizedLandmark> l : hands) {
            if (l.size() < 21) continue;
            found.add(analyse(l, w, h, false));
        }
        if (found.size() >= 2) {
            Hand a = found.get(0), b = found.get(1);
            lh = a.cx < b.cx ? a : b;
            rh = a.cx < b.cx ? b : a;
        } else if (found.size() == 1) {
            Hand a = found.get(0);
            if (a.cx < 0.5f) lh = a; else rh = a;
        }
        // Pinch has hysteresis, which needs the hand's previous state: a hand already pinching stays pinched until it opens wide.
        if (rh != null && right.state == PINCH && rh.raw == OPEN && rh.pinchRatio < PINCH_OFF) rh.raw = PINCH;
        if (lh != null && left.state == PINCH && lh.raw == OPEN && lh.pinchRatio < PINCH_OFF) lh.raw = PINCH;

        if (lh != null) {
            if (!leftActive || now - leftSeen > RECENTER_MS) { neutralX = lh.cx; neutralY = lh.cy; leftActive = true; }
            leftSeen = now;
            left.feed(lh.raw);
            leftHand(lh, w, h);
        } else if (now - leftSeen > LOST_MS) {
            leftActive = false;
            left.reset();
            moveStick(0, 0);
            setButton(HorizonInput.SOUTH, false);
        }

        if (rh != null) {
            rightSeen = now;
            right.feed(rh.raw);
            setButton(HorizonInput.RT, right.state == PINCH);
            setButton(HorizonInput.LT, right.state == FIST);
            sweeps(rh, now);
            out.pointer(true, rh.cx, rh.cy);
        } else if (now - rightSeen > LOST_MS) {
            right.reset();
            setButton(HorizonInput.RT, false);
            setButton(HorizonInput.LT, false);
            sweep.clear();
            out.pointer(false, 0, 0);
        }
        publish(lh, rh, w, h);
    }

    private void publish(Hand lh, Hand rh, int w, int h) {
        HandFrame fr = new HandFrame();
        fr.aspect = (float) w / h;
        if (lh != null || rh != null) framesWithHands++;
        if (wantPreview && bitmap != null) fr.preview = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (lh != null) { fr.present[HandFrame.LEFT] = true; System.arraycopy(lh.pts, 0, fr.pts[HandFrame.LEFT], 0, 63); fr.state[HandFrame.LEFT] = left.state; }
        if (rh != null) { fr.present[HandFrame.RIGHT] = true; System.arraycopy(rh.pts, 0, fr.pts[HandFrame.RIGHT], 0, 63); fr.state[HandFrame.RIGHT] = right.state; }
        fr.leftActive = leftActive && lh != null;
        fr.neutralX = neutralX;
        fr.neutralY = neutralY;
        fr.rangeX = STICK_RANGE;
        fr.rangeY = STICK_RANGE * w / h;
        out.frame(fr);
    }

    /** A gesture must be seen on two frames in a row before it counts: one bad frame does not click. */
    private static final class Gesture {
        int state = OPEN, candidate = OPEN;

        void feed(int raw) {
            if (raw == state) candidate = state;
            else if (raw == candidate) state = raw;
            else candidate = raw;
        }

        void reset() { state = candidate = OPEN; }
    }

    private void leftHand(Hand hand, int w, int h) {
        // Pixels, so a step sideways and a step up feel the same on a 4:3 picture.
        float range = STICK_RANGE * w;
        float dx = (hand.cx - neutralX) * w / range;
        float dy = (neutralY - hand.cy) * h / range;
        float len = (float) Math.hypot(dx, dy);
        if (len < DEAD_ZONE) { dx = 0; dy = 0; }
        else {
            float scaled = Math.min(1f, (len - DEAD_ZONE) / (1f - DEAD_ZONE));
            dx *= scaled / len;
            dy *= scaled / len;
        }
        stickX = 0.5f * stickX + 0.5f * dx;
        stickY = 0.5f * stickY + 0.5f * dy;
        moveStick(stickX, stickY);
        setButton(HorizonInput.SOUTH, left.state == FIST);   // fist = jump
    }

    private void moveStick(float x, float y) {
        if (x == 0 && y == 0) { stickX = 0; stickY = 0; }
        out.stick(x, y);
        boolean run = Math.hypot(x, y) > 0.95 && y > 0.7;    // pushed to the far edge: sprint, as the on-screen stick does
        if (run != sprint) { sprint = run; setButton(HorizonInput.LSTICK, run); }
    }

    private void sweeps(Hand hand, long now) {
        sweep.addLast(new double[] {now, hand.cx});
        while (!sweep.isEmpty() && now - (long) sweep.peekFirst()[0] > SWEEP_WINDOW_MS) sweep.removeFirst();
        if (right.state != OPEN || now - lastSweep < SWEEP_COOLDOWN_MS || sweep.size() < 3) return;
        double moved = hand.cx - sweep.peekFirst()[1];
        if (Math.abs(moved) >= SWEEP_DISTANCE) {
            lastSweep = now;
            sweep.clear();
            pulse(moved > 0 ? HorizonInput.RB : HorizonInput.LB);   // right = next slot
        }
    }

    private void pulse(final int button) {
        setButton(button, true);
        handler.postDelayed(() -> setButton(button, false), 80);
    }

    private void setButton(int button, boolean on) {
        if (down[button] == on) return;
        down[button] = on;
        out.button(button, on);
    }

    private void releaseAll() {
        out.pointer(false, 0, 0);
        out.frame(new HandFrame());
        moveStick(0, 0);
        for (int i = 0; i < down.length; i++) setButton(i, false);
        leftActive = false;
        left.reset();
        right.reset();
        sweep.clear();
    }
}
