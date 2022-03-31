package com.cges.graph;

import com.cges.algorithm.DeviationSolver;
import com.cges.algorithm.PunishmentStrategy;
import com.cges.graph.HistoryGame.HistoryState;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.PayoffAssignment;
import com.cges.model.Transition;
import com.cges.output.DotFormatted;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import owl.automaton.Automaton;
import owl.automaton.acceptance.BuchiAcceptance;
import owl.automaton.edge.Edge;
import owl.ltl.BooleanConstant;
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
  private final DeviationSolver<S> deviationSolver;
  private final HistoryGame<S> historyGame;

  @SuppressWarnings("unchecked")
  public RunGraph(SuspectGame<S> suspectGame, PayoffAssignment payoffAssignment) {
    this.suspectGame = suspectGame;
    this.historyGame = suspectGame.historyGame();
    ConcurrentGame<S> concurrentGame = suspectGame.historyGame().concurrentGame();
    Set<Agent> agents = concurrentGame.agents();

    LabelledFormula eveGoal = SimplifierRepository.SYNTACTIC_FIXPOINT.apply(LabelledFormula.of(
        Conjunction.of(Stream.concat(agents.stream().map(a -> payoffAssignment.isLoser(a) ? a.goal().not() : a.goal()),
            Stream.of(suspectGame.historyGame().concurrentGame().goal().formula()))),
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
    HistoryState<S> initialState = historyGame.initialState();
    if (historyGame.transitions(initialState).map(Transition::move).anyMatch(m -> deviationSolver.isWinning(initialState, m))) {
      return automaton.initialStates().stream()
          .map(s -> new RunState<>(s, initialState))
          .collect(Collectors.toSet());
    }
    return Set.of();
  }

  public PunishmentStrategy<S> deviationStrategy() {
    return deviationSolver;
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
        .filter(t -> deviationSolver.isWinning(current.historyState(), t.move()))
        .flatMap(transition -> automatonEdges.stream().map(edge ->
            new RunTransition<>(new RunState<>(edge.successor(), transition.destination()), !edge.colours().isEmpty())))
        .collect(Collectors.toSet());
    assert !transitions.isEmpty() : "No winning moves in state %s".formatted(current);
    return transitions;
  }

  public Set<RunState<S>> successors(RunState<S> state) {
    return transitions(state).stream().map(RunTransition::successor).collect(Collectors.toSet());
  }

  public SuspectGame<S> suspectGame() {
    return suspectGame;
  }

  public List<String> automatonPropositions() {
    return automaton.atomicPropositions();
  }

  public record RunState<S>(Object automatonState, HistoryState<S> historyState) implements DotFormatted {
    @Override
    public String toString() {
      return "(%s x %s)".formatted(historyState, automatonState);
    }


    @Override
    public String dotString() {
      ConcurrentGame<S> game = historyState.game().concurrentGame();
      var goalsString = game.agents().stream()
          .sorted(Comparator.comparing(Agent::name))
          .filter(a -> !historyState.goal(a).equals(BooleanConstant.TRUE))
          .map(a -> {
            var goalString = LabelledFormula.of(historyState.goal(a), game.atomicPropositions()).toString();
            int goalLength = goalString.length();
            return goalLength < 20
                ? goalString
                : "%s...%s".formatted(goalString.substring(0, 7), goalString.substring(goalLength - 7));
          }).collect(Collectors.joining(",", "[", "]"));
      var automatonString = DotFormatted.toDotString(automatonState);
      int automatonLength = automatonString.length();
      if (automatonLength > 20) {
        automatonString = "%s...%s".formatted(automatonString.substring(0, 7), automatonString.substring(automatonLength - 7));
      }
      return "%s: %s x %s".formatted(DotFormatted.toDotString(historyState.state()), goalsString, automatonString);
    }
  }
}
