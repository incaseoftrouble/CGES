package com.cges.algorithm;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.cges.model.RunGraph;
import com.cges.model.RunGraph.State;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.BitVecSort;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.BoolSort;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.Model;
import com.microsoft.z3.SeqSort;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import com.microsoft.z3.Z3Exception;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import owl.automaton.algorithm.SccDecomposition;

public final class BoundedChecker {
  private record SccPair(Set<State> scc, Set<State> accepting) {}

  private static final Logger logger = Logger.getLogger(BoundedChecker.class.getName());

  private BoundedChecker() {}

  public static List<State> checkScc(RunGraph graph) {
    Set<State> acceptingStates = graph.states().stream().filter(State::accepting).collect(Collectors.toSet());
    List<SccPair> sccs = SccDecomposition.of(graph.initialStates(), graph::successors).sccsWithoutTransient()
        .stream()
        .map(scc -> new SccPair(scc, Set.copyOf(Sets.intersection(scc, acceptingStates))))
        .filter(pair -> !pair.accepting().isEmpty())
        .toList();
    Set<State> statesInAcceptingSccs = sccs.stream().map(SccPair::scc).flatMap(Collection::stream).collect(Collectors.toSet());

    List<State> path = new ArrayList<>();
    {
      Set<State> states = new HashSet<>(graph.initialStates());
      Queue<State> queue = new ArrayDeque<>(states);
      Map<State, State> predecessor = new HashMap<>();

      while (!queue.isEmpty()) {
        State current = queue.poll();
        if (statesInAcceptingSccs.contains(current)) {
          while (current != null) {
            path.add(current);
            current = predecessor.get(current);
          }
          break;
        }
        for (State successor : graph.successors(current)) {
          if (states.add(successor)) {
            predecessor.put(successor, current);
            queue.add(successor);
          }
        }
      }
    }
    if (path.isEmpty()) {
      return path;
    }
    State recurrentState = path.get(0);
    SccPair scc = sccs.stream().filter(pair -> pair.scc().contains(recurrentState)).findAny().orElseThrow();

    List<State> sccPath = new ArrayList<>();
    {
      Set<State> states = new HashSet<>(List.of(recurrentState));
      Queue<State> queue = new ArrayDeque<>(states);
      Map<State, State> predecessor = new HashMap<>();

      while (!queue.isEmpty()) {
        State current = queue.poll();
        if (current.accepting()) {
          while (current != null) {
            sccPath.add(current);
            current = predecessor.get(current);
          }
          break;
        }
        for (State successor : Sets.intersection(graph.successors(current), scc.scc())) {
          if (states.add(successor)) {
            predecessor.put(successor, current);
            queue.add(successor);
          }
        }
      }
    }

    State acceptingState = sccPath.get(0);
    List<State> sccRecurrentPath = new ArrayList<>();
    {
      Set<State> states = new HashSet<>(List.of(acceptingState));
      Queue<State> queue = new ArrayDeque<>(states);
      Map<State, State> predecessor = new HashMap<>();

      while (!queue.isEmpty()) {
        State current = queue.poll();
        if (current.equals(acceptingState)) {
          while (current != null) {
            sccRecurrentPath.add(current);
            current = predecessor.get(current);
          }
          break;
        }
        for (State successor : Sets.intersection(graph.successors(current), scc.scc())) {
          if (states.add(successor)) {
            predecessor.put(successor, current);
            queue.add(successor);
          }
        }
      }
    }

    List<State> loop = new ArrayList<>();
    loop.addAll(sccRecurrentPath);
    loop.addAll(sccPath);
    loop.addAll(path);
    return Lists.reverse(loop);
  }

  @SuppressWarnings("unchecked")
  public static List<State> check(RunGraph graph) {
    // TODO Derive upper bound on depth from graph
    // TODO QBF or similar instead of bit encoding

    Set<State> acceptingStates = graph.states().stream().filter(State::accepting).collect(Collectors.toSet());
    if (acceptingStates.isEmpty()) {
      return List.of();
    }

    // TODO Better bound on the largest loop -- most distant scc
    int depth = graph.size();


    int bits = (int) Math.ceil(StrictMath.log(graph.size()) / StrictMath.log(2));

    try (Context ctx = new Context()) {
      BoolExpr[][] variables = new BoolExpr[depth + 1][bits];
      for (int step = 0; step <= depth; step++) {
        for (int bit = 0; bit < bits; bit++) {
          variables[step][bit] = ctx.mkBoolConst("v_%d_%d".formatted(step, bit));
        }
      }

      List<State> stateNumbering = List.copyOf(graph.states());
      @SuppressWarnings("unchecked")
      Map<State, BoolExpr>[] stepStateExpressions = new Map[depth + 1];
      Arrays.setAll(stepStateExpressions, i -> new HashMap<>());

      ListIterator<State> iterator = stateNumbering.listIterator();
      while (iterator.hasNext()) {
        int index = iterator.nextIndex();
        State state = iterator.next();

        for (int step = 0; step <= depth; step++) {
          BoolExpr[] stepVariables = variables[step];
          BoolExpr[] bitExpression = new BoolExpr[bits];
          Arrays.setAll(bitExpression, bit -> (index & (1 << bit)) == 0 ? ctx.mkNot(stepVariables[bit]) : stepVariables[bit]);
          stepStateExpressions[step].put(state, ctx.mkAnd(bitExpression));
        }
      }

      Solver solver = ctx.mkSolver("QF_FD");

      solver.add(ctx.mkOr(graph.initialStates().stream()
          .map(stepStateExpressions[0]::get).toArray(BoolExpr[]::new)).simplify());

      List<State> lasso = new ArrayList<>(depth + 1);

      for (int k = 1; k <= depth; k++) {
        logger.log(Level.FINE, "Building model for depth {0}", k);

        int cutoff = k;

        solver.add(ctx.mkAnd(graph.states().stream().map(state -> {
          var successors = graph.successors(state);
          return ctx.mkImplies(stepStateExpressions[cutoff - 1].get(state),
                  ctx.mkOr(successors.stream().map(stepStateExpressions[cutoff]::get).toArray(BoolExpr[]::new)));
        }).toArray(BoolExpr[]::new)).simplify());

        Expr<BoolSort>[] acceptingLoopAtStep = new Expr[cutoff + 1];

        // Can we use SCCs here?
        Arrays.setAll(acceptingLoopAtStep, step -> {
          BoolExpr[] loopExpr = new BoolExpr[bits];
          Arrays.setAll(loopExpr, i -> ctx.mkEq(variables[step][i], variables[cutoff][i]));
          Expr<BoolSort> loop = ctx.mkAnd(loopExpr).simplify();

          Expr<BoolSort> visitAcceptingState = ctx.mkOr(IntStream.range(step, cutoff)
              .mapToObj(i -> ctx.mkOr(acceptingStates.stream().map(stepStateExpressions[i]::get).toArray(BoolExpr[]::new)).simplify())
              .toArray(Expr[]::new));
          return ctx.mkAnd(loop, visitAcceptingState);
        });
        Expr<BoolSort> acceptingLassoExpression = ctx.mkOr(acceptingLoopAtStep).simplify();

        Status check = solver.check(acceptingLassoExpression);
        if (check == Status.UNKNOWN) {
          throw new Z3Exception("Status unknown");
        }
        if (check == Status.UNSATISFIABLE) {
          continue;
        }

        Model model = solver.getModel();
        boolean[][] valuation = new boolean[cutoff + 1][bits];
        for (int step = 0; step <= cutoff; step++) {
          for (int bit = 0; bit < bits; bit++) {
            Expr<BoolSort> z3valuation = model.eval(variables[step][bit], false);
            checkState(z3valuation.isTrue() || z3valuation.isFalse());
            valuation[step][bit] = z3valuation.isTrue();
          }
        }
        for (int step = 0; step <= cutoff; step++) {
          int stateIndex = 0;
          boolean[] stepValuation = valuation[step];
          for (int bit = 0; bit < stepValuation.length; bit++) {
            if (stepValuation[bit]) {
              stateIndex |= (1 << bit);
            }
          }
          assert 0 <= stateIndex && stateIndex < stateNumbering.size();
          lasso.add(checkNotNull(stateNumbering.get(stateIndex)));
        }
        checkState(lasso.subList(0, cutoff).contains(lasso.get(cutoff)), lasso);
        return lasso;
      }
    }
    return List.of();
  }


  @SuppressWarnings("unchecked")
  public static List<RunGraph.State> checkBV(RunGraph graph, int depth) {
    Set<State> acceptingStates = graph.states().stream().filter(State::accepting).collect(Collectors.toSet());
    if (acceptingStates.isEmpty()) {
      return List.of();
    }

    int bits = (int) Math.ceil(StrictMath.log(graph.size()) / StrictMath.log(2));

    try (Context ctx = new Context()) {
      BitVecExpr[] stepVariables = new BitVecExpr[depth + 1];
      for (int step = 0; step <= depth; step++) {
          stepVariables[step] = ctx.mkBVConst("t_%d".formatted(step), bits);
      }

      List<State> stateNumbering = List.copyOf(graph.states());
      @SuppressWarnings("unchecked")
      Map<State, BoolExpr>[] stepStateExpressions = new Map[depth + 1];
      Arrays.setAll(stepStateExpressions, i -> new HashMap<>());

      ListIterator<State> iterator = stateNumbering.listIterator();
      while (iterator.hasNext()) {
        int index = iterator.nextIndex();
        State state = iterator.next();
        for (int step = 0; step <= depth; step++) {
          stepStateExpressions[step].put(state, ctx.mkEq(ctx.mkBV(index, bits), stepVariables[step]));
        }
      }
      SeqSort<BitVecSort> seqSort = ctx.mkSeqSort(ctx.mkBitVecSort(bits));

      Solver solver = ctx.mkSolver();
      solver.add(ctx.mkOr(graph.initialStates().stream()
              .map(stepStateExpressions[0]::get)
              .toArray(BoolExpr[]::new)));

      List<RunGraph.State> lasso = new ArrayList<>(depth + 1);
      for (int k = 1; k <= depth; k++) {
        logger.log(Level.FINE, "Building model for depth {0}", k);

        int cutoff = k;

        graph.states().forEach(state -> {
          var successors = graph.successors(state);
          solver.add(ctx.mkImplies(stepStateExpressions[cutoff - 1].get(state),
              ctx.mkOr(successors.stream().map(stepStateExpressions[cutoff]::get).toArray(BoolExpr[]::new))).simplify());
        });

        Expr<BoolSort>[] acceptingLoopAtStep = new Expr[cutoff + 1];
        Arrays.setAll(acceptingLoopAtStep, step -> {
          Expr<BoolSort> loop = ctx.mkEq(stepVariables[step], stepVariables[cutoff]);
          Expr<BoolSort> visitAcceptingState = ctx.mkOr(IntStream.range(step, cutoff) // Omit step = cutoff since we ensure loop anyway
              .mapToObj(i -> ctx.mkOr(acceptingStates.stream().map(stepStateExpressions[i]::get).toArray(BoolExpr[]::new)).simplify())
              .toArray(Expr[]::new)).simplify();
          return ctx.mkAnd(loop, visitAcceptingState).simplify();
        });
        Expr<BoolSort> acceptingLassoExpression = ctx.mkOr(acceptingLoopAtStep).simplify();

        Status check = solver.check(acceptingLassoExpression);
        if (check == Status.UNKNOWN) {
          throw new Z3Exception("Status unknown");
        }
        if (check == Status.UNSATISFIABLE) {
          continue;
        }

        Model model = solver.getModel();
        for (int step = 0; step <= cutoff; step++) {
            Expr<BitVecSort> z3valuation = model.eval(stepVariables[step], false);
            lasso.add(checkNotNull(stateNumbering.get(((BitVecNum) z3valuation).getInt())));
        }
        checkState(lasso.subList(0, cutoff).contains(lasso.get(cutoff)), lasso);
        return lasso;
      }
    }
    return List.of();
  }
}
