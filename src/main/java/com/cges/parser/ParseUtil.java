package com.cges.parser;

import com.cges.grammar.PropositionalLexer;
import com.cges.grammar.PropositionalParser;
import com.cges.grammar.PropositionalParserVisitor;
import com.cges.model.Agent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ConsoleErrorListener;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import owl.bdd.BddSet;
import owl.ltl.parser.TokenErrorListener;

public final class ParseUtil {
    private ParseUtil() {
    }

    static BddSet parse(String formula, PropositionalParserVisitor<BddSet> visitor) {
        try {
            PropositionalLexer lexer = new PropositionalLexer(CharStreams.fromString(formula));
            lexer.removeErrorListener(ConsoleErrorListener.INSTANCE);
            lexer.addErrorListener(new TokenErrorListener());
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            PropositionalParser parser = new PropositionalParser(tokens);
            parser.setErrorHandler(new BailErrorStrategy());
            return visitor.visit(parser.formula());
        } catch (ParseCancellationException e) {
            throw new IllegalArgumentException("Failed to parse formula " + formula, e);
        }
    }

    static Stream<JsonElement> stream(JsonArray array) {
        return StreamSupport.stream(Spliterators.spliterator(array.iterator(), array.size(),
                        Spliterator.IMMUTABLE | Spliterator.SIZED | Spliterator.ORDERED), false);
    }

    static Agent.Payoff parsePayoff(JsonPrimitive payoffPrimitive) {
        if (payoffPrimitive.isBoolean()) {
            return payoffPrimitive.getAsBoolean() ? Agent.Payoff.WINNING : Agent.Payoff.LOSING;
        }
        String payoffString = payoffPrimitive.getAsString();
        return Agent.Payoff.parse(payoffString);
    }
}
