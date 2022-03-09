package com.cges.output;

import com.cges.algorithm.OinkGameSolver;
import com.cges.algorithm.RunGraph;
import com.cges.algorithm.SuspectGame;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.EquilibriumStrategy;
import com.cges.model.Move;
import com.cges.model.Transition;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public final class DotWriter {
  private DotWriter() {}

  public static <S> void writeSuspectGame(SuspectGame<S> game, PrintStream writer, @Nullable Predicate<SuspectGame.EveState<S>> winning) {
    Object2IntMap<SuspectGame.EveState<S>> eveIds = new Object2IntOpenHashMap<>();
    for (SuspectGame.EveState<S> eveState : game.eveStates()) {
      eveIds.put(eveState, eveIds.size());
    }
    Object2IntMap<SuspectGame.AdamState<S>> adamIds = new Object2IntOpenHashMap<>();
    for (SuspectGame.AdamState<S> adamState : game.adamStates()) {
      adamIds.put(adamState, adamIds.size());
    }

    writer.append("digraph {\n");
    for (Object2IntMap.Entry<SuspectGame.EveState<S>> entry : eveIds.object2IntEntrySet()) {
      SuspectGame.EveState<S> eveState = entry.getKey();
      writer.append("ES_%d [shape=record,color=%s,fillcolor=white,label=\"{%s|%s}\"]\n".formatted(
          entry.getIntValue(),
          winning == null ? "black" : (winning.test(eveState) ? "green" : "red"),
          DotFormatted.toString(eveState.historyState()),
          eveState.suspects().stream().map(Agent::name).sorted().collect(Collectors.joining(",")))
      );
    }

    for (Object2IntMap.Entry<SuspectGame.AdamState<S>> entry : adamIds.object2IntEntrySet()) {
      SuspectGame.AdamState<S> adamState = entry.getKey();
      writer.append("AS_%d [shape=record,style=filled,fillcolor=black,fontcolor=white,label=\"{%s|%s|%s}\"]\n".formatted(
          entry.getIntValue(),
          DotFormatted.toString(adamState.eveState().historyState()),
          adamState.eveState().suspects().stream().map(Agent::name).sorted().collect(Collectors.joining(",")),
          DotFormatted.toString(adamState.move())));
    }
    for (Object2IntMap.Entry<SuspectGame.EveState<S>> entry : eveIds.object2IntEntrySet()) {
      for (SuspectGame.AdamState<S> successor : game.successors(entry.getKey())) {
        writer.append("ES_%d -> AS_%d [label=\"%s\"]\n".formatted(
            entry.getIntValue(),
            adamIds.getInt(successor),
            DotFormatted.toString(successor.move())));
      }
    }
    for (Object2IntMap.Entry<SuspectGame.AdamState<S>> entry : adamIds.object2IntEntrySet()) {
      for (SuspectGame.EveState<S> successor : game.successors(entry.getKey())) {
        writer.append("AS_%d -> ES_%d [style=dotted]\n".formatted(entry.getIntValue(), eveIds.getInt(successor)));
      }
    }
    writer.append("}");
  }

  public static <S> void writeParityGame(OinkGameSolver.ParityGame<S> game, PrintStream writer,
      @Nullable Function<S, String> stateFormatter) {
    Object2IntMap<S> ids = new Object2IntOpenHashMap<>();
    for (S state : game.states()) {
      ids.put(state, ids.size());
    }
    Function<S, String> formatter = stateFormatter == null ? DotFormatted::toString : stateFormatter;

    writer.append("digraph {\n");
    for (var entry : ids.object2IntEntrySet()) {
      S state = entry.getKey();
      writer.append("S_%d [shape=%s,label=\"%s %s\"]\n".formatted(
          entry.getIntValue(),
          game.isEvenPlayer(state) ? "box" : "ellipse",
          formatter.apply(state),
          game.priority(state))
      );
    }
    for (var entry : ids.object2IntEntrySet()) {
      for (S successor : game.successors(entry.getKey())) {
        writer.append("S_%d -> S_%d\n".formatted(entry.getIntValue(), ids.getInt(successor)));
      }
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
      game.transitions(entry.getKey()).forEach(transition ->
          writer.append("S_%d -> S_%d [label=\"%s\"];\n".formatted(
              entry.getIntValue(), ids.getInt(transition.destination()), DotFormatted.toString(transition.move()))));
    }
    writer.append("}");
  }

  public static <S> void writeSolution(SuspectGame<S> suspectGame, RunGraph<S> runGraph, EquilibriumStrategy<S> strategy,
      PrintStream writer) {
    Object2IntMap<RunGraph.RunState<S>> ids = new Object2IntOpenHashMap<>();
    runGraph.states().forEach(state -> ids.put(state, ids.size()));

    writer.append("digraph {\n");
    Set<RunGraph.RunState<S>> loopStates = strategy.lasso().loopStates(false).collect(Collectors.toSet());
    Set<RunGraph.RunState<S>> successors = loopStates.stream().map(runGraph::transitions)
        .flatMap(Collection::stream)
        .map(Transition::destination)
        .filter(s -> !loopStates.contains(s))
        .collect(Collectors.toSet());

    Stream.concat(loopStates.stream(), successors.stream()).distinct().forEach(runState -> {
      int id = ids.getInt(runState);
      // TODO Automaton state labels
      writer.append("S_%d [label=\"{%s}\",color=%s,fillcolor=white]\n".formatted(id,
          DotFormatted.toString(runState.eveState()),
          runState.accepting() ? "green" : "gray"));
    });

    strategy.lasso().loopStates(false).forEach(runState -> {
      int id = ids.getInt(runState);
      Move move = strategy.moves().get(runState);
      SuspectGame.AdamState<S> adamSuccessor = new SuspectGame.AdamState<>(runState.eveState(), move);
      Collection<SuspectGame.EveState<S>> eveSuccessors = suspectGame.successors(adamSuccessor);

      runGraph.transitions(runState).forEach(transition ->
          writer.append("S_%d -> S_%d [color=%s,label=\"%s\"];\n".formatted(id, ids.getInt(transition.destination()),
              eveSuccessors.contains(transition.destination().eveState()) ? "green" : "gray",
              DotFormatted.toString(transition.move()))));
    });

    writer.append("}");
  }
}
