package com.cges.output;

import com.cges.algorithm.OinkGameSolver;
import com.cges.model.ConcurrentGame;
import com.cges.model.SuspectGame;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.PrintStream;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class DotWriter {
  public static void writeSuspectGame(SuspectGame game, PrintStream writer, @Nullable Predicate<SuspectGame.EveState> winning) {
    Object2IntMap<SuspectGame.EveState> eveIds = new Object2IntOpenHashMap<>();
    for (SuspectGame.EveState eveState : game.eveStates()) {
      eveIds.put(eveState, eveIds.size());
    }
    Object2IntMap<SuspectGame.AdamState> adamIds = new Object2IntOpenHashMap<>();
    for (SuspectGame.AdamState adamState : game.adamStates()) {
      adamIds.put(adamState, adamIds.size());
    }

    writer.append("digraph {\n");
    for (Object2IntMap.Entry<SuspectGame.EveState> entry : eveIds.object2IntEntrySet()) {
      SuspectGame.EveState eveState = entry.getKey();
      writer.append("ES_%d [shape=record,color=%s,fillcolor=white,label=\"{%s|%s}\"]\n".formatted(
          entry.getIntValue(),
          winning == null ? "black" : (winning.test(eveState) ? "green" : "red"),
          eveState.gameState(),
          eveState.suspects().stream().map(ConcurrentGame.Agent::name).sorted().collect(Collectors.joining(",")))
      );
    }
    for (Object2IntMap.Entry<SuspectGame.AdamState> entry : adamIds.object2IntEntrySet()) {
      SuspectGame.AdamState adamState = entry.getKey();
      writer.append("AS_%d [shape=record,style=filled,fillcolor=black,fontcolor=white,label=\"{%s|%s|%s}\"]\n".formatted(
          entry.getIntValue(),
          adamState.eveState().gameState(),
          adamState.eveState().suspects().stream().map(ConcurrentGame.Agent::name).sorted().collect(Collectors.joining(",")),
          adamState.move().actionsOrdered().stream().map(ConcurrentGame.Action::name).collect(Collectors.joining())));
    }
    for (Object2IntMap.Entry<SuspectGame.EveState> entry : eveIds.object2IntEntrySet()) {
      for (SuspectGame.AdamState successor : game.successors(entry.getKey())) {
        writer.append("ES_%d -> AS_%d [label=\"%s\"]\n".formatted(
            entry.getIntValue(),
            adamIds.getInt(successor),
            successor.move().actionsOrdered().stream().map(ConcurrentGame.Action::name).collect(Collectors.joining())));
      }
    }
    for (Object2IntMap.Entry<SuspectGame.AdamState> entry : adamIds.object2IntEntrySet()) {
      for (SuspectGame.EveState successor : game.successors(entry.getKey())) {
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
    Function<S, String> formatter = stateFormatter == null ? Object::toString : stateFormatter;

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
}
