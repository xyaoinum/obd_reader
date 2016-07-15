package com.github.pires.obd.reader.shared;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class BluetoothPreferences {

    public static String getPairedAddress(Context context) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        String paired_address = settings.getString("paired_address", null);
        return paired_address;
    }

    public static void setPairedAddress(Context context, String paired_address) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("paired_address", paired_address);
        editor.commit();
    }

}