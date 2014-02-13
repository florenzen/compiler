package org.sugarj.driver;

import static org.sugarj.common.ATermCommands.fixSDF;
import static org.sugarj.common.ATermCommands.getApplicationSubterm;
import static org.sugarj.common.ATermCommands.isApplication;
import static org.sugarj.common.Log.log;
import static org.sugarj.driver.SDFCommands.extractSDF;
import static org.sugarj.driver.STRCommands.extractEditor;
import static org.sugarj.driver.STRCommands.extractSTR;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.ParseException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.spoofax.interpreter.terms.IStrategoAppl;
import org.spoofax.interpreter.terms.IStrategoList;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.client.ITreeBuilder;
import org.spoofax.jsglr.client.InvalidParseTableException;
import org.spoofax.jsglr.client.ParseTable;
import org.spoofax.jsglr.client.SGLR;
import org.spoofax.jsglr.client.imploder.ImploderAttachment;
import org.spoofax.jsglr.client.imploder.TreeBuilder;
import org.spoofax.jsglr.shared.BadTokenException;
import org.spoofax.jsglr.shared.SGLRException;
import org.spoofax.jsglr.shared.TokenExpectedException;
import org.spoofax.terms.Term;
import org.strategoxt.HybridInterpreter;
import org.strategoxt.lang.StrategoException;
import org.sugarj.AbstractBaseLanguage;
import org.sugarj.AbstractBaseProcessor;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.ATermCommands.PrettyPrintError;
import org.sugarj.common.CommandExecution;
import org.sugarj.common.Environment;
import org.sugarj.common.FileCommands;
import org.sugarj.common.Log;
import org.sugarj.common.StringCommands;
import org.sugarj.common.errors.SourceCodeException;
import org.sugarj.common.errors.SourceLocation;
import org.sugarj.common.path.AbsolutePath;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.caching.ModuleKeyCache;
import org.sugarj.driver.declprovider.SourceToplevelDeclarationProvider;
import org.sugarj.driver.declprovider.TermToplevelDeclarationProvider;
import org.sugarj.driver.declprovider.ToplevelDeclarationProvider;
import org.sugarj.driver.transformations.primitive.SugarJPrimitivesLibrary;
import org.sugarj.stdlib.StdLib;
import org.sugarj.transformations.analysis.AnalysisDataInterop;
import org.sugarj.util.Pair;
import org.sugarj.util.ProcessingListener;
import org.sugarj.util.Renaming;


/**
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class Driver {
  
  private final static int PENDING_TIMEOUT = 30000;

  private static Map<Path, Entry<ToplevelDeclarationProvider, Driver>> pendingRuns = new HashMap<>();
//  private static List<Path> pendingInputFiles = new ArrayList<Path>();
  private static List<ProcessingListener> processingListener = new LinkedList<ProcessingListener>();

  private List<Driver> currentlyProcessing;
  private Set<Set<RelativePath>> circularLinks = new HashSet<>();
  private boolean dependsOnModel = false;

  private IProgressMonitor monitor;
  
  private Environment environment;
  
  private Result driverResult;
  
  private RelativePath depOutFile;

  private Set<RelativePath> sourceFiles;
  private ToplevelDeclarationProvider declProvider;

  private Path currentGrammarSDF;
  private String currentGrammarModule;
  private Path currentTransSTR;
  private String currentTransModule;
  private List<String> availableSDFImports;
  private List<String> availableSTRImports;
  
  private List<IStrategoTerm> sugaredBodyDecls = new ArrayList<IStrategoTerm>();
  private List<IStrategoTerm> desugaredBodyDecls = new ArrayList<IStrategoTerm>();
  
  private IStrategoTerm lastSugaredToplevelDecl;
  
  private SGLR sdfParser;
  private SGLR strParser;
  private SGLR editorServicesParser;
  private SGLR parser;
  
  private SDFCommands sdf;
  private STRCommands str;
  
  /**
   * cache location -> base language -> cache
   */
  private static Map<Path, Map<String,ModuleKeyCache<Path>>> sdfCaches;
  private static Map<Path, Map<String,ModuleKeyCache<Path>>> strCaches;
  
  private ModuleKeyCache<Path> sdfCache;
  private ModuleKeyCache<Path> strCache;
  
  private Path currentGrammarTBL;
  private Path currentTransProg;
  
  private boolean interrupt = false;
  
  private boolean inDesugaredDeclList;
  
  private AbstractBaseLanguage baseLanguage;
  private AbstractBaseProcessor baseProcessor;
  private boolean definesNonBaseDec = false;
  
  private AnalysisDataInterop analysisDataInterop;
  
  
  public Driver(Environment env, AbstractBaseLanguage baseLang, List<Driver> currentlyProcessing) {
    this.environment = env;
    this.baseLanguage = baseLang;
    this.baseProcessor = baseLang.createNewProcessor();
    this.currentlyProcessing = currentlyProcessing;
    this.driverResult = new Result(env.getStamper(), env.doGenerateFiles() ? null : env.getParseBin());
    
    baseProcessor.setInterpreter(new HybridInterpreter());
    HybridInterpreter interp = baseProcessor.getInterpreter();
    
    interp.addOperatorRegistry(new SugarJPrimitivesLibrary(this, environment, driverResult, monitor));
    analysisDataInterop = new AnalysisDataInterop();
    analysisDataInterop.createInteropRegisterer().register(interp.getContext(), interp.getCompiledContext());
    
    try {      
      if (environment.getCacheDir() != null)
        FileCommands.createDir(environment.getCacheDir());
      
      initializeCaches(environment, false);
      sdfCache = selectCache(sdfCaches, baseLang, environment);
      strCache = selectCache(strCaches, baseLang, environment);
    } catch (IOException e) {
      throw new RuntimeException("error while initializing driver", e);
    }
  }  
  
  private static synchronized Entry<ToplevelDeclarationProvider, Driver> getPendingRun(Path file) {
    return pendingRuns.get(file);
  }
  
  private static synchronized void putPendingRun(Path file, ToplevelDeclarationProvider declProvider, Driver driver) {
    pendingRuns.put(file, new AbstractMap.SimpleImmutableEntry<ToplevelDeclarationProvider, Driver>(declProvider, driver));
  }
  
  public static synchronized void addProcessingDoneListener(ProcessingListener listener) {
    processingListener.add(listener);
  }
  
  public static synchronized void removeProcessingDoneListener(ProcessingListener listener) {
    processingListener.remove(listener);
  }
  
  private static void waitForPending(Path file) {
    int count = 0;
    Object lock = new Object();
    synchronized (lock) {
      while (true) {
        synchronized (pendingRuns) {
          if (!pendingRuns.containsKey(file))
            return;
        }
        
        if (count > PENDING_TIMEOUT)
          throw new IllegalStateException("pending result timed out for " + file);
        
        count += 100;
        try {
          lock.wait(100);
        } catch (InterruptedException e) {
        }
      }
    }
  }

  public static Result run(RelativePath sourceFile, Environment env, IProgressMonitor monitor, AbstractBaseLanguage baseLang) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(sourceFile, env, monitor, baseLang, new LinkedList<Driver>());
  }

  public static Result run(RelativePath sourceFile, Environment env, IProgressMonitor monitor, AbstractBaseLanguage baseLang, List<Driver> currentlyProcessing) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(FileCommands.readFileAsString(sourceFile), sourceFile, env, monitor, baseLang, currentlyProcessing);
  }

  public static Result run(String source, RelativePath sourceFile, Environment env, IProgressMonitor monitor, AbstractBaseLanguage baseLang) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(source, sourceFile, env, monitor, baseLang, new LinkedList<Driver>());
  }
  
  public static Result run(String source, RelativePath sourceFile, Environment env, IProgressMonitor monitor, AbstractBaseLanguage baseLang, List<Driver> currentlyProcessing) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    Driver driver = new Driver(env, baseLang, currentlyProcessing);
    return run(driver, new SourceToplevelDeclarationProvider(driver, source, sourceFile), sourceFile, monitor);
  }

  public static Result run(IStrategoTerm source, RelativePath sourceFile, Environment env, IProgressMonitor monitor, AbstractBaseLanguage baseLang, List<Driver> currentlyProcessing) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    Driver driver = new Driver(env, baseLang, currentlyProcessing);
    return run(driver, new TermToplevelDeclarationProvider(source, sourceFile, env), sourceFile, monitor);
  }
  
  private static Result run(Driver driver, ToplevelDeclarationProvider declProvider, RelativePath sourceFile, IProgressMonitor monitor) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    Entry<ToplevelDeclarationProvider, Driver> pending = null;
    String modulePath = FileCommands.dropExtension(sourceFile.getRelativePath());
    
    pending = getPendingRun(sourceFile);
    if (pending != null && !pending.getKey().equals(declProvider) && pending.getValue().environment.doGenerateFiles() == driver.environment.doGenerateFiles()) {
      log.log("interrupting " + sourceFile, Log.CORE);
      pending.getValue().interrupt();
    }

    if (pending == null) {
      Result result = ModuleSystemCommands.locateResult(modulePath, driver.environment);
      
      boolean isUpToDate = result != null && result.isConsistent();
      if (isUpToDate) {
        if (driver.environment.doGenerateFiles() && result.isParseResult()) {
          Log.log.beginTask("Moving result", Log.DETAIL);
          try {
          result = result.moveTo(driver.environment.getBin(), false);
          } finally {
            Log.log.endTask();
          }
        }
        
        if (driver.environment.doGenerateFiles() || result.getSugaredSyntaxTree() != null)
          return result;
      }
    }
    
    if (pending == null)
      putPendingRun(sourceFile, declProvider, driver);
    
    if (pending != null) {
      waitForPending(sourceFile);
      return run(driver, declProvider, sourceFile, monitor);
    }
    
    try {
      ProcessingListener.notifyProcessingStarts(Driver.class, processingListener, sourceFile);
    
      driver.process(declProvider, sourceFile, monitor);
      Driver.storeCaches(driver.environment);
    
      ProcessingListener.notifyProcessingDone(Driver.class, processingListener, driver.driverResult);
      
    } catch (InterruptedException e) {
      // nothing
    } catch (Exception e) {
      org.strategoxt.imp.runtime.Environment.logException(e);
    } finally {
      pendingRuns.remove(sourceFile);
      if (!driver.environment.doGenerateFiles()) {
        Path binDep = driver.environment.createOutPath(modulePath + ".dep");
        driver.driverResult.cacheInMemory(binDep);
      }
    }

    return driver.driverResult;
  }
  
  private void init(ToplevelDeclarationProvider declProvider, RelativePath sourceFile, IProgressMonitor monitor) throws FileNotFoundException, IOException, InvalidParseTableException {
    this.monitor = monitor;
    Path  baseLangPath = new AbsolutePath(baseLanguage.getPluginDirectory().getAbsolutePath());
    if (!environment.getIncludePath().contains(baseLangPath))
      environment.addToIncludePath(baseLangPath);
  
    depOutFile = null;
  
    sourceFiles = new HashSet<>();
    sourceFiles.add(sourceFile);
    
    this.declProvider = declProvider;
    
    currentGrammarSDF = baseLanguage.getInitGrammar();
    currentGrammarModule = baseLanguage.getInitGrammarModuleName();
    
    currentTransSTR = baseLanguage.getInitTrans();
    currentTransModule = baseLanguage.getInitTransModuleName();
    
    // list of imports that contain SDF extensions
    availableSDFImports = new ArrayList<String>();    
    availableSDFImports.add(baseLanguage.getInitGrammarModuleName());
  
    // list of imports that contain Stratego extensions
    availableSTRImports = new ArrayList<String>();
    availableSTRImports.add(baseLanguage.getInitTransModuleName());
  
    sdfParser = new SGLR(new TreeBuilder(), ATermCommands.parseTableManager.loadFromFile(StdLib.sdfTbl.getAbsolutePath()));
    strParser = new SGLR(new TreeBuilder(), ATermCommands.parseTableManager.loadFromFile(StdLib.strategoTbl.getAbsolutePath()));
    editorServicesParser = new SGLR(new TreeBuilder(), ATermCommands.parseTableManager.loadFromFile(StdLib.editorServicesTbl.getAbsolutePath()));
    
    sdf = new SDFCommands(sdfParser, sdfCache, environment);
    str = new STRCommands(strParser, strCache, environment);
  }

  /**
   * Process the given Extensible Java file.
   * 
   * @throws IOException 
   * @throws SGLRException 
   * @throws InvalidParseTableException 
   * @throws ParseException 
   * @throws TokenExpectedException 
   * @throws InterruptedException 
   */
  private void process(ToplevelDeclarationProvider declProvider, RelativePath sourceFile, IProgressMonitor monitor) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    if (sourceFile == null)
      throw new IllegalArgumentException("Required source file argument was null.");
    
    List<Renaming> originalRenamings = new LinkedList<Renaming>(environment.getRenamings());
    currentlyProcessing.add(this);
    
    log.beginTask("processing", "Process " + sourceFile.getRelativePath(), Log.CORE);
    boolean success = false;
    try {
      init(declProvider, sourceFile, monitor);
      driverResult.addSourceArtifact(sourceFile, declProvider.getSourceStamp());
      
      baseProcessor.init(sourceFile.getRelativePath(), environment);

      depOutFile = environment.createOutPath(FileCommands.dropExtension(sourceFile.getRelativePath()) + ".dep");
      // clearGeneratedStuff();

      initEditorServices();

      boolean done = false;
      while (!done) {
        stepped();
        
        // PARSE the next top-level declaration
        lastSugaredToplevelDecl = declProvider.getNextToplevelDecl(true, false);
        
        stepped();
        
        // ANALYZE the parsed top-level declaration
        IStrategoTerm analyzed = currentAnalyze(lastSugaredToplevelDecl);
        
        stepped();
        
        // DESUGAR the analyzed top-level declaration
        IStrategoTerm desugared = currentDesugar(analyzed);
        
        stepped();
        
        // RENAME the desugared top-level declaration
        IStrategoTerm renamed = currentRename(desugared);
        
        stepped();
        
        // PROCESS the assimilated top-level declaration
        processToplevelDeclaration(renamed);

        done = !declProvider.hasNextToplevelDecl();
      }
      
      stepped();
            
      // check final grammar and transformation for errors
      if (!environment.isNoChecking()) {
        checkCurrentGrammar();
      }
      
      stepped();
      
      // need to build current transformation program for editor services
      checkCurrentTransformation();
      
      stepped();
      
      // GENERATE model
      generateModel();
      
      // COMPILE the generated java file
      if (circularLinks.isEmpty())
        compileGeneratedFiles();
      else {
        Result delegate = null;
        for (Driver dr : currentlyProcessing)
          if (circularLinks.contains(dr.sourceFiles)) {
            delegate = dr.driverResult;
            break;
          }
        if (delegate != null)
          driverResult.delegateCompilation(delegate, baseProcessor.getGeneratedSourceFile(), baseProcessor.getGeneratedSource(), definesNonBaseDec);
        else if (!dependsOnModel)
          throw new IllegalStateException("Could not delegate compilation of circular dependency to other compiler instance.");
      }
        
      driverResult.setSugaredSyntaxTree(makeSugaredSyntaxTree());
      driverResult.setDesugaredSyntaxTree(makeDesugaredSyntaxTree());
      
      if (currentGrammarTBL != null)
        driverResult.registerParseTable(currentGrammarTBL);
      
      if (currentTransProg != null) {
        driverResult.addEditorService(
            ATermCommands.atermFromString(
              "Builders(\"sugarj checking\", [SemanticObserver(Strategy(\"sugarj-analyze\"))])"));
        driverResult.registerEditorDesugarings(currentTransProg);
      }

     driverResult.write(depOutFile);

      success = true;
    } 
    finally {
      log.endTask(success, "done processing " + sourceFile, "failed to process " + sourceFile);
      driverResult.setFailed(!success);
      currentlyProcessing.remove(this);
      environment.setRenamings(originalRenamings);
    }
  }

  private void compileGeneratedFiles() throws IOException {
    boolean good = false;
    log.beginTask("compilation", "COMPILE generated " + baseProcessor.getLanguage().getLanguageName() + " files", Log.CORE);
    try {
      try {
        Set<Path> generatedFiles = 
          baseProcessor.compile(
              baseProcessor.getGeneratedSourceFile(), 
              baseProcessor.getGeneratedSource(),
              environment.getBin(), 
              new ArrayList<Path>(environment.getIncludePath()), 
              driverResult.getDeferredSourceFiles());
        for (Path file : generatedFiles)
          driverResult.addGeneratedFile(file);
      } catch (ClassNotFoundException e) {
        setErrorMessage("Could not resolve imported class " + e.getMessage());
      } catch (SourceCodeException e) {
        for (Pair<SourceLocation, String> err : e.getErrors())
          setErrorMessage(err.b + " lines " + err.a.lineStart + "-" + err.a.lineEnd
                                + " columns " + err.a.columnStart + "-" + err.a.columnEnd);
      }
      good = true;
    } finally {
      log.endTask(good);
    }
  }

  private void processToplevelDeclaration(IStrategoTerm toplevelDecl) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException {
    try {
      if (baseLanguage.isImportDecl(toplevelDecl) || baseLanguage.isTransformationApplication(toplevelDecl)) {
        if (inDesugaredDeclList || !environment.isAtomicImportParsing())
          processImportDec(toplevelDecl);
        else
          processImportDecs(toplevelDecl);
      } 
      else if (baseLanguage.isBaseDecl(toplevelDecl)) {
        List<String> additionalModules = processLanguageDec(toplevelDecl);
        for (String module : additionalModules) {
          prepareImport(toplevelDecl, module);
          Path clazz = ModuleSystemCommands.importBinFile(module, environment, baseProcessor, driverResult);
          if (clazz == null)
            setErrorMessage(toplevelDecl, "Could not resolve required module " + module);
        }
      }
      else if (baseLanguage.isExtensionDecl(toplevelDecl))
        processExtensionDec(toplevelDecl);
      else if (baseLanguage.isPlainDecl(toplevelDecl)) // XXX: Decide what to do
                                                      // with "Plain"--leave in
                                                      // the language or create
                                                      // a new "Plain" language
        processPlainDec(toplevelDecl);
      else if (baseLanguage.isTransformationDec(toplevelDecl))
        processTransformationDec(toplevelDecl);
      else if (baseLanguage.isModelDec(toplevelDecl))
        processModelDec(toplevelDecl);
      else if (ATermCommands.isList(toplevelDecl)) {
        /*
         * Desugarings may generate lists of toplevel declarations.
         */
        List<IStrategoTerm> list = ATermCommands.getList(toplevelDecl);
        // sortForImports(list);

        boolean old = inDesugaredDeclList;
        inDesugaredDeclList = true;

        try {
          for (IStrategoTerm term : list)
            processToplevelDeclaration(term);
        } finally {
          inDesugaredDeclList = old;
        }
      } 
      else if (ATermCommands.isString(toplevelDecl)) {
        if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
          sugaredBodyDecls.add(lastSugaredToplevelDecl);
        if (!desugaredBodyDecls.contains(toplevelDecl))
          desugaredBodyDecls.add(toplevelDecl);

      } 
      else
        throw new IllegalArgumentException("unexpected toplevel declaration, desugaring probably failed: " + toplevelDecl.toString(20));
    } catch (Exception e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();

      if (!(e instanceof StrategoException))
        e.printStackTrace();

      setErrorMessage(lastSugaredToplevelDecl, msg);
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
    }
  }

  private void processEditorServices(String extName, IStrategoTerm services) throws IOException {
    if (!ATermCommands.isList(services))
      throw new IllegalStateException("editor services are not a list: " + services);
    
    RelativePath editorServicesFile = environment.createOutPath(baseProcessor.getRelativeNamespaceSep() + extName + ".serv");
    List<IStrategoTerm> editorServices = ATermCommands.getList(services);
    
    log.log("writing editor services to " + editorServicesFile, Log.DETAIL);
    
    StringBuffer buf = new StringBuffer();
    
    for (IStrategoTerm service : driverResult.getEditorServices())
      buf.append(service).append('\n');
    
    for (IStrategoTerm service : editorServices) {
      driverResult.addEditorService(service);
      buf.append(service).append('\n');
    }
    
    driverResult.generateFile(editorServicesFile, buf.toString());
  }
  
  private void processPlainDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask("processing", "PROCESS plain declaration.", Log.CORE);
    try {
      definesNonBaseDec = true;
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      if (!desugaredBodyDecls.contains(toplevelDecl))
        desugaredBodyDecls.add(toplevelDecl);


      IStrategoTerm head = getApplicationSubterm(toplevelDecl, "PlainDec", 0);
      IStrategoTerm body= getApplicationSubterm(toplevelDecl, "PlainDec", 1);
      
      String extName = ATermCommands.getString(getApplicationSubterm(head, "PlainDecHead", 1));
      checkModuleName(extName, toplevelDecl);

      String extension = null;
      if (head.getSubtermCount() >= 3 && isApplication(getApplicationSubterm(head, "PlainDecHead", 2), "Some"))
        extension = Term.asJavaString(getApplicationSubterm(getApplicationSubterm(head, "PlainDecHead", 2), "Some", 0));    

      String fullExtName = getFullRenamedDeclarationName(extName);
      fullExtName = fullExtName + (extension == null ? "" : ("." + extension));
      
      log.log("The name is '" + extName + "'.", Log.DETAIL);
      log.log("The full name is '" + fullExtName + "'.", Log.DETAIL);

      if (dependsOnModel)
        return;
      
      String plainContent = Term.asJavaString(ATermCommands.getApplicationSubterm(body, "PlainBody", 0));
      
      String ext = extension == null ? "" : ("." + extension);
      RelativePath plainFile = environment.createOutPath(baseProcessor.getRelativeNamespaceSep() + extName + ext);
      FileCommands.createFile(plainFile);

      log.log("writing plain content to " + plainFile, Log.DETAIL);
      driverResult.generateFile(plainFile, plainContent);
    } finally {
      log.endTask();
    }
  }
  
  
  public Pair<IStrategoTerm, Integer> currentParse(String remainingInput, ITreeBuilder treeBuilder, boolean recovery) throws IOException, InvalidParseTableException, TokenExpectedException, SGLRException {
    
    currentGrammarTBL = sdf.compile(currentGrammarSDF, currentGrammarModule, driverResult.getTransitivelyAffectedFiles(), baseLanguage.getPackagedGrammars(), baseLanguage.getPluginDirectory());

    ParseTable table = ATermCommands.parseTableManager.loadFromFile(currentGrammarTBL.getAbsolutePath());
    
    Pair<SGLR, Pair<IStrategoTerm, Integer>> parseResult = null;

    // read next toplevel decl and stop if that fails
    try {
      parseResult = SDFCommands.parseImplode(
          table,
          remainingInput,
          StringCommands.printListSeparated(sourceFiles, "&"),
          "ToplevelDeclaration",
          recovery,
          true,
          treeBuilder);
//    } catch (SGLRException e) {
//      this.parser = e.getParser();
//      log.logErr(e.getMessage(), Log.DETAIL);
//      return null;
    } finally {
      if (parseResult != null)
        this.parser = parseResult.a;
      
      if (recovery && parser != null) {
        for (BadTokenException e : parser.getCollectedErrors())
          driverResult.logParseError(e);
      }
    }
    
    return parseResult.b;
  }

  private IStrategoTerm currentAnalyze(IStrategoTerm term) throws IOException, InvalidParseTableException, TokenExpectedException, SGLRException {
  // assimilate toplevelDec using current transformation
  
    log.beginTask("analyze", "ANALYZE toplevel declaration.", Log.CORE);
    try {
      currentTransProg = str.compile(currentTransSTR, "main", driverResult.getTransitivelyAffectedFiles(), baseLanguage.getPluginDirectory());
    
      return STRCommands.assimilate("analyze-main", currentTransProg, term, baseProcessor.getInterpreter());
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
      
      log.logErr(msg, Log.DETAIL);
      setErrorMessage(msg);
      return term;
    } finally {
      log.endTask();
    }
  }

  private IStrategoTerm currentDesugar(IStrategoTerm term) throws IOException,
      InvalidParseTableException, TokenExpectedException, SGLRException {
    // assimilate toplevelDec using current transformation

    log.beginTask("desugaring", "DESUGAR toplevel declaration.", Log.CORE);
    try {
      currentTransProg = str.compile(currentTransSTR, "main", driverResult.getTransitivelyAffectedFiles(), baseLanguage.getPluginDirectory());

      return STRCommands.assimilate(currentTransProg, term, baseProcessor.getInterpreter());
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
      
      log.logErr(msg, Log.DETAIL);
      setErrorMessage(msg);
      return term;
    } finally {
      log.endTask();
    }
  }

  /**
   * Apply current renamings stored in environment to the given term.
   */
  public IStrategoTerm currentRename(IStrategoTerm term) throws IOException, InvalidParseTableException, TokenExpectedException, SGLRException {
    try {
      if (currentTransProg == null)
        return term;
      
      IStrategoTerm result = STRCommands.assimilate("apply-renamings", currentTransProg, term, baseProcessor.getInterpreter());
      return result == null ? term : result;
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();

      log.logErr(msg, Log.DETAIL);
      setErrorMessage(msg);
      return term;
    }
  }

  
  private void processImportDecs(IStrategoTerm toplevelDecl) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException {
    List<IStrategoTerm> pendingImports = new ArrayList<IStrategoTerm>();
    pendingImports.add(toplevelDecl);
    
    while (declProvider.hasNextToplevelDecl()) {
      IStrategoTerm term = null;
      
      try {
        log.beginSilent();
        term = declProvider.getNextToplevelDecl(false, true);
      }
      catch (Throwable t) {
        term = null;
      }
      finally {         
        log.endSilent(); 
      }
    
      if (term != null && (baseLanguage.isImportDecl(term) || baseLanguage.isTransformationApplication(term)))
        pendingImports.add(term);
      else {
        declProvider.retract(term);
        break;
      }
    }
    
    for (IStrategoTerm pendingImport : pendingImports) {
      lastSugaredToplevelDecl = pendingImport;
      processImportDec(pendingImport);
    }
  }

  private void processImportDec(IStrategoTerm toplevelDecl) {
    
    if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
      sugaredBodyDecls.add(lastSugaredToplevelDecl);
    if (!desugaredBodyDecls.contains(toplevelDecl))
      desugaredBodyDecls.add(toplevelDecl);
    
    log.beginTask("processing", "PROCESS import declaration.", Log.CORE);
    try {
      String modulePath;
      boolean isCircularImport;
      if (!baseLanguage.isTransformationApplication(toplevelDecl)) {
        modulePath = baseProcessor.getModulePathOfImport(toplevelDecl);
        
        isCircularImport = prepareImport(toplevelDecl, modulePath);
        
        String localModelName = baseProcessor.getImportLocalName(toplevelDecl);
        
        if (localModelName != null)
          environment.getRenamings().add(0, new Renaming(Collections.<String>emptyList(), localModelName, FileCommands.fileName(modulePath)));
      } else {
        IStrategoTerm appl = baseLanguage.getTransformationApplication(toplevelDecl);
        IStrategoTerm model = getApplicationSubterm(appl, "TransApp", 1);
        IStrategoTerm transformation = getApplicationSubterm(appl, "TransApp", 0);
        
        ImportCommands imp = new ImportCommands(baseProcessor, environment, this, driverResult, str);
        Pair<String, Boolean> transformationResult = imp.transformModel(model, transformation, toplevelDecl);

        if (transformationResult == null)
          return ;
        
        modulePath = transformationResult.a;
        isCircularImport = transformationResult.b;
        
        String localModelName = baseProcessor.getImportLocalName(toplevelDecl);
        
        if (localModelName != null)
          environment.getRenamings().add(0, new Renaming(Collections.<String>emptyList(), localModelName, FileCommands.fileName(modulePath)));
        else
          environment.getRenamings().add(0, new Renaming(ImportCommands.getTransformationApplicationModelPath(appl, baseProcessor), modulePath));
        
        IStrategoTerm reconstructedImport = baseProcessor.reconstructImport(modulePath, toplevelDecl);
        desugaredBodyDecls.remove(toplevelDecl);
        desugaredBodyDecls.add(reconstructedImport);
        toplevelDecl = reconstructedImport;
      }
      
      if (isCircularImport)
        return;
      boolean codeImportSuccess = processImport(modulePath, toplevelDecl);
      boolean modelImportSuccess = processModelImport(modulePath);
      if (modelImportSuccess && !codeImportSuccess)
        dependsOnModel = true;
      boolean success = codeImportSuccess || modelImportSuccess;
      
      if (!success)
        setErrorMessage("module not found: " + modulePath);
      
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      log.endTask();
    }
  }

  /**
   * Prepare import:
   *  - locate pre-existing result and/or source file
   *  - determine whether the import is circular
   *  - initiate subcompilation of imported source file if necessary
   *  - add appropriate dependencies to driverResult 
   * 
   * @param toplevelDecl
   * @param modulePath
   * @return true iff the import is circular.
   * @throws IOException
   * @throws InterruptedException 
   * @throws SGLRException 
   * @throws InvalidParseTableException 
   * @throws ParseException 
   * @throws TokenExpectedException 
   */
  protected boolean prepareImport(IStrategoTerm toplevelDecl, String modulePath) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    boolean isCircularImport = false;
    
    if (!modulePath.startsWith("org/sugarj")) { // module is not in sugarj standard library
      Result res = ModuleSystemCommands.locateResult(modulePath, environment);
      Set<RelativePath> importSourceFiles;
      if (res != null && res.getSourceArtifacts().isEmpty())
        importSourceFiles = res.getSourceArtifacts();
      else {
        importSourceFiles = new HashSet<>();
        RelativePath importSourceFile = ModuleSystemCommands.locateSourceFileOrModel(modulePath, environment.getSourcePath(), baseProcessor, environment);
        if (importSourceFile != null)
          importSourceFiles.add(importSourceFile);
      }

      boolean sourceFileAvailable = !importSourceFiles.isEmpty();
      boolean requiresUpdate = res == null ||
//                               !Collections.disjoint(pendingInputFiles, res.getSourceArtifacts()) ||
                               !res.isConsistent() || 
                               environment.doGenerateFiles() && res.isParseResult();
      
      if (sourceFileAvailable && requiresUpdate && getCircularImportResult(importSourceFiles) != null) {
        // Circular import. Assume source file does not provide syntactic sugar.
        log.log("Circular import detected: " + modulePath + ".", Log.IMPORT);
        baseProcessor.processModuleImport(toplevelDecl);
        isCircularImport = true;
        circularLinks.add(importSourceFiles);
      }
      else if (sourceFileAvailable && requiresUpdate) {
        // Required module needs recompilation.
        log.log("Need to compile imported module " + modulePath + " first.", Log.IMPORT);
        
        // FIXME
        assert importSourceFiles.size() == 1 : "Cannot yet pass multiple source files as input to compiler, need's fixing.";
        res = subcompile(toplevelDecl, importSourceFiles.iterator().next());
        if (res == null || res.hasFailed())
          setErrorMessage("Problems while compiling " + modulePath);
          
        log.log("CONTINUE PROCESSING'" + sourceFiles + "'.", Log.CORE);
      }
      
      if (isCircularImport) {
        driverResult.addCircularModuleDependency(getCircularImportResult(importSourceFiles));
      }
      
      if (!isCircularImport && res != null) {
        if (res.getPersistentPath() == null || res.hasPersistentVersionChanged())
          setErrorMessage("Result is inconsitent with persistent version.");
        driverResult.addModuleDependency(res);
      }
      
      if (!isCircularImport && !importSourceFiles.isEmpty())
        // if importSourceFile is delegated to something currently being processed
        for (Driver dr : currentlyProcessing)
          if (dr.driverResult.isDelegateOf(importSourceFiles)) {
            baseProcessor.processModuleImport(toplevelDecl);
            isCircularImport = true;
            
            if (dr != this)
              circularLinks.add(dr.sourceFiles);
            
            break;
          }
    }
    
    return isCircularImport;
  }
  
  /**
   * Checks if the given source file is a circular import.
   * Checks the ongoing driver runs to determine whether the source file in turn imports the current source file.
   * 
   * @return null if the import is not circular. The path to the imported file's driver result otherwise.
   */
  private Result getCircularImportResult(Set<RelativePath> importSourceFiles) {
    for (Driver dr : currentlyProcessing)
      if (!Collections.disjoint(dr.sourceFiles, importSourceFiles))
        return dr.driverResult;
    
    return null;
  }

  /**
   * Subcompile source file.
   * @param toplevelDecl
   * @param importSourceFile
   * @return
   * @throws InterruptedException
   */
  public Result subcompile(IStrategoTerm toplevelDecl, RelativePath importSourceFile) throws InterruptedException {
    try {
      Result result;
      if ("model".equals(FileCommands.getExtension(importSourceFile))) {
        IStrategoTerm term = ATermCommands.atermFromFile(importSourceFile.getAbsolutePath());
        result = run(term, importSourceFile, environment, monitor, baseProcessor.getLanguage(), currentlyProcessing);
      }
      else
        result = run(importSourceFile, environment, monitor, baseProcessor.getLanguage(), currentlyProcessing);
      if (result.isParseResult())
        environment.addToIncludePath(result.getParseResultPath());
      return result;
    } catch (IOException e) {
      setErrorMessage("Problems while compiling " + importSourceFile);
    } catch (TokenExpectedException e) {
      setErrorMessage("Problems while compiling " + importSourceFile);
    } catch (ParseException e) {
      setErrorMessage("Problems while compiling " + importSourceFile);
    } catch (InvalidParseTableException e) {
      setErrorMessage("Problems while compiling " + importSourceFile);
    } catch (SGLRException e) {
      setErrorMessage("Problems while compiling " + importSourceFile);
    }
    return null;
  }
  
  private boolean processImport(String modulePath, IStrategoTerm importTerm) throws IOException {
    boolean success = false;
    
    Path clazz = ModuleSystemCommands.importBinFile(modulePath, environment, baseProcessor, driverResult);
    if (clazz != null || baseProcessor.isModuleExternallyResolvable(modulePath)) {
      success = true;
      baseProcessor.processModuleImport(importTerm);
    }

    Path sdf = ModuleSystemCommands.importSdf(modulePath, environment, driverResult);
    if (sdf != null) {
      success = true;
      availableSDFImports.add(modulePath);
      buildCompoundSdfModule();
    }
    
    Path str = ModuleSystemCommands.importStratego(modulePath, environment, driverResult);
    if (str != null) {
      success = true;
      availableSTRImports.add(modulePath);
      buildCompoundStrModule();
    }
    
    success |= ModuleSystemCommands.importEditorServices(modulePath, environment, driverResult);
    
    return success;
  }
  
  private boolean processModelImport(String modulePath) throws IOException {
    RelativePath model = ModuleSystemCommands.importModel(modulePath, environment, driverResult);
    if (model != null) {
//      availableModels.add(model);
      return true;
    }
    
    return false;
  }

  private List<String> processLanguageDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask("processing", "PROCESS " + baseProcessor.getLanguage().getLanguageName() + " declaration: " + ((toplevelDecl instanceof IStrategoAppl) ? ((IStrategoAppl) toplevelDecl).getName() : toplevelDecl.toString(0)), Log.CORE);
    try {
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      if (!desugaredBodyDecls.contains(toplevelDecl))
        desugaredBodyDecls.add(toplevelDecl);
      
      if (dependsOnModel)
        return Collections.emptyList();
      
      return baseProcessor.processBaseDecl(toplevelDecl);
    } finally {
      log.endTask();
    }
  }

  private void processExtensionDec(IStrategoTerm toplevelDecl) throws IOException, InvalidParseTableException, TokenExpectedException, SGLRException {
    log.beginTask("processing", "PROCESS sugar declaration.", Log.CORE);
    try {
      definesNonBaseDec = true;
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      if (!desugaredBodyDecls.contains(toplevelDecl))
        desugaredBodyDecls.add(toplevelDecl);

      String extName = baseProcessor.getExtensionName(toplevelDecl);
      String fullExtName = getFullRenamedDeclarationName(extName);
      checkModuleName(extName, toplevelDecl);

      log.log("The name of the sugar is '" + extName + "'.", Log.DETAIL);
      log.log("The full name of the sugar is '" + fullExtName + "'.", Log.DETAIL);
      
      if (dependsOnModel)
        return;
      
      RelativePath sdfExtension = environment.createOutPath(baseProcessor.getRelativeNamespaceSep() + extName + ".sdf");
      RelativePath strExtension = environment.createOutPath(baseProcessor.getRelativeNamespaceSep() + extName + ".str");
      
      String sdfImports = " imports " + StringCommands.printListSeparated(availableSDFImports, " ") + "\n";
      String strImports = " imports " + StringCommands.printListSeparated(availableSTRImports, " ") + "\n";
      
      // this is a list of SDF and Stratego statements
      
      IStrategoTerm extensionBody = baseProcessor.getExtensionBody(toplevelDecl);

      IStrategoTerm sdfExtract = fixSDF(extractSDF(extensionBody), baseProcessor.getInterpreter());
      IStrategoTerm strExtract = extractSTR(extensionBody);
      IStrategoTerm editorExtract = extractEditor(extensionBody);
      
      String sdfExtensionHead =
        "module " + fullExtName + "\n" 
        + sdfImports
        + "exports " + "\n"
        + "  (/)" + "\n";

      String sdfExtensionContent = SDFCommands.prettyPrintSDF(sdfExtract, baseProcessor.getInterpreter());

      String sdfSource = SDFCommands.makePermissiveSdf(sdfExtensionHead + sdfExtensionContent);
      driverResult.generateFile(sdfExtension, sdfSource);
      availableSDFImports.add(fullExtName);
      
      if (CommandExecution.FULL_COMMAND_LINE)
        log.log("Wrote SDF file to '" + sdfExtension.getAbsolutePath() + "'.", Log.DETAIL);
      
      String strExtensionTerm = "Module(" + "\"" + fullExtName+ "\"" + ", " + strExtract + ")" + "\n";
//      try {
//        strExtensionTerm = STRCommands.assimilate("strip-annos", currentTransProg, strExtensionTerm, langLib.getInterpreter());
//      } catch (Exception e) {
//        e.printStackTrace();
//      }
//      String strExtensionContent = SDFCommands.prettyPrintSTR(strExtensionTerm, langLib.getInterpreter());
      String strExtensionContent = SDFCommands.prettyPrintSTR(ATermCommands.atermFromString(strExtensionTerm), baseProcessor.getInterpreter());
      
      int index = strExtensionContent.indexOf('\n');
      if (index >= 0)
        strExtensionContent =
          strExtensionContent.substring(0, index + 1) + "\n"
          + strImports + "\n"
          + strExtensionContent.substring(index + 1);
      else
        strExtensionContent += strImports;
        
      
      driverResult.generateFile(strExtension, strExtensionContent);
      availableSTRImports.add(fullExtName);
      
      if (CommandExecution.FULL_COMMAND_LINE)
        log.log("Wrote Stratego file to '" + strExtension.getAbsolutePath() + "'.", Log.DETAIL);
      
      processEditorServices(extName, editorExtract);
      
      /*
       * adapt current grammar
       */
      if (FileCommands.exists(sdfExtension)) {
        buildCompoundSdfModule();
      }

      /*
       * adapt current transformation
       */
      if (FileCommands.exists(strExtension))
        buildCompoundStrModule();

    } catch (PrettyPrintError e) {
      setErrorMessage(e.getMsg());
    } finally {
      log.endTask();
    }
  }
  
  private void processTransformationDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask("processing", "PROCESS transformation declaration.", Log.CORE);
    try {
      definesNonBaseDec = true;
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      if (!desugaredBodyDecls.contains(toplevelDecl))
        desugaredBodyDecls.add(toplevelDecl);

      String extName = baseLanguage.getTransformationName(toplevelDecl);
      String fullExtName = getFullRenamedDeclarationName(extName);
      checkModuleName(extName, toplevelDecl);
      
      RelativePath strExtension = environment.createOutPath(baseProcessor.getRelativeNamespaceSep() + extName + ".str");
      IStrategoTerm transBody = baseLanguage.getTransformationBody(toplevelDecl);
      if (isApplication(transBody, "TransformationDef")) 
        transBody = ATermCommands.factory.makeListCons(ATermCommands.makeAppl("Rules", "Rules", 1, transBody.getSubterm(0)), (IStrategoList) transBody.getSubterm(1));
      
      log.log("The name of the transformation is '" + extName + "'.", Log.DETAIL);
      log.log("The full name of the transformation is '" + fullExtName + "'.", Log.DETAIL);
      
      if (dependsOnModel)
        return;
      
      String qualifiedMain = "main-" + fullExtName.replace('/', '_');
      IStrategoTerm renamedTransBody = STRCommands.renameRules(transBody, "main", qualifiedMain);
      
      String strImports = " imports " + StringCommands.printListSeparated(availableSTRImports, " ") + "\n";
      String strExtensionTerm = "Module(" + "\"" + fullExtName+ "\"" + ", " + renamedTransBody + ")" + "\n";
      String strExtensionContent = SDFCommands.prettyPrintSTR(ATermCommands.atermFromString(strExtensionTerm), baseProcessor.getInterpreter());
      
      int index = strExtensionContent.indexOf('\n');
      if (index >= 0)
        strExtensionContent =
          strExtensionContent.substring(0, index + 1) + "\n"
          + strImports + "\n"
          + strExtensionContent.substring(index + 1);
      else
        strExtensionContent += strImports;
            
      driverResult.generateFile(strExtension, strExtensionContent);
      availableSTRImports.add(fullExtName);
      
      log.log("Wrote Stratego file to '" + strExtension.getAbsolutePath() + "'.", Log.DETAIL);
      
      /*
       * adapt current transformation
       */
      if (FileCommands.exists(strExtension))
        buildCompoundStrModule();

    } finally {
      log.endTask();
    }
  }
  
  private String getFullRenamedDeclarationName(String declName) {
    String fullExtName = baseProcessor.getRelativeNamespaceSep() + declName;
    
//    for (Renaming ren : environment.getRenamings())
//      fullExtName = StringCommands.rename(fullExtName, ren);

//    fullExtName = fullExtName.replace("$", "-");
    return fullExtName;
  }
  
  private void processModelDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask("processing", "PROCESS model declaration.", Log.CORE);
    try {
      definesNonBaseDec = true;
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      if (!desugaredBodyDecls.contains(toplevelDecl))
        desugaredBodyDecls.add(toplevelDecl);
  
      String modelName = baseLanguage.getModelName(toplevelDecl);
//      String fullModelName = getFullRenamedDeclarationName(modelName);
      checkModuleName(modelName, toplevelDecl);
  
      log.log("The name of the model is '" + modelName + "'.", Log.DETAIL);
//      checkToplevelDeclarationName(modelName.replace("-", "$"), "model", toplevelDecl);
    } finally {
      log.endTask();
    }
  }
  
  private void generateModel() throws IOException {
    log.beginTask("Generate model.", Log.DETAIL);
    try {
      String moduleName = FileCommands.dropExtension(depOutFile.getRelativePath());
      RelativePath modelOutFile = environment.createOutPath(moduleName + ".model");
      
      IStrategoTerm modelTerm = makeDesugaredSyntaxTree();
      String string = ATermCommands.atermToString(modelTerm);
      driverResult.generateFile(modelOutFile, string);
      
      if (sourceFiles.contains(modelOutFile))
        driverResult.addSourceArtifact(modelOutFile);
    } finally {
      log.endTask();
    }
  }
  
  private void buildCompoundSdfModule() throws IOException {
    FileCommands.deleteTempFiles(currentGrammarSDF);
    currentGrammarSDF = FileCommands.newTempFile("sdf");
    currentGrammarModule = FileCommands.fileName(currentGrammarSDF);
    StringBuilder builder = new StringBuilder();
    builder.append("module ").append(currentGrammarModule).append("\n");
    builder.append("imports ");
    for (String m : availableSDFImports)
      builder.append(m).append(" ");
    
    FileCommands.writeToFile(currentGrammarSDF, builder.toString());
  }
  
  private void buildCompoundStrModule() throws IOException {
    FileCommands.deleteTempFiles(currentTransSTR);
    currentTransSTR = FileCommands.newTempFile("str");
    currentTransModule = FileCommands.fileName(currentTransSTR);
    StringBuilder builder = new StringBuilder();
    builder.append("module ").append(currentTransModule).append("\n");
    builder.append("imports ");
    for (String m : availableSTRImports)
      builder.append(m).append(" ");
    
    FileCommands.writeToFile(currentTransSTR, builder.toString());
  }

  private void checkCurrentGrammar() throws IOException, InvalidParseTableException, TokenExpectedException, SGLRException {
    log.beginTask("checking grammar", "CHECK current grammar", Log.CORE);
    
    try {
      sdf.compile(currentGrammarSDF, currentGrammarModule, driverResult.getTransitivelyAffectedFiles(), baseLanguage.getPackagedGrammars(), baseLanguage.getPluginDirectory());
    } finally {
      log.endTask();
    }
  }
  
  private void checkCurrentTransformation() throws TokenExpectedException, IOException, InvalidParseTableException, SGLRException {
    log.beginTask("checking transformation", "CHECK current transformation", Log.CORE);
    
    try {
      currentTransProg = str.compile(currentTransSTR, "main", driverResult.getTransitivelyAffectedFiles(), baseLanguage.getPluginDirectory());
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
      log.logErr(msg, Log.DETAIL);
      setErrorMessage(msg);
    } finally {
      log.endTask();
    }
  }
    
  private void checkModuleName(String decName, IStrategoTerm toplevelDecl) {
    String expectedDecName = FileCommands.fileName(baseProcessor.getGeneratedSourceFile());
    if (expectedDecName != null && !expectedDecName.equals(decName))
      setErrorMessage(lastSugaredToplevelDecl, "Declaration name " + decName + " does not match file name " + expectedDecName);
  }

  private void initEditorServices() throws IOException, TokenExpectedException, SGLRException, InterruptedException {
    List<IStrategoTerm> stdServices = parseEditorServiceFile(StdLib.stdEditor);
    for (IStrategoTerm service : stdServices)
      driverResult.addEditorService(service);
    
    List<IStrategoTerm> baseServices = parseEditorServiceFile(baseLanguage.getInitEditor());
    for (IStrategoTerm service : baseServices)
      driverResult.addEditorService(service);
  }
  
  private List<IStrategoTerm> parseEditorServiceFile(Path editorFile) throws TokenExpectedException, BadTokenException, org.spoofax.jsglr.client.ParseException, SGLRException, InterruptedException, IOException {
    IStrategoTerm initEditor = (IStrategoTerm) editorServicesParser.parse(FileCommands.readFileAsString(editorFile), editorFile.getAbsolutePath(), "Module");

    IStrategoTerm services = ATermCommands.getApplicationSubterm(initEditor, "Module", 2);
    
    if (!ATermCommands.isList(services))
      throw new IllegalStateException("initial editor ill-formed");
    
    return ATermCommands.getList(services);
  }
  
  @SuppressWarnings("unchecked")
  private static synchronized void initializeCaches(Environment environment, boolean force) throws IOException {
    if (environment.getCacheDir() == null)
      return;
    
    Path stdlibVersion = environment.createCachePath("version");
    if (!stdlibVersion.getFile().exists() || !FileCommands.readFileAsString(stdlibVersion).equals(StdLib.VERSION)) {
      for (File f : environment.getCacheDir().getFile().listFiles())
        f.delete();
      FileCommands.writeToFile(stdlibVersion, StdLib.VERSION);
    }
    
    Path sdfCachePath = environment.createCachePath("sdfCaches");
    Path strCachePath = environment.createCachePath("strCaches");
    
    if (sdfCaches == null || force)
      sdfCaches = new HashMap<Path, Map<String,ModuleKeyCache<Path>>>();
    if (strCaches == null || force)
      strCaches = new HashMap<Path, Map<String, ModuleKeyCache<Path>>>();
    
    ObjectInputStream sdfIn = null;
    ObjectInputStream strIn = null;
    try{
      sdfIn = new ObjectInputStream(new FileInputStream(sdfCachePath.getFile()));
      if (!sdfCaches.containsKey(environment.getCacheDir())) {
        Map<String, ModuleKeyCache<Path>> sdfLocalCaches = (Map<String, ModuleKeyCache<Path>>) sdfIn.readObject();
        sdfCaches.put(environment.getCacheDir(), sdfLocalCaches);
      }
      strIn = new ObjectInputStream(new FileInputStream(strCachePath.getFile()));
      if (!strCaches.containsKey(environment.getCacheDir())) {
        Map<String, ModuleKeyCache<Path>> strLocalCaches = (Map<String, ModuleKeyCache<Path>>) strIn.readObject();
        strCaches.put(environment.getCacheDir(), strLocalCaches);
      }
    } catch (Exception e) {
      sdfCaches.put(environment.getCacheDir(), new HashMap<String, ModuleKeyCache<Path>>());
      strCaches.put(environment.getCacheDir(), new HashMap<String, ModuleKeyCache<Path>>());
      for (File f : environment.getCacheDir().getFile().listFiles())
        f.delete();
    } finally {
      if (sdfIn != null)
        sdfIn.close();
      if (strIn != null)
        strIn.close();
    }
  }

  private static ModuleKeyCache<Path> selectCache(Map<Path, Map<String, ModuleKeyCache<Path>>> caches, AbstractBaseLanguage baseLang, Environment environment) throws IOException {
    if (caches == null)
      return null;
    synchronized (caches) {
      ModuleKeyCache<Path> cache = caches.get(environment.getCacheDir()).get(baseLang.getLanguageName());
      Path versionPath = environment.createCachePath(baseLang.getLanguageName() + ".version");
      if (cache != null &&
          (!FileCommands.exists(versionPath) || !baseLang.getVersion().equals(FileCommands.readFileAsString(versionPath))))
        cache = null;
      if (cache == null) {
        cache = new ModuleKeyCache<Path>(caches);
        FileCommands.writeToFile(versionPath, baseLang.getVersion());
        caches.get(environment.getCacheDir()).put(baseLang.getLanguageName(), cache);
      }
      return cache;
    }
  }
  
//TODO is this needed?
//  private static ModuleKeyCache<Path> reallocate(ModuleKeyCache<Path> cache, Environment env) {
//    ModuleKeyCache<Path> res = new ModuleKeyCache<Path>();
//    
//    for (Entry<ModuleKey, Path> e : cache.entrySet()) {
//      Map<Path, Integer> imports = new HashMap<Path, Integer>();
//      for (Entry<Path, Integer> e2 : e.getKey().imports.entrySet())
//        imports.put(Path.reallocate(e2.getKey(), env), e2.getValue());
//      
//      res.put(new ModuleKey(imports, e.getKey().body), Path.reallocate(e.getValue(), env));
//    }
//    
//    return res;
//  }


  private static synchronized void storeCaches(Environment environment) throws IOException {
    if (environment.getCacheDir() == null)
      return;
    
    Path cacheVersion = environment.createCachePath("version");
    FileCommands.writeToFile(cacheVersion, StdLib.VERSION);
    
    Path sdfCachePath = environment.createCachePath("sdfCaches");
    Path strCachePath = environment.createCachePath("strCaches");

    if (!sdfCachePath.getFile().exists())
      FileCommands.createFile(sdfCachePath);

    if (!strCachePath.getFile().exists())
      FileCommands.createFile(strCachePath);
    
    if (sdfCaches != null) {
      ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(sdfCachePath.getFile()));
      try {
        oos.writeObject(sdfCaches.get(environment.getCacheDir()));
      } finally {
        oos.close();
      }
    }
    
    if (strCaches != null) {
      ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(strCachePath.getFile()));
      try {
        oos.writeObject(strCaches.get(environment.getCacheDir()));
      } finally {
        oos.close();
      }
    }
  }


  
  /**
   * @return the non-desugared syntax tree of the complete file.
   */
  private IStrategoTerm makeSugaredSyntaxTree() {
    IStrategoTerm decls = ATermCommands.makeList("Decl*", declProvider.getStartToken(), sugaredBodyDecls);
    IStrategoTerm term = ATermCommands.makeAppl("CompilationUnit", "CompilationUnit", 1, decls);
    
    if (ImploderAttachment.getTokenizer(term) != null) {
      ImploderAttachment.getTokenizer(term).setAst(term);
      ImploderAttachment.getTokenizer(term).initAstNodeBinding();
    }
    
    analysisDataInterop.storeAnalysisData(term);
    
    return term;
  }
  
  /**
   * @return the desugared syntax tree of the complete file.
   */
  private IStrategoTerm makeDesugaredSyntaxTree() {
    IStrategoTerm decls = ATermCommands.makeList("Decl*", declProvider.getStartToken(), desugaredBodyDecls);
    IStrategoTerm term = ATermCommands.makeAppl("CompilationUnit", "CompilationUnit", 1, decls);
        
    return term;
  }

  
  public synchronized void interrupt() {
    this.interrupt = true;
  }
  
  private synchronized void stopIfInterrupted() throws InterruptedException {
    if (interrupt || monitor.isCanceled()) {
      monitor.setCanceled(true);
      log.log("interrupted " + sourceFiles, Log.CORE);
      throw new InterruptedException("Compilation interrupted");
    }
  }

  private void stepped() throws InterruptedException {
    stopIfInterrupted();
    monitor.worked(1);
  }
  
  // FIXME 
//  private void clearGeneratedStuff() throws IOException {    
//    if (driverResult.getGenerationLog() != null && FileCommands.exists(driverResult.getGenerationLog())) {
//
//      ObjectInputStream ois = null;
//      
//      try {
//        ois = new ObjectInputStream(new FileInputStream(driverResult.getGenerationLog().getFile()));
//        while (true) {
//          try {
//            Path p = (Path) ois.readObject();
//            FileCommands.delete(p);
//          } catch (ClassNotFoundException e) { 
//          }
//        }
//      } catch (EOFException e) {
//      } catch (Exception e) {
//        e.printStackTrace();
//      } finally {
//        if (ois != null)
//          ois.close();
//        FileCommands.delete(driverResult.getGenerationLog());
//      }
//    }
//  }
  
  public void setErrorMessage(IStrategoTerm toplevelDecl, String msg) {
    driverResult.logError(msg);
    log.logErr(msg, Log.CORE);
    ATermCommands.setErrorMessage(toplevelDecl, msg);
  }

  public void setErrorMessage(String msg) {
    setErrorMessage(lastSugaredToplevelDecl, msg);
  }
  
  public AbstractBaseProcessor getBaseLanguage() {
    return baseProcessor;
  }
  
  public String getModuleName() {
    return FileCommands.fileName(depOutFile);
  }
  
  public SGLR getParser() {
    return parser;
  }

  public IStrategoTerm getTreeForErrorMarking() {
    return lastSugaredToplevelDecl;
  }
  
  public Result getCurrentResult() {
    return driverResult;
  }
  
  public Environment getEnvironment() {
    return environment;
  }
}
