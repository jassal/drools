/*
 * Copyright 2005 JBoss Inc
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

package org.drools.modelcompiler.builder.generator;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.drools.compiler.lang.descr.AccumulateDescr;
import org.drools.compiler.lang.descr.AndDescr;
import org.drools.compiler.lang.descr.AnnotationDescr;
import org.drools.compiler.lang.descr.AttributeDescr;
import org.drools.compiler.lang.descr.BaseDescr;
import org.drools.compiler.lang.descr.BehaviorDescr;
import org.drools.compiler.lang.descr.ConditionalBranchDescr;
import org.drools.compiler.lang.descr.ConditionalElementDescr;
import org.drools.compiler.lang.descr.EvalDescr;
import org.drools.compiler.lang.descr.ExistsDescr;
import org.drools.compiler.lang.descr.ForallDescr;
import org.drools.compiler.lang.descr.NamedConsequenceDescr;
import org.drools.compiler.lang.descr.NotDescr;
import org.drools.compiler.lang.descr.OrDescr;
import org.drools.compiler.lang.descr.PackageDescr;
import org.drools.compiler.lang.descr.PatternDescr;
import org.drools.compiler.lang.descr.PatternSourceDescr;
import org.drools.compiler.lang.descr.QueryDescr;
import org.drools.compiler.lang.descr.RuleDescr;
import org.drools.compiler.lang.descr.TypeDeclarationDescr;
import org.drools.core.definitions.InternalKnowledgePackage;
import org.drools.core.rule.Behavior;
import org.drools.core.time.TimeUtils;
import org.drools.core.util.ClassUtils;
import org.drools.core.util.index.IndexUtil.ConstraintType;
import org.drools.drlx.DrlxParser;
import org.drools.javaparser.JavaParser;
import org.drools.javaparser.ast.Modifier;
import org.drools.javaparser.ast.NodeList;
import org.drools.javaparser.ast.body.MethodDeclaration;
import org.drools.javaparser.ast.body.Parameter;
import org.drools.javaparser.ast.drlx.OOPathExpr;
import org.drools.javaparser.ast.drlx.expr.DrlxExpression;
import org.drools.javaparser.ast.drlx.expr.PointFreeExpr;
import org.drools.javaparser.ast.drlx.expr.TemporalLiteralChunkExpr;
import org.drools.javaparser.ast.drlx.expr.TemporalLiteralExpr;
import org.drools.javaparser.ast.expr.AssignExpr;
import org.drools.javaparser.ast.expr.BinaryExpr;
import org.drools.javaparser.ast.expr.BinaryExpr.Operator;
import org.drools.javaparser.ast.expr.ClassExpr;
import org.drools.javaparser.ast.expr.Expression;
import org.drools.javaparser.ast.expr.FieldAccessExpr;
import org.drools.javaparser.ast.expr.LambdaExpr;
import org.drools.javaparser.ast.expr.MethodCallExpr;
import org.drools.javaparser.ast.expr.NameExpr;
import org.drools.javaparser.ast.expr.SimpleName;
import org.drools.javaparser.ast.expr.StringLiteralExpr;
import org.drools.javaparser.ast.expr.UnaryExpr;
import org.drools.javaparser.ast.expr.VariableDeclarationExpr;
import org.drools.javaparser.ast.stmt.BlockStmt;
import org.drools.javaparser.ast.stmt.ExpressionStmt;
import org.drools.javaparser.ast.stmt.ReturnStmt;
import org.drools.javaparser.ast.type.ClassOrInterfaceType;
import org.drools.javaparser.ast.type.Type;
import org.drools.javaparser.ast.type.UnknownType;
import org.drools.model.BitMask;
import org.drools.model.Query;
import org.drools.model.Rule;
import org.drools.model.Variable;
import org.drools.modelcompiler.builder.PackageModel;
import org.kie.soup.project.datamodel.commons.types.TypeResolver;

import static java.util.stream.Collectors.toList;

import static org.drools.javaparser.printer.PrintUtil.toDrlx;
import static org.drools.model.impl.NamesGenerator.generateName;
import static org.drools.modelcompiler.builder.JavaParserCompiler.compileAll;
import static org.drools.modelcompiler.builder.generator.DrlxParseUtil.generateLambdaWithoutParameters;
import static org.drools.modelcompiler.builder.generator.DrlxParseUtil.getClassFromContext;
import static org.drools.modelcompiler.builder.generator.DrlxParseUtil.parseBlock;
import static org.drools.modelcompiler.builder.generator.DrlxParseUtil.toVar;
import static org.drools.modelcompiler.util.StringUtil.toId;

public class ModelGenerator {

    private static final ClassOrInterfaceType RULE_TYPE = JavaParser.parseClassOrInterfaceType( Rule.class.getCanonicalName() );
    private static final ClassOrInterfaceType BITMASK_TYPE = JavaParser.parseClassOrInterfaceType( BitMask.class.getCanonicalName() );

    private static final Expression RULE_ATTRIBUTE_NO_LOOP = JavaParser.parseExpression("Rule.Attribute.NO_LOOP");

    public static final boolean GENERATE_EXPR_ID = true;

    public static final String BUILD_CALL = "build";
    public static final String RULE_CALL = "rule";
    public static final String EXECUTE_CALL = "execute";
    public static final String ON_CALL = "on";
    public static final String QUERY_CALL = "query";
    public static final String EXPR_CALL = "expr";
    public static final String INPUT_CALL = "input";
    public static final String BIND_CALL = "bind";
    public static final String BIND_AS_CALL = "as";
    private static final String ATTRIBUTE_CALL = "attribute";
    private static final String DECLARATION_OF_CALL = "declarationOf";
    private static final String TYPE_CALL = "type";
    private static final String TYPE_META_DATA_CALL = "typeMetaData";

    public static PackageModel generateModel(InternalKnowledgePackage pkg, PackageDescr packageDescr) {
        String pkgName = pkg.getName();
        TypeResolver typeResolver = pkg.getTypeResolver();

        PackageModel packageModel = new PackageModel(pkgName);
        packageModel.addImports(pkg.getTypeResolver().getImports());
        packageModel.addGlobals(pkg.getGlobals());
        new WindowReferenceGenerator(packageModel, pkg).addWindowReferences(packageDescr.getWindowDeclarations());
        packageModel.addAllFunctions(packageDescr.getFunctions().stream().map(FunctionGenerator::toFunction).collect(toList()));

        for (TypeDeclarationDescr typeDescr : packageDescr.getTypeDeclarations()) {
            try {
                processType( packageModel, typeDescr, typeResolver.resolveType( typeDescr.getTypeName() ));
            } catch (ClassNotFoundException e) {
                packageModel.addGeneratedPOJO(POJOGenerator.toClassDeclaration(typeDescr));
            }
        }

        Map<String, Class<?>> classMap = compileAll( pkg.getPackageClassLoader(), pkgName, packageModel.getGeneratedPOJOsSource() );
        for (Map.Entry<String, Class<?>> entry : classMap.entrySet()) {
            typeResolver.registerClass( entry.getKey(), entry.getValue() );
            typeResolver.registerClass( entry.getValue().getSimpleName(), entry.getValue() );
        }

        for (RuleDescr descr : packageDescr.getRules()) {
            if (descr instanceof QueryDescr) {
                processQuery(pkg, packageModel, (QueryDescr) descr);
            } else {
                processRule(pkg, packageModel, descr);
            }
        }

        return packageModel;
    }

    private static void processType(PackageModel packageModel, TypeDeclarationDescr typeDescr, Class<?> type) {
        MethodCallExpr typeMetaDataCall = new MethodCallExpr(null, TYPE_META_DATA_CALL);
        typeMetaDataCall.addArgument( new StringLiteralExpr(type.getPackage().getName()) );
        typeMetaDataCall.addArgument( new StringLiteralExpr(type.getSimpleName()) );

        for (AnnotationDescr ann : typeDescr.getAnnotations()) {
            typeMetaDataCall = new MethodCallExpr(typeMetaDataCall, "addAnnotation");
            typeMetaDataCall.addArgument( new StringLiteralExpr( ann.getName() ) );
            for (Map.Entry<String, Object> entry : ann.getValueMap().entrySet()) {
                MethodCallExpr annotationValueCall = new MethodCallExpr(null, "annotationValue");
                annotationValueCall.addArgument( new StringLiteralExpr(entry.getKey()) );
                annotationValueCall.addArgument( new StringLiteralExpr(entry.getValue().toString()) );
                typeMetaDataCall.addArgument( annotationValueCall );
            }
        }
        packageModel.addTypeMetaDataExpressions(typeMetaDataCall);
    }

    private static void processRule(InternalKnowledgePackage pkg, PackageModel packageModel, RuleDescr ruleDescr) {
        RuleContext context = new RuleContext(pkg, packageModel.getExprIdGenerator(), Optional.of(ruleDescr));

        for(Entry<String, Object> kv : ruleDescr.getNamedConsequences().entrySet()) {
            context.addNamedConsequence(kv.getKey(), kv.getValue().toString());
        }

        visit(context, packageModel, ruleDescr.getLhs());
        MethodDeclaration ruleMethod = new MethodDeclaration(EnumSet.of(Modifier.PRIVATE), RULE_TYPE, "rule_" + toId( ruleDescr.getName() ) );

        VariableDeclarationExpr ruleVar = new VariableDeclarationExpr(RULE_TYPE, RULE_CALL);

        MethodCallExpr ruleCall = new MethodCallExpr(null, RULE_CALL);
        ruleCall.addArgument( new StringLiteralExpr( ruleDescr.getName() ) );

        MethodCallExpr buildCallScope = ruleCall;
        List<Entry<Expression, Expression>> ruleAttributes = ruleAttributes(ruleDescr);
        for (Entry<Expression, Expression> ra : ruleAttributes) {
            MethodCallExpr attributeCall = new MethodCallExpr(buildCallScope, ATTRIBUTE_CALL);
            attributeCall.addArgument(ra.getKey());
            attributeCall.addArgument(ra.getValue());
            buildCallScope = attributeCall;
        }

        MethodCallExpr buildCall = new MethodCallExpr(buildCallScope, BUILD_CALL, NodeList.nodeList(context.expressions));

        BlockStmt ruleConsequence = rewriteConsequence(context, ruleDescr.getConsequence().toString());

        BlockStmt ruleVariablesBlock = createRuleVariables(packageModel, context);
        ruleMethod.setBody(ruleVariablesBlock);

        List<String> usedDeclarationInRHS = extractUsedDeclarations(packageModel, context, ruleConsequence);
        MethodCallExpr onCall = onCall(usedDeclarationInRHS);
        MethodCallExpr executeCall = executeCall(context, ruleVariablesBlock, ruleConsequence, usedDeclarationInRHS, onCall);

        buildCall.addArgument( executeCall );

        ruleVariablesBlock.addStatement(new AssignExpr(ruleVar, buildCall, AssignExpr.Operator.ASSIGN));

        ruleVariablesBlock.addStatement( new ReturnStmt(RULE_CALL) );
        packageModel.putRuleMethod("rule_" + toId( ruleDescr.getName() ), ruleMethod);
    }

    /**
     * Build a list of tuples for the arguments of the model DSL method {@link org.drools.model.impl.RuleBuilder#attribute(org.drools.model.Rule.Attribute, Object)}
     * starting from a drools-compiler {@link RuleDescr}.
     * The tuple represent the Rule Attribute expressed in JavParser form, and the attribute value expressed in JavaParser form.
     */
    private static List<Entry<Expression, Expression>> ruleAttributes(RuleDescr ruleDescr) {
        List<Entry<Expression, Expression>> ruleAttributes = new ArrayList<>();
        for (Entry<String, AttributeDescr> as : ruleDescr.getAttributes().entrySet()) {
            switch (as.getKey()) {
                case "dialect":
                    if (!as.getValue().getValue().equals("java")) {
                        throw new UnsupportedOperationException("Unsupported dialect: " + as.getValue().getValue());
                    }
                    break;
                case "no-loop":
                    ruleAttributes.add(new AbstractMap.SimpleEntry<>(RULE_ATTRIBUTE_NO_LOOP, JavaParser.parseExpression(as.getValue().getValue())));
                    break;
                default:
                    throw new UnsupportedOperationException("Unhandled case for rule attribute: " + as.getKey());
            }
        }
        return ruleAttributes;
    }

    public static BlockStmt rewriteConsequence(RuleContext context, String consequence ) {
        String ruleConsequenceAsBlock = rewriteConsequenceBlock(context, consequence.trim() );
        return parseBlock(ruleConsequenceAsBlock);
    }

    public static List<String> extractUsedDeclarations(PackageModel packageModel, RuleContext context, BlockStmt ruleConsequence) {
        List<String> declUsedInRHS = ruleConsequence.getChildNodesByType(NameExpr.class).stream().map(NameExpr::getNameAsString).collect(toList());
        Set<String> existingDecls = new HashSet<>();
        existingDecls.addAll(context.getDeclarations().stream().map(DeclarationSpec::getBindingId).collect(toList()));
        existingDecls.addAll(packageModel.getGlobals().keySet());
        return existingDecls.stream().filter(declUsedInRHS::contains).collect(toList());
    }

    public static MethodCallExpr executeCall(RuleContext context, BlockStmt ruleVariablesBlock, BlockStmt ruleConsequence, List<String> verifiedDeclUsedInRHS, MethodCallExpr onCall) {
        boolean rhsRewritten = rewriteRHS(context, ruleVariablesBlock, ruleConsequence);
        MethodCallExpr executeCall = new MethodCallExpr(onCall, EXECUTE_CALL);
        LambdaExpr executeLambda = new LambdaExpr();
        executeCall.addArgument(executeLambda);
        executeLambda.setEnclosingParameters(true);
        if (rhsRewritten) {
            executeLambda.addParameter(new Parameter(new UnknownType(), "drools"));
        }
        verifiedDeclUsedInRHS.stream().map(x -> new Parameter(new UnknownType(), x)).forEach(executeLambda::addParameter);
        executeLambda.setBody( ruleConsequence );
        return executeCall;
    }

    public static MethodCallExpr onCall(List<String> usedArguments) {
        MethodCallExpr onCall = null;

        if (!usedArguments.isEmpty()) {
            onCall = new MethodCallExpr(null, ON_CALL);
            usedArguments.stream().map(DrlxParseUtil::toVar).forEach(onCall::addArgument );
        }
        return onCall;
    }

    public static BlockStmt createRuleVariables(PackageModel packageModel, RuleContext context) {
        BlockStmt ruleBlock = new BlockStmt();

        for (DeclarationSpec decl : context.getDeclarations()) {
            if (!packageModel.getGlobals().containsKey(decl.getBindingId())) {
                addVariable(ruleBlock, decl);
            }
        }
        return ruleBlock;
    }

    private static void processQuery(InternalKnowledgePackage pkg, PackageModel packageModel, QueryDescr queryDescr) {
        RuleContext context = new RuleContext(pkg, packageModel.getExprIdGenerator(), Optional.of(queryDescr));
        visit(context, packageModel, queryDescr);
        MethodDeclaration queryMethod = new MethodDeclaration(EnumSet.of(Modifier.PRIVATE), getQueryType(context.queryParameters), "query_" + toId(queryDescr.getName()));

        BlockStmt queryVariables = createRuleVariables(packageModel, context);
        queryMethod.setBody(queryVariables);
        VariableDeclarationExpr queryVar = new VariableDeclarationExpr(getQueryType(context.queryParameters), QUERY_CALL);

        MethodCallExpr queryCall = new MethodCallExpr(null, QUERY_CALL);
        queryCall.addArgument(new StringLiteralExpr(queryDescr.getName()));
        for (QueryParameter qp : context.queryParameters) {
            queryCall.addArgument(new NameExpr(toVar(qp.name)));
        }

        MethodCallExpr viewCall = new MethodCallExpr(queryCall, BUILD_CALL);
        context.expressions.forEach(viewCall::addArgument);

        AssignExpr ruleAssign = new AssignExpr(queryVar, viewCall, AssignExpr.Operator.ASSIGN);
        queryVariables.addStatement(ruleAssign);

        queryVariables.addStatement(new ReturnStmt(QUERY_CALL));
        packageModel.putQueryMethod(queryMethod);
    }

    private static void addVariable(BlockStmt ruleBlock, DeclarationSpec decl) {
        ClassOrInterfaceType varType = JavaParser.parseClassOrInterfaceType(Variable.class.getCanonicalName());
        Type declType = DrlxParseUtil.classToReferenceType(decl.declarationClass );

        varType.setTypeArguments(declType);
        VariableDeclarationExpr var_ = new VariableDeclarationExpr(varType, toVar(decl.getBindingId()), Modifier.FINAL);

        MethodCallExpr declarationOfCall = new MethodCallExpr(null, DECLARATION_OF_CALL);
        MethodCallExpr typeCall = new MethodCallExpr(null, TYPE_CALL);
        typeCall.addArgument( new ClassExpr(declType ));

        declarationOfCall.addArgument(typeCall);
        declarationOfCall.addArgument(new StringLiteralExpr(decl.getBindingId()));

        decl.declarationSource.ifPresent(declarationOfCall::addArgument);

        decl.getEntryPoint().ifPresent( ep -> {
            MethodCallExpr entryPointCall = new MethodCallExpr(null, "entryPoint");
            entryPointCall.addArgument( new StringLiteralExpr(ep ) );
            declarationOfCall.addArgument( entryPointCall );
        } );
        for ( BehaviorDescr behaviorDescr : decl.getBehaviors() ) {
            MethodCallExpr windowCall = new MethodCallExpr(null, "window");
            if ( Behavior.BehaviorType.TIME_WINDOW.matches(behaviorDescr.getSubType() ) ) {
                windowCall.addArgument( "Window.Type.TIME" );
                windowCall.addArgument( "" + TimeUtils.parseTimeString(behaviorDescr.getParameters().get(0 ) ) );
            }
            if ( Behavior.BehaviorType.LENGTH_WINDOW.matches( behaviorDescr.getSubType() ) ) {
                windowCall.addArgument( "Window.Type.LENGTH" );
                windowCall.addArgument( "" + Integer.valueOf( behaviorDescr.getParameters().get( 0 ) ) );
            }
            declarationOfCall.addArgument( windowCall );
        }

        AssignExpr var_assign = new AssignExpr(var_, declarationOfCall, AssignExpr.Operator.ASSIGN);
        ruleBlock.addStatement(var_assign);
    }


    private static ClassOrInterfaceType getQueryType(List<QueryParameter> queryParameters) {
        Class<?> res = Query.getQueryClassByArity(queryParameters.size());
        ClassOrInterfaceType queryType = JavaParser.parseClassOrInterfaceType(res.getCanonicalName());

        Type[] genericType = queryParameters.stream()
                .map(e -> e.type)
                .map(DrlxParseUtil::classToReferenceType)
                .toArray(Type[]::new);

        if (genericType.length > 0) {
            queryType.setTypeArguments(genericType);
        }

        return queryType;
    }

    private static String rewriteConsequenceBlock( RuleContext context, String consequence ) {
        int modifyPos = consequence.indexOf( "modify" );
        if (modifyPos < 0) {
            return consequence;
        }

        int lastCopiedEnd = 0;
        StringBuilder sb = new StringBuilder();
        sb.append( consequence.substring( lastCopiedEnd, modifyPos ) );

        for (; modifyPos >= 0; modifyPos = consequence.indexOf( "modify", modifyPos+6 )) {
            int declStart = consequence.indexOf( '(', modifyPos+6 );
            int declEnd = consequence.indexOf( ')', declStart+1 );
            if (declEnd < 0) {
                continue;
            }
            String decl = consequence.substring( declStart+1, declEnd ).trim();
            if ( !context.getDeclarationById( decl ).isPresent()) {
                continue;
            }
            int blockStart = consequence.indexOf( '{', declEnd+1 );
            int blockEnd = consequence.indexOf( '}', blockStart+1 );
            if (blockEnd < 0) {
                continue;
            }

            if (lastCopiedEnd < modifyPos) {
                sb.append( consequence.substring( lastCopiedEnd, modifyPos ) );
            }
            String block = consequence.substring( blockStart+1, blockEnd ).trim();
            for (String blockStatement : block.split( ";" )) {
                sb.append( decl ).append( "." ).append( blockStatement.trim() ).append( ";\n" );
            }
            sb.append( "update(" ).append( decl ).append( ");\n" );
            lastCopiedEnd = blockEnd+1;
        }

        if (lastCopiedEnd < consequence.length()) {
            sb.append( consequence.substring( lastCopiedEnd ) );
        }

        return sb.toString();
    }

    private static boolean rewriteRHS(RuleContext context, BlockStmt ruleBlock, BlockStmt rhs) {
        List<MethodCallExpr> methodCallExprs = rhs.getChildNodesByType(MethodCallExpr.class);
        List<MethodCallExpr> updateExprs = new ArrayList<>();

        boolean hasWMAs = methodCallExprs.stream()
           .filter( ModelGenerator::isWMAMethod )
           .peek( mce -> {
                if (!mce.getScope().isPresent()) {
                    mce.setScope(new NameExpr("drools"));
                }
                if (mce.getNameAsString().equals("update")) {
                    updateExprs.add(mce);
                }
                if (mce.getNameAsString().equals("retract")) {
                    mce.setName( new SimpleName( "delete" ) );
                }
           })
           .count() > 0;

        for (MethodCallExpr updateExpr : updateExprs) {
            Expression argExpr = updateExpr.getArgument( 0 );
            if (argExpr instanceof NameExpr) {
                String updatedVar = ( (NameExpr) argExpr ).getNameAsString();
                Class<?> updatedClass = context.getDeclarationById( updatedVar ).map(DeclarationSpec::getDeclarationClass).orElseThrow(RuntimeException::new);

                MethodCallExpr bitMaskCreation = new MethodCallExpr( new NameExpr( BitMask.class.getCanonicalName() ), "getPatternMask" );
                bitMaskCreation.addArgument( new ClassExpr( JavaParser.parseClassOrInterfaceType( updatedClass.getCanonicalName() ) ) );

                methodCallExprs.subList( 0, methodCallExprs.indexOf( updateExpr ) ).stream()
                               .filter( mce -> mce.getScope().isPresent() && hasScope( mce, updatedVar ) )
                               .map( mce -> ClassUtils.setter2property( mce.getNameAsString() ) )
                               .filter( Objects::nonNull )
                               .distinct()
                               .forEach( s -> bitMaskCreation.addArgument( new StringLiteralExpr( s ) ) );

                VariableDeclarationExpr bitMaskVar = new VariableDeclarationExpr(BITMASK_TYPE, "mask_" + updatedVar, Modifier.FINAL);
                AssignExpr bitMaskAssign = new AssignExpr(bitMaskVar, bitMaskCreation, AssignExpr.Operator.ASSIGN);
                ruleBlock.addStatement(bitMaskAssign);

                updateExpr.addArgument( "mask_" + updatedVar );
            }
        }

        return hasWMAs;
    }

    private static boolean isWMAMethod( MethodCallExpr mce ) {
        return isDroolsScopeInWMA( mce ) && (
                mce.getNameAsString().equals("insert") ||
                mce.getNameAsString().equals("delete") ||
                mce.getNameAsString().equals("retract") ||
                mce.getNameAsString().equals("update") );
    }

    private static boolean isDroolsScopeInWMA( MethodCallExpr mce ) {
        return !mce.getScope().isPresent() || hasScope( mce, "drools" );
    }

    private static boolean hasScope( MethodCallExpr mce, String scope ) {
        return mce.getScope().get() instanceof NameExpr &&
               ( (NameExpr) mce.getScope().get() ).getNameAsString().equals( scope );
    }

    private static void visit(RuleContext context, PackageModel packageModel, BaseDescr descr) {
        if ( descr instanceof AndDescr) {
            visit(context, packageModel, ( (AndDescr) descr ));
        } else if ( descr instanceof OrDescr) {
            visit( context, packageModel, ( (OrDescr) descr ), "or");
        } else if ( descr instanceof PatternDescr && ((PatternDescr)descr).getSource() instanceof AccumulateDescr) {
            new AccumulateVisitor(context, packageModel).visit(( (AccumulateDescr)((PatternDescr) descr).getSource() ));
        } else if ( descr instanceof PatternDescr ) {
            visit( context, packageModel, ( (PatternDescr) descr ));
        } else if ( descr instanceof EvalDescr ) {
            visit( context, packageModel, ( (EvalDescr) descr ));
        } else if ( descr instanceof NotDescr) {
            visit( context, packageModel, ( (NotDescr) descr ), "not");
        } else if ( descr instanceof ExistsDescr) {
            visit( context, packageModel, ( (ExistsDescr) descr ), "exists");
        } else if ( descr instanceof ForallDescr) {
            visit( context, packageModel, ( (ForallDescr) descr ), "forall");
        } else if ( descr instanceof QueryDescr) {
            visit( context, packageModel, ( (QueryDescr) descr ));
        } else if ( descr instanceof NamedConsequenceDescr) {
           new NamedConsequenceVisitor(context, packageModel).visit(((NamedConsequenceDescr) descr ));
        } else if ( descr instanceof ConditionalBranchDescr) {
            new NamedConsequenceVisitor(context, packageModel).visit(((ConditionalBranchDescr) descr ));
        } else {
            throw new UnsupportedOperationException("TODO"); // TODO
        }
    }


    private static void visit(RuleContext context, PackageModel packageModel, QueryDescr descr) {

        for (int i = 0; i < descr.getParameters().length; i++) {
            final String argument = descr.getParameters()[i];
            final String type = descr.getParameterTypes()[i];
            context.addDeclaration(new DeclarationSpec(argument, getClassFromContext(context.getPkg(), type)));
            QueryParameter queryParameter = new QueryParameter(argument, getClassFromContext(context.getPkg(),type));
            context.queryParameters.add(queryParameter);
            packageModel.putQueryVariable("query_" + descr.getName(), queryParameter);
        }

        visit(context, packageModel, descr.getLhs());
    }

    private static void visit( RuleContext context, PackageModel packageModel, ConditionalElementDescr descr, String methodName ) {
        final MethodCallExpr ceDSL = new MethodCallExpr(null, methodName);
        context.addExpression(ceDSL);
        context.pushExprPointer( ceDSL::addArgument );
        for (BaseDescr subDescr : descr.getDescrs()) {
            visit(context, packageModel, subDescr );
        }
        context.popExprPointer();
    }

    private static void visit( RuleContext context, PackageModel packageModel, EvalDescr descr ) {
        final String expression = descr.getContent().toString();
        final String bindingId = DrlxParseUtil.findBindingIdFromDotExpression(expression);

        Class<?> patternType = context.getDeclarationById(bindingId)
                .map(DeclarationSpec::getDeclarationClass)
                .orElseThrow(RuntimeException::new);
        processExpression( context, drlxParse(context, packageModel, patternType, bindingId, expression) );
    }

    private static void visit(RuleContext context, PackageModel packageModel, AndDescr descr) {
        // if it's the first (implied) `and` wrapping the first level of patterns, skip adding it to the DSL.
        if ( context.getExprPointerLevel() != 1 ) {
            final MethodCallExpr andDSL = new MethodCallExpr(null, "and");
            context.addExpression(andDSL);
            context.pushExprPointer( andDSL::addArgument );
        }
        for (BaseDescr subDescr : descr.getDescrs()) {
            context.parentDesc = descr;
            visit( context, packageModel, subDescr );
        }
        if ( context.getExprPointerLevel() != 1 ) {
            context.popExprPointer();
        }
    }

    public static void visit(RuleContext context, PackageModel packageModel, PatternDescr pattern ) {
        String className = pattern.getObjectType();
        List<? extends BaseDescr> constraintDescrs = pattern.getConstraint().getDescrs();

        // Expression is a query, get bindings from query parameter type
        if ( bindQuery( context, packageModel, className, constraintDescrs ) ) {
            return;
        }

        Class<?> patternType = getClassFromContext(context.getPkg(),className);

        if (pattern.getIdentifier() == null) {
            pattern.setIdentifier( generateName("pattern_" + patternType.getSimpleName()) );
        }

        Optional<PatternSourceDescr> source = Optional.ofNullable(pattern.getSource());
        Optional<Expression> declarationSourceFrom = source.flatMap(new FromVisitor(context, packageModel)::visit);
        Optional<Expression> declarationSourceWindow = source.flatMap(new WindowReferenceGenerator(packageModel, context.getPkg())::visit);
        Optional<Expression> declarationSource = declarationSourceFrom.isPresent() ? declarationSourceFrom : declarationSourceWindow;
        context.addDeclaration(new DeclarationSpec(pattern.getIdentifier(), patternType, Optional.of(pattern), declarationSource));

        if (constraintDescrs.isEmpty() && pattern.getSource() == null) {
            MethodCallExpr dslExpr = new MethodCallExpr(null, INPUT_CALL);
            dslExpr.addArgument(new NameExpr(toVar(pattern.getIdentifier())));
            context.addExpression( dslExpr );
        } else {
            for (BaseDescr constraint : constraintDescrs) {
                String expression = constraint.toString();
                String patternIdentifier = pattern.getIdentifier();
                DrlxParseResult drlxParseResult = drlxParse(context, packageModel, patternType, patternIdentifier, expression);

                if(drlxParseResult.expr instanceof OOPathExpr) {

                    // If the  outer pattern does not have a binding we generate it
                    if(patternIdentifier == null) {
                        patternIdentifier = context.getExprId(patternType, expression);
                        context.addDeclaration(new DeclarationSpec(patternIdentifier, patternType, Optional.of(pattern), Optional.empty()));
                    }

                    new OOPathExprVisitor(context, packageModel).visit(patternType, patternIdentifier, (OOPathExpr)drlxParseResult.expr);
                } else {
                    // need to augment the reactOn inside drlxParseResult with the look-ahead properties.
                    Collection<String> lookAheadFieldsOfIdentifier = context.getRuleDescr()
                        .map(ruleDescr -> ruleDescr.lookAheadFieldsOfIdentifier(pattern))
                            .orElseGet(Collections::emptyList);
                    drlxParseResult.reactOnProperties.addAll(lookAheadFieldsOfIdentifier);
                    drlxParseResult.watchedProperties = getPatternListenedProperties(pattern);
                    processExpression( context, drlxParseResult );
                }
            }
        }
    }

    private static String[] getPatternListenedProperties(PatternDescr pattern) {
        AnnotationDescr watchAnn = pattern != null ? pattern.getAnnotation( "watch" ) : null;
        return watchAnn == null ? new String[0] : watchAnn.getValue().toString().split(",");
    }

    private static boolean bindQuery( RuleContext context, PackageModel packageModel, String className, List<? extends BaseDescr> descriptors ) {
        String queryName = "query_" + className;
        MethodDeclaration queryMethod = packageModel.getQueryMethod(queryName);
        if (queryMethod != null) {
            NameExpr queryCall = new NameExpr(queryMethod.getNameAsString());
            MethodCallExpr callCall = new MethodCallExpr(queryCall, "call");

            for (int i = 0; i < descriptors.size(); i++) {
                String itemText = descriptors.get(i).getText();
                if(isLiteral(itemText)) {
                    MethodCallExpr valueOfMethod = new MethodCallExpr(null, "valueOf");
                    valueOfMethod.addArgument(new NameExpr(itemText));
                    callCall.addArgument(valueOfMethod);
                } else {
                    QueryParameter qp = packageModel.queryVariables(queryName).get(i);
                    context.addDeclaration(new DeclarationSpec(itemText, qp.type));
                    callCall.addArgument(new NameExpr(toVar(itemText)));
                }
            }

            context.addExpression(callCall);
            return true;
        }
        return false;
    }

    private static void processExpression( RuleContext context, DrlxParseResult drlxParseResult ) {
        if (drlxParseResult.expr != null) {
            Expression dslExpr = buildExpressionWithIndexing( drlxParseResult );
            context.addExpression( dslExpr );
        }
        if (drlxParseResult.exprBinding != null) {
            Expression dslExpr = buildBinding( drlxParseResult );
            context.addExpression( dslExpr );
        }
    }

    public static boolean isLiteral(String value) {
        return value != null && value.length() > 0 &&
                ( Character.isDigit(value.charAt(0)) || value.charAt(0) == '"' || "true".equals(value) || "false".equals(value) || "null".equals(value) );
    }

    public static DrlxParseResult drlxParse(RuleContext context, PackageModel packageModel, Class<?> patternType, String bindingId, String expression) {
        if ( expression.startsWith( bindingId + "." ) ) {
            expression = expression.substring( bindingId.length()+1 );
        }

        DrlxExpression drlx = DrlxParser.parseExpression( expression );
        DrlxParseResult result = getDrlxParseResult( context, packageModel, patternType, bindingId, expression, drlx );
        if (drlx.getBind() != null) {
            String bindId = drlx.getBind().asString();
            context.addDeclaration( new DeclarationSpec( bindId, result.exprType ) );
            result.setExprBinding( bindId );
        }

        return result;
    }

    private static DrlxParseResult getDrlxParseResult( RuleContext context, PackageModel packageModel, Class<?> patternType, String bindingId, String expression, DrlxExpression drlx ) {
        Expression drlxExpr = drlx.getExpr();

        String exprId;
        if ( GENERATE_EXPR_ID ) {
            exprId = context.getExprId( patternType, expression );
        }

        if ( drlxExpr instanceof BinaryExpr ) {
            BinaryExpr binaryExpr = (BinaryExpr) drlxExpr;
            Operator operator = binaryExpr.getOperator();

            ConstraintType decodeConstraintType = DrlxParseUtil.toConstraintType( operator );
            Set<String> usedDeclarations = new HashSet<>();
            Set<String> reactOnProperties = new HashSet<>();
            TypedExpression left = DrlxParseUtil.toTypedExpression( context, packageModel, patternType, binaryExpr.getLeft(), usedDeclarations, reactOnProperties );
            TypedExpression right = DrlxParseUtil.toTypedExpression( context, packageModel, patternType, binaryExpr.getRight(), usedDeclarations, reactOnProperties );

            Expression combo;
            if ( left.isPrimitive() ) {
                combo = new BinaryExpr( left.getExpression(), right.getExpression(), operator );
            } else {
                switch ( operator ) {
                    case EQUALS:
                        MethodCallExpr methodCallExpr = new MethodCallExpr( left.getExpression(), "equals" );
                        methodCallExpr.addArgument( right.getExpression() ); // don't create NodeList with static method because missing "parent for child" would null and NPE
                        combo = methodCallExpr;
                        break;
                    case NOT_EQUALS:
                        MethodCallExpr methodCallExpr2 = new MethodCallExpr( left.getExpression(), "equals" );
                        methodCallExpr2.addArgument( right.getExpression() );
                        combo = methodCallExpr2;
                        combo = new UnaryExpr( combo, UnaryExpr.Operator.LOGICAL_COMPLEMENT );
                        break;
                    default:
                        combo = new BinaryExpr( left.getExpression(), right.getExpression(), operator );
                }
            }

            if ( left.getPrefixExpression() != null ) {
                combo = new BinaryExpr( left.getPrefixExpression(), combo, Operator.AND );
            }

            return new DrlxParseResult(patternType, exprId, bindingId, combo, left.getType())
                    .setDecodeConstraintType( decodeConstraintType ).setUsedDeclarations( usedDeclarations )
                    .setReactOnProperties( reactOnProperties ).setLeft( left ).setRight( right );
        }

        if ( drlxExpr instanceof UnaryExpr ) {
            UnaryExpr unaryExpr = (UnaryExpr) drlxExpr;

            Set<String> usedDeclarations = new HashSet<>();
            Set<String> reactOnProperties = new HashSet<>();
            TypedExpression left = DrlxParseUtil.toTypedExpression( context, packageModel, patternType, unaryExpr, usedDeclarations, reactOnProperties );

            return new DrlxParseResult(patternType, exprId, bindingId, left.getExpression(), left.getType())
                    .setUsedDeclarations( usedDeclarations ).setReactOnProperties( reactOnProperties ).setLeft( left );
        }

        if ( drlxExpr instanceof PointFreeExpr ) {
            PointFreeExpr pointFreeExpr = (PointFreeExpr) drlxExpr;

            Set<String> usedDeclarations = new HashSet<>();
            Set<String> reactOnProperties = new HashSet<>();
            TypedExpression left = DrlxParseUtil.toTypedExpression( context, packageModel, patternType, pointFreeExpr.getLeft(), usedDeclarations, reactOnProperties );
            DrlxParseUtil.toTypedExpression( context, packageModel, patternType, pointFreeExpr.getRight(), usedDeclarations, reactOnProperties );

            MethodCallExpr methodCallExpr = new MethodCallExpr( null, pointFreeExpr.getOperator().asString() );
            if (pointFreeExpr.getArg1() != null) {
                addArgumentToMethodCall( pointFreeExpr.getArg1(), methodCallExpr );
                if (pointFreeExpr.getArg2() != null) {
                    addArgumentToMethodCall( pointFreeExpr.getArg2(), methodCallExpr );
                }
            }

            return new DrlxParseResult(patternType, exprId, bindingId, methodCallExpr, left.getType() )
                    .setUsedDeclarations( usedDeclarations ).setReactOnProperties( reactOnProperties ).setStatic( true );
        }

        if (drlxExpr instanceof MethodCallExpr) {
            MethodCallExpr methodCallExpr = (MethodCallExpr) drlxExpr;

            NameExpr _this = new NameExpr("_this");
            TypedExpression converted = DrlxParseUtil.toMethodCallWithClassCheck(methodCallExpr, patternType);
            Expression withThis = DrlxParseUtil.prepend(_this, converted.getExpression());

            return new DrlxParseResult(patternType, exprId, bindingId, withThis, converted.getType());
        }

        if (drlxExpr instanceof NameExpr) {
            NameExpr methodCallExpr = (NameExpr) drlxExpr;

            NameExpr _this = new NameExpr("_this");
            TypedExpression converted = DrlxParseUtil.toMethodCallWithClassCheck(methodCallExpr, patternType);
            Expression withThis = DrlxParseUtil.prepend(_this, converted.getExpression());

            if (drlx.getBind() != null) {
                return new DrlxParseResult( patternType, exprId, bindingId, null, converted.getType() )
                        .setLeft( new TypedExpression( withThis, converted.getType() ) )
                        .addReactOnProperty( methodCallExpr.getNameAsString() );
            } else {
                return new DrlxParseResult( patternType, exprId, bindingId, withThis, converted.getType() )
                        .addReactOnProperty( methodCallExpr.getNameAsString() );
            }
        }

        if (drlxExpr instanceof OOPathExpr ) {
            return new DrlxParseResult(patternType, exprId, bindingId, drlxExpr, null);
        }

        throw new UnsupportedOperationException("Unknown expression: " + toDrlx(drlxExpr)); // TODO
    }

    static class DrlxParseResult {

        final Class<?> patternType;
        final Expression expr;
        final Class<?> exprType;

        private String exprId;
        private String patternBinding;
        private String exprBinding;

        ConstraintType decodeConstraintType;
        Set<String> usedDeclarations = Collections.emptySet();
        Set<String> reactOnProperties = Collections.emptySet();
        String[] watchedProperties;

        TypedExpression left;
        TypedExpression right;
        boolean isStatic;

        public DrlxParseResult( Class<?> patternType, String exprId, String patternBinding, Expression expr, Class<?> exprType) {
            this.patternType = patternType;
            this.exprId = exprId;
            this.patternBinding = patternBinding;
            this.expr = expr;
            this.exprType = exprType;
        }

        public DrlxParseResult setDecodeConstraintType( ConstraintType decodeConstraintType ) {
            this.decodeConstraintType = decodeConstraintType;
            return this;
        }

        public DrlxParseResult setUsedDeclarations( Set<String> usedDeclarations ) {
            this.usedDeclarations = usedDeclarations;
            return this;
        }

        public DrlxParseResult setReactOnProperties( Set<String> reactOnProperties ) {
            this.reactOnProperties = reactOnProperties;
            return this;
        }

        public DrlxParseResult addReactOnProperty( String reactOnProperty ) {
            if (reactOnProperties.isEmpty()) {
                reactOnProperties = new HashSet<>();
            }
            this.reactOnProperties.add(reactOnProperty);
            return this;
        }

        public DrlxParseResult setLeft( TypedExpression left ) {
            this.left = left;
            return this;
        }

        public DrlxParseResult setRight( TypedExpression right ) {
            this.right = right;
            return this;
        }

        public DrlxParseResult setStatic( boolean aStatic ) {
            isStatic = aStatic;
            return this;
        }

        public String getExprId() {
            return exprId;
        }

        public String getPatternBinding() {
            return patternBinding;
        }

        public void setExprId(String exprId) {
            this.exprId = exprId;
        }

        public void setPatternBinding(String patternBinding) {
            this.patternBinding = patternBinding;
        }

        public void setExprBinding(String exprBinding) {
            this.exprBinding = exprBinding;
        }
    }



    private static void addArgumentToMethodCall( Expression expr, MethodCallExpr methodCallExpr ) {
        if (expr instanceof TemporalLiteralExpr ) {
            TemporalLiteralExpr tempExpr1 = (TemporalLiteralExpr) expr;
            final TemporalLiteralChunkExpr firstTemporalExpression = tempExpr1.getChunks().iterator().next();
            methodCallExpr.addArgument("" + firstTemporalExpression.getValue() );
            methodCallExpr.addArgument( "java.util.concurrent.TimeUnit." + firstTemporalExpression.getTimeUnit() );
        } else {
            methodCallExpr.addArgument( expr );
        }
    }

    public static Expression buildExpressionWithIndexing(DrlxParseResult drlxParseResult) {
        String exprId = drlxParseResult.exprId;
        MethodCallExpr exprDSL = new MethodCallExpr(null, EXPR_CALL);
        if (exprId != null && !"".equals(exprId)) {
            exprDSL.addArgument( new StringLiteralExpr(exprId) );
        }

        exprDSL = buildExpression( drlxParseResult, exprDSL );
        exprDSL = buildIndexedBy( drlxParseResult, exprDSL );
        exprDSL = buildReactOn( drlxParseResult, exprDSL );
        return exprDSL;
    }

    private static MethodCallExpr buildExpression( DrlxParseResult drlxParseResult, MethodCallExpr exprDSL ) {
        exprDSL.addArgument( new NameExpr(toVar(drlxParseResult.patternBinding)) );
        drlxParseResult.usedDeclarations.stream().map( x -> new NameExpr(toVar(x))).forEach(exprDSL::addArgument);
        exprDSL.addArgument(buildConstraintExpression( drlxParseResult, drlxParseResult.expr ));
        return exprDSL;
    }


    private static MethodCallExpr buildIndexedBy( DrlxParseResult drlxParseResult, MethodCallExpr exprDSL ) {
        Set<String> usedDeclarations = drlxParseResult.usedDeclarations;
        ConstraintType decodeConstraintType = drlxParseResult.decodeConstraintType;
        TypedExpression left = drlxParseResult.left;
        TypedExpression right = drlxParseResult.right;

        // .indexBy(..) is only added if left is not an identity expression:
        if ( decodeConstraintType != null && !(left.getExpression() instanceof NameExpr && ((NameExpr)left.getExpression()).getName().getIdentifier().equals("_this")) ) {
            Class<?> indexType = Stream.of( left, right ).map( TypedExpression::getType )
                                       .filter( Objects::nonNull )
                                       .findFirst().get();

            ClassExpr indexedBy_indexedClass = new ClassExpr( JavaParser.parseType( indexType.getCanonicalName() ) );
            FieldAccessExpr indexedBy_constraintType = new FieldAccessExpr( new NameExpr( "org.drools.model.Index.ConstraintType" ), decodeConstraintType.toString()); // not 100% accurate as the type in "nameExpr" is actually parsed if it was JavaParsers as a big chain of FieldAccessExpr
            LambdaExpr indexedBy_leftOperandExtractor = new LambdaExpr();
            indexedBy_leftOperandExtractor.addParameter(new Parameter(new UnknownType(), "_this"));
            boolean leftContainsThis = left.getExpression().toString().contains("_this");
            indexedBy_leftOperandExtractor.setBody(new ExpressionStmt(leftContainsThis ? left.getExpression() : right.getExpression()) );

            MethodCallExpr indexedByDSL = new MethodCallExpr(exprDSL, "indexedBy");
            indexedByDSL.addArgument( indexedBy_indexedClass );
            indexedByDSL.addArgument( indexedBy_constraintType );
            indexedByDSL.addArgument( indexedBy_leftOperandExtractor );
            if ( usedDeclarations.isEmpty() ) {
                Expression indexedBy_rightValue = right.getExpression();
                indexedByDSL.addArgument( indexedBy_rightValue );
            } else if ( usedDeclarations.size() == 1 ) {
                LambdaExpr indexedBy_rightOperandExtractor = new LambdaExpr();
                indexedBy_rightOperandExtractor.addParameter(new Parameter(new UnknownType(), usedDeclarations.iterator().next()));
                indexedBy_rightOperandExtractor.setBody(new ExpressionStmt(!leftContainsThis ? left.getExpression() : right.getExpression()) );
                indexedByDSL.addArgument( indexedBy_rightOperandExtractor );
            } else {
                throw new UnsupportedOperationException( "TODO" ); // TODO: possibly not to be indexed
            }
            return indexedByDSL;
        }
        return exprDSL;
    }

    private static MethodCallExpr buildReactOn( DrlxParseResult drlxParseResult, MethodCallExpr exprDSL ) {
        if ( !drlxParseResult.reactOnProperties.isEmpty() ) {
            exprDSL = new MethodCallExpr(exprDSL, "reactOn");
            drlxParseResult.reactOnProperties.stream()
                             .map( StringLiteralExpr::new )
                             .forEach( exprDSL::addArgument );

        }

        if ( drlxParseResult.watchedProperties != null && drlxParseResult.watchedProperties.length > 0 ) {
            exprDSL = new MethodCallExpr(exprDSL, "watch");
            Stream.of( drlxParseResult.watchedProperties )
                    .map( StringLiteralExpr::new )
                    .forEach( exprDSL::addArgument );
        }

        return exprDSL;
    }

    private static Expression buildConstraintExpression( DrlxParseResult drlxParseResult, Expression expr ) {
        return drlxParseResult.isStatic ? expr : generateLambdaWithoutParameters(drlxParseResult.usedDeclarations, expr);
    }

    public static Expression buildBinding(DrlxParseResult drlxParseResult ) {
        MethodCallExpr bindDSL = new MethodCallExpr(null, BIND_CALL);
        bindDSL.addArgument( new NameExpr(toVar(drlxParseResult.exprBinding)) );
        MethodCallExpr bindAsDSL = new MethodCallExpr(bindDSL, BIND_AS_CALL);
        bindAsDSL.addArgument( new NameExpr(toVar(drlxParseResult.patternBinding)) );
        bindAsDSL.addArgument( buildConstraintExpression( drlxParseResult, drlxParseResult.left.getExpression() ) );
        return buildReactOn( drlxParseResult, bindAsDSL );
    }
}