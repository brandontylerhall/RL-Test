package com.lootlogger.data;

public enum ActionType {
    DESTROY, // will cover drop, break, bury, etc.
    CONSUME, // will cover use, eat, drink, etc.
    GATHER_GAIN, // will cover all types of gathering gains
    BANK_DEPOSIT,
    BANK_WITHDRAWAL,
    SWAP,
    TAKE,
    DROP
}