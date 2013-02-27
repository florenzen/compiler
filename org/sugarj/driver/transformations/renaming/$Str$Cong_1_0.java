package org.sugarj.driver.transformations.renaming;

import org.strategoxt.stratego_lib.*;
import org.strategoxt.lang.*;
import org.spoofax.interpreter.terms.*;
import static org.strategoxt.lang.Term.*;
import org.spoofax.interpreter.library.AbstractPrimitive;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

@SuppressWarnings("all") public class $Str$Cong_1_0 extends Strategy 
{ 
  public static $Str$Cong_1_0 instance = new $Str$Cong_1_0();

  @Override public IStrategoTerm invoke(Context context, IStrategoTerm term, Strategy j_15)
  { 
    ITermFactory termFactory = context.getFactory();
    context.push("StrCong_1_0");
    Fail86:
    { 
      IStrategoTerm c_105 = null;
      IStrategoTerm b_105 = null;
      if(term.getTermType() != IStrategoTerm.APPL || out._consStrCong_1 != ((IStrategoAppl)term).getConstructor())
        break Fail86;
      b_105 = term.getSubterm(0);
      IStrategoList annos75 = term.getAnnotations();
      c_105 = annos75;
      term = j_15.invoke(context, b_105);
      if(term == null)
        break Fail86;
      term = termFactory.annotateTerm(termFactory.makeAppl(out._consStrCong_1, new IStrategoTerm[]{term}), checkListAnnos(termFactory, c_105));
      context.popOnSuccess();
      if(true)
        return term;
    }
    context.popOnFailure();
    return null;
  }
}