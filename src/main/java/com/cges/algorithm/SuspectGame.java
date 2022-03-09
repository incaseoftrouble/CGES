package com.cges.algorithm;

import com.cges.algorithm.HistoryGame.HistoryState;
import com.cges.model.Agent;
import com.cges.model.Move;
import com.cges.model.Transition;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SuspectGame<S> {
  public static <S> SuspectGame<S> create(HistoryGame<S> game) {
    HistoryState<S> initialState = game.initialState();

    EveState<S> initialEveState = new EveState<>(initialState, Set.copyOf(game.concurrentGame().agents()));
    Queue<EveState<S>> eveQueue = new ArrayDeque<>(List.of(initialEveState));
    Set<EveState<S>> eveStates = new HashSet<>();

    ImmutableSetMultimap.Builder<EveState<S>, AdamState<S>> eveTransitions = ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<AdamState<S>, EveState<S>> adamTransitions = ImmutableSetMultimap.builder();
    Map<AdamState<S>, EveState<S>> compliantSuccessor = new HashMap<>();

    var movesReachingSuccessor = game.concurrentGame().states()
        .collect(Collectors.toMap(Function.identity(), gameState -> game.concurrentGame().transitions(gameState)
            .collect(ImmutableSetMultimap.toImmutableSetMultimap(Transition::destination, Transition::move))));

    while (!eveQueue.isEmpty()) {
      EveState<S> currentEveState = eveQueue.poll();
      HistoryState<S> gameState = currentEveState.historyState();

      var movesBySuccessor = movesReachingSuccessor.get(gameState.state());
      Collection<AdamState<S>> adamSuccessors = game.transitions(gameState)
          .map(transition -> new AdamState<>(currentEveState, transition.move())).toList();
      eveTransitions.putAll(currentEveState, adamSuccessors);

      for (AdamState<S> adamSuccessor : adamSuccessors) {
        Iterator<Transition<HistoryState<S>>> transitionIterator = game.transitions(currentEveState.historyState()).iterator();
        while (transitionIterator.hasNext()) {
          Transition<HistoryState<S>> gameTransition = transitionIterator.next();
          Set<Move> availableMoves = movesBySuccessor.get(gameTransition.destination().state());
          Collection<Agent> currentSuspects = currentEveState.suspects();
          Collection<Agent> successorSuspects;
          boolean complies = availableMoves.contains(adamSuccessor.move());
          if (complies) {
            // Nobody needs to deviate to achieve this move
            successorSuspects = currentSuspects;
          } else {
            successorSuspects = new HashSet<>();
            for (Move move : availableMoves) {
              // Check if there is a single suspect who could deviate to achieve this move (i.e. move to the successor state)
              var iterator = currentSuspects.stream()
                  .filter(a -> !adamSuccessor.move().action(a).equals(move.action(a)))
                  .iterator();
              if (iterator.hasNext()) {
                Agent deviating = iterator.next();
                if (!iterator.hasNext()) {
                  successorSuspects.add(deviating);
                }
              }
            }
          }
          assert currentSuspects.containsAll(successorSuspects);

          if (!successorSuspects.isEmpty()) {
            EveState<S> eveSuccessor = new EveState<>(gameTransition.destination(), Set.copyOf(successorSuspects));
            adamTransitions.put(adamSuccessor, eveSuccessor);
            if (complies) {
              EveState<S> oldSuccessor = compliantSuccessor.put(adamSuccessor, eveSuccessor);
              assert oldSuccessor == null || eveSuccessor.equals(oldSuccessor); // Complying should yield a unique successor
            }
            if (eveStates.add(eveSuccessor)) {
              eveQueue.add(eveSuccessor);
            }
          }
        }
      }
    }
    return new SuspectGame<>(game, initialEveState, eveTransitions.build(), adamTransitions.build(), compliantSuccessor);
  }

  private final HistoryGame<S> game;
  private final EveState<S> initialState;
  private final SetMultimap<EveState<S>, AdamState<S>> eveTransitions;
  private final SetMultimap<AdamState<S>, EveState<S>> adamTransitions;
  private final Map<AdamState<S>, EveState<S>> compliantSuccessor;

  private SuspectGame(HistoryGame<S> game, EveState<S> initialState,
      SetMultimap<EveState<S>, AdamState<S>> eveTransitions,
      SetMultimap<AdamState<S>, EveState<S>> adamTransitions,
      Map<AdamState<S>, EveState<S>> compliantSuccessor) {
    assert eveTransitions.entries().stream().allMatch(e -> e.getKey().equals(e.getValue().eveState()));
    assert adamTransitions.entries().stream().allMatch(e -> e.getKey().eveState().suspects().containsAll(e.getValue().suspects()));
    this.game = game;
    this.initialState = initialState;
    this.eveTransitions = eveTransitions;
    this.adamTransitions = adamTransitions;
    this.compliantSuccessor = compliantSuccessor;
  }

  public Set<EveState<S>> eveStates() {
    return eveTransitions.keySet();
  }

  public Set<AdamState<S>> adamStates() {
    return adamTransitions.keySet();
  }

  public EveState<S> initialState() {
    return initialState;
  }

  public Collection<AdamState<S>> successors(EveState<S> eveState) {
    return eveTransitions.get(eveState);
  }

  public Collection<EveState<S>> successors(AdamState<S> adamState) {
    return adamTransitions.get(adamState);
  }

  public Optional<EveState<S>> compliantSuccessor(AdamState<S> adamState) {
    return Optional.ofNullable(compliantSuccessor.get(adamState));
  }

  public HistoryGame<S> historyGame() {
    return game;
  }

  public record AdamState<S>(EveState<S> eveState, Move move) {
    @Override
    public String toString() {
      return "AS[%s]@[%s]".formatted(eveState.historyState(), move);
    }
  }

  public record EveState<S>(HistoryState<S> historyState, Set<Agent> suspects) {
    public S gameState() {
      return historyState.state();
    }

    @Override
    public String toString() {
      return "ES[%s]suspect{%s}".formatted(historyState, suspects.stream().map(Agent::name).sorted().collect(Collectors.joining(",")));
    }
  }
}
