package com.fairphone.keyguard;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.keyguard.R;

/**
 * A #LinearLayout that features a battery level (icon + text) and a charging
 * indication similar to what is displayed on the lock-screen.
 * <p>
 * The battery status monitoring is done locally. Furthermore, it is expected
 * that a callback to {@link #setChargingIndication(String)} is hooked to
 * define the current charging indication (i.e. from the
 * {@link #com.android.systemui.statusbar.KeyguardIndicationController}).
 * The rationale is that the SystemUI is already computing a nice charging
 * indication text, and we don't want to duplicate the code too much. So we
 * expect a hook installed from there.
 * <p>
 * Once the children views have been inflated, the current battery status is
 * retrieved to initialise the widgets.
 */
public class BatteryStatusView extends LinearLayout {

    private static final String LOG_TAG = "BatteryStatusView";
    private static final boolean DBG = false;
    private static final String ACTION_LEVEL_TEST = "com.android.systemui.BATTERY_LEVEL_TEST";

    private ImageView mBatteryIconView;
    private TextView mBatteryIndicationView;
    private View mChargingStatusView;
    private TextView mChargingStateView;
    private TextView mChargingIndicationView;

    private final int mLighterWidgetTextColor;
    private final int mRegularWidgetTextColor;
    private final Drawable mBatteryDrawable;
    private final Drawable mBatteryChargingDrawable;

    private String mChargingIndication;
    private boolean mDemoMode = false;
    private BatteryTracker mBatteryTracker = new BatteryTracker();
    private BatteryTracker mDemoTracker = new BatteryTracker();

    /*
     * Battery tracker (roughly) copied from the SystemUI's
     * com.android.systemui.BatteryMeterView class.
     */
    private final class BatteryTracker extends BroadcastReceiver {
        public static final int UNKNOWN_LEVEL = -1;

        public int level = UNKNOWN_LEVEL;
        public boolean plugged;
        public int status;
        public boolean testMode = false;

        /* Irrelevant while in demo mode. */
        public int plugType;

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                if (testMode && !intent.getBooleanExtra("testmode", false)) return;

                plugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                plugged = plugType != 0;
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);

                /*
                 * Make sure the level is set to 100% if the battery is charged.
                 */
                if (status == BatteryManager.BATTERY_STATUS_FULL) {
                    level = 100;
                } else {
                    level = (int) (100f
                            * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                            / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                }

                postInvalidate();
            } else if (action.equals(ACTION_LEVEL_TEST)) {
                testMode = true;
                post(new Runnable() {
                    int curLevel = 0;
                    int incr = 1;
                    int saveLevel = level;
                    int savePlugged = plugType;
                    final Intent dummy = new Intent(Intent.ACTION_BATTERY_CHANGED);

                    @Override
                    public void run() {
                        if (curLevel < 0) {
                            testMode = false;
                            dummy.putExtra("level", saveLevel);
                            dummy.putExtra("plugged", savePlugged);
                            dummy.putExtra("testmode", false);
                        } else {
                            dummy.putExtra("level", curLevel);
                            dummy.putExtra("plugged", incr > 0 ? BatteryManager.BATTERY_PLUGGED_AC
                                    : 0);
                            dummy.putExtra("testmode", true);
                        }
                        getContext().sendBroadcast(dummy);

                        if (!testMode) return;

                        curLevel += incr;
                        if (curLevel == 100) {
                            incr *= -1;
                        }
                        postDelayed(this, 200);
                    }
                });
            }
        }
    }

    public BatteryStatusView(Context context) {
        this(context, null);
    }

    public BatteryStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public BatteryStatusView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public BatteryStatusView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mLighterWidgetTextColor = context.getColor(R.color.widget_lighter_color_fp);
        mRegularWidgetTextColor = context.getColor(R.color.widget_regular_color_fp);

        mBatteryDrawable = context.getDrawable(R.drawable.ic_battery);
        mBatteryChargingDrawable = context.getDrawable(R.drawable.ic_battery_charging);
    }

    @Override
    protected void onFinishInflate() {
        if (DBG)
            Log.d(LOG_TAG, "onFinishInflate:+");

        super.onFinishInflate();

        mBatteryIconView = (ImageView) findViewById(R.id.battery_icon_fp);
        mBatteryIndicationView = (TextView) findViewById(R.id.battery_indication_fp);
        mChargingStatusView = findViewById(R.id.charging_status_fp);
        mChargingStateView = (TextView) findViewById(R.id.charging_state_fp);
        mChargingIndicationView = (TextView) findViewById(R.id.charging_indication_fp);

        if (DBG) Log.d(LOG_TAG, "onFinishInflate:-");
    }

    @Override
    public void onAttachedToWindow() {
        if (DBG)
            Log.d(LOG_TAG, "onAttachedToWindow:+");

        super.onAttachedToWindow();

        /*
         * Get the battery status sticky broadcast so that we have the current
         * values to initialise the children with.
         */
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        intentFilter.addAction(ACTION_LEVEL_TEST);
        final Intent stickyIntent = getContext().registerReceiver(mBatteryTracker, intentFilter);
        if (stickyIntent != null) {
            mBatteryTracker.onReceive(getContext(), stickyIntent);
        } else if (DBG)
            Log.d(LOG_TAG, "onAttachedToWindow: no battery status is available");

        if (DBG)
            Log.d(LOG_TAG, "onAttachedToWindow:-");
    }

    @Override
    public void invalidate() {
        if (DBG)
            Log.d(LOG_TAG, "invalidate:+");

        final BatteryTracker tracker = mDemoMode ? mDemoTracker : mBatteryTracker;

        if (DBG)
            Log.d(LOG_TAG, "invalidate: battery status=" + getBatteryStatus(tracker));

        updateBatteryIcon(tracker);
        updateIndication(tracker);

        super.invalidate();

        if (DBG)
            Log.d(LOG_TAG, "invalidate:-");
    }

    public void setChargingIndication(String chargingIndication) {
        if (DBG) {
            Log.d(LOG_TAG, "setChargingIndication:+");
            Log.d(LOG_TAG, "setChargingIndication:"
                    + " was '" + mChargingIndication + "'"
                    + " now '" + chargingIndication + "'");
        }

        mChargingIndication = chargingIndication;

        // Force refresh the layout
        invalidate();

        if (DBG)
            Log.d(LOG_TAG, "setChargingIndication:-");
    }

    private void updateBatteryIcon(BatteryTracker tracker) {
        mBatteryIconView.setImageDrawable(tracker.plugged ? mBatteryChargingDrawable : mBatteryDrawable);
        mBatteryIconView.setImageLevel(tracker.level);
    }

    private void updateIndication(BatteryTracker tracker) {
        if (tracker.plugged) {
            /*
             * The charging indication will eventually be set by a call to
             * #setChargingIndication(String).
             * In the mean time, use a simple version.
             */
            if (mChargingIndication == null) {
                mChargingIndication = getContext().getString(R.string.keyguard_plugged_in);

                if (DBG)
                    Log.d(LOG_TAG, "updateIndication: no charging indication, falling back to default value:"
                            + mChargingIndication);
            }

            if (tracker.level == 100) {
                /*
                 * The device is fully charged.
                 */
                if (DBG)
                    Log.d(LOG_TAG, "updateIndication: device is fully charged");

                mChargingStateView.setText(mChargingIndication);
                mChargingStateView.setTextColor(mRegularWidgetTextColor);
                mChargingIndicationView.setText(getContext().getString(R.string.keyguard_disconnect_from_power_source_fp));
                mChargingIndicationView.setTextColor(mRegularWidgetTextColor);

                mBatteryIndicationView.setVisibility(View.GONE);
                mChargingStateView.setVisibility(View.VISIBLE);
                mChargingIndicationView.setVisibility(View.VISIBLE);
            } else {
                final int beginPar = mChargingIndication.indexOf('(');

                if (beginPar == -1) {
                    /*
                     * The device is charging but there is no indication on
                     * the duration remaining.
                     */
                    if (DBG)
                        Log.d(LOG_TAG, "updateIndication: device is charging, no estimated duration");

                    mChargingStateView.setText(mChargingIndication);
                    mChargingStateView.setTextColor(mLighterWidgetTextColor);

                    mBatteryIndicationView.setVisibility(View.GONE);
                    mChargingStateView.setVisibility(View.VISIBLE);
                    mChargingIndicationView.setVisibility(View.GONE);
                } else {
                    /*
                     * The device is charging and there is an indication on
                     * the duration remaining.
                     */
                    if (DBG)
                        Log.d(LOG_TAG, "updateIndication: device is charging, available estimation");

                    mChargingStateView.setText(mChargingIndication.substring(0, beginPar).trim());
                    mChargingStateView.setTextColor(mLighterWidgetTextColor);
                    mChargingIndicationView.setText(mChargingIndication.substring(beginPar + 1, mChargingIndication.lastIndexOf(')')).trim());
                    mChargingIndicationView.setTextColor(mLighterWidgetTextColor);

                    mBatteryIndicationView.setVisibility(View.GONE);
                    mChargingStateView.setVisibility(View.VISIBLE);
                    mChargingIndicationView.setVisibility(View.VISIBLE);
                }
            }
        } else {
            /*
             * The device is discharging.
             */
            if (DBG)
                Log.d(LOG_TAG, "updateIndication: device is discharging");

            // Force the charging indication to be reset
            mChargingIndication = null;

            mBatteryIndicationView.setText(getContext().getString(R.string.keyguard_battery_level_fp, tracker.level));

            mBatteryIndicationView.setVisibility(View.VISIBLE);
            mChargingStateView.setVisibility(View.GONE);
            mChargingIndicationView.setVisibility(View.GONE);
        }
    }

    private static String getBatteryStatus(BatteryTracker tracker) {
        final StringBuilder builder = new StringBuilder("{");

        builder.append("status:");
        switch (tracker.status) {
            case android.os.BatteryManager.BATTERY_STATUS_FULL:
                builder.append("full");
                break;
            case android.os.BatteryManager.BATTERY_STATUS_CHARGING:
                builder.append("charging");
                break;
            case android.os.BatteryManager.BATTERY_STATUS_DISCHARGING:
                builder.append("discharging");
                break;
            case android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                builder.append("not charging");
                break;
            case android.os.BatteryManager.BATTERY_STATUS_UNKNOWN:
            default:
                builder.append("unknown");
                break;
        }

        builder.append(",level:");
        builder.append(tracker.level);

        builder.append(",plugged:");
        builder.append(tracker.plugged);

        builder.append(",plugType:");
        switch (tracker.plugType) {
            case 0:
                builder.append("battery");
                break;
            case android.os.BatteryManager.BATTERY_PLUGGED_AC:
                builder.append("A/C");
                break;
            case android.os.BatteryManager.BATTERY_PLUGGED_USB:
                builder.append("USB");
                break;
            case android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS:
                builder.append("wireless");
                break;
            default:
                builder.append("unknown");
                break;
        }

        builder.append(",test mode:");
        builder.append(tracker.testMode);

        builder.append("}");

        return builder.toString();
    }

}
