package com.cges.algorithm;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.cges.algorithm.RunGraph.RunState;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.BitVecSort;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.BoolSort;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.Model;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import com.microsoft.z3.Z3Exception;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

public final class RunGraphBmcSolver {
  private static final Logger logger = Logger.getLogger(RunGraphBmcSolver.class.getName());

  private RunGraphBmcSolver() {}

  @SuppressWarnings("unchecked")
  public static <S> List<RunState<S>> check(RunGraph<S> graph) {
    // TODO Derive upper bound on depth from graph
    // TODO QBF or similar instead of bit encoding

    Set<RunState<S>> states = new HashSet<>(graph.initialStates());
    Queue<RunState<S>> queue = new ArrayDeque<>(states);
    while (!queue.isEmpty()) {
      var state = queue.poll();
      graph.successors(state).forEach(successor -> {
        if (states.add(successor)) {
          queue.add(successor);
        }
      });
    }

    // TODO
    Set<RunState<S>> acceptingStates = Set.of(); // states.stream().filter(RunState::accepting).collect(Collectors.toSet());
    if (acceptingStates.isEmpty()) {
      return List.of();
    }

    // TODO Better bound on the largest loop -- most distant scc
    int depth = states.size();

    @SuppressWarnings("NumericCastThatLosesPrecision")
    int bits = (int) Math.ceil(StrictMath.log(states.size()) / StrictMath.log(2));

    try (Context ctx = new Context()) {
      BoolExpr[][] variables = new BoolExpr[depth + 1][bits];
      for (int step = 0; step <= depth; step++) {
        for (int bit = 0; bit < bits; bit++) {
          variables[step][bit] = ctx.mkBoolConst("v_%d_%d".formatted(step, bit));
        }
      }

      List<RunState<S>> stateNumbering = List.copyOf(states);
      @SuppressWarnings("unchecked")
      Map<RunState<S>, BoolExpr>[] stepStateExpressions = new Map[depth + 1];
      Arrays.setAll(stepStateExpressions, i -> new HashMap<>());

      ListIterator<RunState<S>> iterator = stateNumbering.listIterator();
      while (iterator.hasNext()) {
        int index = iterator.nextIndex();
        RunState<S> state = iterator.next();

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

      List<RunState<S>> lasso = new ArrayList<>(depth + 1);

      for (int k = 1; k <= depth; k++) {
        logger.log(Level.FINE, "Building model for depth {0}", k);

        int cutoff = k;

        solver.add(ctx.mkAnd(states.stream().map(state -> {
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
  public static <S> List<RunState<S>> checkBV(RunGraph<S> graph, int depth) {
    Set<RunState<S>> states = new HashSet<>(graph.initialStates());
    Queue<RunState<S>> queue = new ArrayDeque<>(states);
    while (!queue.isEmpty()) {
      var state = queue.poll();
      graph.successors(state).forEach(successor -> {
        if (states.add(successor)) {
          queue.add(successor);
        }
      });
    }

    // TODO
    Set<RunState<S>> acceptingStates = Set.of(); // states.stream().filter(RunState::accepting).collect(Collectors.toSet());
    if (acceptingStates.isEmpty()) {
      return List.of();
    }

    @SuppressWarnings("NumericCastThatLosesPrecision")
    int bits = (int) Math.ceil(StrictMath.log(states.size()) / StrictMath.log(2));

    try (Context ctx = new Context()) {
      BitVecExpr[] stepVariables = new BitVecExpr[depth + 1];
      for (int step = 0; step <= depth; step++) {
          stepVariables[step] = ctx.mkBVConst("t_%d".formatted(step), bits);
      }

      List<RunState<S>> stateNumbering = List.copyOf(states);
      @SuppressWarnings("unchecked")
      Map<RunState<S>, BoolExpr>[] stepStateExpressions = new Map[depth + 1];
      Arrays.setAll(stepStateExpressions, i -> new HashMap<>());

      ListIterator<RunState<S>> iterator = stateNumbering.listIterator();
      while (iterator.hasNext()) {
        int index = iterator.nextIndex();
        RunState<S> state = iterator.next();
        for (int step = 0; step <= depth; step++) {
          stepStateExpressions[step].put(state, ctx.mkEq(ctx.mkBV(index, bits), stepVariables[step]));
        }
      }

      Solver solver = ctx.mkSolver();
      solver.add(ctx.mkOr(graph.initialStates().stream()
              .map(stepStateExpressions[0]::get)
              .toArray(BoolExpr[]::new)));

      List<RunState<S>> lasso = new ArrayList<>(depth + 1);
      for (int k = 1; k <= depth; k++) {
        logger.log(Level.FINE, "Building model for depth {0}", k);

        int cutoff = k;

        states.forEach(state -> {
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
