/*
 * Copyright (c) 2020 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.middleatlantic.select;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.SocketCommunicator;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.ConnectException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import static com.avispl.symphony.dal.communicator.middleatlantic.select.MiddleAtlanticSelectControls.*;
import static java.util.concurrent.CompletableFuture.runAsync;

/**
 * An implementation of SocketCommunicator to provide TCP communication with Middle Atlantic Select devices
 * Controlling functionality:
 *      - Outlets switch on/off
 *      - On/Off Sequence initiation
 * @version 1.0
 * @author Maksym.Rossiytsev
 */
public class MiddleAtlanticPowerUnitCommunicator extends SocketCommunicator implements Monitorable, Controller {
    private static final int CONTROLS_COOLDOWN_PERIOD = 5000;
    private static final String CONTROL_PROTOCOL_STATUS_PROPERTY = "ControlProtocolStatus";
    private static final String AVAILABLE = "AVAILABLE";
    private static final String UNAVAILABLE = "UNAVAILABLE";

    private final ReentrantLock controlOperationsLock = new ReentrantLock();
    private ExtendedStatistics localStatistics;
    private Exception latestException = null;
    private long latestControlTimestamp;
    /**
     * To avoid timeout errors, caused by the unavailability of the control protocol, all polling-dependent communication operations (monitoring)
     * should be performed asynchronously. This executor service executes such operations.
     */
    private ExecutorService executorService;
    /**
     * */
    private CompletableFuture dataCollector;

    public MiddleAtlanticPowerUnitCommunicator(){
        super();
        this.setCommandErrorList(Arrays.asList(String.valueOf(BAD_CRC), String.valueOf(BAD_LENGTH),
                String.valueOf(BAD_ESCAPE), String.valueOf(PREVIOUS_CMD_INVALID),
                String.valueOf(PREVIOUS_SUBCMD_INVALID), String.valueOf(PREVIOUS_BYTE_COUNT_INVALID),
                String.valueOf(DATA_BYTES_INVALID), String.valueOf(BAD_CREDS),
                String.valueOf(UNKNOWN), String.valueOf(ACCESS_DENIED)));

        this.setCommandSuccessList(Collections.singletonList("\n"));
    }

    @Override
    protected void internalInit() throws Exception {
        executorService = Executors.newFixedThreadPool(1);
        super.internalInit();
    }

    @Override
    protected void internalDestroy() {
        try {
            dataCollector.cancel(true);
            executorService.shutdownNow();
            disconnect();
        } catch (Exception e) {
            logger.warn("Unable to end the TCP connection.", e);
        } finally {
            super.internalDestroy();
        }
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String property = controllableProperty.getProperty();
        String value = String.valueOf(controllableProperty.getValue());

        if(logger.isInfoEnabled()){
            logger.debug(String.format("Received controllable property '%s' with value '%s'", property, value));
        }
        refreshTCPSession();
        controlOperationsLock.lock();
        try {
            if(property.equals(SEQUENCE_DOWN_COMMAND_NAME)){
                boolean result = sendSequencingCommand(buildSequenceCommand(DOWN_SEQUENCE));
                if(result) {
                    updateLocalSequencingStatistics(DOWN_SEQUENCE);
                }
            } else if (property.equals(SEQUENCE_UP_COMMAND_NAME)){
                boolean result = sendSequencingCommand(buildSequenceCommand(UP_SEQUENCE));
                if(result){
                    updateLocalSequencingStatistics(UP_SEQUENCE);
                }
            } else if(property.matches(".+?-\\s\\d")){
                byte outletNumber = Byte.parseByte(property.substring(property.lastIndexOf(" ") + 1));
                byte response;
                switch (value) {
                    case "1":
                        response = sendControlCommand(buildWriteOutletStatusCommandBytes(outletNumber, (byte) 0x01));
                        updateLocalOutletState(property, "1", String.valueOf(response));
                        break;
                    case "0":
                        response = sendControlCommand(buildWriteOutletStatusCommandBytes(outletNumber, (byte) 0x00));
                        updateLocalOutletState(property, "0", String.valueOf(response));
                        break;
                    default:
                        break;
                }
            }
        } finally {
            controlOperationsLock.unlock();
            dataCollector.cancel(true);
            executorService.shutdownNow();
            try {
                disconnect();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> controllablePropertyList) throws Exception {
        if (CollectionUtils.isEmpty(controllablePropertyList)) {
            throw new IllegalArgumentException("NetGearCommunicator: Controllable properties cannot be null or empty");
        }

        for(ControllableProperty controllableProperty: controllablePropertyList){
            controlProperty(controllableProperty);
        }
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        ExtendedStatistics statistics = new ExtendedStatistics();
        Map<String, String> outletsStatistics = new HashMap<>();
        List<AdvancedControllableProperty> controllableProperties = new ArrayList<>();
        statistics.setStatistics(outletsStatistics);
        statistics.setControllableProperties(controllableProperties);
        if (localStatistics == null) {
            localStatistics = new ExtendedStatistics();
            localStatistics.setStatistics(new HashMap<>());
            localStatistics.setControllableProperties(new ArrayList<>());
        }
        if(isValidControlCoolDown() && localStatistics != null){
            if (logger.isDebugEnabled()) {
                logger.debug("Device is occupied by control operations. Skipping monitoring statistics retrieval.");
            }
            outletsStatistics.putAll(localStatistics.getStatistics());
            controllableProperties.addAll(localStatistics.getControllableProperties());
            return Collections.singletonList(statistics);
        }

        if (executorService.isShutdown() || executorService.isTerminated()) {
            // If executor service was shut down or terminated prior to this point
            executorService = Executors.newFixedThreadPool(1);
        }
        if (dataCollector != null && !dataCollector.isDone()) {
            // If previous task is delayed and is not over
            dataCollector.cancel(true);
            dataCollector = null;
        }
        dataCollector = runAsync(() -> {
            controlOperationsLock.lock();
            Map<String, String> internalOutletStatistics = localStatistics.getStatistics();
            List<AdvancedControllableProperty> internalControllableProperties = localStatistics.getControllableProperties();
            try {
                if (logger.isDebugEnabled()) {
                    logger.debug("Device is not occupied by control operations. Retrieving monitoring statistics.");
                }
                refreshTCPSession();

                fetchOutletsData(internalOutletStatistics, internalControllableProperties);
                internalOutletStatistics.put(CONTROL_PROTOCOL_STATUS_PROPERTY, AVAILABLE);
                latestException = null;
            } catch (Exception ce) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Exception white collecting device data.", ce);
                }
                if (ce instanceof ConnectException) {
                    String message = ce.getMessage();
                    if (!StringUtils.isEmpty(message) && message.contains("Connection timed out")) {
                        logger.warn("Connection timed out: Unable to connect to the device, TCP protocol is occupied.");
                        internalOutletStatistics.put(CONTROL_PROTOCOL_STATUS_PROPERTY, UNAVAILABLE);
                        localStatistics.getControllableProperties().clear();
                    } else {
                        latestException = ce;
                    }
                }
            } finally {
                controlOperationsLock.unlock();
                try {
                    disconnect();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }, executorService);

        if (latestException != null) {
            throw latestException;
        }
        outletsStatistics.putAll(localStatistics.getStatistics());
        controllableProperties.addAll(localStatistics.getControllableProperties());
        return Collections.singletonList(statistics);
    }

    private void refreshTCPSession() throws Exception {
        if (!sendPing()) {
            if(logger.isDebugEnabled()) {
                logger.debug("Logging in on an invalid ping response.");
            }
            if(!login()){
                throw new RuntimeException("Unable to refresh TCP session. Login rejected.");
            }
        }
    }

    /**
     * Start character - always 0xFE
     * Length - length of bytes 2 to x
     * Destination - always 0x00
     * Command - 0x02 - Login
     * Subcommand - 0x01 - Set
     * Data - Username|Password
     * Checksum - sum of all previous values + bitwise & 0x007f
     * Tail - 0xFF
     *
     * Login response:
     *              Envelope: 4 bytes at the beginning, 2 bytes at the end
     *              0x00: Rejected
     *              0x01: Accepted
     *
     * We can either make a logged in socket session live for an indefinite amount of time, which would also require an extra
     * thread to keep it alive (replying to ping messages from the device) or we can send
     * extra 11+ bytes of data (7 bytes of envelope + "user" for username) on every statistics collection event.
     * @return boolean value indicating the login success
     * @throws Exception during TCP socket communication
     */
    private boolean login() throws Exception {
        if(getConnectionStatus().getConnectionState().isConnected()){
            disconnect();
        }
        String data = String.format("%s|%s", getLogin(), getPassword());
        byte[] loginRequest = buildLoginCommand(data);
        byte[] response = send(loginRequest);
        if(response.length < 6){
            throw new RuntimeException("TCP login failed.");
        }
        if(logger.isDebugEnabled()){
            logger.debug("TCP login succeeded with response code: " + response[5]);
        }
        return response[5] == 0x01;
    }

    /**
     * Build login command
     * @param data login|password value
     * @return byte[] request with a proper checksum and contents
     * */
    private byte[] buildLoginCommand(String data){
        byte[] bytes = new byte[data.length() + 7]; // 7 bytes is an envelope
        int checksum = HEAD + COMMAND_LOGIN + SUBCOMMAND_SET + data.length() + 3;

        bytes[0] = HEAD;
        bytes[1] = (byte) (data.length() + 3);
        bytes[2] = DESTINATION;
        bytes[3] = COMMAND_LOGIN;
        bytes[4] = SUBCOMMAND_SET;

        int i = 5;
        for(char b: data.toCharArray()){
            bytes[i] = (byte) b;
            checksum += b;
            i++;
        }
        bytes[i] = (byte) (checksum & 0x7f);
        bytes[i + 1] = TAIL;
        return bytes;
    }

    /***
     * Start character - 0xFE
     * Length - 0x09
     * Destination - 0x00
     * Command - 0x20: Power Outlets
     *           0x30: Dry Contact
     * Subcommand - 0x01: Set State
     * Outlet/Contact - 0x01 to 0x10 (1 to 16 for outlets)
     *                  0x1 to 0x08 (1 to 8 for contacts)
     * Desired State - 0x00 - OFF
     *                 0x01 - ON
     *                 0x02 - Cycle (set time in next field)
     *                 0x03 - Not Controllable (response only)
     * Cycle time - Cycle time .
     *              Range is 0000 to 3600 seconds .
     *              Sent as ACII characters (whereas 0 is 0x30, 9 is 0x39, etc)     .
     *              example: 0000 seconds would be encoded as 0x30303030     .
     *              example: 3600 seconds would be encoded as 0x33363030
     *
     *              not used for on/off cmds. Send 0000 (0x30303030)
     * Checksum -   Sum of all previous bytes (incl header)
     *              masked with 0x007f
     * Tail - 0xFF
     *
     * @param outlet outlet number
     * @param state outlet state
     * @return outlet state change command
     */
    private byte[] buildWriteOutletStatusCommandBytes(byte outlet, byte state){
        byte[] bytes = new byte[13];

        prepareWriteCommand(bytes, outlet, (byte) 0x09, POWER_OUTLET, state);
        bytes[10] = ZERO_DELAY;

        int checksum = 0;
        for(byte b: bytes){
            checksum += b;
        }
        checksum &= 0x007f;

        bytes[11] = (byte) checksum;
        bytes[12] = TAIL;
        return bytes;
    }

    /**
     * Build ON/OFF sequence command
     * Sequence: 0x01 - sequence up
     *           0x03 - sequence down
     * @param sequence byte value of a sequence 0x01|0x03
     * @return byte[] command for the sequence launch
     */
    private byte[] buildSequenceCommand(byte sequence){
        byte[] bytes = new byte[12];
        prepareWriteCommand(bytes, sequence, (byte) 0x08, POWER_SEQUENCE, ZERO_DELAY);

        int checksum = 0;
        for(byte b: bytes){
            checksum += b;
        }
        checksum &= 0x007f;

        bytes[10] = (byte) checksum;
        bytes[11] = TAIL;
        return bytes;
    }

    /**
     * Prepare write command for MA device using the template
     * @param bytes initial command bytes to use
     * @param sequence sequence to start
     * @param length length of the command, static for most operations
     * @param writeCommand command to start (write/sequence/etc)
     * @param state state for the write command (On/off/cycle/response only)
     * */
    private void prepareWriteCommand(byte[] bytes, byte sequence, byte length, byte writeCommand, byte state) {
        bytes[0] = HEAD;
        bytes[1] = length;
        bytes[2] = DESTINATION;
        bytes[3] = writeCommand;
        bytes[4] = SUBCOMMAND_SET;
        bytes[5] = sequence;
        bytes[6] = state;
        bytes[7] = ZERO_DELAY;
        bytes[8] = ZERO_DELAY;
        bytes[9] = ZERO_DELAY;
    }

    /***
     * Fetch outlets data: name, number, state, control abilities
     * @param outletsStatistics to add outlet stats to
     * @param controllableProperties to add controls to
     * @throws Exception during TCP communication
     */
    private void fetchOutletsData(Map<String, String> outletsStatistics, List<AdvancedControllableProperty> controllableProperties) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Fetching outlet information for device " + getHost());
        }
        byte[] outletsRequest = new byte[]{HEAD, 0x03, 0x00, 0x22, 0x02, 0x25, TAIL};
        byte[] response = send(outletsRequest);
        byte[] data = Arrays.copyOfRange(response, 5, response.length - 2); // 4 bytes envelope at the beginning and 2 at the end

        int number = 1;
        int enabledControlled = 0;
        int disabledControlled = 0;

        for(byte state: data){
            if(state == 0x58){
                if(logger.isInfoEnabled()){
                    logger.debug(String.format("Outlet #%s does not exist", number));
                }
            } else if (state == 0x43 || state == 0x4E){
                // 0x43 is controllable, 0x4E is not controllable
                String outletNameRaw = getOutletName((byte)number);
                if(StringUtils.isEmpty(outletNameRaw)){
                    continue;
                }

                boolean controllableOutlet = state == 0x43;
                int outletStatus = getOutletStatus((byte) number);

                String outletName = String.format("%s%s - %s", controllableOutlet ? "Controllable outlets#" : "", outletNameRaw, number);
                outletsStatistics.put(outletName, controllableOutlet ? String.valueOf(outletStatus) :  outletStatus == 1 ? "On" : "Off");

                if (controllableOutlet) {
                    addControl(controllableProperties, createOutletSwitch(outletName, outletStatus));
                    if(outletStatus == 1){
                        enabledControlled++;
                    } else {
                        disabledControlled++;
                    }
                }
            }
            number++;
        }

        if(enabledControlled + disabledControlled > 0){
            if(enabledControlled == 0){
                addControl(controllableProperties, createSequenceButton(SEQUENCE_UP_COMMAND_NAME));
                outletsStatistics.put(SEQUENCE_UP_COMMAND_NAME, "");
            } else if (disabledControlled == 0){
                addControl(controllableProperties, createSequenceButton(SEQUENCE_DOWN_COMMAND_NAME));
                outletsStatistics.put(SEQUENCE_DOWN_COMMAND_NAME, "");
            } else {
                addControl(controllableProperties, createSequenceButton(SEQUENCE_UP_COMMAND_NAME));
                addControl(controllableProperties, createSequenceButton(SEQUENCE_DOWN_COMMAND_NAME));
                outletsStatistics.put(SEQUENCE_UP_COMMAND_NAME, "");
                outletsStatistics.put(SEQUENCE_DOWN_COMMAND_NAME, "");
            }
        }
    }

    /**
     * Adds controllable property in list of controllable properties, if such property does not exist
     *
     * @param advancedControllableProperties list to keep all controls in
     * @param newProperty new controllable property
     * */
    private void addControl(List<AdvancedControllableProperty> advancedControllableProperties, AdvancedControllableProperty newProperty) {
        Optional<AdvancedControllableProperty> controllableProperty = advancedControllableProperties.stream().filter(property ->
                newProperty.getName().equals(property.getName())).findFirst();
        if(!controllableProperty.isPresent()) {
            advancedControllableProperties.add(newProperty);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Controllable property %s already exists. Updating property value.", newProperty.getName()));
            }
            controllableProperty.ifPresent(advancedControllableProperty -> advancedControllableProperty.setValue(newProperty.getValue()));
        }
    }
    /**
     * Create an ON|OFF sequence button
     * @param sequenceCommandName name of the sequence
     * @return AdvancedControllableProperty button instance
     *  */
    private AdvancedControllableProperty createSequenceButton(String sequenceCommandName){
        AdvancedControllableProperty.Button btn = new AdvancedControllableProperty.Button();
        btn.setLabel("Launch");
        btn.setLabelPressed("Processing...");
        btn.setGracePeriod(0L);
        return new AdvancedControllableProperty(sequenceCommandName, new Date(), btn, "");
    }

    /**
     * Create an outlet switch
     * @param outletName name of the outlet
     * @param outletStatus initial outlet status (0|1)
     * @return AdvancedControllableProperty button instance
     *  */
    private AdvancedControllableProperty createOutletSwitch(String outletName, int outletStatus){
        AdvancedControllableProperty.Switch toggle = new AdvancedControllableProperty.Switch();
        toggle.setLabelOff("Off");
        toggle.setLabelOn("On");

        AdvancedControllableProperty advancedControllableProperty = new AdvancedControllableProperty();
        advancedControllableProperty.setName(outletName);
        advancedControllableProperty.setValue(outletStatus);
        advancedControllableProperty.setType(toggle);
        advancedControllableProperty.setTimestamp(new Date());

        return advancedControllableProperty;
    }

    /***
     * Get the name of an outlet
     * Envelope: 5 bytes at front and 2 at the end
     * @param outlet number of an outlet
     * @return String value containing outlet name
     * @throws Exception during TCP communication
     */
    private String getOutletName(byte outlet) throws Exception {
        int checksum = 293 + outlet; // envelope checksum
        byte[] bytes = {(byte) 0xFE, 0x04, 0x00, 0x21, 0x02, outlet, (byte) (checksum  & 0x007f),(byte) 0xFF};
        byte[] response = send(bytes);
        if(response[3] != 0x21){
            if(logger.isDebugEnabled()){
                logger.debug("Invalid response received for outlet name request. '33' byte value expected, but was: " + response[3]);
            }
            return "";
        }
        String outletName = new String(Arrays.copyOfRange(response, 6, response.length - 2));
        // we don't know if this one contains any specific characters, but may end up with an invalid db record
        // so it's better to clean this up
        return outletName.replaceAll("[^A-Za-z0-9()\\[\\]]", "");
    }

    /**
     * Get outlet status (0|1)
     * Envelope: 5 bytes at front and 6 at the end
     * @param outlet number of an outlet
     * @return int value that represents current outlet status
     * @throws Exception during TCP communication
     */
    private int getOutletStatus(byte outlet) throws Exception {
        int checksum = 0xFE + 0x04 + 0x20 + 0x02;
        checksum += outlet;
        byte[] bytes = {(byte) 0xFE, 0x04, 0x00, 0x20, 0x02, outlet, (byte) (checksum  & 0x007f),(byte) 0xFF};
        byte[] response = send(bytes);

        return response[response.length - 7];
    }

    /***
     * Every once in a while a device will ping the control system (adapter) to keep the connection alive.
     * After 3 unreplied ping messages the device will break the connection.
     * We're not aiming to keep the connection indefinitely since that would require having a separate thread
     * to handle that asynchronyously, so instead we just send a ping before the operation we're about to start
     * and if succeeded - proceed with the operation, otherwise - retry login and proceed with the opreation
     *
     * @return boolean value indicating the ping response (if the device has immideately responded with a pong ->
     * connection is still active)
     * @throws Exception during TCP communication
     */
    private boolean sendPing() throws Exception {
        if(!getConnectionStatus().getConnectionState().isConnected()){
            if(logger.isDebugEnabled()){
                logger.debug("Socket connection is not active.");
            }
            return false;
        } else {
            byte[] ping = {HEAD, 0x03, 0x00, 0x01, 0x10, 0x12, TAIL};
            byte[] pingResponse = send(ping);

            if (pingResponse[3] == 0x01 && pingResponse[4] == 0x01) {
                return true;
            }
        }
        return false;
    }

    /**
     * Send a control command to the device
     * @param cmd control command in byte[]
     * @return byte indicating the new status of the outlet
     * @throws Exception during TCP communication
     * */
    private byte sendControlCommand(byte[] cmd) throws Exception {
        byte[] response = send(cmd);
        updateLatestControlTimestamp();

        return response[4] == 0x10 ? response[6] : cmd[6];
    }

    /**
     * Send a sequence start command to the device
     * @param cmd control command in byte[]
     * @return boolean indicating success of an operation
     * @throws Exception during TCP communication
     * */
    private boolean sendSequencingCommand(byte[] cmd) throws Exception {
        byte[] response = send(cmd);
        boolean success = (cmd[5] == 0x01 || cmd[5] == 0x03) && response[5] > 0x00;
        updateLatestControlTimestamp();
        return success;
    }

    /***
     * Update local controls state on UI (if the OFF sequence is launched -> all the outlets has to be toggled to OFF
     * and vice-versa, the sequence button has to be removed afterwards)
     * @param sequence 0x01 is for UP 0x03 is for DOWN
     */
    private void updateLocalSequencingStatistics(byte sequence){
        if(localStatistics != null){
            Map<String, String> statistics = localStatistics.getStatistics();

            if(sequence == 0x01){
                if(logger.isDebugEnabled()){
                    logger.debug("Removing 'Sequence up' button from local statistics.");
                }
                replaceLocalStatisticsControls(SEQUENCE_UP_COMMAND_NAME, SEQUENCE_DOWN_COMMAND_NAME);
                statistics.keySet().forEach(s -> { if("0".equals(statistics.get(s))) statistics.put(s, "1"); });
                processOutletsSequence(1);
            } else if (sequence == 0x03){
                if(logger.isDebugEnabled()){
                    logger.debug("Removing 'Sequence down' button from local statistics.");
                }
                replaceLocalStatisticsControls(SEQUENCE_DOWN_COMMAND_NAME, SEQUENCE_UP_COMMAND_NAME);
                statistics.keySet().forEach(s -> { if("1".equals(statistics.get(s))) statistics.put(s, "0"); });
                processOutletsSequence(0);
            }

            if(logger.isDebugEnabled()){
                logger.debug("Sequence button removed from local statistics.");
            }
        }
    }

    /**
     * In order to update controls after sequencing we need to update control value and it's timestamp
     * otherwise the value won't be refreshed within "extraProperties" during the statistics collection
     * cycle that was triggered by control action
     * @param state new state of the controllable property
     * */
    private void processOutletsSequence(int state){
        localStatistics.getControllableProperties().forEach(cp -> {
            if(cp.getName().startsWith("Controllable outlets")) {
                cp.setValue(state);
                cp.setTimestamp(new Date());
            }
        });
    }

    /**
     * Replace one sequence control with another
     * @param replaceName name of the control to replace
     * @param newName name of the control to replace with
     * */
    private void replaceLocalStatisticsControls(String replaceName, String newName) {
        localStatistics.getStatistics().remove(replaceName);
        localStatistics.getStatistics().put(newName, "");

        Iterator<AdvancedControllableProperty> advancedControllablePropertyIterator = localStatistics.getControllableProperties().iterator();
        while(advancedControllablePropertyIterator.hasNext()){
            AdvancedControllableProperty currentProperty = advancedControllablePropertyIterator.next();
            if(logger.isDebugEnabled()){
                logger.debug("Checking local statistics for stale controls to replace in local statistics.");
            }
            if(currentProperty.getName().equals(replaceName)){
                if(logger.isDebugEnabled()){
                    logger.debug("Removing stale controllable property from local statistics: " + currentProperty.getName());
                }
                advancedControllablePropertyIterator.remove();
            }
        }
        addControl(localStatistics.getControllableProperties(), createSequenceButton(newName));
        if(logger.isDebugEnabled()){
            logger.debug("Controllable properties available after cleanup: " + localStatistics.getControllableProperties());
        }
    }

    /***
     * Update local statistics outlet state
     * @param outletName name of the outlet
     * @param requestedState state that is requested by the control event
     * @param actualState state that is returned by the device to approve the operation success
     */
    private void updateLocalOutletState(String outletName, String requestedState, String actualState){
        if(logger.isInfoEnabled()){
            logger.info(String.format("Updating local outlet control state: Outlet '%s' with requestedState '%s' and actual state '%s'", outletName, requestedState, actualState));
        }
        if(localStatistics != null && requestedState.equals(actualState)){
            localStatistics.getStatistics().put(outletName, actualState);
            localStatistics.getControllableProperties().stream().filter(advancedControllableProperty ->
                    advancedControllableProperty.getName().equals(outletName))
                    .findFirst().ifPresent(advancedControllableProperty -> advancedControllableProperty.setValue(actualState));

            int outletsOff = (int) localStatistics.getStatistics().keySet().stream().filter(s -> "0".equals(localStatistics.getStatistics().get(s))).count();
            int outletsOn = (int) localStatistics.getStatistics().keySet().stream().filter(s -> "1".equals(localStatistics.getStatistics().get(s))).count();

            if(requestedState.equals("1")){
                if(outletsOff == 0) {
                    replaceLocalStatisticsControls(SEQUENCE_UP_COMMAND_NAME, SEQUENCE_DOWN_COMMAND_NAME);
                } else if(outletsOn == 1) {
                    localStatistics.getStatistics().put(SEQUENCE_DOWN_COMMAND_NAME, "");
                    addControl(localStatistics.getControllableProperties(), createSequenceButton(SEQUENCE_DOWN_COMMAND_NAME));
                }
            } else if(requestedState.equals("0")){
                if(outletsOn == 0) {
                    replaceLocalStatisticsControls(SEQUENCE_DOWN_COMMAND_NAME, SEQUENCE_UP_COMMAND_NAME);
                } else if(outletsOff == 1) {
                    localStatistics.getStatistics().put(SEQUENCE_UP_COMMAND_NAME, "");
                    addControl(localStatistics.getControllableProperties(), createSequenceButton(SEQUENCE_UP_COMMAND_NAME));
                }
            }
        }
    }

    /***
     * Update the time of the latest toggled control
     */
    private void updateLatestControlTimestamp(){
        latestControlTimestamp = new Date().getTime();
    }

    /**
     * Check whether the control operation cooldown is over
     * @return boolean true if controls are still on cooldown within the CONTROLS_COOLDOWN_PERIOD
     * */
    private boolean isValidControlCoolDown(){
        return (new Date().getTime() - latestControlTimestamp) < CONTROLS_COOLDOWN_PERIOD;
    }
}