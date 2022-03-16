package com.cges;

import com.cges.algorithm.FormulaHistoryGame;
import com.cges.algorithm.RunGraph;
import com.cges.algorithm.RunGraphSccSolver;
import com.cges.algorithm.SuspectGame;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.EquilibriumStrategy;
import com.cges.model.PayoffAssignment;
import com.cges.output.Formatter;
import com.cges.parser.GameParser;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Sets;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Main {
  private Main() {}

  public static void main(String[] args) throws IOException {
    Stopwatch overall = Stopwatch.createStarted();
    ConcurrentGame<?> game;
    if ("game".equals(args[0])) {
      try (BufferedReader reader = Files.newBufferedReader(Path.of(args[1]))) {
        game = GameParser.parseExplicit(reader.lines());
      }
    } else {
      try (BufferedReader reader = Files.newBufferedReader(Path.of(args[0]))) {
        game = GameParser.parseExplicit(JsonParser.parseReader(reader).getAsJsonObject());
      }
    }
    var list = analyse(game).peek(solution -> System.out.printf("Found NE for %s:%n%s%n",
        Formatter.format(solution.assignment(), game),
        solution.strategy())).collect(Collectors.toList());
    System.out.println("Overall: " + overall);
    list.sort(Comparator.comparingLong(solution -> game.agents().stream().map(solution.assignment()::isLoser).count()));
    list.stream().map(solution -> Formatter.format(solution.assignment(), game)).forEach(System.out::println);
  }

  private static <S> Stream<GameSolution<S>> analyse(ConcurrentGame<S> game) {
    var suspectGame = new SuspectGame<>(new FormulaHistoryGame<>(game));

    Set<Agent> undefinedAgents = game.agents().stream()
        .filter(a -> a.payoff().equals(Agent.Payoff.UNDEFINED))
        .collect(Collectors.toSet());
    return Sets.powerSet(undefinedAgents).stream()
        .map(PayoffAssignment::new)
        .peek(p -> System.out.printf("Processing: %s%n", Formatter.format(p, game)))
        .map(payoff -> {
          Stopwatch solutionStopwatch = Stopwatch.createStarted();
          var strategy = RunGraphSccSolver.solve(new RunGraph<>(suspectGame, payoff));
          System.out.println("Solution: " + solutionStopwatch);
          return strategy.map(s -> new GameSolution<>(payoff, s));
        }).flatMap(Optional::stream);
  }

  record GameSolution<S>(PayoffAssignment assignment, EquilibriumStrategy<S> strategy) {}
}
