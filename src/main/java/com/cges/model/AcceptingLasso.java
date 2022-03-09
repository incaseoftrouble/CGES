package com.cges.model;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.algorithm.RunGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class AcceptingLasso<S> {
  private final List<RunGraph.RunState<S>> transientStates;
  private final List<RunGraph.RunState<S>> loopStates;

  public AcceptingLasso(List<RunGraph.RunState<S>> loop) {
    List<RunGraph.RunState<S>> transientStates = new ArrayList<>();
    List<RunGraph.RunState<S>> loopStates = new ArrayList<>();
    RunGraph.RunState<S> backLink = loop.get(loop.size() - 1);
    boolean inLoop = false;
    for (RunGraph.RunState<S> state : loop.subList(0, loop.size() - 1)) {
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
    checkArgument(loopStates.stream().anyMatch(RunGraph.RunState::accepting));

    this.transientStates = List.copyOf(transientStates);
    this.loopStates = List.copyOf(loopStates);
  }

  public Stream<RunGraph.RunState<S>> transientStates() {
    return transientStates.stream();
  }

  public Stream<RunGraph.RunState<S>> loopStates(boolean withClosingState) {
    return withClosingState ? Stream.concat(loopStates.stream(), Stream.of(loopStates.get(0))) : loopStates.stream();
  }

  public Stream<RunGraph.RunState<S>> states(boolean withClosingState) {
    return Stream.concat(transientStates(), loopStates(withClosingState));
  }

  public int size() {
    return transientStates.size() + loopStates.size();
  }
}
