package com.cges.parity;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.algorithm.SuspectGame;
import com.cges.algorithm.SuspectGame.EveState;
import com.cges.model.ConcurrentGame;
import com.google.common.collect.Iterables;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import owl.automaton.Automaton;
import owl.automaton.acceptance.ParityAcceptance;
import owl.automaton.edge.Edge;

public final class SuspectParityGame<S> implements ParityGame<PriorityState<S>> {
  private final SuspectGame<S> suspectGame;
  private final PriorityState<S> initialState;
  private final Automaton<Object, ParityAcceptance> dpa;
  private final Map<S, BitSet> labelCache;
  private final int maximumPriority;

  private SuspectParityGame(SuspectGame<S> suspectGame, PriorityState<S> initialState, Automaton<Object, ParityAcceptance> dpa) {
    assert !dpa.acceptance().parity().max();
    this.suspectGame = suspectGame;
    this.initialState = initialState;
    this.dpa = dpa;

    // We have a min even objective and want max + let eve be odd player
    int maximumPriority = dpa.acceptance().acceptanceSets();
    if (dpa.acceptance().isAccepting(maximumPriority)) {
      // Ensure that maximum priority is odd, so if priority is even then maximum priority is odd
      maximumPriority += 1;
    }
    this.maximumPriority = maximumPriority;

    List<String> propositions = dpa.factory().atomicPropositions();
    Map<String, Integer> propositionIndex = IntStream.range(0, propositions.size())
        .boxed()
        .collect(Collectors.toMap(propositions::get, Function.identity()));
    ConcurrentGame<S> concurrentGame = suspectGame.historyGame().concurrentGame();
    labelCache = concurrentGame.states().collect(Collectors.toUnmodifiableMap(Function.identity(), state -> {
      BitSet set = new BitSet();
      concurrentGame.labels(state).stream().map(propositionIndex::get).filter(Objects::nonNull).forEach(set::set);
      return set;
    }));
  }

  public static <S> SuspectParityGame<S>
  create(SuspectGame<S> suspectGame, EveState<S> eveState, Automaton<Object, ParityAcceptance> dpa) {
    checkArgument(dpa.acceptance().parity().equals(ParityAcceptance.Parity.MIN_EVEN));
    return new SuspectParityGame<>(suspectGame, new PriorityState<S>(dpa.onlyInitialState(), eveState, 0), dpa);
  }

  @Override
  public PriorityState<S> initialState() {
    return initialState;
  }

  @Override
  public Stream<PriorityState<S>> successors(PriorityState<S> current) {
    if (current.isEve()) {
      BitSet label = labelCache.get(current.eve().gameState());
      assert dpa.edges(current.automatonState(), label).size() == 1;
      Edge<Object> automatonEdge = dpa.edge(current.automatonState(), label);
      assert automatonEdge != null;
      int priority = automatonEdge.hasAcceptanceSets() ? maximumPriority - automatonEdge.smallestAcceptanceSet() : 0;
      return suspectGame.successors(current.eve()).stream().map(successor -> {
        Collection<EveState<S>> eveSuccessors = suspectGame.successors(successor);
        return new PriorityState<S>(automatonEdge.successor(),
            eveSuccessors.size() == 1 ? Iterables.getOnlyElement(eveSuccessors) : successor, priority);
      });
    }
    return suspectGame.successors(current.adam()).stream()
        .map(successor -> new PriorityState<S>(current.automatonState(), successor, 0));
  }

  @Override
  public int priority(PriorityState<S> state) {
    return state.priority();
  }

  @Override
  public Player owner(PriorityState<S> state) {
    return state.isEve() ? Player.ODD : Player.EVEN;
  }
}
