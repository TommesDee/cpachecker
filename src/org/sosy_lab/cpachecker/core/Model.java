/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2012  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.core;

import static com.google.common.base.Preconditions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.sosy_lab.common.Appender;
import org.sosy_lab.common.Appenders;
import org.sosy_lab.common.Pair;
import org.sosy_lab.cpachecker.cfa.ast.AExpressionAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCallAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.AParameterDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.IADeclaration;
import org.sosy_lab.cpachecker.cfa.ast.IAExpression;
import org.sosy_lab.cpachecker.cfa.ast.IALeftHandSide;
import org.sosy_lab.cpachecker.cfa.ast.IAStatement;
import org.sosy_lab.cpachecker.cfa.ast.IAssignment;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.ADeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.AReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.AStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdgeType;
import org.sosy_lab.cpachecker.cfa.model.FunctionCallEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.types.Type;
import org.sosy_lab.cpachecker.cfa.types.c.CSimpleType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.c.DefaultCTypeVisitor;
import org.sosy_lab.cpachecker.core.Model.AssignableTerm;
import org.sosy_lab.cpachecker.core.Model.CFAPathWithAssignments.CFAEdgeWithAssignments;
import org.sosy_lab.cpachecker.core.Model.CFAPathWithAssignments.CFAEdgeWithAssignments.Assignment;
import org.sosy_lab.cpachecker.cpa.arg.ARGPath;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.util.predicates.PathChecker;

import com.google.common.base.Joiner;
import com.google.common.base.Joiner.MapJoiner;
import com.google.common.collect.ForwardingMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

/**
 * This class represents an assignment of values to program variables
 * along a path. Each variable can have several assignments with different
 * SSA indices if it gets re-assigned along the path.
 *
 * The value of each variable can be an arbitrary object, but usually,
 * this is a {@link Number}.
 */
public class Model extends ForwardingMap<AssignableTerm, Object> implements Appender {

  public static enum TermType {
    Boolean,
    Uninterpreted,
    Integer,
    Real,
    Bitvector;
  }

  public static interface AssignableTerm {

    public TermType getType();
    public String getName();

  }

  public static class Constant implements AssignableTerm {

    public Constant(final String name, final TermType type) {
      this.name = name;
      this.type = type;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public TermType getType() {
      return type;
    }

    @Override
    public String toString() {
      return name + " : " + type;
    }

    @Override
    public int hashCode() {
      return 324 + name.hashCode() + type.hashCode();
    }

    @Override
    public boolean equals(final Object other) {
      if (this == other) {
        return true;
      }

      if (other == null) {
        return false;
      }

      if (!getClass().equals(other.getClass())) {
        return false;
      }

      final Constant otherConstant = (Constant) other;

      return name.equals(otherConstant.name)
          && type.equals(otherConstant.type);
    }

    protected final String name;
    protected final TermType type;
  }

  public static class Variable extends Constant implements AssignableTerm {

    public Variable(final String name, final int ssaIndex, final TermType type) {
      super(name, type);
      this.ssaIndex = ssaIndex;
    }

    public int getSSAIndex() {
      return ssaIndex;
    }

    @Override
    public String toString() {
      return name + "@" + ssaIndex + " : " + type;
    }

    @Override
    public int hashCode() {
      return 324 + name.hashCode() + ssaIndex + type.hashCode();
    }

    @Override
    public boolean equals(final Object other) {
      if (this == other) {
        return true;
      }

      if (other == null) {
        return false;
      }

      if (!getClass().equals(other.getClass())) {
        return false;
      }

      Variable otherVariable = (Variable) other;

      return name.equals(otherVariable.name)
          && (ssaIndex == otherVariable.ssaIndex)
          && type.equals(otherVariable.type);
    }

    private final int ssaIndex;
  }

  public static class Function implements AssignableTerm {

    private final String mName;
    private final TermType mReturnType;
    private final List<Object> mArguments;

    private int mHashCode;

    public Function(String pName, TermType pReturnType, Object[] pArguments) {
      mName = pName;
      mReturnType = pReturnType;
      mArguments = ImmutableList.copyOf(pArguments);

      mHashCode = 32453 + mName.hashCode() + mReturnType.hashCode();

      for (Object lValue : mArguments) {
        mHashCode += lValue.hashCode();
      }
    }

    @Override
    public String getName() {
      return mName;
    }

    @Override
    public TermType getType() {
      return mReturnType;
    }

    public int getArity() {
      return mArguments.size();
    }

    public Object getArgument(int lArgumentIndex) {
      return mArguments.get(lArgumentIndex);
    }

    @Override
    public String toString() {
      return mName + "(" + Joiner.on(',').join(mArguments) + ") : " + mReturnType;
    }

    @Override
    public int hashCode() {
      return mHashCode;
    }

    @Override
    public boolean equals(Object pOther) {
      if (this == pOther) {
        return true;
      }

      if (pOther == null) {
        return false;
      }

      if (!getClass().equals(pOther.getClass())) {
        return false;
      }

      Function lFunction = (Function)pOther;

      return (lFunction.mName.equals(mName)
          && lFunction.mReturnType.equals(mReturnType)
          && lFunction.mArguments.equals(mArguments));
    }
  }

  private final Map<AssignableTerm, Object> mModel;
  private final CFAPathWithAssignments assignments;

  @Override
  protected Map<AssignableTerm, Object> delegate() {
    return mModel;
  }

  public static Model empty() {
    return new Model();
  }

  private Model() {
    mModel = ImmutableMap.of();
    assignments = new CFAPathWithAssignments();
  }

  public Model(Map<AssignableTerm, Object> content) {
    mModel = ImmutableMap.copyOf(content);
    assignments = new CFAPathWithAssignments();
  }

  public Model(Map<AssignableTerm, Object> content, CFAPathWithAssignments pAssignments) {
    mModel = ImmutableMap.copyOf(content);
    assignments = pAssignments;

    checkArgument(mModel.keySet().containsAll(
        assignments.getAllAssignedTerms()));
  }

  /**
   * Return a new model that is equal to the current one,
   * but additionally has information about when each variable was assigned.
   * @see Model#getAssignedTermsPerEdge()
   */
  public Model withAssignmentInformation(CFAPathWithAssignments pAssignments) {
    checkState(assignments.isEmpty());
    return new Model(mModel, pAssignments);
  }

  /**
   * Return a path that indicates which terms where assigned at which edge.
   * Note that it is not guaranteed that this is information is present for
   * all terms, thus <code>this.getAssignedTermsPerEdge().getAllAssignedTerms()</code> may
   * be smaller than <code>this.keySet()</code> (but not larger).
   */
  public CFAPathWithAssignments getAssignedTermsPerEdge() {
    return assignments;
  }

  @Nullable
  public Map<ARGState, CFAEdgeWithAssignments> getexactVariableValues(ARGPath pPath) {

    if (assignments.isEmpty()) {
      return null;
    }

    return assignments.getExactVariableValues(pPath);
  }

  @Nullable
  public CFAPathWithAssignments getExactVariableValuePath(List<CFAEdge> pPath) {

    if (assignments.isEmpty()) {
      return null;
    }

    return assignments.getExactVariableValues(pPath);
  }

  private static final MapJoiner joiner = Joiner.on('\n').withKeyValueSeparator(": ");

  @Override
  public void appendTo(Appendable output) throws IOException {
    joiner.appendTo(output, mModel);
  }

  @Override
  public String toString() {
    return Appenders.toString(this);
  }

  /**
   * This class represents a path of cfaEdges, that contain the additional Information
   * at which edge which assignableTerm was created when this path was checked by
   * the class {@link PathChecker}.
   *
   */
  public static class CFAPathWithAssignments implements Iterable<Model.CFAPathWithAssignments.CFAEdgeWithAssignments> {

    private final List<CFAEdgeWithAssignments> pathWithAssignments;
    private final Multimap<CFAEdge, AssignableTerm> assignableTerms;

    private CFAPathWithAssignments(
        List<CFAEdgeWithAssignments> pPathWithAssignments,
        Multimap<CFAEdge, AssignableTerm> pAssignableTerms) {
      pathWithAssignments = pPathWithAssignments;
      assignableTerms = pAssignableTerms;
    }

    public CFAPathWithAssignments(List<CFAEdge> pPath,
        Multimap<Integer, AssignableTerm> pAssignedTermsPosition,
        Model pModel) {

      List<CFAEdgeWithAssignments> pathWithAssignments = new ArrayList<>(pPath.size());

      Multimap<CFAEdge, AssignableTerm> multimap = HashMultimap.create();

      for (int index = 0; index < pPath.size(); index++) {
        CFAEdge cfaEdge = pPath.get(index);
        Collection<AssignableTerm> terms = pAssignedTermsPosition.get(index);

        Set<Assignment> termSet = new HashSet<>();

        for (AssignableTerm term : terms) {
          termSet.add(new Assignment(term, pModel.get(term)));
        }

        CFAEdgeWithAssignments cfaEdgeWithAssignment = new CFAEdgeWithAssignments(cfaEdge, termSet);
        pathWithAssignments.add(cfaEdgeWithAssignment);
        multimap.putAll(cfaEdge, terms);
      }

      this.pathWithAssignments = ImmutableList.copyOf(pathWithAssignments);
      assignableTerms = ImmutableMultimap.copyOf(multimap);
    }

    private CFAPathWithAssignments getExactVariableValues(List<CFAEdge> pPath) {

      if (fitsPath(pPath)) {
        return this;
      }

      int index = pathWithAssignments.size() - pPath.size();

      if (index < 0) {
        return null;
      }

      List<CFAEdgeWithAssignments> result;

      result = new ArrayList<>(pPath.size());

      for (CFAEdge edge : pPath) {

        if (index > pathWithAssignments.size()) { return null; }

        CFAEdgeWithAssignments cfaWithAssignment = pathWithAssignments.get(index);

        if (!edge.equals(cfaWithAssignment.getCFAEdge())) { return null; }

        result.add(new CFAEdgeWithAssignments(edge, cfaWithAssignment.getAssignments()));
        index++;
      }

      return new CFAPathWithAssignments(result, assignableTerms);
    }

    private boolean fitsPath(List<CFAEdge> pPath) {

      int index = 0;

      for (CFAEdge edge : pPath) {

        if (index > pathWithAssignments.size()) {
          return false; }

        CFAEdgeWithAssignments cfaWithAssignment = pathWithAssignments.get(index);

        if (!edge.equals(cfaWithAssignment.getCFAEdge())) { return false; }
        index++;

        return true;
      }


      return false;
    }

    private Map<ARGState, CFAEdgeWithAssignments> getExactVariableValues(ARGPath pPath) {

      if (pPath.isEmpty() || pPath.size() != pathWithAssignments.size()) {
        return null;
      }

      Map<ARGState, CFAEdgeWithAssignments> result = new HashMap<>();

      int index = 0;

      for (Pair<ARGState, CFAEdge> argPair : pPath) {

        CFAEdgeWithAssignments edgeWithAssignment = pathWithAssignments.get(index);

        if (!edgeWithAssignment.getCFAEdge().equals(argPair.getSecond())) {
          // path is not equivalent
          return null;
        }

        result.put(argPair.getFirst(), edgeWithAssignment);
        index++;
      }

      return result;
    }

    public Collection<AssignableTerm> getAllAssignedTerms() {
      return assignableTerms.values();
    }

    public Collection<AssignableTerm> getAllAssignedTerms(CFAEdge edge) {
      return assignableTerms.get(edge);
    }

    public boolean isEmpty() {
      return pathWithAssignments.isEmpty();
    }

    public CFAPathWithAssignments() {
      pathWithAssignments = ImmutableList.of();
      assignableTerms = ImmutableMultimap.of();
    }

    @Override
    public String toString() {
      return pathWithAssignments.toString();
    }

    public CFAEdge getCFAEdgeAtPosition(int index) {
      return pathWithAssignments.get(index).getCFAEdge();
    }

    public int size() {
      return pathWithAssignments.size();
    }

    @Override
    public Iterator<CFAEdgeWithAssignments> iterator() {
      return pathWithAssignments.iterator();
    }

    public static class CFAEdgeWithAssignments {

      private final CFAEdge edge;
      private final Set<Assignment> assignments;

      public CFAEdgeWithAssignments(CFAEdge pEdge, Set<Assignment> pAssignments) {
        edge = pEdge;
        assignments = pAssignments;
      }

      public Set<Assignment> getAssignments() {
        return assignments;
      }

      public CFAEdge getCFAEdge() {
        return edge;
      }

      @Override
      public String toString() {
        return edge.toString() + " " + assignments.toString();
      }

      @Nullable
      public String getAsCode() {

        if (assignments.size() < 0) {
          return null;
        }

        if (edge.getEdgeType() == CFAEdgeType.DeclarationEdge) {
          return handleDeclaration(((ADeclarationEdge) edge).getDeclaration());
        } else if (edge.getEdgeType() == CFAEdgeType.StatementEdge) {
          return handleStatement(((AStatementEdge) edge).getStatement());
        } else if (edge.getEdgeType() == CFAEdgeType.FunctionCallEdge) {
          return handleFunctionCall( ((FunctionCallEdge)edge));
        } else if(edge.getEdgeType() == CFAEdgeType.ReturnStatementEdge) {
          return handleReturnStatement(((AReturnStatementEdge)edge).getExpression());
        }

        return null;
      }

      private  String handleFunctionCall(FunctionCallEdge pFunctionCallEdge) {

        FunctionEntryNode functionEntryNode = pFunctionCallEdge.getSuccessor();

        String functionName = functionEntryNode.getFunctionName();

        List<? extends AParameterDeclaration> formalParameters =
            functionEntryNode.getFunctionParameters();

        List<String> formalParameterNames =
            functionEntryNode.getFunctionParameterNames();


        if (formalParameters == null) {
          return null;
        }

        String[] parameterValuesAsCode = new String[formalParameters.size()];

        for (Assignment valuePair : assignments) {

          String termName = valuePair.getTerm().getName();
          String[] termFunctionAndVariableName = termName.split("::");

          if (!(termFunctionAndVariableName.length == 2)) {
            return null;
          }

          String termVariableName = termFunctionAndVariableName[1];
          String termFunctionName = termFunctionAndVariableName[0];

          if (!termFunctionName.equals(functionName)) {
            return null;
          }

          if (formalParameterNames.contains(termVariableName)) {

            int formalParameterPosition =
                formalParameterNames.indexOf(termVariableName);

            AParameterDeclaration formalParameterDeclaration =
                formalParameters.get(formalParameterPosition);

            String valueAsCode = getValueAsCode(valuePair.getValue(), formalParameterDeclaration.getType());

            if (valueAsCode == null ||
                !formalParameterDeclaration.getName().equals(termVariableName)) {
              return null;
            }

            parameterValuesAsCode[formalParameterPosition] = valueAsCode;
          } else {
            return null;
          }
        }

        if (parameterValuesAsCode.length < 1) {
          return null;
        }

        Joiner joiner = Joiner.on(", ");
        String arguments = "(" + joiner.join(parameterValuesAsCode) + ")";

        return functionName + arguments + ";";
      }

      private String handleReturnStatement(IAExpression pExpression) {

        if (pExpression != null && assignments.size() == 1) {

          Type expectedType = pExpression.getExpressionType();
          Object value = getValueObject(pExpression.toASTString());

          if (value == null) {
            return null;
          }

          String valueAsCode = getValueAsCode(value, expectedType);

          if (valueAsCode == null) {
            return null;
          }

          return "return " + valueAsCode + ";";
        }

        return null;
      }


      @Nullable
      private String handleAssignment(IAssignment assignment) {

        IALeftHandSide leftHandSide = assignment.getLeftHandSide();
        Object value = getValueObject(leftHandSide.toASTString());

        if (value == null) {
          return null;
        }

        Type expectedType = leftHandSide.getExpressionType();
        String valueAsCode = getValueAsCode(value, expectedType);

        if(valueAsCode == null) {
          return null;
        }

        return leftHandSide.toASTString() + " = " + valueAsCode + ";";
      }

      @Nullable
      private Object getValueObject(String pExpressionAst) {


        //If their is only one value, its the one we search
        if (assignments.size() == 1) {
          return assignments.iterator().next().getValue();
        }

        Object value = null;

        for (Assignment assignment : assignments) {

          String termName = assignment.getTerm().getName();
          String[] termFunctionAndVariableName = termName.split("::");

          String termVariableName;

          if (termFunctionAndVariableName.length == 1) {
            termVariableName = termFunctionAndVariableName[0];
          } else if(termFunctionAndVariableName.length == 2) {
            termVariableName = termFunctionAndVariableName[1];
          } else {
            return null;
          }

          if (termVariableName.equals(pExpressionAst)) {
            if (value == null) {
              value = assignment.getValue();
            } else {
              // More than one value found.
              return null;
            }
          }
        }

        return value;
      }

      @Nullable
      private String getValueAsCode(Object pValue, Type pExpectedType) {

        // TODO processing for other languages
        if(pExpectedType instanceof CType) {
          return ((CType) pExpectedType).accept(new TypeValueAsCodeVisitor(pValue));
        }

        return null;
      }

      @Nullable
      private String handleStatement(IAStatement pStatement) {

        if (pStatement instanceof AFunctionCallAssignmentStatement) {
          IAssignment assignmentStatement =
              ((AFunctionCallAssignmentStatement) pStatement);
          return handleAssignment(assignmentStatement);
        }

        if (pStatement instanceof AExpressionAssignmentStatement) {
          IAssignment assignmentStatement =
              ((AExpressionAssignmentStatement) pStatement);
          return handleAssignment(assignmentStatement);
        }

        return null;
      }

      private String handleDeclaration(IADeclaration dcl) {

        if (dcl instanceof CVariableDeclaration) {

          Object value = getValueObject(dcl.getName());

          if (value == null) {
            return null;
          }

          Type dclType = dcl.getType();
          String valueAsCode = getValueAsCode(value, dclType);

          if (valueAsCode == null) {
            return null;
          }

          return dclType.toASTString(dcl.getName()) + " = "
              + valueAsCode + ";";
        }

        return null;
      }

      private static class TypeValueAsCodeVisitor extends DefaultCTypeVisitor<String, RuntimeException> {

        private final Object value;

        public TypeValueAsCodeVisitor(Object pValue) {
          value = pValue;
        }

        @Override
        public String visitDefault(CType pT) throws RuntimeException {
          return null;
        }

        @Override
        public String visit(CSimpleType simpleType) throws RuntimeException {
          switch (simpleType.getType()) {
          case BOOL:
          case INT:
            return handleIntegerNumbers(simpleType);
          case FLOAT:
          case DOUBLE:
            return handleFloatingPointNumbers(simpleType);
          }

          return null;
        }

        private String handleFloatingPointNumbers(CSimpleType pSimpleType) {

          //TODO Check length in given constraints.

          String value = this.value.toString();

          return value.matches("(\\d*)|(.(\\d*))|((\\d*).)|((\\d*).(\\d*))") ? value : null;
        }

        private String handleIntegerNumbers(CSimpleType pSimpleType) {

          //TODO Check length in given constraints.
          String value = this.value.toString();

          if (value.matches("\\d*")) {
            return value;
          } else {
            String[] numberParts = value.split("\\.");

            if (numberParts.length == 2 &&
                numberParts[1].matches("0*") &&
                numberParts[0].matches("\\d*")) {

              return numberParts[0];
            }
          }

          return null;
        }
      }

      public static class Assignment {

        private final AssignableTerm term;
        private final Object value;

        public Assignment(AssignableTerm pTerm, Object pValue) {
          term = pTerm;
          value = pValue;
        }

        public AssignableTerm getTerm() {
          return term;
        }

        public Object getValue() {
          return value;
        }

        @Override
        public String toString() {
          return "term: " + term.toString() + "value: " + value.toString();
        }
      }
    }
  }
}