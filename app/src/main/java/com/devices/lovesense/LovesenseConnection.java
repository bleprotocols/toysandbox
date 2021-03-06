package com.devices.lovesense;

import android.bluetooth.BluetoothDevice;

import com.ble.GattDeviceConnection;

public abstract class LovesenseConnection extends GattDeviceConnection {

    public LovesenseConnection() {
        super();
        this.setServiceUUID(LovensenseUUID.LOVESENSE_TOY_SERVICE)
                .setTxUUID(LovensenseUUID.LOVESENSE_BODY_TX_CHANNEL)
                .setRxUUID(LovensenseUUID.LOVESENSE_BODY_RX_CHANNEL);
    }


    @Override
    public void onDisconnect() {
        this.connect();
    }


    public String sendCommand(String message) {
        this.write(message.getBytes());

        return tryReadResponse();
    }


    private String tryReadResponse() {
        String response = "";

        for (int i = 0; i < 64; i++) {
            byte[] responseByte = this.read(1);
            if (responseByte.length == 0) {
                return response;
            }
            response = response + (char) responseByte[0];

            if (responseByte[0] == ';') {
                return response;
            }
        }
        return response;
    }

    @Override
    public boolean isDevice(BluetoothDevice result) {
        return result != null && result.getName().startsWith("LVS-");
    }
}
