/*******************************************************************************
 * Copyright (c) 2014 Michael Vorburger.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Michael Vorburger - initial API and implementation
 ******************************************************************************/
package com.temenos.ds.op.xtext.generator.ui;

import static com.google.common.collect.Maps.newHashMap;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.ecore.plugin.RegistryReader;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.builder.BuilderParticipant;
import org.eclipse.xtext.builder.EclipseOutputConfigurationProvider;
import org.eclipse.xtext.builder.EclipseResourceFileSystemAccess2;
import org.eclipse.xtext.generator.IGenerator;
import org.eclipse.xtext.generator.OutputConfiguration;
import org.eclipse.xtext.resource.IResourceDescription.Delta;
import org.eclipse.xtext.ui.editor.preferences.IPreferenceStoreAccess;
import org.eclipse.xtext.ui.editor.preferences.PreferenceStoreAccessImpl;
import org.eclipse.xtext.util.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import com.temenos.ds.op.xtext.ui.internal.NODslActivator;
import com.temenos.ds.op.xtext.ui.internal.se.JdtBasedClassLoaderProvider;


/**
 * An IXtextBuilderParticipant which... TODO Doc! ;-)
 * 
 * @author Michael Vorburger
 */
public class MultiGeneratorsXtextBuilderParticipant extends BuilderParticipant /* implements IXtextBuilderParticipant */ {
	// TODO Xtext MAYBE later send a proposal to Xtext core to split up BuilderParticipant so it's more suitable to be extended here
	
	private final static Logger logger = LoggerFactory.getLogger(MultiGeneratorsXtextBuilderParticipant.class);
	
	// These three, incl. volatile, inspired by (copy/pasted from) org.eclipse.xtext.builder.impl.RegistryBuilderParticipant
	private @Inject	IExtensionRegistry extensionRegistry;
	private volatile Iterable<GeneratorIdPair> generatorToId;
	private @Inject	JdtBasedClassLoaderProvider classloaderProvider;
	private SingleThreadLocal<IBuildContext> buildContextLocal = new SingleThreadLocal<IBuildContext>();
	private @Inject PreferenceStoreAccessImpl preferenceStoreAccess;
	private SubMonitor subMonitor;
	
	public PreferenceStoreAccessImpl getPreferenceStoreAccess() {
		return preferenceStoreAccess;
	}

	@Override
	public void build(IBuildContext context, IProgressMonitor monitor) throws CoreException {
		try {
			buildContextLocal.set(context);
			subMonitor = SubMonitor.convert(monitor, context.getBuildType() == BuildType.RECOVERY ? 5 : 3);
			super.build(context, monitor);
		} finally {
			buildContextLocal.remove();
			subMonitor = null;
		}
	}

	@Override
	protected void handleChangedContents(Delta delta, IBuildContext context,
			EclipseResourceFileSystemAccess2 fileSystemAccess) throws CoreException {

		if (delta.getUri().scheme().equals("java"))
			return; // Skip any Xbase java:/Objects/test.Generator like resources
		
		// copy/paste from super() -- TODO Xtext refactor BuilderParticipant with more protected methods so that this can be done cleanly
		Resource resource = context.getResourceSet().getResource(delta.getUri(), true);
		if (shouldGenerate(resource, context)) {
			try {
				registerCurrentSourceFolder(context, delta, fileSystemAccess);
				// TODO LATER inject generator with lang specific configuration.. is to return only class, not instance, and re-lookup from lang specific injector obtained from extension going to have any perf. drawback? measure it.
				for (GeneratorIdPair entry : getGenerators()) {
					IGenerator generator = entry.getGenerator();
					String generatorId = entry.getId();
					generate(context, fileSystemAccess, resource, generator, generatorId);
				}

				// TODO HIGH look up a list, don't hard-code just one, as for first test..
				String generatorClassName = "test.Generator";
				classloaderProvider.setParentClassLoaderClass(IGenerator.class);
				Optional<IGenerator> generator = classloaderProvider.getInstance(resource, generatorClassName);
				if (!generator.isPresent()) {
					final String msg = "Generator class could not be found on this project: " + generatorClassName;
					logger.error(msg);
					// TODO HIGH Why is this not shown to the users in the UI?? Would it be, if it was a CoreException? Then they all need to be wrapped..
					throw new IllegalStateException(msg);
				}
				
				final Object generator2 = generator.get();
				IGenerator generator3 = (IGenerator) generator2;
				// TODO LATER generatorId could be read from an fixed annotation on classname (which would remain stable on refactorings)
				generate(context, fileSystemAccess, resource, generator3, generatorClassName);

			} catch (RuntimeException e) {
				if (e.getCause() instanceof CoreException) {
					throw (CoreException) e.getCause();
				}
				throw e;
			}
		}
	}

	protected void generate(IBuildContext context, EclipseResourceFileSystemAccess2 fileSystemAccess, Resource resource, IGenerator generator, String generatorId) throws CoreException {
		preferenceStoreAccess.setLanguageNameAsQualifier(generatorId);
		// This is copy/pasted from BuilderParticipant.build() - TODO Xtext refactor (PR) to be able to share code
		// TODO HIGH do we need to copy/paste more here.. what's all the Cleaning & Markers stuff??  
		final Map<String, OutputConfiguration> outputConfigurations = getOutputConfigurations(context, generatorId);
		refreshOutputFolders(context, outputConfigurations, subMonitor.newChild(1));
		fileSystemAccess.setOutputConfigurations(outputConfigurations);
		GenerationTimeLogger logger = GenerationTimeLogger.getInstance();
		StopWatch stopWatch = new StopWatch();
		generator.doGenerate(resource, fileSystemAccess);
		logger.updateTime(generatorId, (int)stopWatch.reset());
		logger.updateCount(generatorId, 1);
	}

	@Override
	protected Map<OutputConfiguration, Iterable<IMarker>> getGeneratorMarkers(
			IProject builtProject,Collection<OutputConfiguration> outputConfigurations)
			throws CoreException {

		Map<OutputConfiguration, Iterable<IMarker>> generatorMarkers = newHashMap();
		
		final Iterable<GeneratorIdPair> generators = getGenerators();
		if (generators == null)
			// TODO HIGH This shouldn't happen, but it sometimes does, seen NPE in following line, debug why, and fix root cause
			return generatorMarkers;
		
		for (GeneratorIdPair entry : generators) {
			String generatorId = entry.getId();
			final Map<String, OutputConfiguration> modifiedConfigs = getOutputConfigurations(buildContextLocal.get(), generatorId);
			if (generatorMarkers.isEmpty()) {
				generatorMarkers = super.getGeneratorMarkers(builtProject, modifiedConfigs.values());
			} else {
				Map<OutputConfiguration, Iterable<IMarker>> markers = super.getGeneratorMarkers(builtProject, modifiedConfigs.values());
				for (Object key : markers.keySet()) {
					Iterable<IMarker> mainMarkers = generatorMarkers.get(key);
					Iterable<IMarker> newMarkers = markers.get(key);
					generatorMarkers.put((OutputConfiguration) key,	Iterables.concat(mainMarkers, newMarkers));
				}
			}
		}
		return generatorMarkers;
	}
	
	protected Map<String, OutputConfiguration> getOutputConfigurations(IBuildContext context, String generatorId) {
		IPreferenceStoreAccess preferenceStoreAccess = getOutputConfigurationProvider().getPreferenceStoreAccess();
		PreferenceStoreAccessImpl preferenceStoreAccessImpl = (PreferenceStoreAccessImpl) preferenceStoreAccess;
		preferenceStoreAccessImpl.setLanguageNameAsQualifier(generatorId);
		return super.getOutputConfigurations(context);
	}
	
	// inspired by (copy/pasted from) org.eclipse.xtext.builder.impl.RegistryBuilderParticipant.getParticipants()
	protected Iterable<GeneratorIdPair> getGenerators() {
		Iterable<GeneratorIdPair> result = generatorToId;
		if (generatorToId == null) {
			result = initGenerators();
		}
		return result;
	}
	
	// inspired by (copy/pasted from) org.eclipse.xtext.builder.impl.RegistryBuilderParticipant.initParticipants()
	// If later sending a proposal to Xtext core to split up BuilderParticipant so it's more suitable to be extended here, refactor to make this re-usable across instead copy/paste 
	protected synchronized Iterable<GeneratorIdPair> initGenerators() {
		Iterable<GeneratorIdPair> result = generatorToId;
		if (result == null) {
			if (generatorToId == null) {
				HashBiMap<String, IGenerator> idToGenerator = HashBiMap.create();
				NODslActivator activator = NODslActivator.getInstance();
				if (activator != null) {
					String pluginID = activator.getBundle().getSymbolicName();
					GeneratorReader<IGenerator> reader = new GeneratorReader<IGenerator>(extensionRegistry, pluginID, "multigenerator", idToGenerator);
					reader.readRegistry();
				}
				Builder<GeneratorIdPair> resultBuilder = ImmutableList.builder();
				for (Entry<String, IGenerator> entry : idToGenerator.entrySet()) {
					resultBuilder.add(GeneratorIdPair.of(entry.getValue(), entry.getKey()));
				}
				generatorToId = resultBuilder.build();
			}
		}
		return result;
	}

	// This inner class is inspired by org.eclipse.xtext.builder.impl.RegistryBuilderParticipant.BuilderParticipantReader - 
	// TODO Xtext If later sending a proposal to Xtext core to split up BuilderParticipant so it's more suitable to be extended here, refactor to make this re-usable across instead copy/paste 
	// It's intentionally static to make sure it doesn't access members of the outer class, and will thus be easier to factor out later 
	public static class GeneratorReader<T> extends RegistryReader {
		private final static Logger logger = LoggerFactory.getLogger(MultiGeneratorsXtextBuilderParticipant.GeneratorReader.class);

		private static final String ATT_CLASS = "class";
		private static final String ATT_ID = "id";

		protected final String extensionPointID;
		protected final Map<String, T> generatorIdToInstance;

		public GeneratorReader(IExtensionRegistry pluginRegistry, String pluginID, String extensionPointID, Map<String, T> generatorIdToInstance) {
			super(pluginRegistry, pluginID, extensionPointID);
			this.extensionPointID = extensionPointID;
			this.generatorIdToInstance = generatorIdToInstance;
		}
		
		@Override
		protected boolean readElement(IConfigurationElement element, boolean add) {
			boolean result = false;
			if (element.getName().equals(extensionPointID)) {
				final String id = element.getAttribute(ATT_ID);
				final String className = element.getAttribute(ATT_CLASS);
				if (className == null) {
					logMissingAttribute(element, ATT_CLASS);
				} else if (id == null) {
					logMissingAttribute(element, ATT_ID);
				} else if (add) {
					if (generatorIdToInstance.containsKey(id)) {
						logger.warn("The builder participant '" + id + "' was registered twice."); //$NON-NLS-1$ //$NON-NLS-2$
					}
					Optional<T> instance = get(element);
					if (!instance.isPresent()) {
						result = false;
					}
					generatorIdToInstance.put(id, instance.get());
					// ? participants = null;
					result = true;
				} else {
					generatorIdToInstance.remove(id);
					// ? participants = null;
					result = true;
				}
			}
			return result;
		}


		@SuppressWarnings("unchecked")
		protected Optional<T> get(IConfigurationElement element) {
			try {
				return Optional.of((T) element.createExecutableExtension(ATT_CLASS));
			} catch (CoreException e) {
				logError(element, e);
			} catch (NoClassDefFoundError e) {
				logError(element, e);
			}
			return Optional.absent();
		}

		protected void logError(IConfigurationElement element, Throwable e) {
			logger.error(getErrorTag(element), e);
		}

		@Override
		protected void logError(IConfigurationElement element, String text) {
			logger.error(getErrorTag(element));
			logger.error(text);
		}
		
		protected String getErrorTag(IConfigurationElement element) {
			IExtension extension = element.getDeclaringExtension();
			return "Plugin " + extension.getContributor().getName() + ", extension " + extension.getExtensionPointUniqueIdentifier(); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
	
	@Override
	protected boolean isEnabled(IBuildContext context) {
		// TODO HIGH TDD better later read this from.. an IGenerator specific Preference
		return true;
	}
	
	@Override
	protected List<Delta> getRelevantDeltas(IBuildContext context) {
		// TODO better for future compat. to just make sure we @Inject an resourceServiceProvider where canHandle => true always instead of this short term solution:
		return context.getDeltas();
	}
	
	@Override
	public void setOutputConfigurationProvider(
			EclipseOutputConfigurationProvider outputConfigurationProvider) {
		super.setOutputConfigurationProvider(outputConfigurationProvider);
	}
}
