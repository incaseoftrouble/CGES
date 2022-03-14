package com.cges.model;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.algorithm.RunGraph.RunState;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AcceptingLasso<S> {
  private final List<RunState<S>> transientStates;
  private final List<RunState<S>> loopStates;

  public AcceptingLasso(List<RunState<S>> loop) {
    List<RunState<S>> transientStates = new ArrayList<>();
    List<RunState<S>> loopStates = new ArrayList<>();
    RunState<S> backLink = loop.get(loop.size() - 1);
    boolean inLoop = false;
    for (RunState<S> state : loop.subList(0, loop.size() - 1)) {
      if (!inLoop && state.equals(backLink)) {
        inLoop = true;
      }
      if (inLoop) {
        loopStates.add(state);
      } else {
        transientStates.add(state);
      }
    }
    checkArgument(inLoop);
    checkArgument(loopStates.stream().anyMatch(RunState::accepting));

    this.transientStates = List.copyOf(transientStates);
    this.loopStates = List.copyOf(loopStates);
  }

  public Stream<RunState<S>> transientStates() {
    return transientStates.stream();
  }

  public Stream<RunState<S>> loopStates(boolean withClosingState) {
    return withClosingState ? Stream.concat(loopStates.stream(), Stream.of(loopStates.get(0))) : loopStates.stream();
  }

  public Stream<RunState<S>> states(boolean withClosingState) {
    return Stream.concat(transientStates(), loopStates(withClosingState));
  }

  public int size() {
    return transientStates.size() + loopStates.size();
  }

  @Override
  public String toString() {
    return transientStates.stream().map(RunState::toString).collect(Collectors.joining("   "))
        + " | " + loopStates.stream().map(RunState::toString).collect(Collectors.joining("   "));
  }
}
