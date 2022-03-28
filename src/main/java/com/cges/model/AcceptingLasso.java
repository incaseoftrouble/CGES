package com.cges.model;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.graph.RunGraph.RunState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class AcceptingLasso<S> {
  private final List<RunState<S>> transientStates;
  private final List<RunState<S>> loopStates;
  private final Map<RunState<S>, RunState<S>> successors;

  public AcceptingLasso(List<RunState<S>> loop) {
    List<RunState<S>> transientStates = new ArrayList<>();
    List<RunState<S>> loopStates = new ArrayList<>();
    Map<RunState<S>, RunState<S>> successors = new HashMap<>();
    RunState<S> backLink = loop.get(loop.size() - 1);
    boolean inLoop = false;
    List<RunState<S>> states = loop.subList(0, loop.size() - 1);
    var iterator = states.iterator();
    @Nullable
    RunState<S> previous = null;
    while (iterator.hasNext()) {
      RunState<S> state = iterator.next();
      if (!inLoop && state.equals(backLink)) {
        inLoop = true;
      }
      if (previous != null) {
        successors.put(previous, state);
      }
      if (inLoop) {
        loopStates.add(state);
      } else {
        transientStates.add(state);
      }
      previous = state;
    }
    successors.put(previous, backLink);
    checkArgument(inLoop);

    this.transientStates = List.copyOf(transientStates);
    this.loopStates = List.copyOf(loopStates);
    this.successors = Map.copyOf(successors);
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

  public RunState<S> successor(RunState<S> state) {
    return successors.get(state);
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
