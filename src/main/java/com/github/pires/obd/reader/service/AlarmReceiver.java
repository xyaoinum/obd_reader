package com.github.pires.obd.reader.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;


/**
 * Created by yaoxiao on 7/20/16.
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        System.out.println("receiver onReceive: " + System.currentTimeMillis());
        Intent obdGatewayIntent = new Intent(context, ObdGatewayService.class);
        context.startService(obdGatewayIntent);
    }
}