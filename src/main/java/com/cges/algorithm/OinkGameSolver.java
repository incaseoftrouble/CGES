package com.cges.algorithm;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class OinkGameSolver {
  private static final String OINK_EXECUTABLE_NAME = "oink";
  private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
      .setThreadFactory(Executors.defaultThreadFactory())
      .setDaemon(true).build());

  public OinkGameSolver() {}

  public <S> Solution<S> solve(ParityGame<S> game) {
    Object2IntMap<S> oinkNumbering = new Object2IntLinkedOpenHashMap<>();
    oinkNumbering.defaultReturnValue(-1);
    oinkNumbering.put(game.initialState(), 0);

    Int2ObjectMap<S> reverseMapping = new Int2ObjectOpenHashMap<>();
    reverseMapping.put(0, game.initialState());

    Set<S> reached = new HashSet<>(List.of(game.initialState()));
    ArrayDeque<S> queue = new ArrayDeque<>(reached);

    while (!queue.isEmpty()) {
      S state = queue.poll();
      Collection<S> successors = game.successors(state);
      for (S successor : successors) {
        int id = oinkNumbering.size();
        if (oinkNumbering.putIfAbsent(successor, id) == -1) {
          reverseMapping.put(id, successor);
        }
        if (reached.add(successor)) {
          queue.add(successor);
        }
      }
    }

    ProcessBuilder oinkProcessBuilder = new ProcessBuilder(OINK_EXECUTABLE_NAME, "-o", "/dev/stdout");
    oinkProcessBuilder.redirectErrorStream(false);
    Process oinkProcess;

    try {
      oinkProcess = oinkProcessBuilder.start();
    } catch (IOException e) {
      throw new OinkExecutionException("Oink process could not be started", e);
    }

    var writerFuture = executor.<Void>submit(() -> {
      try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(oinkProcess.getOutputStream()))) {
        writer.append("parity ").append(String.valueOf(oinkNumbering.size())).append(";");
        writer.newLine();
        for (Object2IntMap.Entry<S> entry : oinkNumbering.object2IntEntrySet()) {
          var state = entry.getKey();
          Set<S> successors = game.successors(state);
          assert !successors.isEmpty();
          String successorsString = successors.stream()
              .mapToInt(oinkNumbering::getInt)
              .mapToObj(String::valueOf)
              .collect(Collectors.joining(","));
          writer.append("%d %d %d %s;".formatted(entry.getIntValue(),
              game.priority(state),
              game.isEvenPlayer(state) ? 0 : 1,
              successorsString));
          writer.newLine();
        }
      }
      return null;
    });
    var errorFuture = executor.<Void>submit(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(oinkProcess.getErrorStream()))) {
        String error = reader.lines().collect(Collectors.joining("\n"));
        if (!error.isBlank()) {
          throw new OinkExecutionException("Oink reported an error: " + error);
        }
      }
      return null;
    });

    Set<S> evenWinning = new HashSet<>();
    Set<S> oddWinning = new HashSet<>();
    Map<S, S> strategy = new HashMap<>();
    var readingFuture = executor.<Void>submit(() -> {
      @SuppressWarnings("unchecked")
      Set<S>[] players = new Set[] {evenWinning, oddWinning};
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(oinkProcess.getInputStream()))) {
        Iterator<String[]> iterator = reader.lines().skip(1).filter(line -> !line.contains("["))
            .peek(line -> checkState(line.charAt(line.length() - 1) == ';'))
            .map(line -> line.substring(0, line.length() - 1).split(" "))
            .iterator();
        if (!iterator.hasNext()) {
          throw new OinkExecutionException("Did not read any solution");
        }
        while (iterator.hasNext()) {
          String[] elements = iterator.next();
          int winner = Integer.parseInt(elements[1]);
          S state = requireNonNull(reverseMapping.get(Integer.parseInt(elements[0])));
          players[winner].add(state);
          if (elements.length == 3) {
            S previous = strategy.put(state, requireNonNull(reverseMapping.get(Integer.parseInt(elements[2]))));
            assert previous == null;
          }
        }
      }
      return null;
    });

    try {
      Uninterruptibles.getUninterruptibly(writerFuture);
    } catch (ExecutionException e) {
      readingFuture.cancel(true);
      errorFuture.cancel(true);
      throw new OinkExecutionException("Failed to write to oink", e);
    }
    try {
      Uninterruptibles.getUninterruptibly(errorFuture);
    } catch (ExecutionException e) {
      readingFuture.cancel(true);
      throw new OinkExecutionException("Failed to read from oink's error stream", e);
    }
    try {
      Uninterruptibles.getUninterruptibly(readingFuture);
    } catch (ExecutionException e) {
      throw new OinkExecutionException("Failed to read from oink", e);
    }

    return new Solution<>(evenWinning, oddWinning, strategy);
  }

  public static class OinkExecutionException extends RuntimeException {
    public OinkExecutionException(String message) {
      super(message);
    }

    public OinkExecutionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public record Solution<S>(Set<S> evenWinning, Set<S> oddWinning, Map<S, S> strategy) {}

  public interface ParityGame<S> {
    S initialState();

    Set<S> states();

    Set<S> successors(S state);

    int priority(S state);

    boolean isEvenPlayer(S state);
  }
}