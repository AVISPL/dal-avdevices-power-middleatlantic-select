/*
 * Copyright (c) 2020 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.middleatlantic.select;

final class MiddleAtlanticSelectControls {
    public static final byte HEAD = (byte) 0xFE;
    public static final byte TAIL = (byte) 0xFF;
    public static final byte SUBCOMMAND_SET = 0x01;
    public static final byte COMMAND_LOGIN = 0x02;
    public static final byte DESTINATION = 0x00;
    public static final byte ZERO_DELAY = 0x30;
    public static final byte UP_SEQUENCE = 0x01;
    public static final byte DOWN_SEQUENCE = 0x03;
    public static final byte POWER_SEQUENCE = 0x36;
    public static final byte POWER_OUTLET = 0x20;

    public static final byte[] BAD_CRC = {HEAD, 0x04, 0x00, 0x10, 0x10, 0x01, 0x23, TAIL};
    public static final byte[] BAD_LENGTH = {HEAD, 0x04, 0x00, 0x10, 0x10, 0x02, 0x24, TAIL};
    public static final byte[] BAD_ESCAPE = {HEAD, 0x04, 0x00, 0x10, 0x10, 0x03, 0x25, TAIL};
    public static final byte[] PREVIOUS_CMD_INVALID = {HEAD, 0x04, 0x00, 0x10, 0x10, 0x04, 0x26, TAIL};
    public static final byte[] PREVIOUS_SUBCMD_INVALID = {HEAD, 0x04, 0x00, 0x10, 0x10, 0x05, 0x27, TAIL};
    public static final byte[] PREVIOUS_BYTE_COUNT_INVALID = {HEAD, 0x04, 0x00, 0x10, 0x10, 0x06, 0x28, TAIL};
    public static final byte[] DATA_BYTES_INVALID = {HEAD, 0x04, 0x00, 0x10, 0x10, 0x07, 0x29, TAIL};
    public static final byte[] BAD_CREDS = {HEAD, 0x04, 0x00, 0x10, 0x10, 0x08, 0x2A, TAIL};
    public static final byte[] UNKNOWN = {HEAD, 0x04, 0x00, 0x10, 0x10, 0x10, 0x32, TAIL};
    public static final byte[] ACCESS_DENIED = {HEAD, 0x04, 0x00, 0x10, 0x10, 0x11, 0x33, TAIL};

    public static final String SEQUENCE_UP_COMMAND_NAME = "Sequence up";
    public static final String SEQUENCE_DOWN_COMMAND_NAME = "Sequence down";
}
