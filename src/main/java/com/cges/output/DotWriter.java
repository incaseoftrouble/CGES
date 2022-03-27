package com.cges.output;

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
import com.cges.parser.Module;
import com.cges.parser.ModuleGame;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import owl.bdd.BddSet;
import owl.ltl.BooleanConstant;
import owl.ltl.LabelledFormula;

public final class DotWriter {
  private DotWriter() {}

  public static <S> void writeModule(Module<S> module, ModuleGame<?> game, PrintStream writer) {
    Object2IntMap<S> ids = new Object2IntOpenHashMap<>();
    module.states().forEach(s -> ids.put(s, ids.size()));

    writer.append("digraph {\n");
    for (Object2IntMap.Entry<S> entry : ids.object2IntEntrySet()) {
      writer.append("MS_%d [label=\"%s\"]\n".formatted(
          entry.getIntValue(),
          DotFormatted.toString(entry.getKey())
      ));
    }

    for (var entry : ids.object2IntEntrySet()) {
      S state = entry.getKey();
      Map<S, Map<Action, BddSet>> successorByAction = new HashMap<>();
      for (Action action : module.actions(state)) {
        for (Map.Entry<S, BddSet> transition : module.successorMap(state, action).entrySet()) {
          successorByAction.computeIfAbsent(transition.getKey(), k -> new HashMap<>()).merge(action, transition.getValue(), BddSet::union);
        }
      }
      for (var moveEntry : successorByAction.entrySet()) {
        String label = moveEntry.getValue().entrySet().stream()
            .map(e -> e.getValue().isUniverse()
                ? e.getKey().name()
                : "%s[%s]".formatted(e.getKey().name(), e.getValue().toExpression().map(game.atomicPropositions()::get)))
            .collect(Collectors.joining(", "));

        writer.append("MS_%d -> MS_%d [label=\"%s\"]\n".formatted(entry.getIntValue(), ids.getInt(moveEntry.getKey()), label));
      }
    }
    writer.append("}");
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
      writer.append("HS_%d [color=%s,fillcolor=white,label=\"%s\"]\n".formatted(
          entry.getIntValue(),
          winning == null ? "black" : (winning.test(entry.getKey()) ? "green" : "red"),
          DotFormatted.toString(entry.getKey())
      ));
    }
    for (Object2IntMap.Entry<HistoryState<S>> entry : ids.object2IntEntrySet()) {
      SetMultimap<HistoryState<S>, Move> movesBySuccessor = HashMultimap.create();
      game.transitions(entry.getKey()).forEach(transition ->
          movesBySuccessor.put(transition.destination(), transition.move()));
      for (var moveEntry : movesBySuccessor.asMap().entrySet()) {
        writer.append("HS_%d -> HS_%d [label=\"%s\"]\n".formatted(
            entry.getIntValue(),
            ids.getInt(moveEntry.getKey()),
            moveEntry.getValue().stream().map(DotFormatted::toString).collect(Collectors.joining(", "))));
      }
    }
    writer.append("}");
  }

  public static <S> void writeSuspectGame(SuspectGame<S> game, SuspectGame.EveState<S> initial,
      PrintStream writer, @Nullable Predicate<SuspectGame.EveState<S>> winning) {
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

    writer.append("digraph {\n");
    for (Object2IntMap.Entry<SuspectGame.EveState<S>> entry : ids.object2IntEntrySet()) {
      SuspectGame.EveState<S> eveState = entry.getKey();
      writer.append("ES_%d [color=%s,fillcolor=white,label=\"%s -- %s\"]\n".formatted(
          entry.getIntValue(),
          winning == null ? "black" : (winning.test(eveState) ? "green" : "red"),
          DotFormatted.toString(eveState.historyState()),
          eveState.suspects().stream().map(Agent::name).sorted().collect(Collectors.joining(",")))
      );
    }

    for (Object2IntMap.Entry<SuspectGame.EveState<S>> entry : ids.object2IntEntrySet()) {
      SetMultimap<SuspectGame.EveState<S>, Move> deviatingMovesBySuccessor = HashMultimap.create();
      SetMultimap<SuspectGame.EveState<S>, Move> compliantMoves = HashMultimap.create();

      SuspectGame.EveState<S> eveState = entry.getKey();
      game.successors(eveState).forEach(adamState -> {
        for (Move move : adamState.moves()) {
          var compliantTransition = game.historyGame().transition(eveState.historyState(), move).orElseThrow();
          game.successors(adamState).forEach(eveSuccessor -> {
            if (eveSuccessor.historyState().equals(compliantTransition.destination())) {
              assert compliantTransition.move().equals(adamState.moves());
              compliantMoves.put(eveSuccessor, compliantTransition.move());
            } else {
              deviatingMovesBySuccessor.put(eveSuccessor, move);
            }
          });
        }
      });
      assert !compliantMoves.isEmpty();

      for (var moveEntry : deviatingMovesBySuccessor.asMap().entrySet()) {
        writer.append("ES_%d -> ES_%d [label=\"%s\",style=dotted]\n".formatted(
            entry.getIntValue(),
            ids.getInt(moveEntry.getKey()),
            moveEntry.getValue().stream().map(DotFormatted::toString).collect(Collectors.joining(", "))));
      }
      for (var moveEntry : compliantMoves.asMap().entrySet()) {
        writer.append("ES_%d -> ES_%d [label=\"%s\"]\n".formatted(
            entry.getIntValue(),
            ids.getInt(moveEntry.getKey()),
            moveEntry.getValue().stream().map(DotFormatted::toString).collect(Collectors.joining(", "))));
      }
    }
    writer.append("}");
  }

  public static <S> void writeParityGame(ParityGame<S> game, PrintStream writer,
      @Nullable Function<S, String> stateFormatter) {
    Object2IntMap<S> ids = new Object2IntOpenHashMap<>();
    game.forEachState(state -> ids.put(state, ids.size()));
    Function<S, String> formatter = stateFormatter == null ? DotFormatted::toString : stateFormatter;

    writer.append("digraph {\n");
    for (var entry : ids.object2IntEntrySet()) {
      S state = entry.getKey();
      writer.append("S_%d [shape=%s,label=\"%s %s\"]\n".formatted(
          entry.getIntValue(),
          game.owner(state) == Player.EVEN ? "box" : "ellipse",
          formatter.apply(state),
          game.priority(state))
      );
    }
    for (var entry : ids.object2IntEntrySet()) {
      game.successors(entry.getKey()).distinct().forEach(successor ->
          writer.append("S_%d -> S_%d\n".formatted(entry.getIntValue(), ids.getInt(successor))));
    }
    writer.append("}");
  }

  public static <S> void writeConcurrentGame(ConcurrentGame<S> game, PrintStream writer) {
    Object2IntMap<S> ids = new Object2IntOpenHashMap<>();
    game.states().forEach(state -> ids.put(state, ids.size()));
    writer.append("digraph {\n");
    for (var entry : ids.object2IntEntrySet()) {
      writer.append("S_%d [label=\"%s\"]\n".formatted(entry.getIntValue(), DotFormatted.toString(entry.getKey())));
    }
    for (var entry : ids.object2IntEntrySet()) {
      SetMultimap<S, Move> movesBySuccessor = HashMultimap.create();
      game.transitions(entry.getKey()).forEach(transition ->
          movesBySuccessor.put(transition.destination(), transition.move()));
      for (var moveEntry : movesBySuccessor.asMap().entrySet()) {
        writer.append("S_%d -> S_%d [label=\"%s\"]\n".formatted(
            entry.getIntValue(),
            ids.getInt(moveEntry.getKey()),
            moveEntry.getValue().stream().map(DotFormatted::toString).collect(Collectors.joining(", "))));
      }
    }
    writer.append("}");
  }

  public static <S> void writeSolution(GameSolution<S> solution, PrintStream writer) {
    var suspectGame = solution.suspectGame();
    var strategy = solution.strategy();
    var runGraph = solution.runGraph();
    List<String> atomicPropositions = suspectGame.historyGame().concurrentGame().atomicPropositions();

    writer.append("digraph {\n");
    Set<RunGraph.RunState<S>> loopStates = strategy.lasso().states(false).collect(Collectors.toSet());
    Set<RunGraph.RunState<S>> successors = Set.of();
    /*
    Set<RunGraph.RunState<S>> successors = loopStates.stream().map(runGraph::transitions)
        .flatMap(Collection::stream)
        .map(RunGraph.RunTransition::successor)
        .filter(s -> !loopStates.contains(s))
        .collect(Collectors.toSet());
     */

    Object2IntMap<RunGraph.RunState<S>> ids = new Object2IntOpenHashMap<>();
    ids.defaultReturnValue(-1);
    Sets.union(loopStates, successors).forEach(runState -> ids.put(runState, ids.size()));
    Sets.union(loopStates, successors).forEach(runState -> {
      int id = ids.getInt(runState);
      HistoryState<S> historyState = runState.historyState();
      String label = Stream.concat(Stream.concat(Stream.of(DotFormatted.toRecordString(DotFormatted.toString(historyState.state()))),
                  suspectGame.historyGame().concurrentGame().agents().stream()
                      .filter(a -> !historyState.goal(a).equals(BooleanConstant.TRUE))
                      .map(a -> "%s %s".formatted(
                          DotFormatted.toRecordString(a.name()),
                          DotFormatted.toRecordString(LabelledFormula.of(historyState.goal(a), atomicPropositions).toString())))),
              Stream.of(DotFormatted.toRecordString(DotFormatted.toString(runState.automatonState(), runGraph.automatonPropositions()))))
          .collect(Collectors.joining("|", "{", "}"));
      writer.append("S_%d [shape=record,label=\"%s\"]\n".formatted(id, label));
    });

    strategy.lasso().states(false).forEach(runState -> {
      int id = ids.getInt(runState);
      Move move = strategy.moves().get(runState);
      var historySuccessor = suspectGame.historyGame().transition(runState.historyState(), move).orElseThrow().destination();
      var runSuccessor = runGraph.successors(runState).stream().filter(s -> s.historyState().equals(historySuccessor)).findAny()
          .orElseThrow();

      writer.append("S_%d -> S_%d [label=\"%s\"];\n".formatted(id, ids.getInt(runSuccessor), DotFormatted.toString(move)));

      /*
      runGraph.transitions(runState).forEach(transition ->
          writer.append("S_%d -> S_%d [color=%s,label=\"%s\"];\n".formatted(id, ids.getInt(transition.successor()),
              eveSuccessors.contains(transition.successor().historyState()) ? "green" : "gray",
              DotFormatted.toString(move))));
       */
    });

    writer.append("}");
  }
}
