package com.cges.algorithm;

import com.cges.algorithm.HistoryGame.HistoryState;
import com.cges.model.Agent;
import com.cges.model.Move;
import com.cges.model.Transition;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SuspectGame<S> {
  record AdamTransition<S>(AdamState<S> adamState, EveState<S> eveSuccessor) {}

  public static <S> SuspectGame<S> create(HistoryGame<S> game) {
    HistoryState<S> initialState = game.initialState();

    EveState<S> initialEveState = new EveState<>(initialState, Set.copyOf(game.concurrentGame().agents()));
    Queue<EveState<S>> eveQueue = new ArrayDeque<>(List.of(initialEveState));
    Set<EveState<S>> eveStates = new HashSet<>();

    ImmutableSetMultimap.Builder<EveState<S>, AdamState<S>> eveTransitions = ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<AdamState<S>, EveState<S>> adamTransitions = ImmutableSetMultimap.builder();

    var movesReachingSuccessor = game.concurrentGame().states()
        .collect(Collectors.toMap(Function.identity(), gameState -> game.concurrentGame().transitions(gameState)
            .collect(ImmutableSetMultimap.toImmutableSetMultimap(Transition::destination, Transition::move))));

    while (!eveQueue.isEmpty()) {
      EveState<S> currentEveState = eveQueue.poll();
      HistoryState<S> gameState = currentEveState.historyState();

      var movesBySuccessor = movesReachingSuccessor.get(gameState.state());

      Set<AdamTransition<S>> currentTransitions = new HashSet<>();
      game.transitions(gameState).map(transition -> new AdamState<>(currentEveState, transition.move())).forEach(adamSuccessor -> {
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
              // Check if there is a single suspect who could deviate to achieve this move (i.e. move to the eve successor)
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
            currentTransitions.add(new AdamTransition<>(adamSuccessor, eveSuccessor));
          }
        }
      });

      for (AdamTransition<S> transition : currentTransitions) {
        eveTransitions.put(currentEveState, transition.adamState());
        adamTransitions.put(transition.adamState(), transition.eveSuccessor());
        if (eveStates.add(transition.eveSuccessor())) {
          eveQueue.add(transition.eveSuccessor());
        }
      }
      assert currentTransitions.stream()
          .map(AdamTransition::eveSuccessor)
          .map(EveState::suspects).flatMap(Collection::stream)
          .collect(Collectors.toSet()).equals(currentEveState.suspects()) : "Vanishing suspects";
      assert currentTransitions.stream()
          .map(AdamTransition::eveSuccessor)
          .map(EveState::historyState)
          .collect(Collectors.toSet())
          .containsAll(game.transitions(currentEveState.historyState()).map(Transition::destination).collect(Collectors.toSet()));
    }
    return new SuspectGame<>(game, initialEveState, eveTransitions.build(), adamTransitions.build());
  }

  private final HistoryGame<S> game;
  private final EveState<S> initialState;
  private final SetMultimap<EveState<S>, AdamState<S>> eveTransitions;
  private final SetMultimap<AdamState<S>, EveState<S>> adamTransitions;

  private SuspectGame(HistoryGame<S> game, EveState<S> initialState,
      SetMultimap<EveState<S>, AdamState<S>> eveTransitions,
      SetMultimap<AdamState<S>, EveState<S>> adamTransitions) {
    assert eveTransitions.entries().stream().allMatch(e -> e.getKey().equals(e.getValue().eveState()));
    assert adamTransitions.entries().stream().allMatch(e -> e.getKey().eveState().suspects().containsAll(e.getValue().suspects()));
    this.game = game;
    this.initialState = initialState;
    this.eveTransitions = eveTransitions;
    this.adamTransitions = adamTransitions;
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

  public HistoryGame<S> historyGame() {
    return game;
  }

  public static final class AdamState<S> {
    private final EveState<S> eveState;
    private final Move move;
    private final int hashCode;

    public AdamState(EveState<S> eveState, Move move) {
      this.eveState = eveState;
      this.move = move;
      this.hashCode = eveState.hashCode() ^ move.hashCode();
    }

    @Override
    public String toString() {
      return "AS[%s]@[%s]".formatted(eveState.historyState(), move);
    }

    public EveState<S> eveState() {
      return eveState;
    }

    public Move move() {
      return move;
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || (obj instanceof SuspectGame.AdamState<?> that
          && hashCode == that.hashCode
          && eveState.equals(that.eveState)
          && move.equals(that.move));
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  public static final class EveState<S> {
    private final HistoryState<S> historyState;
    private final Set<Agent> suspects;
    private final int hashCode;

    public EveState(HistoryState<S> historyState, Set<Agent> suspects) {
      this.historyState = historyState;
      this.suspects = Set.copyOf(suspects);
      this.hashCode = historyState.hashCode() ^ suspects.hashCode();
    }

    public S gameState() {
      return historyState.state();
    }

    @Override
    public String toString() {
      return "ES[%s]{%s}".formatted(historyState, suspects.stream().map(Agent::name).sorted().collect(Collectors.joining(",")));
    }

    public HistoryState<S> historyState() {
      return historyState;
    }

    public Set<Agent> suspects() {
      return suspects;
    }

    @Override
    public boolean equals(Object obj) {
      return (this == obj)
          || (obj instanceof EveState<?> that
          && hashCode == that.hashCode
          && historyState.equals(that.historyState)
          && suspects.equals(that.suspects));
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }
}
