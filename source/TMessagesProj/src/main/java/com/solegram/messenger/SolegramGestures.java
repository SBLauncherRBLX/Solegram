/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

/**
 * Motion shortcuts: a movement of the phone runs an action the user chose (opens a chat or a channel, Saved
 * Messages, a link, the archive, SoleWallet, the settings, or locks the app).
 * <p>
 * The sensors are listened to only while Solegram is on the screen, and only when at least one gesture has an
 * action: nothing runs in the background. Five movements are told apart:
 * <ul>
 * <li>shake: quick side-to-side shakes;</li>
 * <li>flip: turning the phone face down on a table;</li>
 * <li>twist: twisting the wrist twice, quickly, as if turning a key;</li>
 * <li>double knock: two knocks on the back of a still phone;</li>
 * <li>chop: two quick chops up and down, as with a hammer.</li>
 * </ul>
 */
public final class SolegramGestures implements SensorEventListener {

    public static final int SHAKE = 0;
    public static final int FLIP = 1;
    public static final int TWIST = 2;
    public static final int KNOCK = 3;
    public static final int CHOP = 4;
    public static final int COUNT = 5;

    public static final int ACTION_NONE = 0;
    public static final int ACTION_CHAT = 1;
    public static final int ACTION_SAVED = 2;
    public static final int ACTION_LINK = 3;
    public static final int ACTION_ARCHIVE = 4;
    public static final int ACTION_WALLET = 5;
    public static final int ACTION_SETTINGS = 6;
    public static final int ACTION_LOCK = 7;
    public static final int ACTION_COUNT = 8;

    /** While the gestures page is open, a gesture is only shown there, not run. */
    public interface Tester {
        void onGesture(int gesture);
    }

    private static SolegramGestures instance;
    private static Tester tester;

    private SensorManager sensors;
    private boolean registered;
    private long cooldownUntil;

    // shake and chop: peaks of linear acceleration along an axis, with their signs
    private final long[] shakePeaks = new long[6];
    private int shakeCount;
    private int shakeLastSign;
    private final long[] chopPeaks = new long[6];
    private int chopCount;
    private int chopLastSign;
    // flip: face up a moment ago, now face down for a while
    private long faceUpAt;
    private long faceDownSince;
    private boolean flipFired;
    // twist: rotation peaks around the long axis
    private final long[] twistPeaks = new long[6];
    private int twistCount;
    private int twistLastSign;
    // knock: short spikes through the phone while it is otherwise still
    private long knockAt;
    private long lastSpikeAt;
    private float gyroMagnitude;
    private final float[] gravity = new float[3];
    private boolean gravityKnown;

    private SolegramGestures() {
    }

    public static synchronized SolegramGestures get() {
        if (instance == null) {
            instance = new SolegramGestures();
        }
        return instance;
    }

    // region Settings

    public static boolean enabled() {
        return SolegramConfig.prefs().getBoolean("gestures", false);
    }

    public static void setEnabled(boolean on) {
        SolegramConfig.prefs().edit().putBoolean("gestures", on).apply();
    }

    public static int actionOf(int gesture) {
        return SolegramConfig.prefs().getInt("gesture" + gesture + "Action", ACTION_NONE);
    }

    public static long peerOf(int gesture) {
        return SolegramConfig.prefs().getLong("gesture" + gesture + "Peer", 0);
    }

    public static String linkOf(int gesture) {
        return SolegramConfig.prefs().getString("gesture" + gesture + "Link", "");
    }

    public static void set(int gesture, int action, long peer, String link) {
        SolegramConfig.prefs().edit()
            .putInt("gesture" + gesture + "Action", action)
            .putLong("gesture" + gesture + "Peer", peer)
            .putString("gesture" + gesture + "Link", link == null ? "" : link)
            .apply();
    }

    private static boolean anyAction() {
        for (int g = 0; g < COUNT; g++) {
            if (actionOf(g) != ACTION_NONE) {
                return true;
            }
        }
        return false;
    }

    public static int nameOf(int gesture) {
        switch (gesture) {
            case SHAKE:
                return R.string.SG_GestureShake;
            case FLIP:
                return R.string.SG_GestureFlip;
            case TWIST:
                return R.string.SG_GestureTwist;
            case KNOCK:
                return R.string.SG_GestureKnock;
            default:
                return R.string.SG_GestureChop;
        }
    }

    public static int hintOf(int gesture) {
        switch (gesture) {
            case SHAKE:
                return R.string.SG_GestureShakeHint;
            case FLIP:
                return R.string.SG_GestureFlipHint;
            case TWIST:
                return R.string.SG_GestureTwistHint;
            case KNOCK:
                return R.string.SG_GestureKnockHint;
            default:
                return R.string.SG_GestureChopHint;
        }
    }

    public static int actionName(int action) {
        switch (action) {
            case ACTION_CHAT:
                return R.string.SG_GestureActionChat;
            case ACTION_SAVED:
                return R.string.SG_GestureActionSaved;
            case ACTION_LINK:
                return R.string.SG_GestureActionLink;
            case ACTION_ARCHIVE:
                return R.string.SG_GestureActionArchive;
            case ACTION_WALLET:
                return R.string.SG_GestureActionWallet;
            case ACTION_SETTINGS:
                return R.string.SG_GestureActionSettings;
            case ACTION_LOCK:
                return R.string.SG_GestureActionLock;
            default:
                return R.string.SG_GestureActionNone;
        }
    }
    // endregion

    // region Listening

    /** The app came to the screen or the settings changed: listen if there is anything to do. */
    public static void onResume() {
        get().update(true);
    }

    public static void onPause() {
        get().update(false);
    }

    public static void setTester(Tester t) {
        tester = t;
        get().update(true);
    }

    private void update(boolean foreground) {
        final boolean want = foreground && (tester != null || enabled() && anyAction());
        try {
            if (sensors == null) {
                sensors = (SensorManager) ApplicationLoader.applicationContext.getSystemService(Context.SENSOR_SERVICE);
            }
            if (sensors == null) {
                return;
            }
            if (want && !registered) {
                final Sensor accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                final Sensor gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                if (accel != null) {
                    sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
                }
                if (gyro != null) {
                    sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
                }
                registered = accel != null || gyro != null;
                reset();
            } else if (!want && registered) {
                sensors.unregisterListener(this);
                registered = false;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void reset() {
        shakeCount = chopCount = twistCount = 0;
        faceUpAt = faceDownSince = 0;
        flipFired = false;
        gravityKnown = false;
        knockAt = lastSpikeAt = 0;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        final long now = SystemClock.elapsedRealtime();
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            onGyro(event.values, now);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            onAccel(event.values, now);
        }
    }

    private void onAccel(float[] v, long now) {
        // gravity by a low-pass filter, the rest is the phone's own movement
        if (!gravityKnown) {
            System.arraycopy(v, 0, gravity, 0, 3);
            gravityKnown = true;
        }
        final float a = 0.85f;
        for (int i = 0; i < 3; i++) {
            gravity[i] = a * gravity[i] + (1 - a) * v[i];
        }
        final float lx = v[0] - gravity[0], ly = v[1] - gravity[1], lz = v[2] - gravity[2];

        // shake: strong side-to-side swings (x), each the other way from the last
        if (Math.abs(lx) > 11f && Math.abs(lx) > Math.abs(ly) * 1.2f) {
            final int sign = lx > 0 ? 1 : -1;
            if (sign != shakeLastSign) {
                shakeLastSign = sign;
                push(shakePeaks, shakeCount++, now);
                if (countWithin(shakePeaks, shakeCount, now, 900) >= 4) {
                    shakeCount = 0;
                    fire(SHAKE);
                }
            }
        }
        // chop: strong swings along the length of the phone (y)
        if (Math.abs(ly) > 15f && Math.abs(ly) > Math.abs(lx) * 1.4f) {
            final int sign = ly > 0 ? 1 : -1;
            if (sign != chopLastSign) {
                chopLastSign = sign;
                push(chopPeaks, chopCount++, now);
                if (countWithin(chopPeaks, chopCount, now, 1000) >= 4) {
                    chopCount = 0;
                    fire(CHOP);
                }
            }
        }
        // flip: the screen faced up, and now faces down and stays so for half a second
        if (gravity[2] > 7f) {
            faceUpAt = now;
            faceDownSince = 0;
            flipFired = false;
        } else if (gravity[2] < -8.5f) {
            if (faceDownSince == 0) {
                faceDownSince = now;
            }
            if (!flipFired && now - faceDownSince > 500 && faceDownSince - faceUpAt < 2500) {
                flipFired = true;
                fire(FLIP);
            }
        }
        // knock: a short spike through the body (z) while the phone hardly moves or turns
        final float side = Math.abs(lx) + Math.abs(ly);
        if (Math.abs(lz) > 2.8f && side < Math.abs(lz) * 0.6f && gyroMagnitude < 0.8f) {
            if (now - lastSpikeAt > 90) { // one knock gives a few samples: count it once
                if (knockAt != 0 && now - knockAt > 110 && now - knockAt < 500) {
                    knockAt = 0;
                    fire(KNOCK);
                } else {
                    knockAt = now;
                }
            }
            lastSpikeAt = now;
        } else if (knockAt != 0 && now - knockAt > 500) {
            knockAt = 0;
        }
    }

    private void onGyro(float[] v, long now) {
        gyroMagnitude = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        // twist: fast turns around the long axis (y), back and forth, twice
        if (Math.abs(v[1]) > 5.5f && Math.abs(v[1]) > Math.abs(v[0]) && Math.abs(v[1]) > Math.abs(v[2])) {
            final int sign = v[1] > 0 ? 1 : -1;
            if (sign != twistLastSign) {
                twistLastSign = sign;
                push(twistPeaks, twistCount++, now);
                if (countWithin(twistPeaks, twistCount, now, 1100) >= 4) {
                    twistCount = 0;
                    fire(TWIST);
                }
            }
        }
    }

    private static void push(long[] ring, int index, long now) {
        ring[index % ring.length] = now;
    }

    private static int countWithin(long[] ring, int count, long now, long window) {
        int n = 0;
        for (int i = 0; i < Math.min(count, ring.length); i++) {
            if (now - ring[i] <= window) {
                n++;
            }
        }
        return n;
    }
    // endregion

    // region Actions

    private void fire(int gesture) {
        final long now = SystemClock.elapsedRealtime();
        if (now < cooldownUntil) {
            return;
        }
        cooldownUntil = now + 1500;
        AndroidUtilities.runOnUIThread(() -> {
            if (tester != null) {
                tester.onGesture(gesture);
                return;
            }
            if (!enabled() || actionOf(gesture) == ACTION_NONE) {
                return;
            }
            run(gesture);
        });
    }

    private static void run(int gesture) {
        final BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null || last.getParentActivity() == null) {
            return;
        }
        // a gesture never acts on top of the passcode screen
        if (SharedConfig.passcodeHash.length() > 0 && SharedConfig.appLocked) {
            return;
        }
        com.solegram.messenger.wallet.tap.WalletTap.hapticContact(last.getFragmentView());
        final int account = UserConfig.selectedAccount;
        final Bundle args = new Bundle();
        switch (actionOf(gesture)) {
            case ACTION_CHAT: {
                final long peer = peerOf(gesture);
                if (peer == 0) {
                    return;
                }
                if (peer > 0) {
                    args.putLong("user_id", peer);
                } else {
                    args.putLong("chat_id", -peer);
                }
                last.presentFragment(new ChatActivity(args));
                break;
            }
            case ACTION_SAVED:
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
                last.presentFragment(new ChatActivity(args));
                break;
            case ACTION_LINK: {
                final String link = linkOf(gesture);
                if (!TextUtils.isEmpty(link)) {
                    Browser.openUrl(last.getParentActivity(), link.startsWith("http") || link.startsWith("tg:") ? link : "https://" + link);
                }
                break;
            }
            case ACTION_ARCHIVE:
                args.putInt("folderId", 1);
                last.presentFragment(new DialogsActivity(args));
                break;
            case ACTION_WALLET:
                last.presentFragment(new com.solegram.messenger.wallet.SoleWalletActivity());
                break;
            case ACTION_SETTINGS:
                last.presentFragment(new org.telegram.ui.SettingsActivity());
                break;
            case ACTION_LOCK:
                if (SharedConfig.passcodeHash.length() > 0 && last.getParentActivity() instanceof LaunchActivity) {
                    SharedConfig.appLocked = true;
                    SharedConfig.saveConfig();
                    ((LaunchActivity) last.getParentActivity()).showPasscodeActivity(true, false, -1, -1, null, null);
                } else {
                    org.telegram.ui.Components.BulletinFactory.of(last).createErrorBulletin(
                        org.telegram.messenger.LocaleController.getString(R.string.SG_GestureNoPasscode)).show();
                }
                break;
        }
    }
    // endregion
}
