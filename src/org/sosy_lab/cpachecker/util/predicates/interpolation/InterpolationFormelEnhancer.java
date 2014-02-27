/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
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
package org.sosy_lab.cpachecker.util.predicates.interpolation;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.sosy_lab.common.Pair;
import org.sosy_lab.cpachecker.util.predicates.FormulaManagerFactory;
import org.sosy_lab.cpachecker.util.predicates.interfaces.BooleanFormula;
import org.sosy_lab.cpachecker.util.predicates.interfaces.Formula;
import org.sosy_lab.cpachecker.util.predicates.interfaces.FormulaType;
import org.sosy_lab.cpachecker.util.predicates.interfaces.InterpolatingProverEnvironment;
import org.sosy_lab.cpachecker.util.predicates.interfaces.RationalFormula;
import org.sosy_lab.cpachecker.util.predicates.interfaces.UnsafeFormulaManager;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.BooleanFormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.FormulaManagerView;

//TODO: edit the end of the InterpolationManager.buildCounterexampleTrace0 to ensure a variable of type A' is never returned

public class InterpolationFormelEnhancer
{
  private BooleanFormulaManagerView bfmgr;
  private FormulaManagerView fmv;
  private FormulaManagerFactory factory;

  public InterpolationFormelEnhancer(FormulaManagerView pFmgr,FormulaManagerFactory fmf)
  {
    fmv = pFmgr;
    bfmgr = pFmgr.getBooleanFormulaManager();
    factory = fmf;
  }

  public void enhance(List<BooleanFormula> orderedFormulas)
  {
    int ap = getAbstractionPoint(orderedFormulas);
    List<Pair<Formula,Formula>> replacements = replaceCommonVariables(orderedFormulas,ap);
    Formula f = orderedFormulas.get(ap);


    getBestLatticeElement(orderedFormulas,replacements);


    int a = 0; int i=0;
    for(int k=0;k<replacements.size();k++) {
      Formula t = replacements.get(k).getFirst();
      if(fmv.getUnsafeFormulaManager().getName(t).indexOf("a@")>=0) {
        a = k;
      }
      if(fmv.getUnsafeFormulaManager().getName(t).indexOf("i@")>=0) {
        i = k;
      }
    }
    RationalFormula fa1 = (RationalFormula) replacements.get(a).getFirst();
    RationalFormula fa2 = (RationalFormula) replacements.get(a).getSecond();
    RationalFormula fi1 = (RationalFormula) replacements.get(i).getFirst();
    RationalFormula fi2 = (RationalFormula) replacements.get(i).getSecond();
    RationalFormula rf1 = fmv.getRationalFormulaManager().subtract(fa1, fi1);
    RationalFormula rf2 = fmv.getRationalFormulaManager().subtract(fa2, fi2);
    BooleanFormula r = fmv.getRationalFormulaManager().equal(rf1, rf2);

    f = bfmgr.and((BooleanFormula) f,r);
    orderedFormulas.set(ap, (BooleanFormula) f);

    System.out.println("##############");
    for(int s=0;s<orderedFormulas.size();s++) {
      System.out.println(orderedFormulas.get(s).toString());
    }
  }

  private BooleanFormula getBestLatticeElement(List<BooleanFormula> orderedFormulas, List<Pair<Formula,Formula>> variablePairs)
  {
    List<BooleanFormula> topElements = getBestLatticeElements(orderedFormulas,variablePairs);

    //TODO: make decision based on type of variables

    //TODO: delete debug stuff here
    System.out.println("xxxxxxxxxxxxxxxx");
    for(BooleanFormula f : topElements) {
      System.out.println(f.toString());
    }
    System.out.println("xxxxxxx");

    return null;
  }

  private List<BooleanFormula> getBestLatticeElements(List<BooleanFormula> orderedFormulas, List<Pair<Formula,Formula>> variablePairs)
  {
    List<BooleanFormula> templates = getTemplateFormulas(variablePairs);
    Stack<BitSet> stack = new Stack<>();
    List<BitSet> topElements = new ArrayList<>();
    List<BooleanFormula> result = new ArrayList<>();
    SATTest<?> tester = new SATTest<>(orderedFormulas);

    BitSet all = new BitSet(templates.size());
    //all.set(0,templates.size());
    // the command above does what [...],templates.size()-1); is supposed to do after the api 7 doc.
    // this is fishy... to be sure fill it manually.
    for(int i=0;i<templates.size();i++) {
      all.set(i);
    }
    stack.push(all);

    while(!stack.empty()) {
      BitSet elem = stack.pop();

      // test if a better element exists already
      boolean betterTopExists = false;
      for(BitSet top : topElements) {
        BitSet copy = (BitSet) top.clone();
        copy.andNot(elem);
        if(copy.isEmpty()) {
          betterTopExists = true;
          break;
        }
      }
      if(betterTopExists) {
        continue;
      }

      List<BitSet> subsets = getSubsets(elem);

      // test if element is a single template (returns only one empty BitSet)
      if(subsets.size()==1) {
        topElements.add(elem);
        result.add(formulaFromSet(templates,elem));
        continue;
      }

      // check for acceptable subgroups and add if applicable
      boolean isTop = true;
      for(BitSet subset : subsets) {
        if(!tester.checkFormula(formulaFromSet(templates,subset))) {  // must be unsat here
          isTop = false;
          stack.add(subset);
        }
      }

      // if this is a top element => add to top element list
      if(isTop) {
        topElements.add(elem);
        result.add(formulaFromSet(templates,elem));
      }
    }

    return result;
  }

  private List<BitSet> getSubsets(BitSet set)
  {
    List<BitSet> result = new ArrayList<>();

    for(int i=set.nextSetBit(0);i>=0;i=set.nextSetBit(i+1)) {
      BitSet copy = (BitSet) set.clone();
      copy.clear(i);
      result.add(copy);
    }

    return result;
  }

  private BooleanFormula formulaFromSet(List<BooleanFormula> templates, BitSet set)
  {
    if(set.isEmpty())
     {
      return null;  // In theory, this will never happen
    }

    int i = set.nextSetBit(0);
    BooleanFormula result = templates.get(i);

    for (i=set.nextSetBit(i+1); i>=0; i=set.nextSetBit(i+1)) {
      result = bfmgr.and(result, templates.get(i));
    }

    return result;
  }

  private List<BooleanFormula> getTemplateFormulas(List<Pair<Formula,Formula>> variablePairs)
  {
    List<BooleanFormula> result = new ArrayList<>();

    // equivalences: x=x'
    for(Pair<Formula,Formula> p : variablePairs) {
      result.add(fmv.makeEqual(p.getFirst(), p.getSecond()));
    }

    // differences: x-y=x'-y'
    for(int i=0;i<variablePairs.size();i++) {
      for(int k=i+1;k<variablePairs.size();k++) {
        Formula fx1 = variablePairs.get(i).getFirst();
        Formula fy1 = variablePairs.get(k).getFirst();
        if(!(fx1 instanceof RationalFormula && fy1 instanceof RationalFormula)) {
          continue;
        }
        Formula fx2 = variablePairs.get(i).getSecond();
        Formula fy2 = variablePairs.get(k).getSecond();
        Formula eq1 = fmv.makeMinus(fx1, fy1);
        Formula eq2 = fmv.makeMinus(fx2, fy2);
        result.add(fmv.makeEqual(eq1, eq2));
      }
    }

    return result;
  }

  private List<Pair<Formula,Formula>> replaceCommonVariables(List<BooleanFormula> orderedFormulas, int abstractionPoint)
  {
    List<Pair<Formula,Formula>> result = new ArrayList<>();

    List<String> commonNames = getCommonVariableNames(orderedFormulas,abstractionPoint);
    Map<String,String> replacementList = new HashMap<>();
    for(String name : commonNames) {
      replacementList.put(name, name+"'");
    }

    for(int i=0;i<=abstractionPoint;i++) {
      BooleanFormula f = orderedFormulas.get(i);

      f = fmv.getUnsafeFormulaManager().typeFormula(FormulaType.BooleanType, renameRek(f,replacementList,result));
      orderedFormulas.set(i, f);
    }

    return result;
  }

  private Formula renameRek(Formula f, Map<String,String> replacementList, List<Pair<Formula,Formula>> replacedVariables)
  {
    UnsafeFormulaManager ufm = fmv.getUnsafeFormulaManager();
    if(ufm.isVariable(f)) {
      String newName = replacementList.get(ufm.getName(f));
      if(newName==null) {
        return f;
      }
      Formula replacement = ufm.replaceName(f, newName);

      //TODO: UNSAFE! Doesn't have to be rational
      Pair<Formula,Formula> p = Pair.of((Formula)ufm.typeFormula(FormulaType.RationalType, f),
                                        (Formula)ufm.typeFormula(FormulaType.RationalType,replacement));
      if(!replacedVariables.contains(p)) {
        replacedVariables.add(p);
      }
      return replacement;
    }
    ArrayList<Formula> args = new ArrayList<>();
    for(int i=0;i<ufm.getArity(f);i++) {
      args.add(renameRek(ufm.getArg(f, i),replacementList,replacedVariables));
    }
    if(args.size()>0) {
      return ufm.replaceArgs(f, args.toArray(new Formula[0]));
    }
    return f;
  }

  private List<String> getCommonVariableNames(List<BooleanFormula> orderedFormulas,int abstractionPoint)
  {
    ArrayList<String> result = new ArrayList<>();
    HashMap<String,String> mapA = new HashMap<>();

    for(int i=0;i<=abstractionPoint;i++) {
      for(String name : fmv.extractVariables(orderedFormulas.get(i))) {
        mapA.put(name,name);
      }
    }

    for(int i=abstractionPoint+1;i<orderedFormulas.size();i++) {
      for(String name : fmv.extractVariables(orderedFormulas.get(i))) {
        if(mapA.containsKey(name) && !result.contains(name)) {
          result.add(name);
        }
      }
    }

    return result;
  }

  private int getAbstractionPoint(List<BooleanFormula> orderedFormulas)
  {
    //TODO: detect loop bodies here
    return orderedFormulas.size()-2;  // two formulas => intercept at pos 0
  }

  private Formula varFromString(BooleanFormula f, String name, FormulaType<?> type)
  {
    Stack<Formula> stack = new Stack<>();
    stack.push(f);

    UnsafeFormulaManager ufm = fmv.getUnsafeFormulaManager();

    while(!stack.isEmpty()) {
      Formula curForm = stack.pop();
      if(ufm.isVariable(curForm) && ufm.getName(curForm).equals(name)) {
        return ufm.typeFormula(type, curForm);
      }
      for(int i=0;i<ufm.getArity(curForm);i++) {
        stack.push(ufm.getArg(curForm,i));
      }
    }
    return null;
  }

  private class SATTest<T>
  {
    private InterpolatingProverEnvironment<T> itpProver;

    @SuppressWarnings("unchecked")
    public SATTest(List<BooleanFormula> formulas, int abstractionPoint)
    {
      itpProver = (InterpolatingProverEnvironment<T>)factory.newProverEnvironmentWithInterpolation(false);
      for(int i=0;i<=abstractionPoint;i++) {
        itpProver.push(formulas.get(i));
      }
    }

    public SATTest(List<BooleanFormula> formulas)
    {
      this(formulas,formulas.size()-1);
    }

    public boolean checkFormula(BooleanFormula f)
    {
      itpProver.push(f);
      boolean result = false;
      try {
        result = !itpProver.isUnsat();
      } catch (InterruptedException e) { }
      itpProver.pop();
      return result;
    }
  }
}
