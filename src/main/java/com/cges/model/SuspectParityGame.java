package com.cges.model;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.algorithm.OinkGameSolver;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import de.tum.in.naturals.bitset.BitSets;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import owl.automaton.Automaton;
import owl.automaton.acceptance.ParityAcceptance;
import owl.automaton.edge.Edge;

public final class SuspectParityGame implements OinkGameSolver.ParityGame<SuspectParityGame.PriorityState> {
  private final PriorityState initialState;
  private final Set<PriorityState> states;
  private final SetMultimap<PriorityState, PriorityState> successors;

  private SuspectParityGame(PriorityState initialState, Set<PriorityState> states, SetMultimap<PriorityState, PriorityState> successors) {
    this.initialState = initialState;
    this.states = states;
    this.successors = successors;
  }

  public static SuspectParityGame create(SuspectGame suspectGame, SuspectGame.EveState eveState, Automaton<Object, ParityAcceptance> dpa,
      @Nullable Predicate<SuspectGame.EveState> winningStates) {
    checkArgument(dpa.acceptance().parity().equals(ParityAcceptance.Parity.MIN_EVEN));
    PriorityState initialState = new PriorityState(dpa.onlyInitialState(), eveState, 0);
    Queue<PriorityState> queue = new ArrayDeque<>(List.of(initialState));
    Set<PriorityState> states = new HashSet<>(queue);
    ImmutableSetMultimap.Builder<PriorityState, PriorityState> successors = ImmutableSetMultimap.builder();

    List<String> propositions = dpa.factory().atomicPropositions();
    Map<String, Integer> propositionIndex = IntStream.range(0, propositions.size())
        .boxed()
        .collect(Collectors.toMap(propositions::get, Function.identity()));

    // We have a min even objective and want max + let eve be odd player
    int maximumPriority = dpa.acceptance().acceptanceSets();
    if (dpa.acceptance().isAccepting(maximumPriority)) {
      // Ensure that maximum priority is odd, so if priority is even then maximumPriority - priority is odd
      maximumPriority += 1;
    }
    PriorityState winningState = new PriorityState(null, null, 1);

    while (!queue.isEmpty()) {
      PriorityState current = queue.poll();
      Collection<PriorityState> currentSuccessors;
      if (Objects.equals(current, winningState)) {
        currentSuccessors = Set.of(winningState);
      } else if (current.isEve()) {
        if (winningStates != null && winningStates.test(current.eve())) {
          currentSuccessors = Set.of(winningState);
        } else {
          Integer index = propositionIndex.get(current.eve().gameState().name());
          BitSet transition = index == null ? BitSets.of() : BitSets.of(index);
          Set<Edge<Object>> automatonEdges = dpa.edges(current.automatonState(), transition);
          Edge<Object> automatonEdge = Iterables.getOnlyElement(automatonEdges);

          int priority = automatonEdge.hasAcceptanceSets() ? maximumPriority - automatonEdge.smallestAcceptanceSet() : 0;
          currentSuccessors = suspectGame.successors(current.eve()).stream()
              .map(successor -> {
                Collection<SuspectGame.EveState> eveSuccessors = suspectGame.successors(successor);
                if (eveSuccessors.size() == 1) {
                  return new PriorityState(automatonEdge.successor(), Iterables.getOnlyElement(eveSuccessors), priority);
                } else {
                  return new PriorityState(automatonEdge.successor(), successor, priority);
                }
              })
              .collect(Collectors.toSet());
        }
      } else {
        currentSuccessors = suspectGame.successors(current.adam()).stream()
            .map(successor -> new PriorityState(current.automatonState(), successor, current.priority()))
            .collect(Collectors.toSet());
      }
      successors.putAll(current, currentSuccessors);
      for (PriorityState paritySuccessor : currentSuccessors) {
        if (states.add(paritySuccessor)) {
          queue.add(paritySuccessor);
        }
      }
    }

    return new SuspectParityGame(initialState, states, successors.build());
  }

  @Override
  public PriorityState initialState() {
    return initialState;
  }

  @Override
  public Set<PriorityState> successors(PriorityState state) {
    assert states.contains(state);
    return successors.get(state);
  }

  @Override
  public int priority(PriorityState state) {
    return state.priority();
  }

  @Override
  public boolean isEvenPlayer(PriorityState state) {
    return !state.isEve();
  }

  @Override
  public Set<PriorityState> states() {
    return states;
  }


  public record PriorityState(Object automatonState, Object gameState, int priority) {
    public boolean isEve() {
      return gameState instanceof SuspectGame.EveState;
    }

    public SuspectGame.AdamState adam() {
      return (SuspectGame.AdamState) gameState;
    }

    public SuspectGame.EveState eve() {
      return (SuspectGame.EveState) gameState;
    }

    @Override
    public String toString() {
      return "[%s,%s,%d]".formatted(automatonState, gameState, priority);
    }
  }
}
