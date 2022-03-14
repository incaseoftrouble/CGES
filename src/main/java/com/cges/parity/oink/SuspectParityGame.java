package com.cges.parity.oink;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.algorithm.SuspectGame;
import com.cges.algorithm.SuspectGame.EveState;
import com.cges.model.ConcurrentGame;
import com.cges.parity.Player;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import owl.automaton.Automaton;
import owl.automaton.acceptance.ParityAcceptance;
import owl.automaton.edge.Edge;

public final class SuspectParityGame<S> implements ParityGame<PriorityState<S>> {
  private final PriorityState<S> initialState;
  private final Set<PriorityState<S>> states;
  private final SetMultimap<PriorityState<S>, PriorityState<S>> successors;

  private SuspectParityGame(PriorityState<S> initialState, Set<PriorityState<S>> states,
      SetMultimap<PriorityState<S>, PriorityState<S>> successors) {
    this.initialState = initialState;
    this.states = Set.copyOf(states);
    this.successors = ImmutableSetMultimap.copyOf(successors);
  }

  public static <S> SuspectParityGame<S> create(SuspectGame<S> suspectGame, EveState<S> eveState, Automaton<Object, ParityAcceptance> dpa) {
    checkArgument(dpa.acceptance().parity().equals(ParityAcceptance.Parity.MIN_EVEN));
    PriorityState<S> initialState = new PriorityState<>(dpa.onlyInitialState(), eveState, 0);
    Queue<PriorityState<S>> queue = new ArrayDeque<>(List.of(initialState));
    Set<PriorityState<S>> states = new HashSet<>(queue);
    ImmutableSetMultimap.Builder<PriorityState<S>, PriorityState<S>> successors = ImmutableSetMultimap.builder();

    List<String> propositions = dpa.factory().atomicPropositions();
    Map<String, Integer> propositionIndex = IntStream.range(0, propositions.size())
        .boxed()
        .collect(Collectors.toMap(propositions::get, Function.identity()));
    ConcurrentGame<S> concurrentGame = suspectGame.historyGame().concurrentGame();
    Map<S, BitSet> labelCache = concurrentGame.states().collect(Collectors.toMap(Function.identity(), state -> {
      BitSet set = new BitSet();
      concurrentGame.labels(state).stream().map(propositionIndex::get).filter(Objects::nonNull).forEach(set::set);
      return set;
    }));

    // We have a min even objective and want max + let eve be odd player
    int maximumPriority = dpa.acceptance().acceptanceSets();
    if (dpa.acceptance().isAccepting(maximumPriority)) {
      // Ensure that maximum priority is odd, so if priority is even then maximumPriority - priority is odd
      maximumPriority += 1;
    }

    while (!queue.isEmpty()) {
      PriorityState<S> current = queue.poll();
      Collection<PriorityState<S>> currentSuccessors;
      if (current.isEve()) {
        assert dpa.edges(current.automatonState()).size() == 1;
        Edge<Object> automatonEdge = dpa.edge(current.automatonState(), labelCache.get(current.eve().gameState()));
        assert automatonEdge != null;

        int priority = automatonEdge.hasAcceptanceSets() ? maximumPriority - automatonEdge.smallestAcceptanceSet() : 0;
        currentSuccessors = suspectGame.successors(current.eve()).stream().map(successor -> {
          Collection<EveState<S>> eveSuccessors = suspectGame.successors(successor);
          return new PriorityState<S>(automatonEdge.successor(),
              eveSuccessors.size() == 1 ? Iterables.getOnlyElement(eveSuccessors) : successor, priority);
        }).collect(Collectors.toSet());
      } else {
        currentSuccessors = suspectGame.successors(current.adam()).stream()
            .map(successor -> new PriorityState<S>(current.automatonState(), successor, 0))
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
  public Stream<PriorityState<S>> successors(PriorityState<S> state) {
    assert states.contains(state);
    return successors.get(state).stream();
  }

  @Override
  public int priority(PriorityState<S> state) {
    return state.priority();
  }

  @Override
  public Player owner(PriorityState<S> state) {
    return state.isEve() ? Player.ODD : Player.EVEN;
  }

  @Override
  public Set<PriorityState<S>> states() {
    return states;
  }

  @Override
  public void forEachState(Consumer<PriorityState<S>> action) {
    states.forEach(action);
  }
}
