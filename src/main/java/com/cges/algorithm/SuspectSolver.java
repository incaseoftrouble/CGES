package com.cges.algorithm;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.algorithm.SuspectGame.EveState;
import com.cges.model.Agent;
import com.cges.model.PayoffAssignment;
import com.cges.parity.Player;
import com.cges.parity.oink.LazySuspectParityGame;
import com.cges.parity.oink.OinkGameSolver;
import com.cges.parity.oink.PriorityState;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Stream;
import owl.automaton.Automaton;
import owl.automaton.MutableAutomatonUtil;
import owl.automaton.ParityUtil;
import owl.automaton.acceptance.ParityAcceptance;
import owl.automaton.acceptance.optimization.AcceptanceOptimizations;
import owl.ltl.BooleanConstant;
import owl.ltl.Conjunction;
import owl.ltl.Formula;
import owl.ltl.LabelledFormula;
import owl.ltl.rewriter.LiteralMapper;
import owl.ltl.rewriter.SimplifierFactory;
import owl.run.Environment;
import owl.translations.ltl2dpa.LTL2DPAFunction;

public final class SuspectSolver<S> {
  private final Environment env = Environment.standard();
  private final LTL2DPAFunction dpaFunction = new LTL2DPAFunction(env, LTL2DPAFunction.RECOMMENDED_SYMMETRIC_CONFIG);
  private final SuspectGame<S> suspectGame;
  // TODO Make caching work on unlabelled formulas?
  private final Map<LabelledFormula, Automaton<Object, ParityAcceptance>> automatonCache = new HashMap<>();
  private final OinkGameSolver solver = new OinkGameSolver();

  private SuspectSolver(SuspectGame<S> suspectGame) {
    this.suspectGame = suspectGame;
  }

  public interface SuspectSolution<S> {
    Set<EveState<S>> winningStates();

    default boolean isWinning(EveState<S> state) {
      return winningStates().contains(state);
    }

    PriorityState<S> initial(EveState<S> state);

    SuspectStrategy<S> strategy(EveState<S> state);
  }

  public interface SuspectStrategy<S> {
    PriorityState<S> winningMove(PriorityState<S> state);
  }

  record Solution<S>(Map<EveState<S>, Strategy<S>> strategies) implements SuspectSolution<S> {
    @Override
    public Set<EveState<S>> winningStates() {
      return strategies.keySet();
    }

    @Override
    public PriorityState<S> initial(EveState<S> state) {
      return strategies.get(state).initialState();
    }

    @Override
    public SuspectStrategy<S> strategy(EveState<S> state) {
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

  public static <S> SuspectSolution<S> computeWinningEveStatesLimitSet(SuspectGame<S> suspectGame, PayoffAssignment payoff) {
    // TODO Reuse winning / losing states
    /*
    SuccessorFunction<EveState<S>> successorFunction = current -> suspectGame.successors(current).stream()
        .map(suspectGame::successors)
        .flatMap(Collection::stream)
        .collect(Collectors.toSet());
    List<Set<EveState<S>>> decomposition = Lists.reverse(SccDecomposition.of(Set.of(suspectGame.initialState()), successorFunction).sccs());
    assert decomposition.stream().flatMap(Collection::stream).collect(Collectors.toSet()).equals(suspectGame.eveStates());
     */

    SuspectSolver<S> solver = new SuspectSolver<>(suspectGame);
    Map<EveState<S>, Strategy<S>> strategies = new HashMap<>();

    for (EveState<S> state : suspectGame.eveStates()) {
      findEventualSuspects(suspectGame, state).stream()
          .map(agents -> Conjunction.of(agents.stream()
              .filter(payoff::isLoser)
              .map(state.historyState()::goal)
              .map(Formula::not)))
          .sorted(Comparator.comparingInt(Formula::height)) // Try to solve "easy" formulae first
          .map(formula -> LabelledFormula.of(formula, suspectGame.historyGame().concurrentGame().atomicPropositions()))
          .flatMap(formula -> solver.isWinning(suspectGame, state, formula).stream())
          .findFirst()
          .ifPresent(strategy -> strategies.put(state, strategy));
    }
    return new Solution<>(Map.copyOf(strategies));
  }

  public static <S> SuspectSolution<S> computeReachableWinningEveStates(SuspectGame<S> suspectGame, PayoffAssignment payoff) {
    SuspectSolver<S> solver = new SuspectSolver<>(suspectGame);
    Map<EveState<S>, Strategy<S>> strategies = new HashMap<>();

    List<String> atomicPropositions = suspectGame.historyGame().concurrentGame().atomicPropositions();
    Set<EveState<S>> states = new HashSet<>(List.of(suspectGame.initialState()));
    Queue<EveState<S>> queue = new ArrayDeque<>(states);

    while (!queue.isEmpty()) {
      EveState<S> state = queue.poll();
      solver.isWinning(suspectGame, state, LabelledFormula.of(Conjunction.of(state.suspects().stream()
              .filter(payoff::isLoser)
              .map(state.historyState()::goal)
              .map(Formula::not)),
          atomicPropositions)).ifPresent(strategy -> {
        strategies.put(state, strategy);
        suspectGame.successors(state).stream().map(suspectGame::successors).flatMap(Collection::stream).forEach(successor -> {
          if (states.add(successor)) {
            queue.add(successor);
          }
        });
      });
    }
    return new Solution<>(Map.copyOf(strategies));
  }

  private Optional<Strategy<S>> isWinning(SuspectGame<S> game, EveState<S> eveState, LabelledFormula agentGoal) {
    var goal = SimplifierFactory.apply(agentGoal, SimplifierFactory.Mode.SYNTACTIC_FIXPOINT);
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
      minimized.trim();
      return minimized;
    });
    if (automaton.size() == 0) {
      return Optional.empty();
    }

    //    if (true) {
    //      var edgeGame = new SuspectEdgeGame<>(game, automaton);
    //      var solution = new StrategyIterationSolver<>(edgeGame).solve();
    //
    //      if (solution.winner(edgeGame.initialState()) == Player.EVEN) {
    //        return Optional.empty();
    //      }
    //
    //      Queue<EdgePriorityState<S>> queue = new ArrayDeque<>(List.of(edgeGame.initialState()));
    //      Set<EdgePriorityState<S>> reached = new HashSet<>();
    //      Map<EdgePriorityState<S>, Edge<EdgePriorityState<S>>> strategy = new HashMap<>();
    //      while (!queue.isEmpty()) {
    //        EdgePriorityState<S> next = queue.poll();
    //        assert solution.winner(next) == Player.ODD;
    //        Stream<Edge<EdgePriorityState<S>>> edges;
    //        if (edgeGame.owner(next) == Player.EVEN) {
    //          edges = edgeGame.edges(next);
    //        } else {
    //          var solutionEdge = solution.oddStrategy().apply(next).iterator().next();
    //          strategy.put(next, solutionEdge);
    //          edges = Stream.of(solutionEdge);
    //        }
    //        edges.forEach(edge -> {
    //          EdgePriorityState<S> successor = edge.successor();
    //          if (reached.add(successor)) {
    //            queue.add(successor);
    //          }
    //        });
    //      }
    //
    //      return Optional.of(new Strategy<>(null, Map.of()));
    //    }
    var parityGame = LazySuspectParityGame.create(game, eveState, automaton);
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
}
