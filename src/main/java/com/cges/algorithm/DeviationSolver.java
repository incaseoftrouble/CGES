package com.cges.algorithm;

import static owl.automaton.acceptance.ParityAcceptance.Parity;

import com.cges.graph.HistoryGame;
import com.cges.graph.HistoryGame.HistoryState;
import com.cges.graph.SuspectGame;
import com.cges.graph.SuspectGame.EveState;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Move;
import com.cges.model.PayoffAssignment;
import com.cges.model.Transition;
import com.cges.parity.OinkGameSolver;
import com.cges.parity.Player;
import com.cges.parity.PriorityState;
import com.cges.parity.Solution;
import com.cges.parity.SuspectParityGame;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import owl.automaton.Automaton;
import owl.automaton.ParityUtil;
import owl.automaton.acceptance.ParityAcceptance;
import owl.ltl.Conjunction;
import owl.ltl.Disjunction;
import owl.ltl.GOperator;
import owl.ltl.LabelledFormula;
import owl.ltl.Literal;
import owl.ltl.rewriter.LiteralMapper;
import owl.ltl.rewriter.SimplifierRepository;
import owl.translations.LtlTranslationRepository;
import owl.translations.LtlTranslationRepository.Option;

public final class DeviationSolver<S> implements PunishmentStrategy<S> {
  private static final boolean CROSS_VALIDATE = false;

  private static final Function<LabelledFormula, Automaton<?, ? extends ParityAcceptance>> translation =
      LtlTranslationRepository.LtlToDpaTranslation.SEJK16_EKRS17.translation(EnumSet.of(Option.SIMPLIFY_AUTOMATON));
  private static final Function<LabelledFormula, Automaton<?, ? extends ParityAcceptance>> referenceTranslation =
      LtlTranslationRepository.defaultTranslation(EnumSet.of(Option.COMPLETE),
          LtlTranslationRepository.BranchingMode.DETERMINISTIC, ParityAcceptance.class);

  private final SuspectGame<S> suspectGame;
  private final OinkGameSolver solver = new OinkGameSolver();
  private final Map<Agent, Literal> agentLiterals;
  private final List<String> atomicPropositions;
  private final Set<Agent> losingAgents;
  private final SuspectParityGame<S> parityGame;
  private final Solution<PriorityState<S>> paritySolution;

  public DeviationSolver(SuspectGame<S> suspectGame, PayoffAssignment payoff) {
    this.suspectGame = suspectGame;

    HistoryGame<S> historyGame = suspectGame.historyGame();
    ConcurrentGame<S> concurrentGame = historyGame.concurrentGame();
    losingAgents = concurrentGame.agents().stream().filter(payoff::isLoser).collect(Collectors.toSet());
    atomicPropositions = Stream.concat(concurrentGame.atomicPropositions().stream(), losingAgents.stream().map(Agent::name)).toList();
    agentLiterals = losingAgents.stream().collect(Collectors.toMap(Function.identity(),
        a -> Literal.of(atomicPropositions.indexOf(a.name()))));
    assert Set.copyOf(atomicPropositions).size() == atomicPropositions.size();

    var historyState = historyGame.initialState();
    var eveState = new EveState<S>(historyState, losingAgents);
    var goal = SimplifierRepository.SYNTACTIC_FAIRNESS.apply(LabelledFormula.of(Disjunction.of(losingAgents.stream()
        .map(a -> Conjunction.of(GOperator.of(agentLiterals.get(a)), historyState.goal(a)))).not(), atomicPropositions));

    var gameSolution = solveParityGame(eveState, goal);
    parityGame = gameSolution.parityGame();
    paritySolution = gameSolution.solution();
  }

  private LabelledFormula eveGoal(HistoryState<S> historyState) {
    return LabelledFormula.of(Disjunction.of(losingAgents.stream()
        .map(a -> Conjunction.of(GOperator.of(agentLiterals.get(a)), historyState.goal(a)))).not(), atomicPropositions);
  }

  public Stream<Move> winningMoves(HistoryState<S> historyState) {
    return suspectGame.historyGame().transitions(historyState)
        .map(Transition::move)
        .filter(m -> isWinning(historyState, m));
  }

  public boolean isWinning(HistoryState<S> historyState, Move proposedMove) {
    return parityGame.deviationStates(historyState, proposedMove).map(paritySolution::winner).allMatch(Player.ODD::equals);
  }

  private boolean computeWinning(HistoryState<S> historyState) {
    var solution = solveParityGame(new EveState<>(historyState, losingAgents), eveGoal(historyState));
    return suspectGame.historyGame().transitions(historyState).map(Transition::move)
        .anyMatch(move -> solution.parityGame.deviationStates(historyState, move)
            .map(solution.solution()::winner)
            .allMatch(Player.ODD::equals));
  }

  @Override
  public Set<PriorityState<S>> states(HistoryState<S> state, Move proposedMove) {
    return parityGame.deviationStates(state, proposedMove).collect(Collectors.toSet());
  }

  @Override
  public PriorityState<S> move(PriorityState<S> state) {
    assert state.isEve();
    PriorityState<S> successor = paritySolution.oddStrategy().get(state);
    assert !successor.isEve() : "Non-alternating players in %s -> %s".formatted(state, successor);
    return successor;
  }

  @Override
  public Set<PriorityState<S>> successors(PriorityState<S> state) {
    return state.isEve() ? Set.of(move(state)) : parityGame.successors(state).collect(Collectors.toSet());
  }

  record ParitySolution<S>(SuspectParityGame<S> parityGame, Solution<PriorityState<S>> solution) {}

  private ParitySolution<S> solveParityGame(EveState<S> eveState, LabelledFormula goal) {
    var shifted = LiteralMapper.shiftLiterals(goal);
    @SuppressWarnings("unchecked")
    var automaton = (Automaton<Object, ParityAcceptance>) ParityUtil.convert(translation.apply(shifted.formula), Parity.MIN_EVEN);
    assert !automaton.states().isEmpty();
    var parityGame = SuspectParityGame.create(suspectGame, eveState, automaton);
    var paritySolution = solver.solve(parityGame);
    return new ParitySolution<>(parityGame, paritySolution);
  }
}
