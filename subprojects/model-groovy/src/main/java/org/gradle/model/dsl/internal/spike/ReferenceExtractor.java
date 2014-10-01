/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.model.dsl.internal.spike;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.gradle.util.CollectionUtils;

import java.util.LinkedList;

public class ReferenceExtractor extends BlockAndExpressionStatementAllowingRestrictiveCodeVisitor {

    private final static String AST_NODE_REWRITE_KEY = ReferenceExtractor.class.getName();

    private boolean referenceEncountered;
    private LinkedList<String> referenceStack = Lists.newLinkedList();
    private ImmutableSet.Builder<String> referencedPaths = ImmutableSet.builder();

    public ReferenceExtractor(SourceUnit sourceUnit) {
        super(sourceUnit, "Expression not allowed");
    }

    @Override
    public void visitVariableExpression(VariableExpression expression) {
        if (expression.getName().equals("$")) {
            referenceEncountered = true;
        }
    }

    private Expression rewrittenOrOriginal(Expression expression) {
        Expression rewritten = expression.getNodeMetaData(AST_NODE_REWRITE_KEY);
        return rewritten != null ? rewritten : expression;
    }

    @Override
    public void visitExpressionStatement(ExpressionStatement statement) {
        super.visitExpressionStatement(statement);
        statement.setExpression(rewrittenOrOriginal(statement.getExpression()));
    }

    @Override
    public void visitConstantExpression(ConstantExpression expression) {
        //allow this kind of expressions
    }

    @Override
    public void visitBinaryExpression(BinaryExpression expression) {
        Expression leftExpression = expression.getLeftExpression();
        leftExpression.visit(this);
        expression.setLeftExpression(rewrittenOrOriginal(leftExpression));

        Expression rightExpression = expression.getRightExpression();
        rightExpression.visit(this);
        expression.setRightExpression(rewrittenOrOriginal(rightExpression));
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        Expression objectExpression = call.getObjectExpression();
        objectExpression.visit(this);
        call.setObjectExpression(rewrittenOrOriginal(objectExpression));
    }

    public void visitPropertyExpression(PropertyExpression expression) {
        boolean topLevel = referenceStack.isEmpty();
        referenceStack.push(expression.getPropertyAsString());
        expression.getObjectExpression().visit(this);
        if (topLevel) {
            if (referenceEncountered) {
                String path = CollectionUtils.join(".", referenceStack);
                referencedPaths.add(path);
                expression.setNodeMetaData(AST_NODE_REWRITE_KEY, rewriteReferenceStatement(path));
                referenceStack.clear();
            }
            referenceEncountered = false;
        }
    }

    private MethodCallExpression rewriteReferenceStatement(String path) {
        Parameter it = new Parameter(ClassHelper.DYNAMIC_TYPE, "it");
        it.setOriginType(ClassHelper.OBJECT_TYPE);
        VariableExpression subject = new VariableExpression(it);
        ArgumentListExpression arguments = new ArgumentListExpression(new ConstantExpression(path));
        return new MethodCallExpression(subject, "getAt", arguments);
    }

    public ImmutableSet<String> getReferencedPaths() {
        return referencedPaths.build();
    }
}