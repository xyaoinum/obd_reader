package com.github.pires.obd.reader.service;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;

import com.github.pires.obd.commands.SpeedCommand;
import com.github.pires.obd.commands.engine.RPMCommand;
import com.github.pires.obd.commands.protocol.EchoOffCommand;
import com.github.pires.obd.commands.protocol.LineFeedOffCommand;
import com.github.pires.obd.commands.protocol.ObdResetCommand;
import com.github.pires.obd.commands.protocol.SelectProtocolCommand;
import com.github.pires.obd.commands.protocol.TimeoutCommand;
import com.github.pires.obd.enums.ObdProtocols;
import com.github.pires.obd.exceptions.UnsupportedCommandException;
import com.github.pires.obd.reader.model.ObdCommandJob;
import com.github.pires.obd.reader.shared.BluetoothPreferences;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ObdGatewayService extends Service {
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final IBinder binder = new Binder();
    private static boolean isRunning = false;
    private BlockingQueue<ObdCommandJob> jobsQueue = new LinkedBlockingQueue<>();
    private BluetoothDevice dev = null;
    private BluetoothSocket sock = null;
    Intent obdGatewayServiceBroadcastIntent;
    public static final String BROADCAST_ACTION = "ObdGatewayServiceBroadcast";

    private final Runnable mQueueCommands = new Runnable() {
        public void run() {
            System.out.println("entering run");

            if(!isRunning) {
                return;
            }

            if (jobsQueue.isEmpty()) {
                queueCommands();
            }
            // run again in 2s
            new Handler().postDelayed(mQueueCommands, 2000);
        }
    };

    private void queueCommands() {
            queueJob(new ObdCommandJob(new SpeedCommand()));
            queueJob(new ObdCommandJob(new RPMCommand()));
    }

    Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
            try {
                executeQueue();
            } catch (InterruptedException e) {
                t.interrupt();
            }
        }
    });

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {

        System.out.println("onCreate Start");


        super.onCreate();
        obdGatewayServiceBroadcastIntent = new Intent(BROADCAST_ACTION);
        try {
            System.out.println("Start service!");
            startService();
        } catch (IOException e) {
            System.out.println("exception start service, stop!");
            stopSelf();
            return;
        }
        t.start();
        System.out.println("onCreate End");

    }

    @Override
    public void onDestroy() {

        System.out.println("onDestroy Start");

        jobsQueue.clear();
        isRunning = false;
        if (sock != null)
            // close socket
            try {
                sock.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        t.interrupt();

        obdGatewayServiceBroadcastIntent.putExtra("Vehicle Speed", "N/A");
        obdGatewayServiceBroadcastIntent.putExtra("Engine RPM", "N/A");
        obdGatewayServiceBroadcastIntent.putExtra("status", "Connecting...");
        sendBroadcast(obdGatewayServiceBroadcastIntent);

        super.onDestroy();

        System.out.println("onDestroy end");

    }

    public void queueJob(ObdCommandJob job) {
        try {
            jobsQueue.put(job);
        } catch (InterruptedException e) {
            job.setState(ObdCommandJob.ObdCommandJobState.QUEUE_ERROR);
        }

    }

    private void executeQueue() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {

            ObdCommandJob job = null;
            try {
                job = jobsQueue.take();

                if (job.getState().equals(ObdCommandJob.ObdCommandJobState.NEW)) {
                    job.setState(ObdCommandJob.ObdCommandJobState.RUNNING);
                    if (sock.isConnected()) {
                        job.getCommand().run(sock.getInputStream(), sock.getOutputStream());
                    } else {
                        job.setState(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR);
                    }
                }
            } catch (InterruptedException i) {
                Thread.currentThread().interrupt();
            } catch (UnsupportedCommandException u) {
                if (job != null) {
                    job.setState(ObdCommandJob.ObdCommandJobState.NOT_SUPPORTED);
                }
            } catch (IOException io) {
                if (job != null) {
                    if(io.getMessage().contains("Broken pipe")) {
                        job.setState(ObdCommandJob.ObdCommandJobState.BROKEN_PIPE);
                        if (isRunning) {
                            stopSelf();
                        }
                    }
                    else
                        job.setState(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR);
                }
            } catch (Exception e) {
                if (job != null) {
                    job.setState(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR);
                }
            }

            if (job != null) {
                System.out.println(job.getCommand().getName() + " " + job.getCommand().getFormattedResult());
                obdGatewayServiceBroadcastIntent.putExtra("status", "Connected");
                obdGatewayServiceBroadcastIntent.putExtra(job.getCommand().getName(), job.getCommand().getFormattedResult());
                sendBroadcast(obdGatewayServiceBroadcastIntent);
            }
        }
    }

    public void startService() throws IOException {
        final String remoteDevice = BluetoothPreferences.getPairedAddress(getApplicationContext());
        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (remoteDevice == null || "".equals(remoteDevice) || btAdapter == null || !btAdapter.isEnabled()) {
            stopSelf();
            throw new IOException();
        } else {

            obdGatewayServiceBroadcastIntent.putExtra("status", "Connecting...");
            sendBroadcast(obdGatewayServiceBroadcastIntent);

            dev = btAdapter.getRemoteDevice(remoteDevice);
            btAdapter.cancelDiscovery();
            try {
                isRunning = true;
                sock = dev.createRfcommSocketToServiceRecord(MY_UUID);
                sock.connect();
                try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
                queueJob(new ObdCommandJob(new ObdResetCommand()));
                queueJob(new ObdCommandJob(new EchoOffCommand()));
                queueJob(new ObdCommandJob(new EchoOffCommand()));
                queueJob(new ObdCommandJob(new LineFeedOffCommand()));
                queueJob(new ObdCommandJob(new TimeoutCommand(50)));
                queueJob(new ObdCommandJob(new SelectProtocolCommand(ObdProtocols.valueOf("AUTO"))));
            } catch (IOException e) {
                throw new IOException();
            }
            new Handler().post(mQueueCommands);
        }
    }

}
