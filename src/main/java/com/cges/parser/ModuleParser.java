package com.cges.parser;

import static com.cges.parser.ParseUtil.stream;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.cges.model.Action;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
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
import owl.bdd.BddSet;
import owl.bdd.BddSetFactory;
import owl.bdd.jbdd.JBddSupplier;
import owl.ltl.BooleanConstant;
import owl.ltl.Formula;
import owl.ltl.LabelledFormula;
import owl.ltl.parser.LtlParser;

public final class ModuleParser {
  private ModuleParser() {}

  public static ConcurrentGame<ModuleState<State>> parse(JsonObject json) {
    String gameName = requireNonNull(json.getAsJsonPrimitive("name"), "Missing name").getAsString();
    List<String> propositions = stream(requireNonNull(json.getAsJsonArray("ap"), "Missing atomic propositions"))
        .map(JsonElement::getAsString).toList();
    Map<String, Integer> propositionIndices = new HashMap<>();
    Indices.forEachIndexed(propositions, (index, proposition) -> propositionIndices.put(proposition, index));
    BddSetFactory factory = JBddSupplier.JBDD_SUPPLIER_INSTANCE.getBddSetFactory();
    FormulaParser visitor = new FormulaParser(factory, propositions);

    LabelledFormula goal = json.has("goal")
        ? LtlParser.parse(json.getAsJsonPrimitive("goal").getAsString(), propositions)
        : LabelledFormula.of(BooleanConstant.TRUE, propositions);

    List<Module<State>> modules = new ArrayList<>();
    for (var agentEntry : requireNonNull(json.getAsJsonObject("modules"), "Missing modules definition").entrySet()) {
      String moduleName = agentEntry.getKey();
      JsonObject agentData = agentEntry.getValue().getAsJsonObject();

      Formula moduleGoal = LtlParser.parse(requireNonNull(agentData.getAsJsonPrimitive("goal"),
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
      Agent agent = new Agent(moduleName, moduleGoal, payoff, actions);

      State initialState = new State(agentData.getAsJsonPrimitive("initial").getAsString());
      Map<State, BitSet> stateLabels = new HashMap<>();

      Map<State, Map<ModuleTransition<State>, BddSet>> transitions = new HashMap<>();
      for (Map.Entry<String, JsonElement> stateEntry : agentData.getAsJsonObject("states").entrySet()) {
        String stateName = stateEntry.getKey();
        JsonObject stateData = stateEntry.getValue().getAsJsonObject();

        Set<String> labels = stream(stateData.getAsJsonArray("labels"))
            .map(JsonElement::getAsString)
            .collect(Collectors.toSet());
        checkArgument(moduleLabels.containsAll(labels), "State %s of module %s labeled by %s",
            stateName, moduleName, Sets.difference(labels, moduleLabels));
        BitSet labelSet = new BitSet();
        labels.stream().map(propositionIndices::get).forEach(labelSet::set);
        State state = new State(stateName);
        stateLabels.put(state, labelSet);

        Map<ModuleTransition<State>, BddSet> stateTransitions = new HashMap<>();
        transitions.put(state, stateTransitions);
        JsonArray modelTransitions = stateData.getAsJsonArray("transitions");
        for (JsonElement transitionData : modelTransitions) {
          JsonObject transition = transitionData.getAsJsonObject();
          State target = new State(transition.getAsJsonPrimitive("to").getAsString());
          BddSet valuation = transition.has("guard")
              ? ParseUtil.parse(transition.getAsJsonPrimitive("guard").getAsString(), visitor)
              : factory.of(true);
          String actionName = transition.getAsJsonPrimitive("action").getAsString();
          checkArgument(actionName.equals("*") || agent.hasAction(actionName),
              "State %s of module %s has uses unknown action %s", stateName, moduleName, actionName);
          (actionName.equals("*") ? agent.actions() : List.of(agent.action(actionName))).forEach(action ->
              stateTransitions.merge(new ModuleTransition<>(action, target), valuation, BddSet::union)
          );
        }

        checkArgument(stateTransitions.values().stream().reduce(BddSet::union).orElse(factory.of(false)).isUniverse(),
            "Incomplete transitions in state %s of module %s", stateName, moduleName);

        Map<Action, BddSet> actionTransitions = new HashMap<>();
        for (var entry : stateTransitions.entrySet()) {
          actionTransitions.merge(entry.getKey().action(), entry.getValue(), (oldVs, newVs) -> {
            checkArgument(oldVs.intersection(newVs).isEmpty(), "Transitions in state %s of module %s under action %s overlap",
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
    return new ModuleGame<>(gameName, propositions, modules, goal);
  }
}
