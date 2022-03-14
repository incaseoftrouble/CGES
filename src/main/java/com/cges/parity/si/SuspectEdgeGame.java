package com.cges.parity.si;

import com.cges.algorithm.SuspectGame;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import owl.automaton.Automaton;
import owl.automaton.acceptance.ParityAcceptance;
import owl.automaton.edge.Edge;

public class SuspectEdgeGame<S> implements EdgeParityGame<EdgePriorityState<S>> {
  private final SuspectGame<S> suspectGame;
  private final Automaton<Object, ParityAcceptance> dpa;
  private final SetMultimap<EdgePriorityState<S>, Edge<EdgePriorityState<S>>> successors;

  public SuspectEdgeGame(SuspectGame<S> suspectGame, Automaton<Object, ParityAcceptance> dpa) {
    this.suspectGame = suspectGame;
    this.dpa = dpa;

    List<String> propositions = dpa.factory().atomicPropositions();
    Map<String, Integer> propositionIndex = IntStream.range(0, propositions.size())
        .boxed()
        .collect(Collectors.toMap(propositions::get, Function.identity()));
    ConcurrentGame<S> concurrentGame = suspectGame.historyGame().concurrentGame();
    var labelCache = concurrentGame.states().collect(Collectors.toUnmodifiableMap(
        Function.identity(), state -> {
          BitSet set = new BitSet();
          concurrentGame.labels(state).stream().map(propositionIndex::get).filter(Objects::nonNull).forEach(set::set);
          return set;
        }));

    ImmutableSetMultimap.Builder<EdgePriorityState<S>, Edge<EdgePriorityState<S>>> successorBuilder = ImmutableSetMultimap.builder();
    Set<EdgePriorityState<S>> reached = new HashSet<>(List.of(new EdgePriorityState<>(dpa.onlyInitialState(), suspectGame.initialState())));
    Queue<EdgePriorityState<S>> queue = new ArrayDeque<>(reached);
    while (!queue.isEmpty()) {
      EdgePriorityState<S> state = queue.poll();

      Set<Edge<EdgePriorityState<S>>> stateSuccessors;
      if (state.isEve()) {
        assert dpa.edges(state.automatonState()).size() == 1;
        Edge<Object> automatonEdge = dpa.edge(state.automatonState(), labelCache.get(state.eve().gameState()));
        assert automatonEdge != null;
        stateSuccessors = suspectGame.successors(state.eve()).stream().map(successor -> {
          Collection<SuspectGame.EveState<S>> eveSuccessors = suspectGame.successors(successor);
          EdgePriorityState<S> prioritySuccessor;
          if (eveSuccessors.size() == 1) {
            // Inline the "choice" of adam
            prioritySuccessor = new EdgePriorityState<>(automatonEdge.successor(), Iterables.getOnlyElement(eveSuccessors));
          } else {
            prioritySuccessor = new EdgePriorityState<>(automatonEdge.successor(), successor);
          }
          return automatonEdge.withSuccessor(prioritySuccessor);
        }).collect(Collectors.toSet());
      } else {
        stateSuccessors = suspectGame.successors(state.adam()).stream()
            .map(successor -> Edge.of(new EdgePriorityState<S>(state.automatonState(), successor), 0)).collect(Collectors.toSet());
      }
      successorBuilder.putAll(state, stateSuccessors);
      for (Edge<EdgePriorityState<S>> edge : stateSuccessors) {
        if (reached.add(edge.successor())) {
          queue.add(edge.successor());
        }
      }
    }
    this.successors = successorBuilder.build();
  }

  @Override
  public EdgePriorityState<S> initialState() {
    return new EdgePriorityState<>(dpa.onlyInitialState(), suspectGame.initialState());
  }

  @Override
  public Stream<Edge<EdgePriorityState<S>>> edges(EdgePriorityState<S> state) {
    return successors.get(state).stream();
  }

  @Override
  public ParityAcceptance acceptance() {
    return dpa.acceptance();
  }

  @Override
  public Set<EdgePriorityState<S>> states() {
    return successors.keySet();
  }

  @Override
  public Set<EdgePriorityState<S>> states(Player owner) {
    return successors.keySet().stream().filter(s -> owner(s) == owner).collect(Collectors.toSet());
  }

  @Override
  public Player owner(EdgePriorityState<S> state) {
    return state.isEve() ? Player.ODD : Player.EVEN;
  }
}
