package com.cges.output;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import owl.collections.Either;
import owl.ltl.Formula;
import owl.ltl.LabelledFormula;
import owl.ltl.rewriter.SimplifierRepository;
import owl.ltl.visitors.PrintVisitor;

public interface DotFormatted {
    String dotString();

    static String toDotString(Object object) {
        if (object instanceof Optional<?> optional) {
            return optional.map(DotFormatted::toDotString).orElse("/");
        }
        if (object instanceof Either<?, ?> either) {
            return either.map(DotFormatted::toDotString, DotFormatted::toDotString);
        }
        if (object instanceof LabelledFormula formula) {
            return PrintVisitor.toString(SimplifierRepository.SYNTACTIC_FIXPOINT.apply(formula), false);
        }
        if (object instanceof Formula formula) {
            return SimplifierRepository.SYNTACTIC_FIXPOINT.apply(formula).toString();
        }
        return (object instanceof DotFormatted format) ? format.dotString() : object.toString();
    }

    static String toDotString(Object object, List<String> propositions) {
        if (object instanceof Optional<?> optional) {
            return optional.map(o -> toDotString(o, propositions)).orElse("/");
        }
        if (object instanceof Either<?, ?> either) {
            return either.map(o -> toDotString(o, propositions), o -> toDotString(o, propositions));
        }
        if (object instanceof LabelledFormula formula) {
            return PrintVisitor.toString(SimplifierRepository.SYNTACTIC_FIXPOINT.apply(formula), false);
        }
        if (object instanceof Formula formula) {
            return toDotString(LabelledFormula.of(SimplifierRepository.SYNTACTIC_FIXPOINT.apply(formula), propositions),
                            propositions);
        }
        return (object instanceof DotFormatted format) ? format.dotString() : object.toString();
    }

    static String toRecordString(String string) {
        var pattern = Pattern.compile("([|{}])");
        return pattern.matcher(string).replaceAll("\\\\$1");
    }
}
