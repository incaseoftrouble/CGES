package com.cges.parity.si;

import com.cges.parity.Player;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import owl.automaton.acceptance.ParityAcceptance;
import owl.automaton.edge.Edge;

public interface EdgeParityGame<S> {
  S initialState();

  Stream<Edge<S>> edges(S state);

  ParityAcceptance acceptance();

  Set<S> states();

  Set<S> states(Player owner);

  Player owner(S state);

  record Solution<S>(Predicate<S> oddWinning, Strategy<S> oddStrategy) {
    public Player winner(S state) {
      return oddWinning.test(state) ? Player.ODD : Player.EVEN;
    }
  }
}
