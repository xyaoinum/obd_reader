package com.github.pires.obd.reader.activity;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.github.pires.obd.commands.SpeedCommand;
import com.github.pires.obd.commands.engine.RPMCommand;
import com.github.pires.obd.enums.AvailableCommandNames;
import com.github.pires.obd.reader.R;
import com.github.pires.obd.reader.io.ObdCommandJob;
import com.github.pires.obd.reader.io.ObdGatewayService;
import com.github.pires.obd.reader.shared.BluetoothPreferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

import roboguice.RoboGuice;
import roboguice.activity.RoboActivity;
import roboguice.inject.ContentView;
import roboguice.inject.InjectView;

@ContentView(R.layout.main)
public class MainActivity extends RoboActivity {

    private static final int START_LIVE_DATA = 1;
    private static final int STOP_LIVE_DATA = 2;
    private static final int SELECT_OBD_DEVICE = 3;
    private static boolean bluetoothDefaultIsEnable = false;

    static {
        RoboGuice.setUseAnnotationDatabases(false);
    }

    @InjectView(R.id.BT_STATUS)
    private TextView btStatusTextView;
    @InjectView(R.id.OBD_STATUS)
    private TextView obdStatusTextView;
    @InjectView(R.id.vehicle_view)
    private LinearLayout vv;
    @InjectView(R.id.data_table)
    private TableLayout tl;

    private PowerManager powerManager;
    private boolean isServiceBound;
    private ObdGatewayService service;
    private final Runnable mQueueCommands = new Runnable() {
        public void run() {
            if (service != null && service.isRunning() && service.queueEmpty()) {
                queueCommands();
            }
            // run again in 2s
            new Handler().postDelayed(mQueueCommands, 2000);
        }
    };
    private PowerManager.WakeLock wakeLock = null;
    private boolean preRequisites = true;
    private ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            isServiceBound = true;
            service = ((ObdGatewayService.ObdGatewayServiceBinder) binder).getService();
            service.setContext(MainActivity.this);
            try {
                service.startService();
                if (preRequisites)
                    btStatusTextView.setText(getString(R.string.status_bluetooth_connected));
            } catch (IOException ioe) {
                btStatusTextView.setText(getString(R.string.status_bluetooth_error_connecting));
                doUnbindService();
            }
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            return super.clone();
        }

        // This method is *only* called when the connection to the service is lost unexpectedly
        // and *not* when the client unbinds (http://developer.android.com/guide/components/bound-services.html)
        // So the isServiceBound attribute should also be set to false when we unbind from the service.
        @Override
        public void onServiceDisconnected(ComponentName className) {
            isServiceBound = false;
        }
    };

    public static String LookUpCommand(String txt) {
        for (AvailableCommandNames item : AvailableCommandNames.values()) {
            if (item.getValue().equals(txt)) return item.name();
        }
        return txt;
    }

    public void stateUpdate(final ObdCommandJob job) {

        final String cmdName = job.getCommand().getName();
        String cmdResult = "";
        final String cmdID = LookUpCommand(cmdName);

        if (job.getState().equals(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR)) {
            cmdResult = job.getCommand().getResult();
            if (cmdResult != null && isServiceBound) {
                obdStatusTextView.setText(cmdResult.toLowerCase());
            }
        } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.BROKEN_PIPE)) {
            if (isServiceBound)
                stopLiveData();
        } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.NOT_SUPPORTED)) {
            cmdResult = getString(R.string.status_obd_no_support);
        } else {
            cmdResult = job.getCommand().getFormattedResult();
            if(isServiceBound)
                obdStatusTextView.setText(getString(R.string.status_obd_data));
        }

        if (vv.findViewWithTag(cmdID) != null) {
            TextView existingTV = (TextView) vv.findViewWithTag(cmdID);
            existingTV.setText(cmdResult);
        } else {
            addTableRow(cmdID, cmdName, cmdResult);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);;
        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) {
            bluetoothDefaultIsEnable = btAdapter.isEnabled();
        }

        obdStatusTextView.setText(getString(R.string.status_obd_disconnected));
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        releaseWakeLockIfHeld();
        if (isServiceBound) {
            doUnbindService();
        }

        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null && btAdapter.isEnabled() && !bluetoothDefaultIsEnable)
            btAdapter.disable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseWakeLockIfHeld();
    }

    /**
     * If lock is held, release. Lock will be held when the service is running.
     */
    private void releaseWakeLockIfHeld() {
        if (wakeLock.isHeld())
            wakeLock.release();
    }

    protected void onResume() {
        super.onResume();
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,
                "ObdReader");

        // get Bluetooth device
        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();

        preRequisites = btAdapter != null && btAdapter.isEnabled();
        if (!preRequisites) {
            preRequisites = btAdapter != null && btAdapter.enable();
        }

        if(!preRequisites){
            btStatusTextView.setText(getString(R.string.status_bluetooth_disabled));
        }else {
            btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
        }
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, START_LIVE_DATA, 0, getString(R.string.menu_start_live_data));
        menu.add(0, STOP_LIVE_DATA, 0, getString(R.string.menu_stop_live_data));
        menu.add(0, SELECT_OBD_DEVICE, 0, getString(R.string.menu_select_obd_device));
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case START_LIVE_DATA:
                startLiveData();
                return true;
            case STOP_LIVE_DATA:
                stopLiveData();
                return true;
            case SELECT_OBD_DEVICE:
                obdSelectionDialog();
                return true;
        }
        return false;
    }

    private void startLiveData() {
        tl.removeAllViews(); //start fresh
        doBindService();

        // start command execution
        new Handler().post(mQueueCommands);

        // screen won't turn off until wakeLock.release()
        wakeLock.acquire();
    }

    private void stopLiveData() {
        doUnbindService();
        releaseWakeLockIfHeld();
    }

    private void obdSelectionDialog() {

        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();

        if (btAdapter == null || !btAdapter.isEnabled()) {
            Toast.makeText(this,
                    "Please enable Bluetooth.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
        if (pairedDevices.size() == 0){
            Toast.makeText(this,
                    "Please pair with the OBD device in Android Bluetooth Settings.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String cur_bt_address = BluetoothPreferences.getPairedAddress(getApplicationContext());
        int cur_cursor = -1;

        final ArrayList<String> pairedDeviceFullNames = new ArrayList<>();
        final ArrayList<String> pairedDeviceAddresses = new ArrayList<>();

        int i = 0;
        for(BluetoothDevice device : pairedDevices) {
            pairedDeviceFullNames.add(device.getName()+" ("+device.getAddress()+")");
            pairedDeviceAddresses.add(device.getAddress());

            if(device.getAddress().equals(cur_bt_address)) {
                cur_cursor = i;
            }
            i++;
        }

        CharSequence[] cs = pairedDeviceFullNames.toArray(new CharSequence[pairedDeviceFullNames.size()]);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle("Select OBD Device")
                .setSingleChoiceItems(cs, cur_cursor, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        BluetoothPreferences.setPairedAddress(getApplicationContext(),pairedDeviceAddresses.get(which));
                    }
                });
        AlertDialog dialog = builder.create();
        dialog.show();
    }


    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem startItem = menu.findItem(START_LIVE_DATA);
        MenuItem stopItem = menu.findItem(STOP_LIVE_DATA);
        MenuItem obdItem = menu.findItem(SELECT_OBD_DEVICE);

        if (service != null && service.isRunning()) {
            startItem.setEnabled(false);
            stopItem.setEnabled(true);
            obdItem.setEnabled(false);
        } else {
            startItem.setEnabled(true);
            stopItem.setEnabled(false);
            obdItem.setEnabled(true);
        }

        return true;
    }

    private void addTableRow(String id, String key, String val) {

        TableRow tr = new TableRow(this);


        MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);

        tr.setLayoutParams(params);

        TextView name = new TextView(this);
        name.setGravity(Gravity.RIGHT);
        name.setText(key + ": ");
        TextView value = new TextView(this);
        value.setGravity(Gravity.LEFT);
        value.setText(val);
        value.setTag(id);
        tr.addView(name);
        tr.addView(value);
        tl.addView(tr, params);
    }

    private void queueCommands() {
        if (isServiceBound) {
            service.queueJob(new ObdCommandJob(new SpeedCommand()));
            service.queueJob(new ObdCommandJob(new RPMCommand()));
        }
    }

    private void doBindService() {
        if (!isServiceBound) {
            if (preRequisites) {
                btStatusTextView.setText(getString(R.string.status_bluetooth_connecting));
                Intent serviceIntent = new Intent(this, ObdGatewayService.class);
                bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
            }
        }
    }

    private void doUnbindService() {
        if (isServiceBound) {
            if (service.isRunning()) {
                service.stopService();
                if (preRequisites)
                    btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
            }
            unbindService(serviceConn);
            isServiceBound = false;
            obdStatusTextView.setText(getString(R.string.status_obd_disconnected));
        }
    }

}
