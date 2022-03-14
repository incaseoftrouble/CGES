package com.cges.algorithm;

import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.PayoffAssignment;
import com.cges.model.Transition;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
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

public final class RunGraph<S> {
  private static final Logger logger = Logger.getLogger(RunGraph.class.getName());

  public static <S> RunGraph<S> create(HistoryGame<S> historyGame, PayoffAssignment payoffAssignment,
      Predicate<HistoryGame.HistoryState<S>> winningHistoryStates) {
    if (!winningHistoryStates.test(historyGame.initialState())) {
      return new RunGraph<>(Set.of(), Set.of(), ImmutableSetMultimap.of());
    }
    logger.log(Level.FINE, "Computing run graph");

    Environment env = Environment.standard();
    ConcurrentGame<S> concurrentGame = historyGame.concurrentGame();
    Set<Agent> agents = concurrentGame.agents();

    LabelledFormula eveGoal = SimplifierFactory.apply(LabelledFormula.of(
        Conjunction.of(agents.stream().map(a -> payoffAssignment.isLoser(a) ? a.goal().not() : a.goal())),
        concurrentGame.atomicPropositions()), SimplifierFactory.Mode.SYNTACTIC_FIXPOINT);
    LiteralMapper.ShiftedLabelledFormula shifted = LiteralMapper.shiftLiterals(eveGoal);
    LTL2NAFunction translator = new LTL2NAFunction(BuchiAcceptance.class, env);
    @SuppressWarnings("unchecked")
    var automaton = AcceptanceOptimizations.optimize((Automaton<Object, ?>) translator.apply(shifted.formula));
    automaton.trim();

    Set<RunState<S>> initialStates = automaton.initialStates().stream()
        .map(s -> new RunState<>(s, historyGame.initialState(), false))
        .collect(Collectors.toSet());

    ImmutableSetMultimap.Builder<RunState<S>, Transition<RunState<S>>> runGraph = ImmutableSetMultimap.builder();
    Set<RunState<S>> states = new HashSet<>(initialStates);
    Queue<RunState<S>> queue = new ArrayDeque<>(states);
    Map<S, BitSet> labelCache = new HashMap<>();

    List<String> propositions = automaton.factory().atomicPropositions();
    Map<String, Integer> propositionIndex = IntStream.range(0, propositions.size())
        .boxed()
        .collect(Collectors.toMap(propositions::get, Function.identity()));

    while (!queue.isEmpty()) {
      RunState<S> current = queue.poll();

      Set<Edge<Object>> automatonEdges = automaton.edges(current.automatonState(),
          labelCache.computeIfAbsent(current.historyState().state(), state -> {
            BitSet set = new BitSet();
            concurrentGame.labels(current.historyState().state()).stream()
                .map(propositionIndex::get)
                .filter(Objects::nonNull)
                .forEach(set::set);
            return set;
          }));
      if (automatonEdges.isEmpty()) {
        continue;
      }

      var transitions = historyGame.transitions(current.historyState())
          .filter(t -> winningHistoryStates.test(t.destination()))
          .flatMap(transition -> automatonEdges.stream().map(edge ->
              transition.withDestination(new RunState<>(edge.successor(), transition.destination(), edge.hasAcceptanceSets()))))
          .toList();
      assert !transitions.isEmpty() : "No winning successor in state %s".formatted(current);

      runGraph.putAll(current, transitions);
      for (Transition<RunState<S>> successor : transitions) {
        if (states.add(successor.destination())) {
          queue.add(successor.destination());
        }
      }
    }

    logger.log(Level.FINER, () -> "Run graph has %d states, %d initial".formatted(states.size(), initialStates.size()));
    return new RunGraph<>(initialStates, states, runGraph.build());
  }

  private final Set<RunState<S>> initialStates;
  private final Set<RunState<S>> states;
  private final SetMultimap<RunState<S>, Transition<RunState<S>>> successors;

  private RunGraph(Set<RunState<S>> initialStates, Set<RunState<S>> states, SetMultimap<RunState<S>, Transition<RunState<S>>> successors) {
    this.initialStates = Set.copyOf(initialStates);
    this.states = Set.copyOf(states);
    this.successors = ImmutableSetMultimap.copyOf(successors);
  }

  public Set<RunState<S>> initialStates() {
    return initialStates;
  }

  public Set<RunState<S>> states() {
    return states;
  }

  public int size() {
    return states.size();
  }

  public Set<Transition<RunState<S>>> transitions(RunState<S> state) {
    assert states.contains(state);
    return successors.get(state);
  }

  public Set<RunState<S>> successors(RunState<S> state) {
    return transitions(state).stream().map(Transition::destination).collect(Collectors.toSet());
  }

  public record RunState<S>(Object automatonState, HistoryGame.HistoryState<S> historyState, boolean accepting) {
    @Override
    public String toString() {
      return "(%s,%s)%s".formatted(automatonState, historyState, accepting ? "!" : "");
    }
  }
}
