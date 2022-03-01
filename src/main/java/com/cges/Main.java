package com.cges;

import com.cges.algorithm.BoundedChecker;
import com.cges.algorithm.SuspectSolver;
import com.cges.model.ConcurrentGame;
import com.cges.model.RunGraph;
import com.cges.model.SuspectGame;
import com.google.common.base.Stopwatch;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class Main {
  public static void main(String[] args) throws IOException {
    Stopwatch stopwatch = Stopwatch.createStarted();
    try (BufferedReader reader = Files.newBufferedReader(Path.of(args[0]))) {
      ConcurrentGame parse = ConcurrentGame.parse(reader.lines());
      System.out.println("Parsing: " + stopwatch);
      SuspectGame suspectGame = SuspectGame.create(parse);
      System.out.println("Suspect: " + stopwatch);
      Set<SuspectGame.EveState> winningEveStates = SuspectSolver.computeWinningEveStates(suspectGame);
      System.out.println("Winning: " + stopwatch);
      RunGraph runGraph = RunGraph.create(suspectGame, winningEveStates);
      System.out.println("Run: " + stopwatch);
      List<RunGraph.State> lasso = BoundedChecker.checkScc(runGraph);
      System.out.println("Lasso: " + stopwatch);
      System.out.println(lasso.size());
      System.out.println(lasso);
    }
  }
}
