package com.cges.output;

import static com.cges.output.DotFormatted.toDotString;

import com.cges.GameSolution;
import com.cges.graph.HistoryGame;
import com.cges.graph.HistoryGame.HistoryState;
import com.cges.graph.RunGraph;
import com.cges.graph.SuspectGame;
import com.cges.model.Action;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Move;
import com.cges.parity.ParityGame;
import com.cges.parity.Player;
import com.cges.parity.PriorityState;
import com.cges.parser.Module;
import com.cges.parser.ModuleGame;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.io.PrintStream;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

import org.checkerframework.checker.units.qual.A;
import owl.bdd.BddSet;
import owl.game.Game;
import owl.ltl.BooleanConstant;
import owl.ltl.LabelledFormula;

public final class DotWriter {
    private DotWriter() {
    }

    public static <S> void writeModule(Module<S> module, ModuleGame<?> game, PrintStream writer) {
        Object2IntMap<S> ids = new Object2IntOpenHashMap<>();
        module.states().forEach(s -> ids.put(s, ids.size()));

        writer.append("digraph {\n");
        for (Object2IntMap.Entry<S> entry : ids.object2IntEntrySet()) {
            writer.append("MS_%d [label=\"%s\"]\n".formatted(entry.getIntValue(), toDotString(entry.getKey())));
        }

        for (var entry : ids.object2IntEntrySet()) {
            S state = entry.getKey();
            Map<S, Map<Action, BddSet>> successorByAction = new HashMap<>();
            for (Action action : module.actions(state)) {
                for (Map.Entry<S, BddSet> transition : module.successorMap(state, action).entrySet()) {
                    successorByAction.computeIfAbsent(transition.getKey(), k -> new HashMap<>()).merge(action,
                            transition.getValue(), BddSet::union);
                }
            }
            for (var moveEntry : successorByAction.entrySet()) {
                String label = moveEntry.getValue().entrySet().stream().map(e -> e.getValue().isUniverse()
                                ? e.getKey().name()
                                : "%s[%s]".formatted(e.getKey().name(),
                                e.getValue().toExpression().map(game.atomicPropositions()::get)))
                        .collect(Collectors.joining(", "));

                writer.append("MS_%d -> MS_%d [label=\"%s\"]\n".formatted(entry.getIntValue(),
                        ids.getInt(moveEntry.getKey()), label));
            }
        }
        writer.append("}");
    }

    public static <S> void writeHistoryGame(HistoryGame<S> game, PrintStream writer) {
        writeHistoryGame(game, writer, null);
    }

    public static <S> void writeHistoryGame(HistoryGame<S> game, PrintStream writer,
                                            @Nullable Predicate<HistoryState<S>> winning) {
        Object2IntMap<HistoryState<S>> ids = new Object2IntOpenHashMap<>();
        ids.defaultReturnValue(-1);
        HistoryState<S> initialState = game.initialState();
        ids.put(initialState, 0);
        Queue<HistoryState<S>> queue = new ArrayDeque<>(List.of(initialState));
        while (!queue.isEmpty()) {
            var state = queue.poll();
            game.transitions(state).forEach(transition -> {
                if (ids.putIfAbsent(transition.destination(), ids.size()) == -1) {
                    queue.add(transition.destination());
                }
            });
        }

        writer.append("digraph {\n");
        for (Object2IntMap.Entry<HistoryState<S>> entry : ids.object2IntEntrySet()) {
            writer.append("HS_%d [color=%s,fillcolor=white,label=\"%s\"]\n".formatted(entry.getIntValue(),
                    winning == null ? "black" : (winning.test(entry.getKey()) ? "green" : "red"),
                    toDotString(entry.getKey())));
        }
        for (Object2IntMap.Entry<HistoryState<S>> entry : ids.object2IntEntrySet()) {
            SetMultimap<HistoryState<S>, Move> movesBySuccessor = HashMultimap.create();
            game.transitions(entry.getKey())
                    .forEach(transition -> movesBySuccessor.put(transition.destination(), transition.move()));
            for (var moveEntry : movesBySuccessor.asMap().entrySet()) {
                writer.append("HS_%d -> HS_%d [label=\"%s\"]\n".formatted(entry.getIntValue(),
                        ids.getInt(moveEntry.getKey()), moveEntry.getValue().stream()
                                .map(DotFormatted::toDotString).collect(Collectors.joining(", "))));
            }
        }
        writer.append("}");
    }

    public static <S> void writeSuspectGame(SuspectGame<S> game, PrintStream writer) {
        writeSuspectGame(game, game.initialState(), writer, null);
    }

    public static <S> void writeSuspectGame(SuspectGame<S> game, SuspectGame.EveState<S> initial, PrintStream writer,
                                            @Nullable Predicate<SuspectGame.EveState<S>> winning) {
        Object2IntMap<SuspectGame.EveState<S>> ids = new Object2IntOpenHashMap<>();
        ids.defaultReturnValue(-1);
        ids.put(initial, 0);
        Queue<SuspectGame.EveState<S>> queue = new ArrayDeque<>(List.of(initial));
        while (!queue.isEmpty()) {
            var state = queue.poll();
            game.eveSuccessors(state).forEach(successor -> {
                if (ids.putIfAbsent(successor, ids.size()) == -1) {
                    queue.add(successor);
                }
            });
        }
        List<Agent> agents = game.historyGame().concurrentGame().agents().stream()
                .sorted(Comparator.comparing(Agent::name)).toList();
        List<String> propositions = game.historyGame().concurrentGame().atomicPropositions();

        writer.append("digraph {\n");
        for (Object2IntMap.Entry<SuspectGame.EveState<S>> entry : ids.object2IntEntrySet()) {
            SuspectGame.EveState<S> eveState = entry.getKey();
            writer.append("ES_%d [color=%s,fillcolor=white,shape=record,label=\"%s\"]\n".formatted(entry.getIntValue(),
                    winning == null ? "black" : (winning.test(eveState) ? "green" : "red"), Stream
                            .concat(Stream.of(toDotString(eveState.historyState().state()),
                                            toDotString(eveState.suspects().stream()
                                                    .sorted(Comparator.comparing(Agent::name))
                                                    .map(Agent::name)
                                                    .collect(Collectors.joining(",")))),
                                    agents.stream().map(a -> a.name() + " "
                                            + toDotString(eveState.historyState()
                                            .goal(a), propositions)))
                            .map(DotFormatted::toRecordString)
                            .collect(Collectors.joining("|", "{", "}"))));
        }

        for (Object2IntMap.Entry<SuspectGame.EveState<S>> entry : ids.object2IntEntrySet()) {
            SetMultimap<SuspectGame.EveState<S>, Move> deviatingMovesBySuccessor = HashMultimap.create();
            SetMultimap<SuspectGame.EveState<S>, Move> compliantMoves = HashMultimap.create();

            SuspectGame.EveState<S> eveState = entry.getKey();
            game.successors(eveState).forEach(adamState -> {
                compliantMoves.put(game.compliantSuccessor(adamState), adamState.move());
                game.deviationSuccessors(adamState)
                        .forEach(eveSuccessor -> deviatingMovesBySuccessor.put(eveSuccessor, adamState.move()));
            });
            assert !compliantMoves.isEmpty();

            for (var moveEntry : deviatingMovesBySuccessor.asMap().entrySet()) {
                writer.append("ES_%d -> ES_%d [label=\"%s\",style=dotted]\n".formatted(entry.getIntValue(),
                        ids.getInt(moveEntry.getKey()), moveEntry.getValue().stream()
                                .map(DotFormatted::toDotString).collect(Collectors.joining(", "))));
            }
            for (var moveEntry : compliantMoves.asMap().entrySet()) {
                writer.append("ES_%d -> ES_%d [label=\"%s\"]\n".formatted(entry.getIntValue(),
                        ids.getInt(moveEntry.getKey()), moveEntry.getValue().stream()
                                .map(DotFormatted::toDotString).collect(Collectors.joining(", "))));
            }
        }
        writer.append("}");
    }

    public static <S> void writeParityGame(ParityGame<S> game, PrintStream writer) {
        writeParityGame(game, writer, null);
    }

    private static <S> void writeParityGame(ParityGame<S> game, PrintStream writer,
                                            @Nullable Function<S, String> stateFormatter) {
        Object2IntMap<S> ids = new Object2IntOpenHashMap<>();
        game.forEachState(state -> ids.put(state, ids.size()));
        Function<S, String> formatter = stateFormatter == null ? DotFormatted::toDotString : stateFormatter;

        writer.append("digraph {\n");
        for (var entry : ids.object2IntEntrySet()) {
            S state = entry.getKey();
            writer.append("S_%d [shape=%s,label=\"%s %s\"]\n".formatted(entry.getIntValue(),
                    game.owner(state) == Player.EVEN ? "box" : "ellipse", formatter.apply(state),
                    game.priority(state)));
        }
        for (var entry : ids.object2IntEntrySet()) {
            game.successors(entry.getKey()).distinct().forEach(successor -> writer
                    .append("S_%d -> S_%d\n".formatted(entry.getIntValue(), ids.getInt(successor))));
        }
        writer.append("}");
    }

    public static <S> void writeConcurrentGame(ConcurrentGame<S> game, PrintStream writer) {
        Object2IntMap<S> ids = new Object2IntOpenHashMap<>();
        game.states().forEach(state -> ids.put(state, ids.size()));
        writer.append("digraph {\n");
        for (var entry : ids.object2IntEntrySet()) {
            writer.append("S_%d [label=\"%s\"]\n".formatted(entry.getIntValue(), toDotString(entry.getKey())));
        }
        for (var entry : ids.object2IntEntrySet()) {
            SetMultimap<S, Move> movesBySuccessor = HashMultimap.create();
            game.transitions(entry.getKey())
                    .forEach(transition -> movesBySuccessor.put(transition.destination(), transition.move()));
            for (var moveEntry : movesBySuccessor.asMap().entrySet()) {
                writer.append("S_%d -> S_%d [label=\"%s\"]\n".formatted(entry.getIntValue(),
                        ids.getInt(moveEntry.getKey()), moveEntry.getValue().stream()
                                .map(DotFormatted::toDotString).collect(Collectors.joining(", "))));
            }
        }
        writer.append("}");
    }

    public static <S> void writeLasso(GameSolution<S> solution, PrintStream writer) {
        var suspectGame = solution.suspectGame();
        var strategy = solution.strategy();
        var runGraph = solution.runGraph();
        List<String> atomicPropositions = suspectGame.historyGame().concurrentGame().atomicPropositions();

        writer.append("digraph {\n");
        Set<RunGraph.RunState<S>> loopStates = strategy.lasso().states(false).collect(Collectors.toSet());

        Object2IntMap<RunGraph.RunState<S>> ids = new Object2IntOpenHashMap<>();
        ids.defaultReturnValue(-1);
        loopStates.forEach(runState -> ids.put(runState, ids.size()));
        ids.forEach((runState, id) -> {
            HistoryState<S> historyState = runState.historyState();
            String label = Stream.concat(Stream.concat(
                                    Stream.of(DotFormatted.toRecordString(toDotString(historyState.state()))),
                                    suspectGame.historyGame().concurrentGame().agents().stream()
                                            .filter(a -> !historyState.goal(a).equals(BooleanConstant.TRUE))
                                            .map(a -> "%s %s".formatted(DotFormatted.toRecordString(a.name()),
                                                    DotFormatted.toRecordString(LabelledFormula
                                                            .of(historyState.goal(a),
                                                                    atomicPropositions)
                                                            .toString())))),
                            Stream.of(DotFormatted.toRecordString(
                                    toDotString(runState.automatonState(), runGraph.automatonPropositions()))))
                    .collect(Collectors.joining("|", "{", "}"));
            writer.append("S_%d [shape=record,label=\"%s\"]\n".formatted(id, label));
        });
        ids.forEach((runState, id) -> {
            Move move = strategy.moves().get(runState);
            var historySuccessor = suspectGame.historyGame().transition(runState.historyState(), move).orElseThrow()
                    .destination();
            var runSuccessor = runGraph.successors(runState).stream()
                    .filter(s -> s.historyState().equals(historySuccessor)).findAny().orElseThrow();

            writer.append("S_%d -> S_%d [label=\"%s\"];\n".formatted(id, ids.getInt(runSuccessor), toDotString(move)));
        });

        writer.append("}");
    }

    public static <S> void writeSolution(GameSolution<S> solution, PrintStream writer) {
        var strategy = solution.strategy();
        var suspectGame = solution.suspectGame();
        var punishmentStrategy = solution.strategy().punishmentStrategy();
        var runGraph = solution.runGraph();
        var lasso = solution.strategy().lasso();
        var moves = solution.strategy().moves();
        List<String> atomicPropositions = suspectGame.historyGame().concurrentGame().atomicPropositions();

        writer.append("digraph {\n");
        Set<RunGraph.RunState<S>> loopStates = lasso.states(false).collect(Collectors.toSet());
        Set<PriorityState<S>> reachableGameStates = loopStates.stream().flatMap(runState -> punishmentStrategy
                        .states(runState.historyState(), moves.get(runState)).stream()
                        .filter(s -> !s.eve().historyState().equals(lasso.successor(runState).historyState())))
                .collect(Collectors.toSet());
        Queue<PriorityState<S>> queue = new ArrayDeque<>(reachableGameStates);
        while (!queue.isEmpty()) {
            var state = queue.poll();
            for (PriorityState<S> successor : punishmentStrategy.successors(state)) {
                if (reachableGameStates.add(successor)) {
                    queue.add(successor);
                }
            }
        }

        Object2IntMap<RunGraph.RunState<S>> loopIds = new Object2IntOpenHashMap<>();
        loopIds.defaultReturnValue(-1);
        loopStates.forEach(run -> loopIds.put(run, loopIds.size()));
        Object2IntMap<PriorityState<S>> gameIds = new Object2IntOpenHashMap<>();
        gameIds.defaultReturnValue(-1);
        reachableGameStates.forEach(game -> gameIds.put(game, gameIds.size()));

        loopIds.forEach((runState, id) -> {
            HistoryState<S> historyState = runState.historyState();
            String label = Stream.concat(
                            Stream.concat(Stream.of(DotFormatted.toRecordString(toDotString(historyState.state()))),
                                    suspectGame.historyGame().concurrentGame().agents().stream()
                                            .sorted(Comparator.comparing(Agent::name))
                                            .filter(a -> !historyState.goal(a)
                                                    .equals(BooleanConstant.TRUE))
                                            .map(a -> "%s: %s".formatted(
                                                    DotFormatted.toRecordString(a.name()),
                                                    DotFormatted.toRecordString(toDotString(
                                                            historyState.goal(a),
                                                            atomicPropositions))))),
                            Stream.of(DotFormatted.toRecordString(
                                    toDotString(runState.automatonState(), runGraph.automatonPropositions()))))
                    .collect(Collectors.joining("|", "{", "}"));
            writer.append("HS_%d [shape=record,label=\"%s\"]\n".formatted(id, label));
        });
        var edgeStates = new HashSet<PriorityState<S>>();
        var idToState = new HashMap<Integer, PriorityState<S>>();
        var stateToId = new HashMap<PriorityState<S>, Integer>();
        gameIds.forEach((gameState, id) -> {
            idToState.put(id, gameState);
            stateToId.put(gameState, id);
            if (gameState.isEve()) {
                String eveState = Stream
                        .concat(Stream.of(toDotString(gameState.eve().gameState())), gameState.eve().suspects()
                                .stream().sorted(Comparator.comparing(Agent::name))
                                .map(a -> "%s: %s".formatted(a.name(),
                                        toDotString(gameState.eve().historyState().goal(a),
                                                atomicPropositions))))
                        .map(DotFormatted::toRecordString).collect(Collectors.joining("|", "{", "}"));
                writer.append("GS_%d [shape=record,style=dotted,label=\"%s|%d\"]\n".formatted(id, eveState,
                        gameState.priority()));
            } else {
                edgeStates.add(gameState);
                writer.append("GS_%d [shape=record,style=dashed,label=\"GS_%d %s|%d\"]\n".formatted(id,id,
                        toDotString(gameState.adam().move()), gameState.priority()));
            }
        });

        loopIds.forEach((runState, id) -> {
            Move move = strategy.moves().get(runState);
            writer.append("HS_%d -> HS_%d [label=\"%s\",penwidth=2];\n".formatted(id,
                    loopIds.getInt(lasso.successor(runState)), toDotString(move)));
            punishmentStrategy.states(runState.historyState(), move).stream()
                    .filter(s -> !s.eve().historyState().equals(lasso.successor(runState).historyState()))
                    .forEach(punishmentState -> writer.append("HS_%d -> GS_%d [style=dotted];\n".formatted(id,
                            gameIds.getInt(punishmentState))));
        });
        var paths = new HashMap<PriorityState<S>, ArrayList<PriorityState<S>>>();
        gameIds.forEach((gameState, id) -> {
            for (PriorityState<S> successor : punishmentStrategy.successors(gameState)) {
                if (edgeStates.contains(successor) || edgeStates.contains(idToState.get(id))) {
                    var fromState = idToState.get(id);
                    if (!paths.containsKey(fromState)) {
                        paths.put(fromState, new ArrayList<>());
                    }
                    paths.get(idToState.get(id)).add(successor);
                } else {
                    writer.append("GS_%d -> GS_%d;\n".formatted(id, gameIds.getInt(successor)));
                }
            }
        });
        paths.forEach((src, edgeArr) -> {
            for (PriorityState<S> edge : edgeArr) {
                if (edgeStates.contains(edge)) {
                    var label = "[label=\"GS_%d %s|%d\"]\n".formatted(gameIds.getInt(edge),toDotString(edge.adam().move()), edge.priority());
                    for (PriorityState<S> dst : paths.get(edge)) {
                        writer.append("GS_%d -> GS_%d %s\n".formatted(gameIds.getInt(src), gameIds.getInt(dst), label));
                    }
                }
            }
        });

        writer.append("}");
    }
}
