package com.cges.parity.oink;

public class OinkExecutionException extends RuntimeException {
  public OinkExecutionException(String message) {
    super(message);
  }

  public OinkExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
