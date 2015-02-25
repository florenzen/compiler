package org.sugarj.driver;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.AbstractBaseLanguage;
import org.sugarj.cleardep.build.BuildRequirement;
import org.sugarj.cleardep.stamp.Stamp;
import org.sugarj.common.FileCommands;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Renaming.FromTo;

/**
 * @author Sebastian Erdweg
 */
public class DriverInput implements Serializable {
  private static final long serialVersionUID = -8640182333713236865L;

  /**
   * Processing environment.
   */
  private final Environment env;
  
  /**
   * Base language that the driver processes files for.
   */
  public final AbstractBaseLanguage baseLang;
  
  /**
   * Files to process.
   */
  public final RelativePath sourceFilePath;
  
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
   * Currently active renamings;
   */
  public final List<FromTo> renamings;
  
  /**
   * Eclipse progress monitor.
   */
  public final transient IProgressMonitor monitor;
  
  /**
   * Build requirements injected into this compiler call. Needed to allow compilation of generated files.
   */
  public final BuildRequirement<?, ?, ?, ?>[] injectedRequirements;
  
  public DriverInput(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, IProgressMonitor monitor, BuildRequirement<?, ?, ?, ?>... injectedRequirements) throws IOException {
    this(env, baseLang, sourceFile, Collections.<RelativePath, String>emptyMap(), Collections.<RelativePath, Stamp>emptyMap(), new LinkedList<FromTo>(), monitor, injectedRequirements);
  }
  
  public DriverInput(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, Map<RelativePath, String> editedSources, Map<RelativePath, Stamp> editedSourceStamps, IProgressMonitor monitor, BuildRequirement<?, ?, ?, ?>... injectedRequirements) throws IOException {
    this(env, baseLang, sourceFile, editedSources, editedSourceStamps, new LinkedList<FromTo>(), monitor, injectedRequirements);
  }
  
  public DriverInput(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, Map<RelativePath, String> editedSources, Map<RelativePath, Stamp> editedSourceStamps, List<FromTo> renamings, IProgressMonitor monitor, BuildRequirement<?, ?, ?, ?>... injectedRequirements) throws IOException {
    String source = editedSources.get(sourceFile);
    if (source == null)
      source = FileCommands.readFileAsString(sourceFile);
    this.env = env;
    this.baseLang = baseLang;
    this.sourceFilePath = sourceFile;
    this.editedSources = Collections.singletonMap(sourceFile, source);
    this.editedSourceStamps = editedSourceStamps;
    this.renamings = renamings;
    this.monitor = monitor;
    this.injectedRequirements = injectedRequirements;
  }
  
  public DriverInput(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, IStrategoTerm termSource, Map<RelativePath, String> editedSources, Map<RelativePath, Stamp> editedSourceStamps, List<FromTo> renamings, IProgressMonitor monitor, BuildRequirement<?, ?, ?, ?>... injectedRequirements) throws IOException {
    this(
        env,
        baseLang,
        sourceFile,
        editedSources,
        editedSourceStamps,
        renamings,
        monitor,
        injectedRequirements);
  }
  
  public Environment getEnvironment() {
      return new Environment(env);
  }
}
