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
    private static final String OUTLETS_GROUP = "Outlets#";
    private static final String OUTLET_PREFIX = "Outlet";
    private static final String OUTLET_NAME_SUFFIX = "Name";
    /** Matches outlet state keys only (Outlets#Outlet<n>), not the Outlets#Outlet<n>Name entries in the same group */
    private static final java.util.regex.Pattern OUTLET_STATE_KEY = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(OUTLETS_GROUP + OUTLET_PREFIX) + "\\d+");

    private final ReentrantLock controlOperationsLock = new ReentrantLock();
    /**
     * Guards {@link #localStatistics}. Held only for short in-memory operations (snapshot copy, swap, control-driven
     * updates), never during TCP communication, so getMultipleStatistics is never blocked by a slow device.
     */
    private final Object statisticsLock = new Object();
    /**
     * Latest complete snapshot of device data. Replaced as a whole by the data collector once a collection cycle is
     * finished, so readers never observe a half-collected state. Guarded by {@link #statisticsLock}.
     */
    private ExtendedStatistics localStatistics = createStatistics(new HashMap<>(), new ArrayList<>());
    private final AdapterMetadata adapterMetadata = new AdapterMetadata();
    private volatile Exception latestException = null;
    private long latestControlTimestamp;
    /**
     * To avoid timeout errors, caused by the unavailability of the control protocol, all polling-dependent communication operations (monitoring)
     * should be performed asynchronously. This executor service executes such operations.
     */
    private volatile ExecutorService executorService;
    /**
     * */
    private volatile CompletableFuture dataCollector;

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
            if (dataCollector != null) {
                dataCollector.cancel(true);
            }
            if (executorService != null) {
                executorService.shutdownNow();
            }
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
        controlOperationsLock.lock();
        try {
            // Session refresh (ping/login) uses the socket, so it must happen under the lock to never interleave
            // with the data collector's requests
            refreshTCPSession();
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
            } else if(isOutletStateKey(property)){
                byte outletNumber = Byte.parseByte(property.substring(property.lastIndexOf(OUTLET_PREFIX) + OUTLET_PREFIX.length()));
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
            // Release the single TCP connection the device allows. A collection cycle queued behind this control
            // will skip itself because of the control cooldown (see collectDeviceData)
            try {
                disconnect();
            } catch (Exception e) {
                logger.warn("Unable to end the TCP connection after control operation.", e);
            } finally {
                controlOperationsLock.unlock();
            }
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> controllablePropertyList) throws Exception {
        if (CollectionUtils.isEmpty(controllablePropertyList)) {
            throw new IllegalArgumentException("MiddleAtlanticPowerUnitCommunicator: Controllable properties cannot be null or empty");
        }

        for(ControllableProperty controllableProperty: controllablePropertyList){
            controlProperty(controllableProperty);
        }
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        if(isValidControlCoolDown()){
            if (logger.isDebugEnabled()) {
                logger.debug("Device is occupied by control operations. Skipping monitoring statistics retrieval.");
            }
            return Collections.singletonList(copyLocalStatistics());
        }

        if (executorService.isShutdown() || executorService.isTerminated()) {
            // If executor service was shut down or terminated prior to this point
            executorService = Executors.newFixedThreadPool(1);
        }
        if (dataCollector != null && !dataCollector.isDone()) {
            // CompletableFuture.cancel() does not interrupt a running task, so cancelling and resubmitting would only
            // queue a second cycle behind the running one. Let the running cycle finish and serve the latest snapshot.
            if (logger.isDebugEnabled()) {
                logger.debug("Previous data collection cycle is still in progress. Skipping new cycle.");
            }
        } else {
            dataCollector = runAsync(this::collectDeviceData, executorService);
        }

        if (latestException != null) {
            throw latestException;
        }
        return Collections.singletonList(copyLocalStatistics());
    }

    /**
     * Collect device data into fresh collections and publish them as a new snapshot once the cycle is complete.
     * Nothing shared is modified while TCP communication is in progress.
     */
    private void collectDeviceData() {
        controlOperationsLock.lock();
        try {
            if (isValidControlCoolDown()) {
                // A control operation ran while this cycle was waiting for the lock, the device needs time to settle
                if (logger.isDebugEnabled()) {
                    logger.debug("Control operation was performed recently. Skipping monitoring statistics retrieval.");
                }
                return;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Device is not occupied by control operations. Retrieving monitoring statistics.");
            }
            refreshTCPSession();

            Map<String, String> newStatistics = new HashMap<>();
            List<AdvancedControllableProperty> newControllableProperties = new ArrayList<>();
            fetchOutletsData(newStatistics, newControllableProperties);
            newStatistics.put(CONTROL_PROTOCOL_STATUS_PROPERTY, AVAILABLE);

            synchronized (statisticsLock) {
                localStatistics = createStatistics(newStatistics, newControllableProperties);
            }
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Collection cycle finished: %d properties, %d controls %s", newStatistics.size(),
                        newControllableProperties.size(), newControllableProperties.stream().map(AdvancedControllableProperty::getName).collect(java.util.stream.Collectors.toList())));
            }
            latestException = null;
        } catch (Exception ce) {
            if (logger.isDebugEnabled()) {
                logger.debug("Exception white collecting device data.", ce);
            }
            if (ce instanceof ConnectException) {
                String message = ce.getMessage();
                if (StringUtils.hasLength(message) && message.contains("Connection timed out")) {
                    logger.warn("Connection timed out: Unable to connect to the device, TCP protocol is occupied.");
                    synchronized (statisticsLock) {
                        // Keep the last known values, mark the protocol unavailable and drop the controls
                        Map<String, String> unavailableStatistics = new HashMap<>(localStatistics.getStatistics());
                        unavailableStatistics.put(CONTROL_PROTOCOL_STATUS_PROPERTY, UNAVAILABLE);
                        localStatistics = createStatistics(unavailableStatistics, new ArrayList<>());
                    }
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
    }

    /**
     * Create an ExtendedStatistics instance with the given contents
     * @param statistics monitored properties
     * @param controllableProperties controls
     * @return ExtendedStatistics instance
     */
    private static ExtendedStatistics createStatistics(Map<String, String> statistics, List<AdvancedControllableProperty> controllableProperties) {
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();
        extendedStatistics.setStatistics(statistics);
        extendedStatistics.setControllableProperties(controllableProperties);
        return extendedStatistics;
    }

    /**
     * Check whether the key is an outlet state key (Outlets#Outlet<n>), as opposed to an outlet name entry
     * (Outlets#Outlet<n>Name) or any other property
     * @param key property key
     * @return true for outlet state keys
     */
    private static boolean isOutletStateKey(String key) {
        return key != null && OUTLET_STATE_KEY.matcher(key).matches();
    }

    /**
     * Copy the latest snapshot so the returned collections are never modified by the data collector or control operations.
     * Adapter metadata is added to the copy only (computed per call, so uptime is current), never to the cached snapshot.
     * @return ExtendedStatistics copy of the local statistics, with adapter metadata
     */
    private ExtendedStatistics copyLocalStatistics() {
        Map<String, String> statistics;
        List<AdvancedControllableProperty> controllableProperties;
        synchronized (statisticsLock) {
            statistics = new HashMap<>(localStatistics.getStatistics());
            controllableProperties = new ArrayList<>(localStatistics.getControllableProperties());
        }
        statistics.putAll(adapterMetadata.statistics());
        return createStatistics(statistics, controllableProperties);
    }

    /**
     * Send a request to the device, logging the raw request and response bytes in hex at debug level
     * to diagnose malformed, misaligned or unexpected (error frame, unsolicited ping) responses.
     *
     * @param label human-readable request name for the log
     * @param request request bytes
     * @param logRequestBytes false for requests carrying credentials (login), so they never reach the log
     * @return response bytes
     * @throws Exception during TCP communication
     */
    private byte[] sendAndLog(String label, byte[] request, boolean logRequestBytes) throws Exception {
        boolean debug = logger.isDebugEnabled();
        if (debug) {
            logger.debug(String.format("[%s] >> %s", label, logRequestBytes ? toHex(request) : "<" + request.length + " bytes, contents hidden>"));
        }
        byte[] response;
        try {
            response = send(request);
        } catch (Exception e) {
            if (debug) {
                logger.debug(String.format("[%s] << no response: %s: %s", label, e.getClass().getSimpleName(), e.getMessage()));
            }
            throw e;
        }
        if (debug) {
            logger.debug(String.format("[%s] << %s (%d bytes)", label, toHex(response), response == null ? 0 : response.length));
        }
        return response;
    }

    /**
     * Format bytes as space-separated uppercase hex, e.g. "FE 03 00 22 02 25 FF"
     * @param bytes bytes to format
     * @return hex string
     */
    private static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
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
        byte[] response = sendAndLog("Login", loginRequest, false);
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
     * Fetch outlets data: number, state, control abilities. Outlets are reported as Outlets#Outlet<number>, names as Outlets#Outlet<number>Name
     * @param outletsStatistics to add outlet stats to
     * @param controllableProperties to add controls to
     * @throws Exception during TCP communication
     */
    private void fetchOutletsData(Map<String, String> outletsStatistics, List<AdvancedControllableProperty> controllableProperties) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Fetching outlet information for device " + getHost());
        }
        byte[] outletsRequest = new byte[]{HEAD, 0x03, 0x00, 0x22, 0x02, 0x25, TAIL};
        byte[] response = sendAndLog("Outlets state", outletsRequest, true);
        byte[] data = Arrays.copyOfRange(response, 5, response.length - 2); // 4 bytes envelope at the beginning and 2 at the end

        int enabledControlled = 0;
        int disabledControlled = 0;

        for (int i = 0; i < data.length; i++) {
            // Outlet number is derived from the byte position, so skipping an outlet (continue) can never
            // shift the numbering of the outlets that follow it
            byte state = data[i];
            int number = i + 1;
            if(state == 0x58){
                if(logger.isInfoEnabled()){
                    logger.debug(String.format("Outlet #%s does not exist", number));
                }
            } else if (state == 0x43 || state == 0x4E){
                // 0x43 is controllable, 0x4E is not controllable
                boolean controllableOutlet = state == 0x43;
                int outletStatus = getOutletStatus((byte) number);

                String outletName = OUTLETS_GROUP + OUTLET_PREFIX + number;
                outletsStatistics.put(outletName, controllableOutlet ? String.valueOf(outletStatus) :  outletStatus == 1 ? "On" : "Off");

                // The name is informational only: a failed lookup omits the name entry but never skips the outlet
                String deviceOutletName = getOutletName((byte) number);
                if (StringUtils.hasLength(deviceOutletName)) {
                    outletsStatistics.put(outletName + OUTLET_NAME_SUFFIX, deviceOutletName);
                }

                if (controllableOutlet) {
                    addControl(controllableProperties, createOutletSwitch(outletName, outletStatus));
                    if(outletStatus == 1){
                        enabledControlled++;
                    } else {
                        disabledControlled++;
                    }
                }
            }
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
     * Get the name of an outlet, as configured on the device
     * Envelope: 6 bytes at front and 2 at the end
     * @param outlet number of an outlet
     * @return outlet name, or an empty string if the response is not a valid outlet name response
     * @throws Exception during TCP communication
     */
    private String getOutletName(byte outlet) throws Exception {
        int checksum = 293 + outlet; // envelope checksum
        byte[] bytes = {(byte) 0xFE, 0x04, 0x00, 0x21, 0x02, outlet, (byte) (checksum  & 0x007f),(byte) 0xFF};
        byte[] response = sendAndLog("Outlet #" + outlet + " name", bytes, true);
        if (response == null || response.length < 8 || response[3] != 0x21) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Invalid response received for outlet #%s name request: %s", outlet, toHex(response)));
            }
            return "";
        }
        String outletName = new String(Arrays.copyOfRange(response, 6, response.length - 2), java.nio.charset.StandardCharsets.US_ASCII);
        // Name is a value (not a property key), so keep spaces and punctuation, drop only non-printable bytes (padding, control chars)
        return outletName.replaceAll("[^\\x20-\\x7E]", "").trim();
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
        byte[] response = sendAndLog("Outlet #" + outlet + " status", bytes, true);

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
            byte[] pingResponse = sendAndLog("Ping", ping, true);

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
        byte[] response = sendAndLog("Outlet control", cmd, true);
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
        byte[] response = sendAndLog("Sequence control", cmd, true);
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
        synchronized (statisticsLock) {
            Map<String, String> statistics = localStatistics.getStatistics();

            if(sequence == 0x01){
                if(logger.isDebugEnabled()){
                    logger.debug("Removing 'Sequence up' button from local statistics.");
                }
                replaceLocalStatisticsControls(SEQUENCE_UP_COMMAND_NAME, SEQUENCE_DOWN_COMMAND_NAME);
                statistics.keySet().forEach(s -> { if(isOutletStateKey(s) && "0".equals(statistics.get(s))) statistics.put(s, "1"); });
                processOutletsSequence(1);
            } else if (sequence == 0x03){
                if(logger.isDebugEnabled()){
                    logger.debug("Removing 'Sequence down' button from local statistics.");
                }
                replaceLocalStatisticsControls(SEQUENCE_DOWN_COMMAND_NAME, SEQUENCE_UP_COMMAND_NAME);
                statistics.keySet().forEach(s -> { if(isOutletStateKey(s) && "1".equals(statistics.get(s))) statistics.put(s, "0"); });
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
            if(isOutletStateKey(cp.getName())) {
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
        if(!requestedState.equals(actualState)){
            return;
        }
        synchronized (statisticsLock) {
            localStatistics.getStatistics().put(outletName, actualState);
            localStatistics.getControllableProperties().stream().filter(advancedControllableProperty ->
                    advancedControllableProperty.getName().equals(outletName))
                    .findFirst().ifPresent(advancedControllableProperty -> advancedControllableProperty.setValue(actualState));

            int outletsOff = (int) localStatistics.getStatistics().keySet().stream().filter(s -> isOutletStateKey(s) && "0".equals(localStatistics.getStatistics().get(s))).count();
            int outletsOn = (int) localStatistics.getStatistics().keySet().stream().filter(s -> isOutletStateKey(s) && "1".equals(localStatistics.getStatistics().get(s))).count();

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