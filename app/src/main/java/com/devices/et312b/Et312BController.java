package com.devices.et312b;

import android.bluetooth.BluetoothDevice;

import com.bluetooth.Controller;
import com.rpc.RpcFunction;
import com.rpc.RpcIntentHandler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static com.common.Common.sleep;
import static com.common.Common.wrap;
import static com.devices.et312b.Et312BConstants.COMMAND_TIMEOUT;
import static com.devices.et312b.Et312BConstants.FADE_DOWN_DELAY;
import static com.devices.et312b.Et312BConstants.FADE_UP_DELAY;
import static com.devices.et312b.Et312BConstants.MAX_LEVEL;
import static com.devices.et312b.Et312BConstants.MODE_OFFSET;
import static com.devices.et312b.Et312BConstants.PERCENTAGE_STEP;
import static com.devices.et312b.Et312BConstants.SMALL_TIMEOUT;
import static com.devices.et312b.Et312BConstants.mode_names;
import static com.devices.et312b.Et312BConstants.power_levels;
import static java.lang.Math.max;
import static java.lang.Math.min;

public class Et312BController extends Et312BPort implements Controller {
    private int chAPower = 0;
    private int chBPower = 0;

    private int chAGoalPower = 0;
    private int chBGoalPower = 0;


    private int multiAdjustLevel = 0;
    private int goalMultiAdjustLevel = 0;


    private byte chAMode = 0;
    private byte chBMode = 0;

    private byte powerLevel = 0;

    private boolean potentiometersEnabled = true;
    private RpcIntentHandler intentHandler = new RpcIntentHandler<>(Et312BController.class, this);
    private ScheduledExecutorService scheduler;

    @Override
    protected void onDisconnect() {

    }

    public Et312BController() {
        super();
        //Ping every second to keep connection alive


    }

    public synchronized void synchronizePower() {
        if (chAGoalPower != chAPower) {
            this.disablePotentiometers();

            int diff = chAGoalPower - chAPower;
            diff = diff > 0 ? min(diff, 10) : max(diff, -10);
            this.fadeParameter(plevel -> this.chAPower = plevel, Et312BConstants.ADC_REGISTERS.POT_A, this.chAPower, chAPower + diff);
        }

        if (chBGoalPower != chBPower) {
            this.disablePotentiometers();

            int diff = chBGoalPower - chBPower;
            diff = diff > 0 ? min(diff, 10) : max(diff, -10);
            this.fadeParameter(plevel -> this.chBPower = plevel, Et312BConstants.ADC_REGISTERS.POT_B, this.chBPower, chBPower + diff);
        }
    }

    public synchronized void synchronizeMultiAdjust() {
        if (multiAdjustLevel != goalMultiAdjustLevel) {
            this.disablePotentiometers();

            int diff = goalMultiAdjustLevel - multiAdjustLevel;
            diff = diff > 0 ? min(diff, 10) : max(diff, -10);
            this.fadeParameter(ma -> this.multiAdjustLevel = ma, Et312BConstants.ADC_REGISTERS.MULTI_ADJUST, this.multiAdjustLevel, multiAdjustLevel + diff);
        }
    }

    private synchronized void ping() {
        for (int i = 0; i < 4096; i++) if (this.read(1, SMALL_TIMEOUT).length==0) break;
        readMem(new byte[1], Et312BConstants.FLASH_MEMORY.BOX_MODEL, true, SMALL_TIMEOUT);
    }

    @RpcFunction
    public synchronized void setPower(int chAPower, int chBPower) {
        if (chAPower > MAX_LEVEL || chBPower > MAX_LEVEL) {
            getLogger().println("   setPower: powerlevel cannot be higher than 100");
            return;
        }

        chAGoalPower = chAPower;
        chBGoalPower = chBPower;
    }


    @RpcFunction
    public synchronized void setPowerLevel(String level) {
        byte level_byte = 1;

        if (null == level) {
            return;
        }

        for (byte i = 0; i < power_levels.length; i++) {
            if (level.compareTo(power_levels[i]) == 0)
                level_byte = (byte) (i + 1);
        }

        if (level_byte == this.powerLevel)
            return;

        getLogger().println("   setPowerLevel(\"" + level + "\"): setting power level to: " + (int) level_byte);
        this.writeMem(Et312BConstants.RAM.POWER_LEVEL, (byte) level_byte);

        sleep(40);
        this.powerLevel = level_byte;
    }


    @RpcFunction
    public synchronized void setMultiAdjust(int level) {
        if (level > 100) {
            getLogger().println("   setMultiAdjust: level cannot be higher than 100");
            return;
        }

        goalMultiAdjustLevel = level;
    }


    private void fadeParameter(IntConsumer updateFunc, short parameter, int from, int to) {
        int step = to > from ? 1 : -1;
        int fade = to > from ? FADE_UP_DELAY : FADE_DOWN_DELAY;

        if (from == to) {
            return;
        }

        for (int i = from; i != to; i += step) {
            long start = System.currentTimeMillis();
            if(!writeMem(parameter, (byte) (i * PERCENTAGE_STEP))){
                return;
            }
            updateFunc.accept(i);
            sleep(fade - (System.currentTimeMillis() - start));
        }

        writeMem(parameter, (byte) (to * PERCENTAGE_STEP));
        updateFunc.accept(to);
        sleep(fade);
    }

    @RpcFunction
    public synchronized void setMode(String channel_a_mode, String channel_b_mode) {
        if (null == channel_a_mode || null == channel_b_mode) {
            return;
        }

        byte byteA = modeToByte(channel_a_mode);
        byte byteB = modeToByte(channel_b_mode);

        if (byteA == this.chAMode && byteB == this.chBMode)
            return;

        //get mode from box and see if it matches..
        getLogger().println("   setMode(\"" + channel_a_mode + "\",\"" + channel_b_mode + "\"): setting mode.");
        this.chAMode = byteA;
        this.chBMode = byteB;

        if (byteA == byteB) {
            this.writeMem(Et312BConstants.RAM.CURRENT_MODE, (byte) (byteA + Et312BConstants.MODE_OFFSET));
            this.writeMem(Et312BConstants.RAM.COMMAND_1, Et312BConstants.box_command.EXIT_MENU);
            this.writeMem(Et312BConstants.RAM.COMMAND_2, Et312BConstants.box_command.SET_MODE);
        } else {
            this.writeMem(Et312BConstants.RAM.CURRENT_MODE, modeToByte("Split"));
            this.writeMem(Et312BConstants.RAM.SPLIT_MODE_CHANNEL_A, (byte) (byteA + Et312BConstants.MODE_OFFSET));
            this.writeMem(Et312BConstants.RAM.SPLIT_MODE_CHANNEL_B, (byte) (byteB + Et312BConstants.MODE_OFFSET));
            this.writeMem(Et312BConstants.RAM.COMMAND_1, Et312BConstants.box_command.EXIT_MENU);
            this.writeMem(Et312BConstants.RAM.COMMAND_2, Et312BConstants.box_command.SET_MODE);
        }

        sleep(50);//wait for the commands to run
    }

    private void readParameter(Consumer<Byte> setter, int parameter) {
        byte[] buffer = {0};
        if (readMem(buffer, parameter, true, COMMAND_TIMEOUT)) {
            setter.accept(buffer[0]);
        }
    }

    private void getMode() {
        byte[] mem = new byte[1];
        readMem(mem, Et312BConstants.RAM.CURRENT_MODE, false, COMMAND_TIMEOUT);
        if (mem[0] == (byte) (modeToByte("Split") + MODE_OFFSET)) {
            readMem(mem, Et312BConstants.RAM.SPLIT_MODE_CHANNEL_A, false, COMMAND_TIMEOUT);
            this.chAMode = (byte) ((mem[0] & 0xFF) - Et312BConstants.MODE_OFFSET);
            readMem(mem, Et312BConstants.RAM.SPLIT_MODE_CHANNEL_B, false, COMMAND_TIMEOUT);
            this.chBMode = (byte) ((mem[0] & 0xFF) - Et312BConstants.MODE_OFFSET);
        } else {
            this.chAMode = (byte) ((mem[0] & 0xFF) - Et312BConstants.MODE_OFFSET);
            this.chBMode = this.chAMode;
        }
    }

    private byte modeToByte(String mode) {
        for (int i = 0; i < mode_names.length; i++) {
            if (mode.compareTo(mode_names[i]) == 0)
                return (byte) (i);
        }

        return 0;
    }

    @RpcFunction
    public synchronized void writeMessage(String message) {
        if (message.length() > 16) {
            getLogger().println("   writeMessage: message cannot be longer than 16 characters");
            return;
        }

        getLogger().println("   writeMessage(\"" + message + "\"): writing message.");
        // message.resze(32, ' ');
        this.disablePotentiometers();

        for (int c = 0; c < message.length(); c++) {
            this.writeMem(Et312BConstants.RAM.WRITE_LCD_PARAM, message.getBytes()[c]);
            this.writeMem(Et312BConstants.RAM.WRITE_LCD_POS, (byte) c);
            this.boxCommand(Et312BConstants.box_command.WRITE_LCD);
        }
    }

    public void disablePotentiometers() {
        if (this.potentiometersEnabled) {
            this.writeMem(Et312BConstants.CPU_REGISTERS.SYSTEM_FLAGS, (byte) (Et312BConstants.system_flags.ADC_DISABLED | Et312BConstants.system_flags.MULTI_ADJUST_DISABLE));  //disable front potentiometers
            //this.writeMem(Et312BConstants.RAM.OUTPUT_MODE, (byte) (Et312BConstants.output_mode.DISABLE_BUTTONS | Et312BConstants.output_mode.PHASE_1)); //disable sub's control over box
            this.potentiometersEnabled = false;
        }
    }

    public void enablePotentiometers() {
        if (!this.potentiometersEnabled) {
            this.writeMem(Et312BConstants.CPU_REGISTERS.SYSTEM_FLAGS, (byte) 0); //enable front potentiometers
            //this.writeMem(Et312BConstants.RAM.OUTPUT_MODE, Et312BConstants.output_mode.PHASE_1); //enable switches
            this.potentiometersEnabled = true;
        }
    }

    //Synchronize our box-state on connect
    @Override
    synchronized void onBoxConnected() {
        getLogger().println("ErosTek box reconnected.");

        this.potentiometersEnabled = true;

        int prevAMode = this.chAMode, prevBMode = this.chBMode;
        getMode();
        setMode(mode_names[prevAMode], mode_names[prevBMode]);

        //force reset the power
        this.chAPower = max(this.chAPower - 1, 0);
        this.chBPower = max(this.chBPower - 1, 0);

        String prevPowerLevel = power_levels[this.powerLevel - 1];
        //readParameter(x -> this.powerLevel = x, Et312BConstants.RAM.POWER_LEVEL);
        setPowerLevel(prevPowerLevel);

        //force reset multi=adjust
        this.multiAdjustLevel = max(this.multiAdjustLevel - 1, 0);
    }
    //test with conflicting commands.


    @Override
    public boolean isDevice(BluetoothDevice result) {
        return result.getName().equals("ET312B");
    }


    @Override
    public synchronized void startControlling() {
        intentHandler.registerHandler(getContext(), "erostek_control");

        this.connect();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        //   scheduler.scheduleAtFixedRate(wrap(() -> ping()), 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(wrap(() -> synchronizePower()), 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(wrap(() -> synchronizeMultiAdjust()), 1, 2, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void stopControlling() {
        intentHandler.unregisterHandler(getContext());
        scheduler.shutdownNow();
        this.close();
    }

    @Override
    public String getTypeName() {
        return "ET312B";
    }

}
