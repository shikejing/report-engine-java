package com.github.shikejing.reportenginejava.model;

import java.util.List;

public record ExpressionNode(
        String type,
        String field,
        String parameter,
        Object value,
        String op,
        List<ExpressionNode> args
) {
}
