package com.cges.model;

import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SuspectGame {
  public static SuspectGame create(ConcurrentGame game) {
    ConcurrentGame.State initialState = game.initialState();

    EveState initialEveState = new EveState(initialState, Set.copyOf(game.agents()));
    Queue<EveState> eveQueue = new ArrayDeque<>(List.of(initialEveState));
    Set<EveState> eveStates = new HashSet<>();

    ImmutableSetMultimap.Builder<EveState, AdamState> eveTransitions = ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<AdamState, EveState> adamTransitions = ImmutableSetMultimap.builder();
    Map<AdamState, EveState> compliantSuccessor = new HashMap<>();

    var movesReachingSuccessor = game.states().stream()
        .collect(Collectors.toMap(Function.identity(), gameState -> {
          Collection<ConcurrentGame.Transition> gameSuccessors = game.transitions(gameState);
          ImmutableSetMultimap.Builder<ConcurrentGame.State, ConcurrentGame.Move> movesBySuccessor = ImmutableSetMultimap.builder();
          for (ConcurrentGame.Transition gameSuccessor : gameSuccessors) {
            movesBySuccessor.put(gameSuccessor.destination(), gameSuccessor.move());
          }
          return movesBySuccessor.build();
        }));

    while (!eveQueue.isEmpty()) {
      EveState currentEveState = eveQueue.poll();
      ConcurrentGame.State gameState = currentEveState.gameState();

      var movesBySuccessor = movesReachingSuccessor.get(gameState);
      Collection<AdamState> adamSuccessors = game.transitions(gameState).stream()
          .map(transition -> new AdamState(currentEveState, transition.move())).toList();
      eveTransitions.putAll(currentEveState, adamSuccessors);

      for (AdamState adamSuccessor : adamSuccessors) {
        for (ConcurrentGame.Transition gameTransition : game.transitions(currentEveState.gameState())) {
          Set<ConcurrentGame.Move> availableMoves = movesBySuccessor.get(gameTransition.destination());
          Collection<ConcurrentGame.Agent> currentSuspects = currentEveState.suspects();
          Collection<ConcurrentGame.Agent> successorSuspects;
          boolean complies = availableMoves.contains(adamSuccessor.move());
          if (complies) {
            // Nobody needs to deviate to achieve this move
            successorSuspects = currentSuspects;
          } else {
            successorSuspects = new HashSet<>();
            for (ConcurrentGame.Move move : availableMoves) {
              // Check if there is a single suspect who could deviate to achieve this move (i.e. move to the successor state)
              var iterator = currentSuspects.stream()
                  .filter(a -> !adamSuccessor.move().action(a).equals(move.action(a)))
                  .iterator();
              if (iterator.hasNext()) {
                ConcurrentGame.Agent deviating = iterator.next();
                if (!iterator.hasNext()) {
                  successorSuspects.add(deviating);
                }
              }
            }
          }
          assert currentSuspects.containsAll(successorSuspects);

          if (!successorSuspects.isEmpty()) {
            EveState eveSuccessor = new EveState(gameTransition.destination(), Set.copyOf(successorSuspects));
            adamTransitions.put(adamSuccessor, eveSuccessor);
            if (complies) {
              EveState oldSuccessor = compliantSuccessor.put(adamSuccessor, eveSuccessor);
              assert oldSuccessor == null || eveSuccessor.equals(oldSuccessor); // Complying should yield a unique successor
            }
            if (eveStates.add(eveSuccessor)) {
              eveQueue.add(eveSuccessor);
            }
          }
        }
      }
    }
    return new SuspectGame(game, initialEveState, eveTransitions.build(), adamTransitions.build(), compliantSuccessor);
  }

  private final ConcurrentGame game;
  private final EveState initialState;
  private final SetMultimap<EveState, AdamState> eveTransitions;
  private final SetMultimap<AdamState, EveState> adamTransitions;
  private final Map<AdamState, EveState> compliantSuccessor;

  private SuspectGame(ConcurrentGame game, EveState initialState,
      SetMultimap<EveState, AdamState> eveTransitions,
      SetMultimap<AdamState, EveState> adamTransitions,
      Map<AdamState, EveState> compliantSuccessor) {
    assert eveTransitions.entries().stream().allMatch(e -> e.getKey().equals(e.getValue().eveState()));
    assert adamTransitions.entries().stream().allMatch(e -> e.getKey().eveState().suspects().containsAll(e.getValue().suspects()));
    this.game = game;
    this.initialState = initialState;
    this.eveTransitions = eveTransitions;
    this.adamTransitions = adamTransitions;
    this.compliantSuccessor = compliantSuccessor;
  }

  public Set<EveState> eveStates() {
    return eveTransitions.keySet();
  }

  public Set<AdamState> adamStates() {
    return adamTransitions.keySet();
  }

  public EveState initialState() {
    return initialState;
  }

  public Collection<AdamState> successors(EveState eveState) {
    return eveTransitions.get(eveState);
  }

  public Collection<EveState> successors(AdamState adamState) {
    return adamTransitions.get(adamState);
  }

  public Optional<EveState> compliantSuccessor(AdamState adamState) {
    return Optional.ofNullable(compliantSuccessor.get(adamState));
  }

  public ConcurrentGame game() {
    return game;
  }

  public record AdamState(EveState eveState, ConcurrentGame.Move move) {
    @Override
    public String toString() {
      return "AS[%s]@[%s]".formatted(eveState.gameState(), move);
    }
  }

  public record EveState(ConcurrentGame.State gameState, Set<ConcurrentGame.Agent> suspects) {
    @Override
    public String toString() {
      return "ES[%s]@[%s]".formatted(gameState, suspects.stream()
          .map(ConcurrentGame.Agent::name).collect(Collectors.joining(",")));
    }
  }
}
