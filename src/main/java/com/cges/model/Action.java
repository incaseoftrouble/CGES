package com.cges.model;

public record Action(String name) {
  @Override
  public String toString() {
    return name;
  }
}
