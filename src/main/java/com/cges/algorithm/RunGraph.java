package com.cges.algorithm;

import com.cges.algorithm.HistoryGame.HistoryState;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.PayoffAssignment;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import owl.automaton.Automaton;
import owl.automaton.acceptance.BuchiAcceptance;
import owl.automaton.edge.Edge;
import owl.ltl.Conjunction;
import owl.ltl.LabelledFormula;
import owl.ltl.rewriter.LiteralMapper;
import owl.ltl.rewriter.SimplifierRepository;
import owl.translations.LtlTranslationRepository;
import owl.translations.LtlTranslationRepository.BranchingMode;
import owl.translations.LtlTranslationRepository.Option;

public final class RunGraph<S> {
  public record RunTransition<S>(RunState<S> successor, boolean accepting) {}

  private final Automaton<Object, BuchiAcceptance> automaton;
  private final SuspectGame<S> suspectGame;
  private final Map<S, BitSet> labelCache = new HashMap<>();
  private final Map<String, Integer> propositionIndex;
  private final Map<HistoryState<S>, Optional<DeviationSolver.PunishmentStrategy<S>>> historySolutions = new HashMap<>();
  private final DeviationSolver<S> deviationSolver;
  private final HistoryGame<S> historyGame;

  @SuppressWarnings("unchecked")
  public RunGraph(SuspectGame<S> suspectGame, PayoffAssignment payoffAssignment) {
    this.suspectGame = suspectGame;
    this.historyGame = suspectGame.historyGame();
    ConcurrentGame<S> concurrentGame = suspectGame.historyGame().concurrentGame();
    Set<Agent> agents = concurrentGame.agents();

    LabelledFormula eveGoal = SimplifierRepository.SYNTACTIC_FIXPOINT.apply(LabelledFormula.of(
        Conjunction.of(agents.stream().map(a -> payoffAssignment.isLoser(a) ? a.goal().not() : a.goal())),
        concurrentGame.atomicPropositions()));
    LiteralMapper.ShiftedLabelledFormula shifted = LiteralMapper.shiftLiterals(eveGoal);
    var translator = LtlTranslationRepository.defaultTranslation(
        EnumSet.of(Option.COMPLETE, Option.SIMPLIFY_AUTOMATON),
        BranchingMode.NON_DETERMINISTIC, BuchiAcceptance.class);
    automaton = (Automaton<Object, BuchiAcceptance>) translator.apply(shifted.formula);

    List<String> propositions = automaton.atomicPropositions();
    propositionIndex = IntStream.range(0, propositions.size())
        .boxed()
        .collect(Collectors.toMap(propositions::get, Function.identity()));
    deviationSolver = new DeviationSolver<>(suspectGame, payoffAssignment);
  }

  public Set<RunState<S>> initialStates() {
    if (isWinning(historyGame.initialState())) {
      return automaton.initialStates().stream()
          .map(s -> new RunState<>(s, historyGame.initialState()))
          .collect(Collectors.toSet());
    }
    return Set.of();
  }

  private boolean isWinning(HistoryState<S> historyState) {
    return historySolutions.computeIfAbsent(historyState, deviationSolver::solve).isPresent();
  }

  public DeviationSolver.PunishmentStrategy<S> deviationStrategy(HistoryState<S> historyState) {
    return historySolutions.computeIfAbsent(historyState, deviationSolver::solve).orElseThrow();
  }

  private BitSet labels(RunState<S> current) {
    return labelCache.computeIfAbsent(current.historyState().state(), state -> {
      BitSet set = new BitSet();
      historyGame.concurrentGame().labels(current.historyState().state()).stream()
          .map(propositionIndex::get)
          .filter(Objects::nonNull)
          .forEach(set::set);
      return set;
    });
  }

  public Set<RunTransition<S>> transitions(RunState<S> current) {
    Set<Edge<Object>> automatonEdges = automaton.edges(current.automatonState(), labels(current));
    if (automatonEdges.isEmpty()) {
      return Set.of();
    }
    var transitions = historyGame.transitions(current.historyState())
        .filter(t -> isWinning(t.destination()))
        .flatMap(transition -> automatonEdges.stream().map(edge ->
            new RunTransition<>(new RunState<>(edge.successor(), transition.destination()), !edge.colours().isEmpty())))
        .collect(Collectors.toSet());
    assert !transitions.isEmpty() : "No winning successor in state %s".formatted(current);
    return transitions;
  }

  public Set<RunState<S>> successors(RunState<S> state) {
    return transitions(state).stream().map(RunTransition::successor).collect(Collectors.toSet());
  }

  public SuspectGame<S> suspectGame() {
    return suspectGame;
  }

  public record RunState<S>(Object automatonState, HistoryState<S> historyState) {
    @Override
    public String toString() {
      return "(%s x %s)".formatted(historyState, automatonState);
    }
  }
}
