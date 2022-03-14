package com.cges.parity;

public enum Player {
  EVEN, ODD;

  public int id() {
    return switch (this) {
      case EVEN -> 0;
      case ODD -> 1;
    };
  }
}
