package com.nodum.modules.spi;

public record ModuleHealth(State state, String message) {
    public enum State { UNKNOWN, STOPPED, STARTING, RUNNING, DEGRADED, ERROR }
}
