package com.cges.model;

public record Transition<S>(Move move, S destination) {
  public <T> Transition<T> withDestination(T destination) {
    return new Transition<>(move, destination);
  }
}
