package com.cges.output;

import com.cges.graph.HistoryGame;
import com.cges.graph.HistoryGame.HistoryState;
import com.cges.graph.RunGraph;
import com.cges.graph.SuspectGame;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.EquilibriumStrategy;
import com.cges.model.Move;
import com.cges.parity.ParityGame;
import com.cges.parity.Player;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public final class DotWriter {
  private DotWriter() {}

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
        var compliantTransition = game.historyGame().transition(eveState.historyState(), adamState.move()).orElseThrow();
        game.successors(adamState).forEach(eveSuccessor -> {
          if (eveSuccessor.historyState().equals(compliantTransition.destination())) {
            assert compliantTransition.move().equals(adamState.move());
            compliantMoves.put(eveSuccessor, compliantTransition.move());
          } else {
            deviatingMovesBySuccessor.put(eveSuccessor, adamState.move());
          }
        });
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

  public static <S> void writeSolution(SuspectGame<S> suspectGame, RunGraph<S> runGraph, EquilibriumStrategy<S> strategy,
      PrintStream writer) {
    Object2IntMap<RunGraph.RunState<S>> ids = new Object2IntOpenHashMap<>();
    // TODO

    writer.append("digraph {\n");
    Set<RunGraph.RunState<S>> loopStates = strategy.lasso().states(false).collect(Collectors.toSet());
    Set<RunGraph.RunState<S>> successors = loopStates.stream().map(runGraph::transitions)
        .flatMap(Collection::stream)
        .map(RunGraph.RunTransition::successor)
        .filter(s -> !loopStates.contains(s))
        .collect(Collectors.toSet());

    Stream.concat(loopStates.stream(), successors.stream()).forEach(runState -> {
      int id = ids.getInt(runState);
      // TODO Automaton state labels
      writer.append("S_%d [label=\"{%s}\"]\n".formatted(id,
          DotFormatted.toString(runState)));
    });

//    oddStrategy.lasso().states(false).forEach(runState -> {
//      int id = ids.getInt(runState);
//      Move move = oddStrategy.moves().get(runState);
//      SuspectGame.AdamState<S> adamSuccessor = new SuspectGame.AdamState<>(runState, move);
//      Collection<SuspectGame.EveState<S>> eveSuccessors = suspectGame.successors(adamSuccessor);
//
//      runGraph.transitions(runState).forEach(transition ->
//          writer.append("S_%d -> S_%d [color=%s,label=\"%s\"];\n".formatted(id, ids.getInt(transition.eveSuccessor()),
//              eveSuccessors.contains(transition.eveSuccessor().eveState()) ? "green" : "gray",
//              DotFormatted.toString(transition.move()))));
//    });

    writer.append("}");
  }
}
