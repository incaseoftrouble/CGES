package com.cges;

import com.cges.algorithm.FormulaHistoryGame;
import com.cges.algorithm.HistoryGame;
import com.cges.algorithm.RunGraph;
import com.cges.algorithm.RunGraphSccSolver;
import com.cges.algorithm.StrategyMapper;
import com.cges.algorithm.SuspectGame;
import com.cges.algorithm.SuspectSolver;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.EquilibriumStrategy;
import com.cges.model.PayoffAssignment;
import com.cges.output.Formatter;
import com.cges.parser.ExplicitParser;
import com.cges.parser.GameParser;
import com.cges.parser.ModuleParser;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Sets;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Main {
  private Main() {}

  public static void main(String[] args) throws IOException {
    Stopwatch overall = Stopwatch.createStarted();
    ConcurrentGame<?> game;
    switch (args[0]) {
      case "game":
        try (BufferedReader reader = Files.newBufferedReader(Path.of(args[1]))) {
          game = GameParser.parse(reader.lines());
        }
        break;
      case "explicit":
        try (BufferedReader reader = Files.newBufferedReader(Path.of(args[1]))) {
          game = ExplicitParser.parse(JsonParser.parseReader(reader).getAsJsonObject());
        }
        break;
      case "module":
        try (BufferedReader reader = Files.newBufferedReader(Path.of(args[1]))) {
          game = ModuleParser.parse(JsonParser.parseReader(reader).getAsJsonObject());
        }
        break;
      default:
        throw new IllegalArgumentException(args[0]);
    }
    analyse(game).forEach(solution -> System.out.printf("Found NE for %s:%n%s%n",
        Formatter.format(solution.assignment(), game),
        solution.strategy()));
    System.out.println("Overall: " + overall);
  }

  private static <S> Stream<GameSolution<S>> analyse(ConcurrentGame<S> game) {
    Stopwatch suspectStopwatch = Stopwatch.createStarted();
    var suspectGame = SuspectGame.create(new FormulaHistoryGame<>(game));
    System.out.printf("Suspect: %s, %d states%n", suspectStopwatch, suspectGame.eveStates().size());

    Set<Agent> undefinedAgents = game.agents().stream()
        .filter(a -> a.payoff().equals(Agent.Payoff.UNDEFINED))
        .collect(Collectors.toSet());
    return Sets.powerSet(undefinedAgents).stream()
        .map(PayoffAssignment::new)
        .peek(p -> System.out.printf("Processing: %s%n", Formatter.format(p, game)))
        .<Optional<GameSolution<S>>>map(payoff -> {
          Stopwatch winningStopwatch = Stopwatch.createStarted();
          var suspectSolution = SuspectSolver.computeReachableWinningEveStates(suspectGame, payoff);
          System.out.println("Winning: " + winningStopwatch);

          Set<HistoryGame.HistoryState<S>> winningHistoryStates = suspectSolution.winningStates().stream()
              .filter(eve -> eve.suspects().equals(game.agents()))
              .map(SuspectGame.EveState::historyState)
              .collect(Collectors.toSet());

          Stopwatch solutionStopwatch = Stopwatch.createStarted();
          var runGraph = RunGraph.create(suspectGame.historyGame(), payoff, winningHistoryStates::contains);
          var lasso = RunGraphSccSolver.solve(runGraph);
          System.out.println("Solution: " + solutionStopwatch);
          if (lasso.isPresent()) {
            var strategy = StrategyMapper.createStrategy(suspectGame, suspectSolution, lasso.get());
            return Optional.of(new GameSolution<>(payoff, strategy));
          } else {
            return Optional.empty();
          }
        }).flatMap(Optional::stream);
  }

  record GameSolution<S>(PayoffAssignment assignment, EquilibriumStrategy<S> strategy) {}
}
