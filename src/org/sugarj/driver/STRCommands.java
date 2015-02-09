package org.sugarj.driver;

import static org.sugarj.common.FileCommands.toWindowsPath;
import static org.sugarj.common.Log.log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.spoofax.interpreter.core.Interpreter;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.IOAgent;
import org.spoofax.interpreter.stratego.CallT;
import org.spoofax.interpreter.stratego.SDefT;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.client.InvalidParseTableException;
import org.spoofax.jsglr.client.SGLR;
import org.spoofax.jsglr.shared.BadTokenException;
import org.spoofax.jsglr.shared.SGLRException;
import org.spoofax.jsglr.shared.TokenExpectedException;
import org.strategoxt.HybridInterpreter;
import org.strategoxt.lang.Context;
import org.strategoxt.lang.StrategoException;
import org.strategoxt.lang.StrategoExit;
import org.strategoxt.strj.main_strj_0_0;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.FileCommands;
import org.sugarj.common.FilteringIOAgent;
import org.sugarj.common.Log;
import org.sugarj.common.path.Path;
import org.sugarj.driver.caching.ModuleKey;
import org.sugarj.driver.caching.ModuleKeyCache;
import org.sugarj.driver.transformations.extraction.extract_editor_0_0;
import org.sugarj.driver.transformations.extraction.extract_str_0_0;
import org.sugarj.driver.transformations.renaming.rename_rules_0_2;
import org.sugarj.stdlib.StdLib;

/**
 * This class provides methods for various SDF commands. Each
 * SDF command is represented by a separate method of a similar
 * name.
 * 
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class STRCommands {
  

  private static IOAgent strjIOAgent = new FilteringIOAgent(Log.CORE | Log.TRANSFORM, 
                                                            Pattern.quote("[ strj | info ]") + ".*", 
                                                            Pattern.quote("[ strj | error ] Compilation failed") + ".*",
                                                            Pattern.quote("[ strj | warning ] Nullary constructor") + ".*");
  
  private final static Pattern STR_FILE_PATTERN = Pattern.compile(".*\\.str");
  
  private final static Path FAILED_COMPILATION_PATH;
  static {
    try {
      FAILED_COMPILATION_PATH = FileCommands.newTempFile("ctree");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
  
  private final SGLR strParser;
  private final ModuleKeyCache<Path> strCache;
  private final Environment environment;
  
  public STRCommands(SGLR strParser, ModuleKeyCache<Path> strCache, Environment environment) {
    this.strParser = strParser;
    this.strCache = strCache;
    this.environment = environment;
  }

  /**
   *  Compiles a {@code *.str} file to a single {@code *.java} file. 
   */
  private static void strj(boolean normalize, Path str, Path out, Collection<Path> paths, Path baseLanguageDir) throws IOException {
    
    /*
     * We can include as many paths as we want here, checking the
     * adequacy of the occurring imports is done elsewhere.
     */
    List<String> cmd = new ArrayList<String>(Arrays.asList(new String[] {
        "-i", toWindowsPath(str.getAbsolutePath()),
        "-o", toWindowsPath(out.getAbsolutePath()),
//        "-m", main,
        "-p", "sugarj",
        "--library",
        "-O", "0",
    }));
    
    if (normalize)
      cmd.add("-F");
    
    cmd.add("-I");
    cmd.add(StdLib.stdLibDir.getAbsolutePath());
    cmd.add("-I");
    cmd.add(baseLanguageDir.getAbsolutePath());

    
    for (Path path : paths)
      if (path.getFile().isDirectory()){
        cmd.add("-I");
        cmd.add(path.getAbsolutePath());
      }
    
    
    final ByteArrayOutputStream log = new ByteArrayOutputStream();

    // Strj requires a fresh context each time.
    Context ctx = org.strategoxt.strj.strj.init();
    try {
      ctx.setIOAgent(strjIOAgent);
      ctx.invokeStrategyCLI(main_strj_0_0.instance, "strj", cmd.toArray(new String[cmd.size()]));
    }
    catch (StrategoExit e) {
      if (e.getValue() != 0)
        throw new StrategoException("STRJ failed", e);
    } finally {
      if (log.size() > 0 && !log.toString().contains("Abstract syntax in"))
        throw new StrategoException(log.toString());
    }
  }
  
  
  public Path compile(Path str,
                              Set<Path> dependentFiles,
                              Path baseLanguageDir) throws IOException,
                                                          InvalidParseTableException,
                                                          TokenExpectedException,
                                                          BadTokenException,
                                                          SGLRException {
    ModuleKey key = getModuleKeyForAssimilation(str, dependentFiles);
    Path prog = lookupAssimilationInCache(strCache, key);
    StrategoException error = null;
    
    if (prog == null) {
      try {
        prog = generateAssimilator(key, str, environment.getIncludePath(), baseLanguageDir);
      } catch (StrategoException e) {
        prog = FAILED_COMPILATION_PATH;
        error = e;
      } finally {
        if (prog != null && FileCommands.exists(prog) && !FileCommands.isEmptyFile(prog))
          prog = cacheAssimilator(strCache, key, prog, environment);
      }

      if (error != null)
        throw error;
    }
        
    return prog;
  }
    
  private static Path generateAssimilator(ModuleKey key,
                                          Path str,
                                          List<Path> paths,
                                          Path baseLanguageDir) throws IOException {
    boolean success = false;
    log.beginTask("Generating", "Generate the assimilator", Log.TRANSFORM);
    try {
      Path prog = FileCommands.newTempFile("ctree");
      log.log("calling STRJ", Log.TRANSFORM);
      strj(true, str, prog, paths, baseLanguageDir);
      success = FileCommands.exists(prog);
      return prog;
    } finally {
      log.endTask(success);
    }
  }
    
  private static Path cacheAssimilator(ModuleKeyCache<Path> strCache, ModuleKey key, Path prog, Environment environment) throws IOException {
    if (strCache == null)
      return prog;
    

    log.beginTask("Caching", "Cache assimilator", Log.CACHING);
    try {
      Path cacheProg = environment.createCachePath(prog.getFile().getName());
      if (FileCommands.exists(prog))
        FileCommands.copyFile(prog, cacheProg);
      else
        cacheProg = prog;
      
      Path oldProg = strCache.putGet(key, cacheProg);
//      FileCommands.delete(oldProg);

      log.log("Cache Location: " + cacheProg, Log.CACHING);
      return cacheProg;
    } finally {
      log.endTask();
    }
  }
  
  private static Path lookupAssimilationInCache(ModuleKeyCache<Path> strCache, ModuleKey key) {
    if (strCache == null)
      return null;
    
    Path result = null;
    
    log.beginTask("Searching", "Search assimilator in cache", Log.CACHING);
    try {
      result = strCache.get(key);
      
      if (result == null || !result.getFile().exists())
        return null;

      log.log("Cache location: '" + result + "'", Log.CACHING);
      
      return result;
    } finally {
      log.endTask(result != null);
    }
  }


  private ModuleKey getModuleKeyForAssimilation(Path str, Set<Path> dependentFiles) throws IOException, InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
    log.beginTask("Generating", "Generate module key for current assimilation", Log.CACHING);
    try {
      IStrategoTerm aterm = (IStrategoTerm) strParser.parse(FileCommands.readFileAsString(str), str.getAbsolutePath(), "StrategoModule");

      aterm = ATermCommands.getApplicationSubterm(aterm, "Module", 1);

      return new ModuleKey(environment.getStamper(), dependentFiles, environment.getRoot(), STR_FILE_PATTERN, aterm);
    } catch (Exception e) {
      throw new SGLRException(strParser, "could not parse STR file " + str, e);
    } finally {
      log.endTask();
    }
    
  }
  
  public static IStrategoTerm execute(String strategyName, Path ctree, IStrategoTerm in, HybridInterpreter interp) throws IOException {
    return execute(strategyName, new IStrategoTerm[0], ctree, in, interp);
  }
  
  public static IStrategoTerm execute(String strategyName, IStrategoTerm[] targs, Path ctree, IStrategoTerm in, HybridInterpreter interp) throws IOException {
    try {
      interp.load(ctree.getAbsolutePath());
      interp.init();
    } catch (InterpreterException e) {
      throw new StrategoException("Transformation failed: " + (e.getCause() == null ? e : e.getCause()).getMessage(), e);
    }
    
    if (targs.length != 0 && !strategyName.contains("_"))
      strategyName = Interpreter.cify(strategyName) + "_0_" + targs.length;
    
    SDefT def = interp.lookupUncifiedSVar(strategyName);
    if (def == null)
      throw new IllegalArgumentException("Could not find strategy " + strategyName);

    Strategy strategy = new CallT(def.getName(), new Strategy[]{}, targs);
    return execute(strategy, ctree, in, interp);
  }
  
  public static IStrategoTerm execute(Strategy strategy, Path ctree, IStrategoTerm in, HybridInterpreter interp) throws IOException {
    try {
      interp.load(ctree.getAbsolutePath());
      interp.init();
      
      interp.setCurrent(in);
      
      if (strategy.evaluate(interp.getContext())) {
        IStrategoTerm term = interp.current();

        //XXX performance improvement?
//        interp.reset();
                
//        IToken left = ImploderAttachment.getLeftToken(in);
//        IToken right = ImploderAttachment.getRightToken(in);
//        String sort = ImploderAttachment.getSort(in);
//        
//        try {
//          term = ATermCommands.makeMutable(term);
//          ImploderAttachment.putImploderAttachment(term, false, sort, left, right);
//        } catch (Exception e) {
//          log.log("origin annotation failed");
//        }
        return term;
      }
      else
        throw new RuntimeException("hybrid interpreter failed");
    }
    catch (InterpreterException e) {
      throw new StrategoException("Transformation failed: " + (e.getCause() == null ? e : e.getCause()).getMessage(), e);
    }
    catch (Exception e) {
      throw new StrategoException("Transformation failed", e);
    }
  }
  
  /**
   * Filters Stratego statements from the given term
   * and compiles desugaring statements to Stratego.
   * 
   * @param term a file containing a list of SDF 
   *             and Stratego statements.
   * @param str result file
   * @throws InvalidParseTableException 
   */
  public static IStrategoTerm extractSTR(IStrategoTerm term) throws IOException {
    IStrategoTerm result = null;
    Context extractionContext = SugarJContexts.extractionContext();
    try {
      result = extract_str_0_0.instance.invoke(extractionContext, term);
    }
    catch (StrategoExit e) {
      if (e.getValue() != 0 || result == null)
        throw new RuntimeException("Stratego extraction failed", e);
    } finally {
      SugarJContexts.releaseContext(extractionContext);
    }
    return result;
  }
  
  /**
   * Filters Spoofax editor-service declarations.
   * 
   * @param term a SugarJ extension declaration.
   */
  public static IStrategoTerm extractEditor(IStrategoTerm term) throws IOException {
    IStrategoTerm result = null;
    Context extractionContext = SugarJContexts.extractionContext();
    try {
      result = extract_editor_0_0.instance.invoke(extractionContext, term);
    }
    catch (StrategoExit e) {
      if (e.getValue() != 0 || result == null)
        throw new RuntimeException("Editor service extraction failed", e);
    } finally {
      SugarJContexts.releaseContext(extractionContext);
    }
    return result;
  }

  public static IStrategoTerm renameRules(IStrategoTerm term, String oldName, String newName) throws IOException {
    IStrategoTerm result = null;
    Context renameRulesContext = SugarJContexts.renameRulesContext();
    try {
      IStrategoTerm toldName = renameRulesContext.getFactory().makeString(oldName);
      IStrategoTerm tnewName = renameRulesContext.getFactory().makeString(newName);
      result = rename_rules_0_2.instance.invoke(renameRulesContext, term, toldName, tnewName);
    }
    catch (StrategoExit e) {
      if (e.getValue() != 0 || result == null)
        throw new RuntimeException("Stratego extraction failed", e);
    } finally {
      SugarJContexts.releaseContext(renameRulesContext);
    }
    return result;
  }

}
