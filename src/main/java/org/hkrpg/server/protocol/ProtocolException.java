package org.hkrpg.server.protocol;

/** A packet has no verified structural mapping to the selected wire schema. */
public class ProtocolException extends IllegalArgumentException {
    private final String diagnosticReason;

    public ProtocolException(String message) { this(message, null); }

    private ProtocolException(String message, String diagnosticReason) {
        super(message);
        this.diagnosticReason = diagnosticReason;
    }

    /** Opt in only for fixed text and symbols taken from the local protocol schema. */
    static ProtocolException diagnostic(String reason) { return new ProtocolException(reason, reason); }

    public String diagnosticReason() { return diagnosticReason; }
}
