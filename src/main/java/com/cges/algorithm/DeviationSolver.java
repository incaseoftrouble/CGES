package com.cges.algorithm;

import com.cges.graph.HistoryGame;
import com.cges.graph.HistoryGame.HistoryState;
import com.cges.graph.SuspectGame;
import com.cges.graph.SuspectGame.EveState;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Move;
import com.cges.model.PayoffAssignment;
import com.cges.parity.OinkGameSolver;
import com.cges.parity.Player;
import com.cges.parity.PriorityState;
import com.cges.parity.Solution;
import com.cges.parity.SuspectParityGame;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import owl.automaton.Automaton;
import owl.automaton.ParityUtil;
import owl.automaton.acceptance.ParityAcceptance;
import owl.ltl.BooleanConstant;
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

  @SuppressWarnings("unchecked")
  private static final Function<LabelledFormula, Automaton<Object, ParityAcceptance>> referenceFunction =
      formula -> (Automaton<Object, ParityAcceptance>) referenceTranslation.apply(formula);

  private final SuspectGame<S> suspectGame;
  private final Map<LabelledFormula, Automaton<?, ?>> automatonCache = new HashMap<>();
  private final OinkGameSolver solver = new OinkGameSolver();
  private final Map<Agent, Literal> agentLiterals;
  private final List<String> atomicPropositions;
  private final Set<Agent> losingAgents;
  private final Map<HistoryState<S>, Map<Move, PriorityState<S>>> initialStates;
  private final Map<PriorityState<S>, PriorityState<S>> strategy;
  @Nullable
  private final SuspectParityGame<S> game;

  @SuppressWarnings("unchecked")
  private final Function<LabelledFormula, Automaton<Object, ParityAcceptance>> dpaCachingFunction = formula ->
      (Automaton<Object, ParityAcceptance>) automatonCache.computeIfAbsent(formula,
          f -> ParityUtil.convert(translation.apply(f), ParityAcceptance.Parity.MIN_EVEN));

  public DeviationSolver(SuspectGame<S> suspectGame, PayoffAssignment payoff) {
    this.suspectGame = suspectGame;

    HistoryGame<S> historyGame = suspectGame.historyGame();
    ConcurrentGame<S> concurrentGame = historyGame.concurrentGame();
    losingAgents = concurrentGame.agents().stream().filter(payoff::isLoser).collect(Collectors.toSet());
    atomicPropositions = Stream.concat(concurrentGame.atomicPropositions().stream(), losingAgents.stream().map(Agent::name)).toList();
    agentLiterals = losingAgents.stream().collect(Collectors.toMap(Function.identity(),
        a -> Literal.of(atomicPropositions.indexOf(a.name()))));
    assert Set.copyOf(atomicPropositions).size() == atomicPropositions.size();

    // TODO Properly do the cache -- it should be possible to solve the complete history game through the initial state only
    var historyState = historyGame.initialState();

    EveState<S> eveState = new EveState<>(historyState, losingAgents);
    LabelledFormula eveGoal = LabelledFormula.of(Disjunction.of(losingAgents.stream()
        .map(a -> Conjunction.of(GOperator.of(agentLiterals.get(a)), historyState.goal(a)))).not(), atomicPropositions);

    var goal = SimplifierRepository.SYNTACTIC_FAIRNESS.apply(eveGoal);
    if (goal.formula() instanceof BooleanConstant) {
      strategy = Map.of();
      initialStates = Map.of();
      game = null;
    } else {
      var gameSolution = solveParityGame(eveState, goal);
      game = gameSolution.parityGame();
      var paritySolution = gameSolution.solution();

      Set<PriorityState<S>> solvedTopLevelEveStates = game.states().stream()
          .filter(p -> p.isEve() && p.eve().suspects().equals(losingAgents))
          .collect(Collectors.toSet());
      Queue<PriorityState<S>> queue = new ArrayDeque<>();
      solvedTopLevelEveStates.stream().filter(p -> paritySolution.winner(p) == Player.ODD).forEach(queue::add);
      Set<PriorityState<S>> reached = new HashSet<>();
      Map<PriorityState<S>, PriorityState<S>> strategy = new HashMap<>();
      while (!queue.isEmpty()) {
        PriorityState<S> next = queue.poll();
        assert paritySolution.winner(next) == Player.ODD;
        Stream<PriorityState<S>> successors;
        if (game.owner(next) == Player.EVEN) {
          successors = game.successors(next);
        } else {
          PriorityState<S> successor = paritySolution.oddStrategy().get(next);
          strategy.put(next, successor);
          successors = Stream.of(successor);
        }
        successors.forEach(successor -> {
          if (reached.add(successor)) {
            queue.add(successor);
          }
        });
      }

      this.strategy = Map.copyOf(strategy);

      Map<HistoryState<S>, Map<Move, PriorityState<S>>> initialStates = new HashMap<>();
      for (PriorityState<S> priorityState : solvedTopLevelEveStates) {
        HistoryState<S> gameHistoryState = priorityState.eve().historyState();
        Map<Move, PriorityState<S>> winningInitial = new HashMap<>();
        game.successors(priorityState)
            .filter(s -> paritySolution.winner(s) == Player.ODD)
            .forEach(winner -> winningInitial.put(winner.adam().move(), winner));
        initialStates.put(gameHistoryState, Map.copyOf(winningInitial));
      }
      this.initialStates = Map.copyOf(initialStates);
    }
  }

  private LabelledFormula eveGoal(HistoryState<S> historyState) {
    return LabelledFormula.of(Disjunction.of(losingAgents.stream()
        .map(a -> Conjunction.of(GOperator.of(agentLiterals.get(a)), historyState.goal(a)))).not(), atomicPropositions);
  }

  public boolean isWinning(HistoryState<S> historyState, Move move) {
    Map<Move, PriorityState<S>> winningMoves = this.initialStates.get(historyState);
    if (winningMoves == null) {
      LabelledFormula eveGoal = eveGoal(historyState);
      var goal = SimplifierRepository.SYNTACTIC_FAIRNESS.apply(eveGoal).formula();
      assert goal instanceof BooleanConstant;
      assert computeWinning(historyState) == ((BooleanConstant) goal).value;
      return ((BooleanConstant) goal).value;
    }
    return winningMoves.containsKey(move);
  }

  private boolean computeWinning(HistoryState<S> historyState) {
    var solution = solveParityGame(new EveState<>(historyState, losingAgents), eveGoal(historyState));
    return solution.solution().winner(solution.parityGame().initialState()) == Player.ODD;
  }

  @Override
  public Set<PriorityState<S>> initialStates(HistoryState<S> state, Move proposedMove) {
    PriorityState<S> initialAdam = initialStates.get(state).get(proposedMove);
    return game == null ? Set.of(initialAdam) : game.successors(initialAdam).collect(Collectors.toSet());
  }

  @Override
  public PriorityState<S> move(PriorityState<S> state) {
    assert state.isEve();
    return strategy.getOrDefault(state, state);
  }

  @Override
  public Set<PriorityState<S>> successors(PriorityState<S> state) {
    if (state.isEve()) {
      return Set.of(move(state));
    }
    return game == null ? Set.of(state) : game.successors(state).collect(Collectors.toSet());
  }

  record ParitySolution<S>(SuspectParityGame<S> parityGame, Solution<PriorityState<S>> solution) {}

  private ParitySolution<S> solveParityGame(EveState<S> eveState, LabelledFormula goal) {
    var shifted = LiteralMapper.shiftLiterals(goal);
    var automaton = dpaCachingFunction.apply(shifted.formula);
    assert !automaton.states().isEmpty();
    var parityGame = SuspectParityGame.create(suspectGame, eveState, automaton);
    var paritySolution = solver.solve(parityGame);
    return new ParitySolution<>(parityGame, paritySolution);
  }
}
