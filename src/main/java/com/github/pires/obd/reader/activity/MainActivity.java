package com.github.pires.obd.reader.activity;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import com.github.pires.obd.reader.R;
import com.github.pires.obd.reader.service.AlarmReceiver;
import com.github.pires.obd.reader.service.ObdGatewayService;
import com.github.pires.obd.reader.shared.BluetoothPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Set;

import roboguice.RoboGuice;
import roboguice.activity.RoboActivity;
import roboguice.inject.ContentView;
import roboguice.inject.InjectView;


@ContentView(R.layout.main)
public class MainActivity extends RoboActivity {

    private static final int SELECT_OBD_DEVICE = 1;

    private AlarmManager alarmMgr;
    private PendingIntent alarmIntent;

    @InjectView(R.id.vehicle_speed_value)
    private TextView vehicleSpeedTextView;
    @InjectView(R.id.engine_rpm_value)
    private TextView engineRPMTextView;
    @InjectView(R.id.status_value)
    private TextView statusTextView;

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String vehicle_speed = intent.getStringExtra("Vehicle Speed");
            String engine_rpm = intent.getStringExtra("Engine RPM");
            String status = intent.getStringExtra("status");

            if(status != null) {
                final String remoteDevice = BluetoothPreferences.getPairedAddress(getApplicationContext());
                if(remoteDevice == null){
                    statusTextView.setText("Disconnected");
                }else{
                    statusTextView.setText(status);
                }
            }

            if(vehicle_speed != null){
                vehicleSpeedTextView.setText(vehicle_speed);
            }

            if(engine_rpm != null){
                engineRPMTextView.setText(engine_rpm);
            }
        }
    };


    static {
        RoboGuice.setUseAnnotationDatabases(false);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        alarmMgr = (AlarmManager)getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(getApplicationContext(), AlarmReceiver.class);
        alarmIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, intent, 0);
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(),
                1000 * 10, alarmIntent);

    }

    @Override
    public void onResume() {
        super.onResume();

        registerReceiver(broadcastReceiver, new IntentFilter(ObdGatewayService.BROADCAST_ACTION));

        // get Bluetooth device
        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null && !btAdapter.isEnabled()) {
            btAdapter.enable();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(broadcastReceiver);
    }


    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, SELECT_OBD_DEVICE, 0, getString(R.string.menu_select_obd_device));
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case SELECT_OBD_DEVICE:
                obdSelectionDialog();
                return true;
        }
        return false;
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
                        statusTextView.setText("Connecting...");
                        stopService(new Intent(getApplicationContext(), ObdGatewayService.class));
                        dialog.cancel();
                    }
                });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

}
