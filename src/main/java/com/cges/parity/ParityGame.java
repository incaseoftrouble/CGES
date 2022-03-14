package com.cges.parity;

import com.cges.parity.Player;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public interface ParityGame<S> {
  S initialState();

  default Set<S> states() {
    Set<S> reached = new HashSet<>(List.of(initialState()));
    Queue<S> queue = new ArrayDeque<>(reached);
    while (!queue.isEmpty()) {
      successors(queue.poll()).forEach(successor -> {
        if (reached.add(successor)) {
          queue.add(successor);
        }
      });
    }
    return reached;
  }

  Stream<S> successors(S state);

  int priority(S state);

  default void forEachState(Consumer<S> action) {
    Set<S> reached = new HashSet<>(List.of(initialState()));
    Queue<S> queue = new ArrayDeque<>(reached);

    while (!queue.isEmpty()) {
      S state = queue.poll();
      successors(state).forEach(successor -> {
        if (reached.add(successor)) {
          action.accept(successor);
          queue.add(successor);
        }
      });
    }
  }

  Player owner(S state);

}
