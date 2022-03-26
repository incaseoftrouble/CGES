package com.cges;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.algorithm.RunGraphSolver;
import com.cges.graph.FormulaHistoryGame;
import com.cges.graph.RunGraph;
import com.cges.graph.SuspectGame;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.EquilibriumStrategy;
import com.cges.model.PayoffAssignment;
import com.cges.output.DotWriter;
import com.cges.output.Formatter;
import com.cges.parser.GameParser;
import com.cges.parser.Module;
import com.cges.parser.ModuleGame;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public final class Main {
  private Main() {}

  public static void main(String[] args) throws IOException {
    Stopwatch overall = Stopwatch.createStarted();
    ConcurrentGame<?> game;
    @Nullable
    Set<Map<Agent, Boolean>> validationSet;
    if ("game".equals(args[0])) {
      try (BufferedReader reader = Files.newBufferedReader(Path.of(args[1]))) {
        game = GameParser.parseExplicit(reader.lines());
      }
      validationSet = null;
    } else {
      try (BufferedReader reader = Files.newBufferedReader(Path.of(args[0]))) {
        JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
        game = GameParser.parse(jsonObject);
        if (game instanceof ModuleGame<?> module) {
          for (Module<?> m : module.modules()) {
            DotWriter.writeModule(m, module, System.out);
            System.out.println();
          }
        }
        JsonArray validation = jsonObject.getAsJsonArray("expected");
        if (validation == null) {
          validationSet = null;
        } else {
          validationSet = new HashSet<>();
          for (JsonElement jsonElement : validation) {
            Map<Agent, Boolean> expectedResult = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : jsonElement.getAsJsonObject().entrySet()) {
              Agent agent = game.agent(entry.getKey());
              boolean payoff = entry.getValue().getAsBoolean();
              expectedResult.put(agent, payoff);
            }
            checkArgument(expectedResult.keySet().equals(game.agents()), "Invalid validation specification");
            validationSet.add(expectedResult);
          }
        }
      }
    }
    var list = analyse(game).peek(solution -> System.out.printf("Found NE for %s:%n%s%n",
        Formatter.format(solution.assignment(), game),
        solution.strategy())).collect(Collectors.toList());
    System.out.println("Overall: " + overall);
    list.sort(Comparator.comparingLong(solution -> game.agents().stream().map(solution.assignment()::isLoser).count()));
    list.stream().map(solution -> Formatter.format(solution.assignment(), game)).forEach(System.out::println);
    if (validationSet != null) {
      Set<Map<Agent, Boolean>> results = list.stream().map(GameSolution::assignment)
          .map(p -> game.agents().stream().collect(Collectors.toUnmodifiableMap(Function.identity(), p::isWinner)))
          .collect(Collectors.toUnmodifiableSet());
      if (!results.equals(validationSet)) {
        var agents = game.agents().stream().sorted(Comparator.comparing(Agent::name)).toList();
        System.out.println("Expected equilibrium:");
        for (Map<Agent, Boolean> map : validationSet) {
          System.out.println(agents.stream().map(a -> "%s:%s".formatted(a.name(), map.get(a))).collect(Collectors.joining(",", "[", "]")));
        }
        System.out.println("Found equilibrium:");
        for (Map<Agent, Boolean> map : results) {
          System.out.println(agents.stream().map(a -> "%s:%s".formatted(a.name(), map.get(a))).collect(Collectors.joining(",", "[", "]")));
        }
        System.exit(1);
      }
    }
  }

  private static <S> Stream<GameSolution<S>> analyse(ConcurrentGame<S> game) {
    var suspectGame = new SuspectGame<>(new FormulaHistoryGame<>(game));

    Set<Agent> undefinedAgents = game.agents().stream()
        .filter(a -> a.payoff().equals(Agent.Payoff.UNDEFINED))
        .collect(Collectors.toSet());
    return Sets.powerSet(undefinedAgents).stream()
        .map(PayoffAssignment::new)
        .map(payoff -> {
          System.out.printf("Processing: %s%n", Formatter.format(payoff, game));
          Stopwatch timer = Stopwatch.createStarted();
          var strategy = RunGraphSolver.solve(new RunGraph<S>(suspectGame, payoff));
          System.out.println("Solution: " + timer);
          return strategy.map(s -> new GameSolution<>(payoff, s));
        }).flatMap(Optional::stream);
  }

  record GameSolution<S>(PayoffAssignment assignment, EquilibriumStrategy<S> strategy) {}
}
