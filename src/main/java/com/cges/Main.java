package com.cges;

import static com.google.common.base.Preconditions.checkArgument;
import static picocli.CommandLine.ArgGroup;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

import com.cges.algorithm.RunGraphSolver;
import com.cges.graph.FormulaHistoryGame;
import com.cges.graph.RunGraph;
import com.cges.graph.SuspectGame;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
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
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import picocli.CommandLine;

@Command(
    name = "cges",
    mixinStandardHelpOptions = true,
    version = "Concurrent Game Equilibrium Solver 0.1",
    description = "Computes Nash-equilibria for concurrent games")
public final class Main implements Callable<Void> {
    private static final Logger log = Logger.getLogger(Main.class.getName());

    private static PrintStream open(String output) throws IOException {
        return "-".equals(output)
            ? System.out
            : new PrintStream(new BufferedOutputStream(Files.newOutputStream(Path.of(output))));
    }

    private static <S> void writeIfPresent(@Nullable String output, S object, BiConsumer<S, PrintStream> formatter)
        throws IOException {
        if (output != null) {
            try (var stream = open(output)) {
                formatter.accept(object, stream);
            }
        }
    }

    @Nullable
    @Option(
        names = {"--write-dot-cg"},
        description = "Write the concurrent game")
    private String writeDotConcurrentGame;

    @Nullable
    @Option(
        names = {"--write-dot-hg"},
        description = "Write the history game")
    private String writeDotHistoryGame;

    @Nullable
    @Option(
        names = {"--write-dot-sg"},
        description = "Write the suspect game")
    private String writeDotSuspectGame;

    @Nullable
    @Option(
        names = {"--write-dot-sol"},
        description = "Write the solutions")
    private String writeDotSolution;

    @Option(
        names = {"--write-dot-module"},
        description = "Write the module (format: <name>,<destination>)")
    private List<String> writeModule = List.of();

    @Option(
        names = {"-O", "--output"},
        description = "Write the assignments with nash equilibria")
    private String writeOutput = "-";

    @Option(
        names = {"--rg-solver"},
        description = "Solver to search for a lasso. Valid: ${COMPLETION-CANDIDATES}, default: ${DEFAULT-VALUE}")
    private RunGraphSolver.LassoSolver solver = RunGraphSolver.LassoSolver.GRAPH_SEARCH;

    @ArgGroup(heading = "game", multiplicity = "1")
    private GameSource gameSource;

    @Option(
        names = {"--memory"},
        description = "Conserve memory by not storing solutions (disables some other options)"
    )
    private boolean memory = false;

    static class GameSource {
        @Nullable
        @Option(names = "--game", description = "Source file in JSON format")
        private String json;

        @Nullable
        @Option(names = "--game-explicit", description = "Source file in explicit format")
        private String explicit;
    }

    private Main() {}

    private record Input<S>(ConcurrentGame<S> game, @Nullable Set<Map<Agent, Boolean>> validationSet) {}

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .execute(args));
    }

    private Input<?> parseGame() throws IOException {
        if (gameSource.explicit == null) {
            assert gameSource.json != null;
            JsonObject jsonObject;
            try (BufferedReader reader = Files.newBufferedReader(Path.of(gameSource.json))) {
                jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            }
            var game = GameParser.parse(jsonObject);
            JsonArray validation = jsonObject.getAsJsonArray("expected");
            if (validation == null) {
                return new Input<>(game, null);
            }
            Set<Map<Agent, Boolean>> validationSet = new HashSet<>();
            for (JsonElement jsonElement : validation) {
                Map<Agent, Boolean> expectedResult = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry :
                    jsonElement.getAsJsonObject().entrySet()) {
                    Agent agent = game.agent(entry.getKey());
                    boolean payoff = entry.getValue().getAsBoolean();
                    expectedResult.put(agent, payoff);
                }
                checkArgument(expectedResult.keySet().equals(game.agents()), "Invalid validation specification");
                validationSet.add(expectedResult);
            }
            return new Input<>(game, validationSet);
        }
        try (BufferedReader reader = Files.newBufferedReader(Path.of(gameSource.explicit))) {
            return new Input<>(GameParser.parseExplicit(reader.lines()), null);
        }
    }

    private <S> Stream<GameSolution<S>> computeSolutions(ConcurrentGame<S> game) throws IOException {
        var historyGame = new FormulaHistoryGame<>(game);
        writeIfPresent(writeDotHistoryGame, historyGame, DotWriter::writeHistoryGame);
        var suspectGame = new SuspectGame<>(historyGame);
        writeIfPresent(writeDotSuspectGame, suspectGame, DotWriter::writeSuspectGame);

        Set<Agent> undefinedAgents = game.agents().stream()
            .filter(a -> a.payoff().equals(Agent.Payoff.UNDEFINED))
            .collect(Collectors.toSet());
        return Sets.powerSet(undefinedAgents).stream()
            .map(PayoffAssignment::new)
            .map(payoff -> {
                log.log(Level.INFO, () -> "Processing: %s".formatted(Formatter.format(payoff, game)));
                Stopwatch timer = Stopwatch.createStarted();
                RunGraph<S> runGraph = new RunGraph<>(suspectGame, payoff);
                var strategy = RunGraphSolver.solve(runGraph, solver);
                log.log(Level.INFO, () -> "Solution: %s".formatted(timer));
                return strategy.map(s -> new GameSolution<>(suspectGame, runGraph, payoff, s));
            })
            .flatMap(Optional::stream);
    }

    private <S> List<GameSolution<S>> solve(Input<S> input) throws IOException {
        Stopwatch overall = Stopwatch.createStarted();
        var game = input.game;
        var solutionList = computeSolutions(game)
            .peek(solution -> log.log(Level.INFO, () -> "Found NE for %s:%n%s%n"
                .formatted(Formatter.format(solution.assignment(), game), solution.strategy())))
            .toList();
        log.log(Level.INFO, () -> "Solving took %s overall".formatted(overall));
        if (!solutionList.isEmpty()) {
            if (writeDotSolution != null) {
                if (writeDotSolution.contains("%A")) {
                    for (GameSolution<S> solution : solutionList) {
                        var destination = writeDotSolution.replaceAll(
                            "%A",
                            game.agents().stream()
                                .sorted()
                                .map(solution.assignment()::map)
                                .map(Agent.Payoff::toString)
                                .collect(Collectors.joining()));
                        try (var stream = open(destination)) {
                            DotWriter.writeSolution(solution, stream);
                        }
                    }
                } else {
                    writeIfPresent(writeDotSolution, solutionList, (list, stream) -> {
                        for (GameSolution<?> solution : list) {
                            DotWriter.writeSolution(solution, stream);
                            stream.println();
                        }
                    });
                }
            }
        }
        if (input.validationSet != null) {
            validate(game, solutionList, input.validationSet);
        }
        return solutionList;
    }

    @Override
    public Void call() throws Exception {
        Input<?> input = parseGame();
        if (!writeModule.isEmpty()) {
            Map<String, String> names = writeModule.stream()
                .map(s -> s.split(","))
                .peek(s -> checkArgument(s.length == 2))
                .collect(Collectors.toMap(s -> s[0], s -> s[1]));
            if (input.game instanceof ModuleGame<?> module) {
                for (Module<?> m : module.modules()) {
                    var output = names.get(m.agent().name());
                    if (output != null) {
                        DotWriter.writeModule(m, module, System.out);
                    }
                }
            } else {
                log.log(Level.WARNING, "Modules to output specified but read an explicit game");
            }
        }
        writeIfPresent(writeDotConcurrentGame, input.game, DotWriter::writeConcurrentGame);

        if (memory) {
            Stopwatch overall = Stopwatch.createStarted();
            try (var stream = open(writeOutput)) {
                var game = input.game;
                var solutions = computeSolutions(game).iterator();
                while (solutions.hasNext()) {
                    {
                        var solution = solutions.next();
                        log.log(Level.INFO, () -> "Found NE for %s:%n%s%n"
                            .formatted(Formatter.format(solution.assignment(), game), solution.strategy()));
                        stream.println(solution.assignment().format(input.game.agents()));
                    }
                    System.gc();
                }
            }
            log.log(Level.INFO, () -> "Solving took %s overall".formatted(overall));
        } else {
            var solutionList = solve(input);
            try (var stream = open(writeOutput)) {
                for (GameSolution<?> solution : solutionList) {
                    stream.println(solution.assignment().format(input.game.agents()));
                }
            }
        }
        return null;
    }

    private <S> void validate(
        ConcurrentGame<S> game, Collection<GameSolution<S>> solutions, Set<Map<Agent, Boolean>> validationSet) {
        Set<Map<Agent, Boolean>> results = solutions.stream()
            .map(GameSolution::assignment)
            .map(p ->
                game.agents().stream().collect(Collectors.toUnmodifiableMap(Function.identity(), p::isWinner)))
            .collect(Collectors.toUnmodifiableSet());
        if (!results.equals(validationSet)) {
            var invalid = Sets.difference(results, validationSet);
            var missing = Sets.difference(validationSet, results);
            var agents = game.agents().stream()
                .sorted(Comparator.comparing(Agent::name))
                .toList();

            System.err.println("Validation failed!");
            if (!invalid.isEmpty()) {
                System.err.println("Invalid equilibria:");
                for (Map<Agent, Boolean> map : invalid) {
                    System.err.println(agents.stream()
                        .map(a -> "%s:%s".formatted(a.name(), map.get(a)))
                        .collect(Collectors.joining(",", "[", "]")));
                }
            }
            if (!missing.isEmpty()) {
                System.err.println("Missing equilibria:");
                for (Map<Agent, Boolean> map : results) {
                    System.err.println(agents.stream()
                        .map(a -> "%s:%s".formatted(a.name(), map.get(a)))
                        .collect(Collectors.joining(",", "[", "]")));
                }
            }
            System.exit(1);
        }
    }
}
