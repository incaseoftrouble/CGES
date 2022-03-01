package com.cges.algorithm;

import com.cges.model.ConcurrentGame;
import com.cges.model.SuspectGame;
import com.cges.model.SuspectParityGame;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import owl.run.Environment;
import owl.translations.ltl2dpa.LTL2DPAFunction;

public class SuspectSolver {
  private final Environment env = Environment.standard();
  private final LTL2DPAFunction dpaFunction = new LTL2DPAFunction(env, LTL2DPAFunction.RECOMMENDED_SYMMETRIC_CONFIG);
  private final SuspectGame suspectGame;
  private final Map<Formula, Automaton<Object, ParityAcceptance>> cache = new HashMap<>();

  public SuspectSolver(SuspectGame suspectGame) {
    this.suspectGame = suspectGame;
  }

  public static Set<SuspectGame.EveState> computeWinningEveStates(SuspectGame suspectGame) {
    SuspectSolver solver = new SuspectSolver(suspectGame);

    Object2IntMap<SuspectGame.EveState> topSort = new Object2IntOpenHashMap<>();
    topSort.defaultReturnValue(-1);
    Deque<SuspectGame.EveState> stack = new ArrayDeque<>(List.of(suspectGame.initialState()));
    topSort.put(suspectGame.initialState(), 0);
    while (!stack.isEmpty()) {
      SuspectGame.EveState current = stack.pollLast();
      suspectGame.successors(current).stream().map(suspectGame::successors).flatMap(Collection::stream).forEach(successor -> {
        if (topSort.put(current, topSort.size()) == -1) {
          stack.push(successor);
        }
      });
    }
    Set<SuspectGame.EveState> winningStates = new HashSet<>();
    Iterator<SuspectGame.EveState> iterator = suspectGame.eveStates().stream()
        .sorted(Comparator.comparingInt(topSort::getInt).reversed())
        .iterator();
    while (iterator.hasNext()) {
      SuspectGame.EveState eveState = iterator.next();
      Iterator<Formula> formulaIterator = findEventualSuspects(suspectGame, eveState).stream()
          .map(agents -> Conjunction.of(agents.stream()
              .filter(ConcurrentGame.Agent::isLoser)
              .map(ConcurrentGame.Agent::goal)
              .map(Formula::not)))
          .sorted(Comparator.comparingInt(Formula::height)).iterator();
      while (formulaIterator.hasNext()) {
        Formula formula = formulaIterator.next();
        if (solver.isWinning(suspectGame, eveState, formula, winningStates)) {
          winningStates.add(eveState);
          break;
        }
      }
    }

    return winningStates;
  }

  private boolean isWinning(SuspectGame game, SuspectGame.EveState eveState, Formula unlabelled, Set<SuspectGame.EveState> winningStates) {
    if (unlabelled instanceof BooleanConstant) {
      return ((BooleanConstant) unlabelled).value;
    }

    Automaton<Object, ParityAcceptance> automaton = cache.computeIfAbsent(unlabelled, formula -> {
      var shifted = LiteralMapper.shiftLiterals(LabelledFormula.of(unlabelled, suspectGame.game().formulaPropositions()));
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
      return false;
    }

    SuspectParityGame parityGame = SuspectParityGame.create(game, eveState, automaton, winningStates::contains);
    return OinkGameSolver.solve(parityGame).oddWinning().contains(parityGame.initialState());
  }

  public static Set<Set<ConcurrentGame.Agent>> findEventualSuspects(SuspectGame game, SuspectGame.EveState root) {
    Deque<SuspectGame.EveState> queue = new ArrayDeque<>(List.of(root));
    Set<SuspectGame.EveState> states = new HashSet<>(queue);
    Set<Set<ConcurrentGame.Agent>> potentialLimitSuspectAgents = new HashSet<>();

    while (!queue.isEmpty()) {
      SuspectGame.EveState current = queue.pollLast();
      for (SuspectGame.AdamState adamSuccessor : game.successors(current)) {
        assert adamSuccessor.eveState().equals(current);
        for (SuspectGame.EveState eveSuccessor : game.successors(adamSuccessor)) {
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
