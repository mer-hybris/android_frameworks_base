/*
* Copyright (C) 2016 The Android Open Source Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.server.display;


import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.util.Slog;
import com.android.internal.app.NightDisplayController.LocalTime;
import com.android.internal.app.NightDisplayController;
import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.OutputStreamWriter;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Tints the display at night.
 */
public final class NightDisplayService extends SystemService
        implements NightDisplayController.Callback {

    private static final String TAG = "NightDisplayService";
    private static final boolean DEBUG = false;

    /**
     * Night mode ~= 3400 K.
     */
    private static final String SETTING_NIGHT = "32768 25000 15000\n";
    private static final String SETTING_DAY   = "32768 32768 32768\n";

    private static final String RGB_FILE_PATH = "/sys/class/graphics/fb0/rgb";

    private int mCurrentUser = UserHandle.USER_NULL;
    private boolean mBootCompleted;

    private NightDisplayController mController;
    private Boolean mIsActivated;
    private AutoMode mAutoMode;

    public NightDisplayService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        // Nothing to publish.
    }

    @Override
    public void onStartUser(int userHandle) {
        super.onStartUser(userHandle);

        // Register listeners for the new user.
        if (mCurrentUser == UserHandle.USER_NULL) {
            mCurrentUser = userHandle;
            if (mBootCompleted) {
                setUpNightMode();
            }
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);

        // Unregister listeners for the old user.
        if (mBootCompleted && mCurrentUser != UserHandle.USER_NULL) {
            tearDownNightMode();
        }

        // Register listeners for the new user.
        mCurrentUser = userHandle;
        if (mBootCompleted) {
            setUpNightMode();
        }
    }

    @Override
    public void onStopUser(int userHandle) {
        super.onStopUser(userHandle);

        // Unregister listeners for the old user.
        if (mCurrentUser == userHandle) {
            if (mBootCompleted) {
                tearDownNightMode();
            }
            mCurrentUser = UserHandle.USER_NULL;
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            mBootCompleted = true;

            // Register listeners now that boot is complete.
            if (mCurrentUser != UserHandle.USER_NULL) {
                setUpNightMode();
            }
        }
    }

    private void setUpNightMode() {
        // Create a new controller for the current user and start listening for changes.
        mController = new NightDisplayController(getContext(), mCurrentUser);
        mController.setListener(this);

        // Initialize the current auto mode.
        onAutoModeChanged(mController.getAutoMode());

        // Force the initialization current activated state.
        if (mIsActivated == null) {
            onActivated(mController.isActivated());
        }
    }

    private void tearDownNightMode() {
        mController.setListener(null);

        if (mAutoMode != null) {
            mAutoMode.onStop();
            mAutoMode = null;
        }

        mIsActivated = null;
        mController = null;
    }

    private void writeString(String str) {
        try {
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(
              new FileOutputStream(RGB_FILE_PATH));
            outputStreamWriter.write(str);
            outputStreamWriter.close();
        } catch (IOException e) {
          Slog.i(TAG, "could not write to file: " + e.toString());
        }
    }


    private void activateNightMode() {
        writeString(SETTING_NIGHT);
    }

    private void deactivateNightMode() {
        writeString(SETTING_DAY);
    }

    @Override
    public void onActivated(boolean activated) {
        if (mIsActivated == null || mIsActivated != activated) {
            Slog.i(TAG, activated ? "Turning on night display" : "Turning off night display");

            mIsActivated = activated;

            if (mAutoMode != null) {
                mAutoMode.onActivated(activated);
            }

            if (activated) {
                activateNightMode();
            } else {
                deactivateNightMode();
            }
        }
    }

    @Override
    public void onAutoModeChanged(int autoMode) {
        if (mAutoMode != null) {
            mAutoMode.onStop();
            mAutoMode = null;
        }

        if (autoMode == NightDisplayController.AUTO_MODE_CUSTOM) {
            mAutoMode = new CustomAutoMode();
        } else if (autoMode == NightDisplayController.AUTO_MODE_TWILIGHT) {
            mAutoMode = new TwilightAutoMode();
        }

        if (mAutoMode != null) {
            mAutoMode.onStart();
        }
    }

    @Override
    public void onCustomStartTimeChanged(NightDisplayController.LocalTime startTime) {
        if (mAutoMode != null) {
            mAutoMode.onCustomStartTimeChanged(startTime);
        }
    }

    @Override
    public void onCustomEndTimeChanged(NightDisplayController.LocalTime endTime) {
        if (mAutoMode != null) {
            mAutoMode.onCustomEndTimeChanged(endTime);
        }
    }

    private abstract class AutoMode implements NightDisplayController.Callback {
        public abstract void onStart();
        public abstract void onStop();

        @Override
        public void onActivated(boolean activated) {}
        @Override
        public void onAutoModeChanged(int autoMode) {}
        @Override
        public void onCustomStartTimeChanged(LocalTime startTime) {}
        @Override
        public void onCustomEndTimeChanged(LocalTime endTime) {}
    }

    private class CustomAutoMode extends AutoMode {

        private final AlarmManager mAlarmManager;
        private final BroadcastReceiver mTimeChangedReceiver;

        private final BroadcastReceiver mAlarmReceiver;
        private PendingIntent mAlarmIntent;

        private NightDisplayController.LocalTime mStartTime;
        private NightDisplayController.LocalTime mEndTime;

        private Calendar mLastActivatedTime;

        public CustomAutoMode() {
            mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateActivated();
                }
            };
            mAlarmReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    onAlarm();
                }
            };
        }

        private void updateActivated() {
            final Calendar now = Calendar.getInstance();
            final Calendar startTime = mStartTime.getDateTimeBefore(now);
            final Calendar endTime = mEndTime.getDateTimeAfter(startTime);
            final boolean activated = now.before(endTime);

            boolean setActivated = mIsActivated == null || mLastActivatedTime == null;
            if (!setActivated && mIsActivated != activated) {
                final TimeZone currentTimeZone = now.getTimeZone();
                if (!currentTimeZone.equals(mLastActivatedTime.getTimeZone())) {
                    final int year = mLastActivatedTime.get(Calendar.YEAR);
                    final int dayOfYear = mLastActivatedTime.get(Calendar.DAY_OF_YEAR);
                    final int hourOfDay = mLastActivatedTime.get(Calendar.HOUR_OF_DAY);
                    final int minute = mLastActivatedTime.get(Calendar.MINUTE);

                    mLastActivatedTime.setTimeZone(currentTimeZone);
                    mLastActivatedTime.set(Calendar.YEAR, year);
                    mLastActivatedTime.set(Calendar.DAY_OF_YEAR, dayOfYear);
                    mLastActivatedTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    mLastActivatedTime.set(Calendar.MINUTE, minute);
                }

                if (mIsActivated) {
                    setActivated = now.before(mStartTime.getDateTimeBefore(mLastActivatedTime))
                            || now.after(mEndTime.getDateTimeAfter(mLastActivatedTime));
                } else {
                    setActivated = now.before(mEndTime.getDateTimeBefore(mLastActivatedTime))
                            || now.after(mStartTime.getDateTimeAfter(mLastActivatedTime));
                }
            }

            if (setActivated) {
                mController.setActivated(activated);
            }
            updateNextAlarm();
        }

        private void updateNextAlarm() {
            if (mIsActivated != null) {
                final Calendar now = Calendar.getInstance();
                final Calendar next = mIsActivated ? mEndTime.getDateTimeAfter(now)
                        : mStartTime.getDateTimeAfter(now);
                mAlarmManager.setExact(AlarmManager.RTC, next.getTimeInMillis(), mAlarmIntent);
            }
        }

        @Override
        public void onStart() {
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);

            Intent intent = new Intent("com.android.server.display.NightDisplayService.CustomAutoMode.ACTION_ALARM");
            IntentFilter filter = new IntentFilter();
            filter.addAction(intent.getAction());
            getContext().registerReceiver(mAlarmReceiver, filter);
            mAlarmIntent = PendingIntent.getBroadcast(getContext(), 0, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT);

            mStartTime = mController.getCustomStartTime();
            mEndTime = mController.getCustomEndTime();

            // Force an update to initialize state.
            updateActivated();
        }

        @Override
        public void onStop() {
            getContext().unregisterReceiver(mTimeChangedReceiver);
            getContext().unregisterReceiver(mAlarmReceiver);

            mLastActivatedTime = null;
        }

        @Override
        public void onActivated(boolean activated) {
            mLastActivatedTime = Calendar.getInstance();
            updateNextAlarm();
        }

        @Override
        public void onCustomStartTimeChanged(NightDisplayController.LocalTime startTime) {
            mStartTime = startTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onCustomEndTimeChanged(NightDisplayController.LocalTime endTime) {
            mEndTime = endTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        public void onAlarm() {
            if (DEBUG) Slog.d(TAG, "onAlarm");
            updateActivated();
        }
    }

    private class TwilightAutoMode extends AutoMode implements TwilightListener {

        private final TwilightManager mTwilightManager;
        private final Handler mHandler;

        private boolean mIsNight;

        public TwilightAutoMode() {
            mTwilightManager = getLocalService(TwilightManager.class);
            mHandler = new Handler(Looper.getMainLooper());
        }

        private void updateActivated() {
            final TwilightState state = mTwilightManager.getCurrentState();
            final boolean isNight = state != null && state.isNight();
            if (mIsNight != isNight) {
                mIsNight = isNight;

                if (mIsActivated == null || mIsActivated != isNight) {
                    mController.setActivated(isNight);
                }
            }
        }

        @Override
        public void onStart() {
            mTwilightManager.registerListener(this, mHandler);

            // Force an update to initialize state.
            updateActivated();
        }

        @Override
        public void onStop() {
            //mTwilightManager.unregisterListener(this);
        }

        @Override
        public void onTwilightStateChanged() {
            if (DEBUG) Slog.d(TAG, "onTwilightStateChanged");
            updateActivated();
        }
    }
}
