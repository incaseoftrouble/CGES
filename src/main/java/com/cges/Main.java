package com.cges;

import com.cges.algorithm.HistoryGame;
import com.cges.algorithm.RunGraph;
import com.cges.algorithm.RunGraphSccSolver;
import com.cges.algorithm.StrategyMapper;
import com.cges.algorithm.SuspectGame;
import com.cges.algorithm.SuspectSolver;
import com.cges.model.ConcurrentGame;
import com.cges.output.DotWriter;
import com.cges.parser.ExplicitParser;
import com.cges.parser.GameParser;
import com.cges.parser.ModuleParser;
import com.google.common.base.Stopwatch;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {
  public static void main(String[] args) throws IOException {
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
    analyse(game);
  }

  private static <S> void analyse(ConcurrentGame<S> game) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    var suspectGame = SuspectGame.create(new HistoryGame<>(game));
    System.out.println("Suspect: " + stopwatch);
    var suspectSolution = SuspectSolver.computeWinningEveStates(suspectGame);
    System.out.println("Winning: " + stopwatch);
    var runGraph = RunGraph.create(suspectGame, suspectSolution);
    System.out.println("Run: " + stopwatch);
    var lasso = RunGraphSccSolver.solve(runGraph);
    System.out.println("Lasso: " + stopwatch);
    if (lasso.isPresent()) {
      var strategy = StrategyMapper.createStrategy(suspectGame, suspectSolution, lasso.get());
      DotWriter.writeSolution(suspectGame, runGraph, strategy, System.out);
    }
  }
}
