package com.cges.parser;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import com.cges.grammar.PropositionalParser;
import com.cges.grammar.PropositionalParserBaseVisitor;
import java.util.HashMap;
import java.util.ListIterator;
import java.util.Map;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import owl.collections.ValuationSet;
import owl.factories.ValuationSetFactory;

public class FormulaParser extends PropositionalParserBaseVisitor<ValuationSet> {
  private final ValuationSetFactory factory;
  private final Map<String, Integer> index;

  public FormulaParser(ValuationSetFactory factory) {
    this.factory = factory;
    ListIterator<String> iterator = this.factory.atomicPropositions().listIterator();
    Map<String, Integer> indexMap = new HashMap<>(factory.atomicPropositions().size());
    while (iterator.hasNext()) {
      int index = iterator.nextIndex();
      indexMap.put(iterator.next(), index);
    }
    this.index = Map.copyOf(indexMap);
  }


  @Override
  public ValuationSet visitExpression(PropositionalParser.ExpressionContext ctx) {
    checkState(ctx.getChildCount() == 1);
    return visit(ctx.getChild(0));
  }

  @Override
  public ValuationSet visitFormula(PropositionalParser.FormulaContext ctx) {
    checkState(ctx.getChildCount() == 2);
    return visit(ctx.getChild(0));
  }

  @Override
  public ValuationSet visitNested(PropositionalParser.NestedContext ctx) {
    checkState(ctx.getChildCount() == 3);
    return visit(ctx.nested);
  }


  @Override
  public ValuationSet visitBoolean(PropositionalParser.BooleanContext ctx) {
    checkState(ctx.getChildCount() == 1);
    if (ctx.constant.TRUE() != null) {
      return factory.universe();
    }
    if (ctx.constant.FALSE() != null) {
      return factory.empty();
    }
    throw new ParseCancellationException("Unknown constant " + ctx.constant.getText());
  }

  @Override
  public ValuationSet visitVariable(PropositionalParser.VariableContext ctx) {
    String variableName = ctx.variable.getText();
    return factory.of(requireNonNull(index.get(variableName),
        () -> "Undefined variable %s".formatted(variableName)));
  }

  @Override
  public ValuationSet visitUnaryOperation(PropositionalParser.UnaryOperationContext ctx) {
    checkState(ctx.getChildCount() == 2);
    if (ctx.unaryOp().NOT() != null) {
      return visit(ctx.inner).complement();
    }
    throw new ParseCancellationException("Unsupported operator "  + ctx.inner.getText());
  }

  @Override
  public ValuationSet visitBinaryOperation(PropositionalParser.BinaryOperationContext ctx) {
    checkState(ctx.getChildCount() == 3);

    ValuationSet left = visit(ctx.left);
    ValuationSet right = visit(ctx.right);

    if (ctx.binaryOp().IMP() != null) {
      return left.complement().union(right);
    }
    if (ctx.binaryOp().XOR() != null) {
      return left.intersection(right.complement()).union(right.intersection(left.complement()));
    }

    throw new ParseCancellationException("Unsupported operator " + ctx.binaryOp().getText());
  }

  @Override
  public ValuationSet visitAndExpression(PropositionalParser.AndExpressionContext ctx) {
    checkState(!ctx.isEmpty());
    return ctx.children.stream()
        .filter(child -> !(child instanceof TerminalNode))
        .map(this::visit)
        .reduce(ValuationSet::intersection)
        .orElseThrow();
  }

  @Override
  public ValuationSet visitOrExpression(PropositionalParser.OrExpressionContext ctx) {
    checkState(!ctx.isEmpty());
    return ctx.children.stream()
        .filter(child -> !(child instanceof TerminalNode))
        .map(this::visit)
        .reduce(ValuationSet::union)
        .orElseThrow();
  }


  @Override
  public ValuationSet visitErrorNode(ErrorNode node) {
    throw new ParseCancellationException();
  }
}
