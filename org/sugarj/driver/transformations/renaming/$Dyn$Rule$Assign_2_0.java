package org.sugarj.driver.transformations.renaming;

import org.strategoxt.stratego_lib.*;
import org.strategoxt.lang.*;
import org.spoofax.interpreter.terms.*;
import static org.strategoxt.lang.Term.*;
import org.spoofax.interpreter.library.AbstractPrimitive;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

@SuppressWarnings("all") public class $Dyn$Rule$Assign_2_0 extends Strategy 
{ 
  public static $Dyn$Rule$Assign_2_0 instance = new $Dyn$Rule$Assign_2_0();

  @Override public IStrategoTerm invoke(Context context, IStrategoTerm term, Strategy q_10, Strategy r_10)
  { 
    ITermFactory termFactory = context.getFactory();
    context.push("DynRuleAssign_2_0");
    Fail21:
    { 
      IStrategoTerm w_91 = null;
      IStrategoTerm s_91 = null;
      IStrategoTerm t_91 = null;
      IStrategoTerm x_91 = null;
      if(term.getTermType() != IStrategoTerm.APPL || out._consDynRuleAssign_2 != ((IStrategoAppl)term).getConstructor())
        break Fail21;
      s_91 = term.getSubterm(0);
      t_91 = term.getSubterm(1);
      IStrategoList annos11 = term.getAnnotations();
      w_91 = annos11;
      term = q_10.invoke(context, s_91);
      if(term == null)
        break Fail21;
      x_91 = term;
      term = r_10.invoke(context, t_91);
      if(term == null)
        break Fail21;
      term = termFactory.annotateTerm(termFactory.makeAppl(out._consDynRuleAssign_2, new IStrategoTerm[]{x_91, term}), checkListAnnos(termFactory, w_91));
      context.popOnSuccess();
      if(true)
        return term;
    }
    context.popOnFailure();
    return null;
  }
}