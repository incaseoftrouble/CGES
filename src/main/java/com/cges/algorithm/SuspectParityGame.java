package com.cges.algorithm;

import static com.cges.algorithm.SuspectParityGame.PriorityState;
import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
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

public final class SuspectParityGame<S> implements OinkGameSolver.ParityGame<PriorityState<S>> {
  private final PriorityState<S> initialState;
  private final Set<PriorityState<S>> states;
  private final SetMultimap<PriorityState<S>, PriorityState<S>> successors;

  private SuspectParityGame(PriorityState<S> initialState, Set<PriorityState<S>> states,
      SetMultimap<PriorityState<S>, PriorityState<S>> successors) {
    this.initialState = initialState;
    this.states = Set.copyOf(states);
    this.successors = ImmutableSetMultimap.copyOf(successors);
  }

  public static <S> SuspectParityGame<S> create(SuspectGame<S> suspectGame, SuspectGame.EveState<S> eveState,
      Automaton<Object, ParityAcceptance> dpa, @Nullable Predicate<SuspectGame.EveState<S>> winningStates) {
    checkArgument(dpa.acceptance().parity().equals(ParityAcceptance.Parity.MIN_EVEN));
    PriorityState<S> initialState = new PriorityState<>(dpa.onlyInitialState(), eveState, 0);
    Queue<PriorityState<S>> queue = new ArrayDeque<>(List.of(initialState));
    Set<PriorityState<S>> states = new HashSet<>(queue);
    ImmutableSetMultimap.Builder<PriorityState<S>, PriorityState<S>> successors = ImmutableSetMultimap.builder();

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
    PriorityState<S> winningState = new PriorityState<>(null, null, 1);

    while (!queue.isEmpty()) {
      PriorityState<S> current = queue.poll();
      Collection<PriorityState<S>> currentSuccessors;
      if (Objects.equals(current, winningState)) {
        currentSuccessors = Set.of(winningState);
      } else if (current.isEve()) {
        if (winningStates != null && winningStates.test(current.eve())) {
          currentSuccessors = Set.of(winningState);
        } else {
          BitSet transition = new BitSet();
          suspectGame.historyGame().concurrentGame().labels(current.eve().gameState()).stream()
              .map(propositionIndex::get)
              .filter(Objects::nonNull)
              .forEach(transition::set);
          Set<Edge<Object>> automatonEdges = dpa.edges(current.automatonState(), transition);
          Edge<Object> automatonEdge = Iterables.getOnlyElement(automatonEdges);

          int priority = automatonEdge.hasAcceptanceSets() ? maximumPriority - automatonEdge.smallestAcceptanceSet() : 0;
          currentSuccessors = suspectGame.successors(current.eve()).stream()
              .map(successor -> {
                Collection<SuspectGame.EveState<S>> eveSuccessors = suspectGame.successors(successor);
                if (eveSuccessors.size() == 1) {
                  // Inline the "choice" of adam
                  return new PriorityState<S>(automatonEdge.successor(), Iterables.getOnlyElement(eveSuccessors), priority);
                } else {
                  return new PriorityState<S>(automatonEdge.successor(), successor, priority);
                }
              })
              .collect(Collectors.toSet());
        }
      } else {
        currentSuccessors = suspectGame.successors(current.adam()).stream()
            .map(successor -> new PriorityState<S>(current.automatonState(), successor, current.priority()))
            .collect(Collectors.toSet());
      }
      successors.putAll(current, currentSuccessors);
      for (PriorityState<S> paritySuccessor : currentSuccessors) {
        if (states.add(paritySuccessor)) {
          queue.add(paritySuccessor);
        }
      }
    }

    return new SuspectParityGame<>(initialState, states, successors.build());
  }

  @Override
  public PriorityState<S> initialState() {
    return initialState;
  }

  @Override
  public Set<PriorityState<S>> successors(PriorityState<S> state) {
    assert states.contains(state);
    return successors.get(state);
  }

  @Override
  public int priority(PriorityState<S> state) {
    return state.priority();
  }

  @Override
  public boolean isEvenPlayer(PriorityState<S> state) {
    return !state.isEve();
  }

  @Override
  public Set<PriorityState<S>> states() {
    return states;
  }


  @SuppressWarnings("unchecked")
  public record PriorityState<S>(Object automatonState, Object gameState, int priority) {
    public boolean isEve() {
      return gameState instanceof SuspectGame.EveState;
    }

    public SuspectGame.AdamState<S> adam() {
      return (SuspectGame.AdamState<S>) gameState;
    }

    public SuspectGame.EveState<S> eve() {
      return (SuspectGame.EveState<S>) gameState;
    }

    @Override
    public String toString() {
      return "[%s,%s,%d]".formatted(automatonState, gameState, priority);
    }
  }
}
