package org.hkrpg.server.protocol;

/** A packet has no verified structural mapping to the selected wire schema. */
public class ProtocolException extends IllegalArgumentException {
    public ProtocolException(String message) { super(message); }
}
