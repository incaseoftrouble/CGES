package com.cges.model;

import java.util.function.Function;

public record Transition<S>(Move move, S destination) {
  public static <S, T> Function<Transition<S>, Transition<T>> transform(Function<S, T> function) {
    return transition -> transition.withDestination(function.apply(transition.destination));
  }

  public <T> Transition<T> withDestination(T destination) {
    return new Transition<>(move, destination);
  }

  @Override
  public String toString() {
    return move + " -> " + destination;
  }
}
