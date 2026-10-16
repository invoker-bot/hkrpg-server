package org.hkrpg.server.protocol;

/** A well-framed command has no bridge mapping; this is distinct from a malformed mapped packet. */
public final class UnsupportedInboundCommandException extends ProtocolException {
  public UnsupportedInboundCommandException(int command) {
    super("Unsupported 4.6 inbound command: " + command);
  }
}
