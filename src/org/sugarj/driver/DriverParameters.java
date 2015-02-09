package org.sugarj.driver;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.AbstractBaseLanguage;
import org.sugarj.cleardep.Synthesizer;
import org.sugarj.cleardep.stamp.Stamp;
import org.sugarj.common.FileCommands;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Renaming.FromTo;
import org.sugarj.driver.declprovider.SourceToplevelDeclarationProvider;
import org.sugarj.driver.declprovider.TermToplevelDeclarationProvider;
import org.sugarj.driver.declprovider.ToplevelDeclarationProvider;

/**
 * @author Sebastian Erdweg
 */
public class DriverParameters {
  /**
   * Processing environment.
   */
  public final Environment env;
  
  /**
   * Base language that the driver processes files for.
   */
  public final AbstractBaseLanguage baseLang;
  
  /**
   * Files to process.
   */
  public final Set<RelativePath> sourceFilePaths;
  
  /**
   * Possibly edited source files and their content.
   */
  public final Map<RelativePath, String> editedSources;
  
  /**
   * Stamps of the source files.
   * `editedSourceStamps.keySet() == editedSources.keySet()`
   */
  public final Map<RelativePath, Stamp> editedSourceStamps;
  
  /**
   * Provides toplevel declarations for all source files.
   */
  public final ToplevelDeclarationProvider declProvider;
  
  /**
   * Currently running driver chain, where each one called `subcompile` on the next one.
   */
  public final List<Driver> currentlyProcessing;
  
  /**
   * Currently active renamings;
   */
  public final List<FromTo> renamings;
  
  /**
   * Eclipse progress monitor.
   */
  public final IProgressMonitor monitor;
  
  /**
   * Synthesizer of module to process.
   */
  public final Synthesizer syn;
  
  public static DriverParameters create(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, IProgressMonitor monitor) throws IOException {
    return create(env, baseLang, sourceFile, Collections.<RelativePath, String>emptyMap(), Collections.<RelativePath, Stamp>emptyMap(), new LinkedList<Driver>(), new LinkedList<FromTo>(), monitor, null);
  }
  
  public static DriverParameters create(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, Map<RelativePath, String> editedSources, Map<RelativePath, Stamp> editedSourceStamps, IProgressMonitor monitor) throws IOException {
    return create(env, baseLang, sourceFile, editedSources, editedSourceStamps, new LinkedList<Driver>(), new LinkedList<FromTo>(), monitor, null);
  }
  
  public static DriverParameters create(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, Map<RelativePath, String> editedSources, Map<RelativePath, Stamp> editedSourceStamps, List<Driver> currentlyProcessing, List<FromTo> renamings, IProgressMonitor monitor, Synthesizer syn) throws IOException {
    String source = editedSources.get(sourceFile);
    if (source == null)
      source = FileCommands.readFileAsString(sourceFile);
    
    return new DriverParameters(
        env,
        baseLang,
        Collections.singleton(sourceFile),
        Collections.singletonMap(sourceFile, source),
        editedSourceStamps,
        new SourceToplevelDeclarationProvider(source, sourceFile),
        currentlyProcessing,
        renamings,
        monitor,
        syn);
  }
  
  public static DriverParameters create(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, IStrategoTerm termSource, Map<RelativePath, String> editedSources, Map<RelativePath, Stamp> editedSourceStamps, List<Driver> currentlyProcessing, List<FromTo> renamings, IProgressMonitor monitor, Synthesizer syn) throws IOException {
    return new DriverParameters(
        env,
        baseLang,
        Collections.singleton(sourceFile),
        editedSources,
        editedSourceStamps,
        new TermToplevelDeclarationProvider(termSource, sourceFile, env),
        currentlyProcessing,
        renamings,
        monitor,
        syn);
  }
  
  public static DriverParameters create(
      Environment env,
      AbstractBaseLanguage baseLang,
      Set<RelativePath> sourceFilePaths,
      Map<RelativePath, String> sourceFiles,
      Map<RelativePath, Stamp> sourceStamps,
      ToplevelDeclarationProvider declProvider, 
      List<Driver> currentlyProcessing,
      List<FromTo> renamings,
      IProgressMonitor monitor,
      Synthesizer syn) {
    return new DriverParameters(
        env,
        baseLang,
        sourceFilePaths,
        sourceFiles,
        sourceStamps,
        declProvider,
        currentlyProcessing,
        renamings,
        monitor,
        syn);
  }
  
  private DriverParameters(
      Environment env,
      AbstractBaseLanguage baseLang,
      Set<RelativePath> sourceFilePaths,
      Map<RelativePath, String> sourceFiles,
      Map<RelativePath, Stamp> sourceStamps,
      ToplevelDeclarationProvider declProvider, 
      List<Driver> currentlyProcessing,
      List<FromTo> renamings,
      IProgressMonitor monitor,
      Synthesizer syn) {
    this.env = env;
    this.baseLang = baseLang;
    this.sourceFilePaths = sourceFilePaths;
    this.editedSources = sourceFiles;
    this.editedSourceStamps = sourceStamps;
    this.declProvider = declProvider;
    this.currentlyProcessing = currentlyProcessing;
    this.renamings = renamings;
    this.monitor = monitor;
    this.syn = syn;
  }
  
}
