package com.cges.parser;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import com.cges.grammar.PropositionalParser;
import com.cges.grammar.PropositionalParserBaseVisitor;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import owl.bdd.BddSet;
import owl.bdd.BddSetFactory;

public class FormulaParser extends PropositionalParserBaseVisitor<BddSet> {
    private final BddSetFactory factory;
    private final Map<String, Integer> index;

    public FormulaParser(BddSetFactory factory, List<String> propositions) {
        this.factory = factory;
        ListIterator<String> iterator = propositions.listIterator();
        Map<String, Integer> indexMap = new HashMap<>(propositions.size());
        while (iterator.hasNext()) {
            int index = iterator.nextIndex();
            indexMap.put(iterator.next(), index);
        }
        this.index = Map.copyOf(indexMap);
    }

    @Override
    public BddSet visitExpression(PropositionalParser.ExpressionContext ctx) {
        checkState(ctx.getChildCount() == 1);
        return visit(ctx.getChild(0));
    }

    @Override
    public BddSet visitFormula(PropositionalParser.FormulaContext ctx) {
        checkState(ctx.getChildCount() == 2);
        return visit(ctx.getChild(0));
    }

    @Override
    public BddSet visitNested(PropositionalParser.NestedContext ctx) {
        checkState(ctx.getChildCount() == 3);
        return visit(ctx.nested);
    }

    @Override
    public BddSet visitBoolean(PropositionalParser.BooleanContext ctx) {
        checkState(ctx.getChildCount() == 1);
        if (ctx.constant.TRUE() != null) {
            return factory.of(true);
        }
        if (ctx.constant.FALSE() != null) {
            return factory.of(false);
        }
        throw new ParseCancellationException("Unknown constant " + ctx.constant.getText());
    }

    @Override
    public BddSet visitVariable(PropositionalParser.VariableContext ctx) {
        String variableName = ctx.variable.getText();
        return factory.of(
                        requireNonNull(index.get(variableName), () -> "Undefined variable %s".formatted(variableName)));
    }

    @Override
    public BddSet visitUnaryOperation(PropositionalParser.UnaryOperationContext ctx) {
        checkState(ctx.getChildCount() == 2);
        if (ctx.unaryOp().NOT() != null) {
            return visit(ctx.inner).complement();
        }
        throw new ParseCancellationException("Unsupported operator " + ctx.inner.getText());
    }

    @Override
    public BddSet visitBinaryOperation(PropositionalParser.BinaryOperationContext ctx) {
        checkState(ctx.getChildCount() == 3);

        BddSet left = visit(ctx.left);
        BddSet right = visit(ctx.right);

        if (ctx.binaryOp().IMP() != null) {
            return left.complement().union(right);
        }
        if (ctx.binaryOp().XOR() != null) {
            return left.intersection(right.complement()).union(right.intersection(left.complement()));
        }

        throw new ParseCancellationException("Unsupported operator " + ctx.binaryOp().getText());
    }

    @Override
    public BddSet visitAndExpression(PropositionalParser.AndExpressionContext ctx) {
        checkState(!ctx.isEmpty());
        return ctx.children.stream().filter(child -> !(child instanceof TerminalNode)).map(this::visit)
                        .reduce(BddSet::intersection).orElseThrow();
    }

    @Override
    public BddSet visitOrExpression(PropositionalParser.OrExpressionContext ctx) {
        checkState(!ctx.isEmpty());
        return ctx.children.stream().filter(child -> !(child instanceof TerminalNode)).map(this::visit)
                        .reduce(BddSet::union).orElseThrow();
    }

    @Override
    public BddSet visitErrorNode(ErrorNode node) {
        throw new ParseCancellationException();
    }
}
