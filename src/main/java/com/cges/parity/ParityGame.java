package com.cges.parity;

import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public interface ParityGame<S> {
  Set<S> states();

  Stream<S> successors(S state);

  int priority(S state);

  default void forEachState(Consumer<S> action) {
    states().forEach(action);
  }

  Player owner(S state);
}
