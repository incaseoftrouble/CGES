package com.cges.parity;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.graph.SuspectGame;
import com.cges.graph.SuspectGame.EveState;
import com.cges.model.Agent;
import de.tum.in.naturals.bitset.BitSets;
import java.util.BitSet;
import java.util.HashMap;
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
  private final Map<S, BitSet> gameStateLabelCache;
  private final int maximumPriority;
  private final Map<String, Integer> propositionIndex;

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

    List<String> propositions = dpa.atomicPropositions();
    propositionIndex = IntStream.range(0, propositions.size())
        .boxed()
        .collect(Collectors.toMap(propositions::get, Function.identity()));
    gameStateLabelCache = new HashMap<>();
  }

  public static <S> SuspectParityGame<S>
  create(SuspectGame<S> suspectGame, EveState<S> eveState, Automaton<Object, ParityAcceptance> dpa) {
    checkArgument(dpa.acceptance().parity().equals(ParityAcceptance.Parity.MIN_EVEN));
    return new SuspectParityGame<>(suspectGame, new PriorityState<>(dpa.initialState(), eveState, 0), dpa);
  }

  @Override
  public PriorityState<S> initialState() {
    return initialState;
  }

  @Override
  public Stream<PriorityState<S>> successors(PriorityState<S> current) {
    if (current.isEve()) {
      EveState<S> eveState = current.eve();
      BitSet label = BitSets.copyOf(gameStateLabelCache.computeIfAbsent(eveState.gameState(), state -> {
        BitSet set = new BitSet();
        suspectGame.historyGame().concurrentGame().labels(state).stream()
            .map(propositionIndex::get)
            .filter(Objects::nonNull)
            .forEach(set::set);
        return set;
      }));
      eveState.suspects().stream().map(Agent::name).map(propositionIndex::get).filter(Objects::nonNull).forEach(label::set);

      assert dpa.edges(current.automatonState(), label).size() == 1;
      Edge<Object> automatonEdge = dpa.edge(current.automatonState(), label);
      assert automatonEdge != null;
      int priority = maximumPriority - automatonEdge.colours().first().orElse(maximumPriority);
      return suspectGame.successors(eveState).map(adam -> new PriorityState<>(automatonEdge.successor(), adam, priority));
    }
    return Stream.concat(Stream.of(suspectGame.compliantSuccessor(current.adam())), suspectGame.deviationSuccessors(current.adam()))
        .map(successor -> new PriorityState<>(current.automatonState(), successor, 0));
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
