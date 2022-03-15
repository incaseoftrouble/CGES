package com.cges.algorithm;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.algorithm.HistoryGame.HistoryState;
import com.cges.algorithm.SuspectGame.EveState;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.PayoffAssignment;
import com.cges.model.Transition;
import com.cges.parity.OinkGameSolver;
import com.cges.parity.Player;
import com.cges.parity.PriorityState;
import com.cges.parity.SuspectParityGame;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import owl.automaton.Automaton;
import owl.automaton.MutableAutomatonUtil;
import owl.automaton.ParityUtil;
import owl.automaton.acceptance.ParityAcceptance;
import owl.automaton.acceptance.optimization.AcceptanceOptimizations;
import owl.ltl.BooleanConstant;
import owl.ltl.Conjunction;
import owl.ltl.Disjunction;
import owl.ltl.GOperator;
import owl.ltl.LabelledFormula;
import owl.ltl.Literal;
import owl.ltl.rewriter.LiteralMapper;
import owl.ltl.rewriter.SimplifierFactory;
import owl.run.Environment;
import owl.translations.ltl2dpa.LTL2DPAFunction;

public final class SuspectSolver<S> {
  private final Environment env = Environment.standard();
  private final LTL2DPAFunction dpaFunction = new LTL2DPAFunction(env, LTL2DPAFunction.RECOMMENDED_ASYMMETRIC_CONFIG);
  private final SuspectGame<S> suspectGame;
  // TODO Make caching work on unlabelled formulas?
  private final Map<LabelledFormula, Automaton<Object, ParityAcceptance>> automatonCache = new HashMap<>();
  private final OinkGameSolver solver = new OinkGameSolver();

  private SuspectSolver(SuspectGame<S> suspectGame) {
    this.suspectGame = suspectGame;
  }

  public static <S> HistorySolution<S> computeReachableWinningStates(SuspectGame<S> suspectGame, PayoffAssignment payoff) {
    SuspectSolver<S> solver = new SuspectSolver<>(suspectGame);
    Map<HistoryState<S>, Strategy<S>> strategies = new HashMap<>();

    HistoryGame<S> historyGame = suspectGame.historyGame();
    ConcurrentGame<S> concurrentGame = historyGame.concurrentGame();
    Set<Agent> losingAgents = concurrentGame.agents().stream().filter(payoff::isLoser).collect(Collectors.toSet());
    List<String> atomicPropositions = Stream.concat(
        concurrentGame.atomicPropositions().stream(),
        losingAgents.stream().map(Agent::name)).toList();
    Map<Agent, Literal> agentLiterals = losingAgents.stream().collect(Collectors.toMap(Function.identity(),
        a -> Literal.of(atomicPropositions.indexOf(a.name()))));
    assert Set.copyOf(atomicPropositions).size() == atomicPropositions.size();
    Set<HistoryState<S>> states = new HashSet<>(List.of(historyGame.initialState()));
    Queue<HistoryState<S>> queue = new ArrayDeque<>(states);

    while (!queue.isEmpty()) {
      HistoryState<S> state = queue.poll();
      LabelledFormula formula = LabelledFormula.of(Disjunction.of(
          losingAgents.stream().map(a -> Conjunction.of(GOperator.of(agentLiterals.get(a)), state.goal(a)))), atomicPropositions);
      solver.isWinning(suspectGame, new EveState<>(state, concurrentGame.agents()), formula.not()).ifPresent(strategy -> {
        strategies.put(state, strategy);
        historyGame.transitions(state).map(Transition::destination).forEach(successor -> {
          if (states.add(successor)) {
            queue.add(successor);
          }
        });
      });
    }
    return new Solution<>(Map.copyOf(strategies));
  }

  private Optional<Strategy<S>> isWinning(SuspectGame<S> game, EveState<S> eveState, LabelledFormula eveGoal) {
    var goal = SimplifierFactory.apply(eveGoal, SimplifierFactory.Mode.SYNTACTIC_FIXPOINT);
    if (goal.formula() instanceof BooleanConstant bool) {
      if (bool.value) {
        PriorityState<S> state = new PriorityState<>(null, eveState, 0);
        return Optional.of(new Strategy<>(state, Map.of(state, state)));
      }
      return Optional.empty();
    }

    var shifted = LiteralMapper.shiftLiterals(goal);
    Automaton<Object, ParityAcceptance> automaton = automatonCache.computeIfAbsent(shifted.formula, formula -> {
      @SuppressWarnings("unchecked")
      Automaton<Object, ParityAcceptance> dpa = (Automaton<Object, ParityAcceptance>) dpaFunction.apply(formula);
      MutableAutomatonUtil.Sink sink = new MutableAutomatonUtil.Sink();
      var mutable = MutableAutomatonUtil.asMutable(
          ParityUtil.convert(dpa, ParityAcceptance.Parity.MIN_EVEN, sink));
      var minimized = AcceptanceOptimizations.optimize(mutable);
      minimized.trim();
      if (minimized.size() == 0) {
        return minimized;
      }
      if (minimized.acceptance().acceptanceSets() == 1) {
        // Hack needed for .complete to work
        minimized.acceptance(minimized.acceptance().withAcceptanceSets(2));
      }
      MutableAutomatonUtil.complete(minimized, sink);
      return minimized;
    });
    if (automaton.size() == 0) {
      return Optional.empty();
    }

    var parityGame = SuspectParityGame.create(game, eveState, automaton);
    var paritySolution = solver.solve(parityGame);

    if (paritySolution.winner(parityGame.initialState()) == Player.EVEN) {
      return Optional.empty();
    }

    Queue<PriorityState<S>> queue = new ArrayDeque<>(List.of(parityGame.initialState()));
    Set<PriorityState<S>> reached = new HashSet<>();
    Map<PriorityState<S>, PriorityState<S>> strategy = new HashMap<>();
    while (!queue.isEmpty()) {
      PriorityState<S> next = queue.poll();
      assert paritySolution.winner(next) == Player.ODD;
      Stream<PriorityState<S>> successors;
      if (parityGame.owner(next) == Player.EVEN) {
        successors = parityGame.successors(next);
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
    return Optional.of(new Strategy<>(parityGame.initialState(), Map.copyOf(strategy)));
  }

  public static <S> Set<Set<Agent>> findEventualSuspects(SuspectGame<S> game, EveState<S> root) {
    Deque<EveState<S>> queue = new ArrayDeque<>(List.of(root));
    Set<EveState<S>> states = new HashSet<>(queue);
    Set<Set<Agent>> potentialLimitSuspectAgents = new HashSet<>();

    while (!queue.isEmpty()) {
      EveState<S> current = queue.pollLast();
      for (SuspectGame.AdamState<S> adamSuccessor : game.successors(current)) {
        assert adamSuccessor.eveState().equals(current);
        for (EveState<S> eveSuccessor : game.successors(adamSuccessor)) {
          assert current.suspects().containsAll(eveSuccessor.suspects());
          if (states.add(eveSuccessor)) {
            queue.addLast(eveSuccessor);
          } else {
            potentialLimitSuspectAgents.add(eveSuccessor.suspects());
          }
        }
      }
    }
    return potentialLimitSuspectAgents;
  }

  public interface HistorySolution<S> {
    Set<HistoryState<S>> winningStates();

    default boolean isWinning(HistoryState<S> state) {
      return winningStates().contains(state);
    }

    PriorityState<S> initial(HistoryState<S> state);

    SuspectStrategy<S> strategy(HistoryState<S> state);
  }

  public interface SuspectStrategy<S> {
    PriorityState<S> winningMove(PriorityState<S> state);
  }

  record Solution<S>(Map<HistoryState<S>, Strategy<S>> strategies) implements HistorySolution<S> {
    @Override
    public Set<HistoryState<S>> winningStates() {
      return strategies.keySet();
    }

    @Override
    public PriorityState<S> initial(HistoryState<S> state) {
      return strategies.get(state).initialState();
    }

    @Override
    public SuspectStrategy<S> strategy(HistoryState<S> state) {
      return strategies.get(state);
    }
  }

  record Strategy<S>(PriorityState<S> initialState, Map<PriorityState<S>, PriorityState<S>> strategies) implements SuspectStrategy<S> {
    @Override
    public PriorityState<S> winningMove(PriorityState<S> state) {
      checkArgument(strategies.containsKey(state));
      return strategies.get(state);
    }
  }
}
