package com.cges.parser;

import static java.util.Objects.requireNonNull;

import com.cges.model.Action;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Transition;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import owl.ltl.Formula;
import owl.ltl.parser.LtlParser;

public final class ExplicitParser {
  private ExplicitParser() {}

  public static ConcurrentGame<State> parse(JsonObject json) {
    String gameName = requireNonNull(json.getAsJsonPrimitive("name"), "Missing name").getAsString();
    List<String> propositions = stream(requireNonNull(json.getAsJsonArray("ap"), "Missing atomic propositions"))
        .map(JsonElement::getAsString).toList();

    Map<String, Agent> agents = requireNonNull(json.getAsJsonObject("agents"), "Missing agents definition").entrySet().stream()
        .map(entry -> {
          String agentName = entry.getKey();
          JsonObject data = entry.getValue().getAsJsonObject();
          Formula goal = LtlParser.parse(requireNonNull(data.getAsJsonPrimitive("goal"),
              () -> "Missing goal for agent %s".formatted(agentName)).getAsString(), propositions).formula();
          Agent.Payoff payoff = ParseUtil.parsePayoff(requireNonNull(data.getAsJsonPrimitive("payoff"),
              () -> "Missing payoff for agent %s".formatted(agentName)));
          Set<Action> actions = stream(requireNonNull(data.getAsJsonArray("actions"),
              () -> "Missing actions for agent %s".formatted(agentName)))
              .map(JsonElement::getAsString)
              .map(Action::new)
              .collect(Collectors.toUnmodifiableSet());
          return new Agent(agentName, goal, payoff, actions);
        })
        .collect(Collectors.toMap(Agent::name, Function.identity()));

    JsonObject arena = requireNonNull(json.getAsJsonObject("arena"), "Missing arena definition");

    State initialState = new State(requireNonNull(arena.getAsJsonPrimitive("initial"), "Missing initial adamState").getAsString());
    ImmutableSet.Builder<State> states = ImmutableSet.builder();
    ImmutableSetMultimap.Builder<State, String> labels = ImmutableSetMultimap.builder();
    ImmutableSetMultimap.Builder<State, Transition<State>> transitions = ImmutableSetMultimap.builder();
    for (var stateEntry : requireNonNull(arena.getAsJsonObject("states"), "Missing states definition").entrySet()) {
      String stateName = stateEntry.getKey();
      State state = new State(stateName);
      JsonObject stateData = stateEntry.getValue().getAsJsonObject();

      states.add(state);
      labels.putAll(state, stream(requireNonNull(stateData.getAsJsonArray("labels"),
          () -> "Missing labels for state %s".formatted(stateName)))
          .map(JsonElement::getAsString)
          .collect(Collectors.toSet()));
      transitions.putAll(state, stream(requireNonNull(stateData.getAsJsonArray("transitions"),
          () -> "No transitions on state %s".formatted(stateName)))
          .map(JsonElement::getAsJsonObject)
          .flatMap(transitionData -> {
            List<Agent> actionAgents = new ArrayList<>(agents.size());
            List<List<Action>> actions = new ArrayList<>(agents.size());
            for (var entry : requireNonNull(transitionData.getAsJsonObject("actions"),
                () -> "Missing actions for transition on state %s".formatted(stateName)).entrySet()) {
              Agent agent = agents.get(entry.getKey());
              actionAgents.add(agent);
              String action = entry.getValue().getAsString();
              actions.add(action.equals("*") ? List.copyOf(agent.actions()) : List.of(agent.action(action)));
            }
            return Lists.cartesianProduct(actions).stream().map(pureMove -> IntStream.range(0, agents.size())
                    .boxed().collect(Collectors.toMap(actionAgents::get, pureMove::get)))
                .map(ExplicitGame.MapMove::new)
                .map(move -> new Transition<>(move, new State(transitionData.getAsJsonPrimitive("to").getAsString())));
          }).toList());
    }
    return new ExplicitGame<>(gameName, Set.copyOf(agents.values()), propositions,
        initialState, states.build(), transitions.build(), labels.build()::get);
  }

  private static Stream<JsonElement> stream(JsonArray array) {
    if (array.isEmpty()) {
      return Stream.of();
    }
    return StreamSupport.stream(Spliterators.spliterator(array.iterator(), array.size(),
        Spliterator.IMMUTABLE | Spliterator.SIZED | Spliterator.ORDERED), false);
  }
}
