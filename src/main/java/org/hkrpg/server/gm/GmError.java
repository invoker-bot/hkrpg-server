package org.hkrpg.server.gm;

final class GmError extends RuntimeException {
  final int status;

  GmError(int status, String message) {
    super(message);
    this.status = status;
  }
}
