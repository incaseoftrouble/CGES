package com.cges.parser;

public record State(String name) {
  @Override
  public String toString() {
    return name;
  }
}
