package com.cges.parity;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class OinkGameSolver {
  private record Indexed<S>(S object, int index) {}

  private static final String OINK_EXECUTABLE_NAME = "oink";
  private static final List<String> OINK_EXECUTION;

  static {
    if (System.getProperty("os.name").contains("Windows")) {
      // Output to "-" requires a patched version of oink
      List<String> execution;
      //noinspection ErrorNotRethrown
      try {
        assert false;
        execution = List.of("wsl", OINK_EXECUTABLE_NAME, "-o", "-");
      } catch (AssertionError ignored) {
        // Let oink verify solution, too
        execution = List.of("wsl", OINK_EXECUTABLE_NAME, "-o", "-", "--verify");
      }
      OINK_EXECUTION = execution;
    } else {
      OINK_EXECUTION = List.of(OINK_EXECUTABLE_NAME, "-o", "/dev/stdout");
    }
  }

  private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
      .setThreadFactory(Executors.defaultThreadFactory())
      .setDaemon(true).build());

  public OinkGameSolver() {}

  public <S> Solution<S> solve(ParityGame<S> game) {
    Object2IntMap<S> oinkNumbering = new Object2IntOpenHashMap<>();
    oinkNumbering.defaultReturnValue(-1);
    oinkNumbering.put(game.initialState(), 0);

    List<S> reverseMapping = new ArrayList<>();
    reverseMapping.add(game.initialState());
    Queue<Indexed<S>> queue = new ArrayDeque<>(List.of(new Indexed<>(game.initialState(), 0)));
    IntSet[] successorIdsBuild = new IntSet[128];

    while (!queue.isEmpty()) {
      Indexed<S> state = queue.poll();
      assert state.index() == oinkNumbering.getInt(state.object());

      IntSet stateSuccessorIds = new IntOpenHashSet();
      game.successors(state.object()).distinct().forEach(successor -> {
        int newId = oinkNumbering.size();
        int existingId = oinkNumbering.putIfAbsent(successor, newId);
        if (existingId == -1) {
          reverseMapping.add(successor);
          queue.add(new Indexed<>(successor, newId));
          stateSuccessorIds.add(newId);
        } else {
          stateSuccessorIds.add(existingId);
        }
      });
      if (successorIdsBuild.length <= state.index()) {
        successorIdsBuild = Arrays.copyOf(successorIdsBuild, Math.max(state.index() + 1, successorIdsBuild.length * 2));
      }
      successorIdsBuild[state.index()] = stateSuccessorIds;
    }
    assert oinkNumbering.object2IntEntrySet().stream().allMatch(entry -> entry.getKey().equals(reverseMapping.get(entry.getIntValue())));

    IntSet[] successorIds = successorIdsBuild;
    assert IntStream.range(0, oinkNumbering.size()).allMatch(i ->
            game.successors(reverseMapping.get(i)).map(oinkNumbering::getInt).collect(Collectors.toSet()).equals(successorIds[i]));

    ProcessBuilder oinkProcessBuilder = new ProcessBuilder(OINK_EXECUTION);
    oinkProcessBuilder.redirectErrorStream(true);
    Process oinkProcess;
    try {
      oinkProcess = oinkProcessBuilder.start();
    } catch (IOException e) {
      throw new OinkExecutionException("Oink process could not be started", e);
    }

    Set<S> oddWinning = new HashSet<>();
    Map<S, S> strategy = new HashMap<>();
    List<String> oinkComments = new ArrayList<>();
    var readingFuture = executor.<Void>submit(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(oinkProcess.getInputStream()))) {
        Iterator<String[]> iterator = reader.lines()
            .filter(line -> {
              if (line.startsWith("[")) {
                oinkComments.add(line);
                return false;
              }
              return true;
            })
            .skip(1)
            .peek(line -> checkState(line.charAt(line.length() - 1) == ';', "Invalid line %s", line))
            .map(line -> line.substring(0, line.length() - 1).split(" "))
            .iterator();
        if (!iterator.hasNext()) {
          throw new OinkExecutionException("Did not read any solution");
        }
        while (iterator.hasNext()) {
          String[] elements = iterator.next();
          int winner = Integer.parseInt(elements[1]);
          if (winner == 1) {
            int index = Integer.parseInt(elements[0]);
            S state = requireNonNull(reverseMapping.get(index));
            oddWinning.add(state);
            if (elements.length == 3) {
              S previous = strategy.put(state, requireNonNull(reverseMapping.get(Integer.parseInt(elements[2]))));
              assert previous == null;
            }
          }
        }
      }
      return null;
    });

    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(oinkProcess.getOutputStream()))) {
      writer.append("parity ").append(String.valueOf(oinkNumbering.size())).append(";");
      writer.newLine();
      ListIterator<S> iterator = reverseMapping.listIterator();
      while (iterator.hasNext()) {
        int index = iterator.nextIndex();
        var state = iterator.next();
        writer.append(String.valueOf(index)).append(' ')
            .append(String.valueOf(game.priority(state))).append(' ')
            .append(String.valueOf(game.owner(state).id()));
        var successorIterator = successorIds[index].intIterator();
        if (successorIterator.hasNext()) {
          writer.append(' ').append(String.valueOf(successorIterator.nextInt()));
          while (successorIterator.hasNext()) {
            writer.append(',').append(String.valueOf(successorIterator.nextInt()));
          }
        }
        writer.append(';');
        writer.newLine();
      }
    } catch (IOException e) {
      throw new OinkExecutionException("Failed to write to oink, output: "
          + oinkComments.stream().collect(Collectors.joining("\n", "\n", "")), e);
    }

    try {
      Uninterruptibles.getUninterruptibly(readingFuture);
    } catch (ExecutionException e) {
      throw new OinkExecutionException("Failed to read from oink, output: "
          + oinkComments.stream().collect(Collectors.joining("\n", "\n", "")), e);
    }

    return new Solution<>(oddWinning, strategy);
  }
}