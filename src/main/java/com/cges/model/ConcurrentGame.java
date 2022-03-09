package com.cges.model;


import static com.google.common.base.Preconditions.checkState;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public interface ConcurrentGame<S> {
  String name();

  List<String> atomicPropositions();

  Set<Agent> agents();

  default Agent agent(String name) {
    @Nullable
    Agent candidate = null;
    for (Agent agent : agents()) {
      if (agent.name().equals(name)) {
        checkState(candidate == null);
        candidate = agent;
      }
    }
    if (candidate == null) {
      throw new NoSuchElementException();
    }
    return candidate;
  }

  S initialState();

  Set<String> labels(S state);

  Stream<S> states();

  Stream<Transition<S>> transitions(S state);

  default Stream<S> successors(S state) {
    return transitions(state).map(Transition::destination).distinct();
  }
}