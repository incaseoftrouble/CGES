package com.cges.algorithm;

import com.cges.algorithm.SuspectGame.EveState;
import com.cges.algorithm.SuspectParityGame.PriorityState;
import com.cges.model.Agent;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
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
import java.util.stream.Collectors;
import owl.automaton.Automaton;
import owl.automaton.MutableAutomatonUtil;
import owl.automaton.ParityUtil;
import owl.automaton.SuccessorFunction;
import owl.automaton.acceptance.ParityAcceptance;
import owl.automaton.acceptance.optimization.AcceptanceOptimizations;
import owl.automaton.algorithm.SccDecomposition;
import owl.ltl.BooleanConstant;
import owl.ltl.Conjunction;
import owl.ltl.Formula;
import owl.ltl.LabelledFormula;
import owl.ltl.rewriter.LiteralMapper;
import owl.run.Environment;
import owl.translations.ltl2dpa.LTL2DPAFunction;

public final class SuspectSolver<S> {
  private final Environment env = Environment.standard();
  private final LTL2DPAFunction dpaFunction = new LTL2DPAFunction(env, LTL2DPAFunction.RECOMMENDED_SYMMETRIC_CONFIG);
  private final SuspectGame<S> suspectGame;
  private final Map<Formula, Automaton<Object, ParityAcceptance>> cache = new HashMap<>();
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

    SuspectStrategy<S> strategy();
  }

  public interface SuspectStrategy<S> {
    PriorityState<S> move(PriorityState<S> state);
  }

  record RecursiveStrategy<S>(
      Map<EveState<S>, PriorityState<S>> initialStates,
      Map<PriorityState<S>, PriorityState<S>> winningMoves,
      Map<PriorityState<S>, RecursiveStrategy<S>> recursiveSolution) implements SuspectStrategy<S> {
    @Override
    public PriorityState<S> move(PriorityState<S> state) {
      assert winningMoves.containsKey(state) || recursiveSolution.containsKey(state);
      PriorityState<S> move = winningMoves.get(state);
      return move == null ? recursiveSolution.get(state).move(state) : move;
    }
  }

  record Solution<S>(
      Map<EveState<S>, PriorityState<S>> initialStates,
      Map<PriorityState<S>, PriorityState<S>> winningMoves) implements SuspectSolution<S>, SuspectStrategy<S> {

    @Override
    public Set<EveState<S>> winningStates() {
      return winningMoves.keySet().stream().filter(PriorityState::isEve).map(PriorityState::eve).collect(Collectors.toSet());
    }

    @Override
    public PriorityState<S> initial(EveState<S> state) {
      return initialStates.get(state);
    }

    @Override
    public SuspectStrategy<S> strategy() {
      return this;
    }

    @Override
    public PriorityState<S> move(PriorityState<S> state) {
      return winningMoves.get(state);
    }
  }

  public static <S> SuspectSolution<S> computeWinningEveStates(SuspectGame<S> suspectGame) {
    SuccessorFunction<EveState<S>> successorFunction = current -> suspectGame.successors(current).stream()
        .map(suspectGame::successors)
        .flatMap(Collection::stream)
        .collect(Collectors.toSet());
    List<Set<EveState<S>>> decomposition = Lists.reverse(SccDecomposition.of(Set.of(suspectGame.initialState()), successorFunction).sccs());

    SuspectSolver<S> solver = new SuspectSolver<>(suspectGame);
    Map<EveState<S>, RecursiveStrategy<S>> solutions = new HashMap<>();
    // Process in reverse topological order
    decomposition.stream().flatMap(Collection::stream).forEachOrdered(eveState -> findEventualSuspects(suspectGame, eveState).stream()
        .map(agents -> Conjunction.of(agents.stream()
            .filter(Agent::isLoser)
            .map(eveState.historyState()::goal)
            .map(Formula::not)))
        .sorted(Comparator.comparingInt(Formula::height)) // Try to solve "easy" formulae first
        .flatMap(formula -> solver.isWinning(suspectGame, eveState, formula, solutions).stream())
        .findFirst()
        .ifPresent(solution -> solutions.put(eveState, solution)));

    Map<EveState<S>, PriorityState<S>> initialStates = new HashMap<>();
    Map<PriorityState<S>, PriorityState<S>> winningMoves = new HashMap<>();
    for (RecursiveStrategy<S> value : solutions.values()) {
      assert Sets.intersection(initialStates.keySet(), value.initialStates().keySet()).isEmpty();
      initialStates.putAll(value.initialStates());
      assert Sets.intersection(winningMoves.keySet(), value.winningMoves().keySet()).isEmpty();
      winningMoves.putAll(value.winningMoves());
    }
    assert initialStates.keySet().equals(solutions.keySet());
    assert winningMoves.keySet().stream().allMatch(PriorityState::isEve);
    assert winningMoves.keySet().stream().map(PriorityState::eve).collect(Collectors.toSet()).equals(solutions.keySet());
    return new Solution<>(Map.copyOf(initialStates), Map.copyOf(winningMoves));
  }

  private Optional<RecursiveStrategy<S>> isWinning(SuspectGame<S> game, EveState<S> eveState, Formula unlabelled,
      Map<EveState<S>, RecursiveStrategy<S>> solutions) {
    if (unlabelled instanceof BooleanConstant bool) {
      if (bool.value) {
        PriorityState<S> state = new PriorityState<>(null, eveState, 0);
        return Optional.of(new RecursiveStrategy<>(Map.of(eveState, state), Map.of(state, state), Map.of()));
      }
      return Optional.empty();
    }

    Automaton<Object, ParityAcceptance> automaton = cache.computeIfAbsent(unlabelled, formula -> {
      var shifted = LiteralMapper.shiftLiterals(LabelledFormula.of(unlabelled,
          suspectGame.historyGame().concurrentGame().atomicPropositions()));
      Automaton<Object, ParityAcceptance> dpa = (Automaton<Object, ParityAcceptance>) dpaFunction.apply(shifted.formula);
      MutableAutomatonUtil.Sink sink = new MutableAutomatonUtil.Sink();
      var mutable = MutableAutomatonUtil.asMutable(
          ParityUtil.convert(dpa, ParityAcceptance.Parity.MIN_EVEN, sink));
      var minimized = AcceptanceOptimizations.optimize(mutable);
      minimized.trim();
      if (minimized.size() == 0) {
        return minimized;
      }
      if (mutable.acceptance().acceptanceSets() == 1) {
        // Hack needed for .complete to work
        mutable.acceptance(mutable.acceptance().withAcceptanceSets(2));
      }
      MutableAutomatonUtil.complete(minimized, sink);
      minimized.trim();
      return minimized;
    });
    if (automaton.size() == 0) {
      return Optional.empty();
    }

    SuspectParityGame<S> parityGame = SuspectParityGame.create(game, eveState, automaton, solutions.keySet()::contains);
    var paritySolution = solver.solve(parityGame);
    if (paritySolution.oddWinning().contains(parityGame.initialState())) {
      Queue<PriorityState<S>> queue = new ArrayDeque<>(List.of(parityGame.initialState()));
      Set<PriorityState<S>> reached = new HashSet<>();
      while (!queue.isEmpty()) {
        PriorityState<S> next = queue.iterator().next();
        assert paritySolution.oddWinning().contains(next);
        Set<PriorityState<S>> successors = parityGame.isEvenPlayer(next)
            ? parityGame.successors(next)
            : Set.of(paritySolution.strategy().get(next));
        for (PriorityState<S> successor : successors) {
          if (reached.add(successor)) {
            queue.add(successor);
          }
        }
      }
      Map<PriorityState<S>, PriorityState<S>> strategy = new HashMap<>();
      Map<PriorityState<S>, RecursiveStrategy<S>> recursiveStrategy = new HashMap<>();
      Map<EveState<S>, PriorityState<S>> initialStates = new HashMap<>();
      for (PriorityState<S> state : reached) {
        if (state.isEve()) {
          RecursiveStrategy<S> recursive = solutions.get(state.eve());
          if (recursive == null) {
            strategy.put(state, paritySolution.strategy().get(state));
            initialStates.putIfAbsent(state.eve(), state);
          } else {
            recursiveStrategy.put(state, recursive);
          }
        }
      }
      assert Sets.intersection(strategy.keySet(), recursiveStrategy.keySet()).isEmpty();
      return Optional.of(new RecursiveStrategy<>(Map.copyOf(initialStates), Map.copyOf(strategy), Map.copyOf(recursiveStrategy)));
    }
    return Optional.empty();
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
