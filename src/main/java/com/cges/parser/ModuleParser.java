package com.cges.parser;

import static com.cges.parser.ParseUtil.stream;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.cges.model.Action;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.google.common.collect.Sets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.tum.in.naturals.Indices;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import owl.collections.ValuationSet;
import owl.factories.ValuationSetFactory;
import owl.factories.jbdd.JBddSupplier;
import owl.ltl.Formula;
import owl.ltl.parser.LtlParser;

public final class ModuleParser {
  private ModuleParser() {}

  public static ConcurrentGame<ModuleState<State>> parse(JsonObject json) {
    String gameName = requireNonNull(json.getAsJsonPrimitive("name"), "Missing name").getAsString();
    List<String> propositions = stream(requireNonNull(json.getAsJsonArray("ap"), "Missing atomic propositions"))
        .map(JsonElement::getAsString).toList();
    Map<String, Integer> propositionIndices = new HashMap<>();
    Indices.forEachIndexed(propositions, (index, proposition) -> propositionIndices.put(proposition, index));
    ValuationSetFactory factory = JBddSupplier.async().getValuationSetFactory(propositions);
    FormulaParser visitor = new FormulaParser(factory);

    List<Module<State>> modules = new ArrayList<>();
    for (var agentEntry : requireNonNull(json.getAsJsonObject("modules"), "Missing modules definition").entrySet()) {
      String moduleName = agentEntry.getKey();
      JsonObject agentData = agentEntry.getValue().getAsJsonObject();

      Formula goal = LtlParser.parse(requireNonNull(agentData.getAsJsonPrimitive("goal"),
          () -> "Missing goal for module %s".formatted(moduleName)).getAsString(), propositions).formula();
      Agent.Payoff payoff = ParseUtil.parsePayoff(requireNonNull(agentData.getAsJsonPrimitive("payoff"),
          () -> "Missing payoff for module %s".formatted(moduleName)));
      Set<String> moduleLabels = stream(requireNonNull(agentData.getAsJsonArray("labels"),
          () -> "Missing labels for module %s".formatted(moduleName))).map(JsonElement::getAsString).collect(Collectors.toSet());
      checkArgument(propositionIndices.keySet().containsAll(moduleLabels),
          "Module %s contains undefined labels %s", moduleName, Sets.difference(moduleLabels, propositionIndices.keySet()));
      Set<Action> actions = stream(requireNonNull(agentData.getAsJsonArray("actions"),
          () -> "Missing actions for module %s".formatted(moduleName)))
          .map(JsonElement::getAsString)
          .map(Action::new)
          .collect(Collectors.toUnmodifiableSet());
      Agent agent = new Agent(moduleName, goal, payoff, actions);

      State initialState = new State(agentData.getAsJsonPrimitive("initial").getAsString());
      Map<State, BitSet> stateLabels = new HashMap<>();

      Map<State, Map<ModuleTransition<State>, ValuationSet>> transitions = new HashMap<>();
      for (Map.Entry<String, JsonElement> stateEntry : agentData.getAsJsonObject("states").entrySet()) {
        String stateName = stateEntry.getKey();
        JsonObject stateData = stateEntry.getValue().getAsJsonObject();

        Set<String> labels = stream(stateData.getAsJsonArray("labels"))
            .map(JsonElement::getAsString)
            .collect(Collectors.toSet());
        checkArgument(moduleLabels.containsAll(labels), "State %s of agent %s labeled by %s",
            stateName, moduleName, Sets.difference(labels, moduleLabels));
        BitSet labelSet = new BitSet();
        labels.stream().map(propositionIndices::get).forEach(labelSet::set);
        State state = new State(stateName);
        stateLabels.put(state, labelSet);

        Map<ModuleTransition<State>, ValuationSet> stateTransitions = new HashMap<>();
        transitions.put(state, stateTransitions);
        for (JsonElement transitionData : stateData.getAsJsonArray("transitions")) {
          JsonObject transition = transitionData.getAsJsonObject();
          State target = new State(transition.getAsJsonPrimitive("to").getAsString());
          ValuationSet valuation = ParseUtil.parse(transition.getAsJsonPrimitive("guard").getAsString(), visitor);
          String actionName = transition.getAsJsonPrimitive("action").getAsString();
          (actionName.equals("*") ? agent.actions() : List.of(agent.action(actionName))).forEach(action ->
              stateTransitions.merge(new ModuleTransition<>(action, target), valuation, ValuationSet::union)
          );
        }

        checkArgument(stateTransitions.values().stream().reduce(ValuationSet::union).orElse(factory.empty()).isUniverse(),
            "Incomplete transitions in state %s of module %s", stateName, moduleName);

        Map<Action, ValuationSet> actionTransitions = new HashMap<>();
        for (var entry : stateTransitions.entrySet()) {
          actionTransitions.merge(entry.getKey().action(), entry.getValue(), (oldVs, newVs) -> {
            checkArgument(!oldVs.intersects(newVs), "Transitions in state %s of module %s under action %s overlap",
                stateName, moduleName, entry.getKey());
            return oldVs.union(newVs);
          });
        }
      }

      for (var transitionEntry : transitions.entrySet()) {
        for (var transition : transitionEntry.getValue().keySet()) {
            checkArgument(stateLabels.containsKey(transition.destination()),
                "Transition in state %s of module %s under action %s leads to undefined state %s",
                transitionEntry.getKey().name(), moduleName, transition.action().name(), transition.destination().name());
        }
      }
      modules.add(new Module<>(agent, initialState, stateLabels, transitions));
    }
    return new ModuleGame<>(gameName, factory, modules);
  }
}
