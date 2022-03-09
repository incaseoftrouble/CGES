package com.cges.parser;

import com.cges.grammar.PropositionalLexer;
import com.cges.grammar.PropositionalParser;
import com.cges.grammar.PropositionalParserVisitor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ConsoleErrorListener;
import owl.collections.ValuationSet;
import owl.ltl.parser.TokenErrorListener;

public final class ParseUtil {
  private ParseUtil() {}

  static ValuationSet parse(String formula, PropositionalParserVisitor<ValuationSet> visitor) {
    PropositionalLexer lexer = new PropositionalLexer(CharStreams.fromString(formula));
    lexer.removeErrorListener(ConsoleErrorListener.INSTANCE);
    lexer.addErrorListener(new TokenErrorListener());
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    PropositionalParser parser = new PropositionalParser(tokens);
    parser.setErrorHandler(new BailErrorStrategy());
    return visitor.visit(parser.formula());
  }

  static Stream<JsonElement> stream(JsonArray array) {
    return StreamSupport.stream(Spliterators.spliterator(array.iterator(), array.size(),
        Spliterator.IMMUTABLE | Spliterator.SIZED | Spliterator.ORDERED), false);
  }
}
