package com.cges.model;

import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import de.tum.in.naturals.bitset.BitSets;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import owl.automaton.Automaton;
import owl.automaton.acceptance.BuchiAcceptance;
import owl.automaton.acceptance.optimization.AcceptanceOptimizations;
import owl.automaton.edge.Edge;
import owl.ltl.Conjunction;
import owl.ltl.LabelledFormula;
import owl.ltl.rewriter.LiteralMapper;
import owl.ltl.rewriter.SimplifierFactory;
import owl.run.Environment;
import owl.translations.LTL2NAFunction;

public final class RunGraph {
  private static final Logger logger = Logger.getLogger(RunGraph.class.getName());

  public static RunGraph create(SuspectGame game, Set<SuspectGame.EveState> winningEveStates) {
    if (!winningEveStates.contains(game.initialState())) {
      return new RunGraph(Set.of(), Set.of(), ImmutableSetMultimap.of());
    }
    logger.log(Level.FINE, "Computing run graph");

    Environment env = Environment.standard();
    LabelledFormula eveGoal = SimplifierFactory.apply(LabelledFormula.of(
        Conjunction.of(game.game().agents().stream().map(a -> a.payoff() ? a.goal() : a.goal().not())),
        game.game().formulaPropositions()), SimplifierFactory.Mode.SYNTACTIC_FIXPOINT);
    LiteralMapper.ShiftedLabelledFormula shifted = LiteralMapper.shiftLiterals(eveGoal);
    LTL2NAFunction translator = new LTL2NAFunction(BuchiAcceptance.class, env);
    var automaton = AcceptanceOptimizations.optimize((Automaton<Object, ?>) translator.apply(shifted.formula));
    automaton.trim();

    Set<State> initialStates = automaton.initialStates().stream()
        .map(s -> new State(s, game.initialState(), false))
        .collect(Collectors.toSet());

    ImmutableSetMultimap.Builder<State, State> runGraph = ImmutableSetMultimap.builder();
    Set<State> states = new HashSet<>(initialStates);
    Queue<State> queue = new ArrayDeque<>(states);

    List<String> propositions = automaton.factory().atomicPropositions();
    Map<String, Integer> propositionIndex = IntStream.range(0, propositions.size())
        .boxed()
        .collect(Collectors.toMap(propositions::get, Function.identity()));

    while (!queue.isEmpty()) {
      State current = queue.poll();
      Integer index = propositionIndex.get(current.eveState().gameState().name());
      BitSet transition = index == null ? BitSets.of() : BitSets.of(index);
      Set<Edge<Object>> automatonEdges = automaton.edges(current.automatonState(), transition);

      Set<State> successors = game.successors(current.eveState()).stream()
          .map(game::compliantSuccessor)
          .flatMap(Optional::stream)
          .filter(winningEveStates::contains) // All winning eve states reachable by complying
          .flatMap(successor -> automatonEdges.stream().map(edge -> new State(edge.successor(), successor, edge.hasAcceptanceSets())))
          .collect(Collectors.toSet());
      runGraph.putAll(current, successors);
      for (State successor : successors) {
        if (states.add(successor)) {
          queue.add(successor);
        }
      }
    }

    logger.log(Level.FINER, () -> "Run graph has %d states, %d initial".formatted(states.size(), initialStates.size()));
    return new RunGraph(initialStates, states, runGraph.build());
  }

  private final Set<State> initialStates;
  private final Set<State> states;
  private final SetMultimap<State, State> successors;

  private RunGraph(Set<State> initialStates, Set<State> states, SetMultimap<State, State> successors) {
    this.initialStates = initialStates;
    this.states = states;
    this.successors = successors;
  }

  public Set<State> initialStates() {
    return initialStates;
  }

  public Set<State> states() {
    return states;
  }

  public int size() {
    return states.size();
  }

  public Set<State> successors(State state) {
    assert states.contains(state);
    return successors.get(state);
  }

  public boolean isAcceptingLasso(List<RunGraph.State> lasso) {
    if (lasso.size() < 2) {
      return false;
    }
    Iterator<State> iterator = lasso.iterator();
    State current = iterator.next();
    if (!initialStates.contains(current)) {
      return false;
    }
    while (iterator.hasNext()) {
      State next = iterator.next();
      if (!successors.get(current).contains(next)) {
        return false;
      }
      current = next;
    }

    State lastState = current;
    int firstOccurrence = lasso.indexOf(lastState);
    if (firstOccurrence == lasso.size() - 1) {
      return false;
    }
    return lasso.subList(firstOccurrence, lasso.size() - 1).stream().anyMatch(State::accepting);
  }

  public record State(Object automatonState, SuspectGame.EveState eveState, boolean accepting) {}
}
