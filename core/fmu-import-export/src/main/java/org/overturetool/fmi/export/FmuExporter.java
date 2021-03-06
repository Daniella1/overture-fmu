/*
 * #%~
 * Fmu import exporter
 * %%
 * Copyright (C) 2015 - 2017 Overture
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #~%
 */
package org.overturetool.fmi.export;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.io.FileUtils;
import org.intocps.java.fmi.shm.SharedMemory;
import org.overture.ast.analysis.AnalysisException;
import org.overture.ast.definitions.AInstanceVariableDefinition;
import org.overture.ast.definitions.ASystemClassDefinition;
import org.overture.ast.definitions.PDefinition;
import org.overture.ast.definitions.SClassDefinition;
import org.overture.ast.definitions.SOperationDefinitionBase;
import org.overture.ast.util.definitions.ClassList;
import org.overture.fmi.annotation.FmuAnnotation;
import org.overturetool.fmi.AbortException;
import org.overturetool.fmi.IProject;
import org.overturetool.fmi.IProject.IJob;
import org.overturetool.fmi.export.ModelDescriptionGenerator.GeneratorInfo;
import org.overturetool.fmi.util.FolderCompressor;
import org.overturetool.fmi.util.Tracability;
import org.overturetool.fmi.util.VdmAnnotationProcesser;

public class FmuExporter
{
	public static final String[] RESOURCE_EXTENSIONS = new String[] { "csv" ,"jar","class","properties"};

	private boolean HWInterfaceHasStatics(IProject project)
	{
		for (SClassDefinition c : project.getClasses())
		{
			if (c.getName().getName().equals("HardwareInterface"))
			{

				for (PDefinition i : c.getDefinitions())
				{
					if (i instanceof AInstanceVariableDefinition)
					{
						if (((AInstanceVariableDefinition) i).getAccess().getStatic() != null)
						{
							return true;
						}
					}
				}
			}
		}

		return false;
	}

	public File exportFmu(IProject project, String title, PrintStream out,
			PrintStream err, boolean force) throws AbortException
	{
		out.println("\n---------------------------------------");
		out.println("|             " + title + "             |");
		out.println("---------------------------------------");
		out.println("Starting FMU export for project: '" + project.getName()
				+ "' ...");

		if (project.typeCheck())
		{
			try
			{
				Map<PDefinition, FmuAnnotation> definitionAnnotation = new VdmAnnotationProcesser().collectAnnotatedDefinitions(project, out, err);

				ASystemClassDefinition system = null;
				boolean hasWorldRun= false;
				for (SClassDefinition cDef : project.getClasses())
				{
					if (cDef instanceof ASystemClassDefinition)
					{
						system = (ASystemClassDefinition) cDef;
						out.println("Found system class: '"
								+ cDef.getName().getName() + "'");
					}else if(cDef.getName().getName().equals("World"))
					{
						for (PDefinition def : cDef.getDefinitions())
						{
							if(def instanceof SOperationDefinitionBase && def.getName().getName().equals("run"))
							{
								hasWorldRun = true;
							}
						}
					}
				}
				
				if(!hasWorldRun)
				{
					err.println("Missing entry point: 'new World.run()'");
					return null;
				}

				Map<String, FmuAnnotation> exportNames = new HashMap<>();
				boolean hasDuplicates = false;
				for (Entry<PDefinition, FmuAnnotation> entry : definitionAnnotation.entrySet())
				{
					FmuAnnotation value = entry.getValue();
					if (exportNames.containsKey(value.name))
					{
						final String export = "'%s' at line %s with type '%s'";
						FmuAnnotation original = exportNames.get(value.name);
						err.print("Duplicate export name: "
								+ String.format(export, value.name, value.tree.getLine(), value.type)
								+ " duplicates: "
								+ String.format(export, original.name, original.tree.getLine(), original.type));
						hasDuplicates = true;
					}
					exportNames.put(value.name, value);
				}

				if (hasDuplicates)
				{
					return null;
				}

				if (HWInterfaceHasStatics(project))
				{
					err.println("The HardwareInterface class must not contain static definitions.");
					return null;
				}

				ClassList classList = new ClassList();
				classList.addAll(project.getClasses());
				ModelDescriptionGenerator generator = new ModelDescriptionGenerator(classList, system);

				ModelDescriptionConfig modelDescriptionConfig = getModelDescriptionConfig(project);
				GeneratorInfo info = generator.generate(definitionAnnotation, project, modelDescriptionConfig, out, err);
				copyFmuResources(info, project.getName(), project, modelDescriptionConfig, system, out, err);

				final String modelDescription = info.modelDescriptionStringGenerator.getModelDescription();

				if (project.isOutputDebugEnabled())
				{
					out.println("\n########################\n Model Description: \n");
					out.println(modelDescription);
				}

				project.createProjectTempRelativeFile("modelDescription.xml", new ByteArrayInputStream(modelDescription.getBytes("UTF-8")));

				final File fmuArchieveName = new File(project.getOutputFolder(), project.getName()
						+ ".fmu");

				if (fmuArchieveName.exists())
				{
					if (force)
						fmuArchieveName.delete();
				}

				project.scheduleJob(new IJob()
				{

					@Override
					public void run()
					{
						File fmuFolderPath;
						try
						{
							fmuFolderPath = project.getTempFolder();
							try
							{
								out.println("Compressing FMU archive ...");
								FolderCompressor.compress(fmuFolderPath, fmuArchieveName);

								String hash = Tracability.calculateGitHash(fmuArchieveName);

								for (SClassDefinition cDef : classList)
								{
									if (ModelDescriptionGenerator.INTERFACE_CLASSNAME.equals(cDef.getName().getName()))
									{
										File hwiFile = cDef.getLocation().getFile();
										String data = FileUtils.readFileToString(hwiFile, Charset.forName("UTF-8"));
										StringBuilder sb = new StringBuilder(data);
										if (project.isTracabilityEnabled())
										{
											sb.insert(0, String.format("--##\tEXPORT\t%s\t%s\t%s\t%s\t%s\n", hash, fmuArchieveName.getName(), Tracability.getCurrentTimeStamp(), getExportType(), Tracability.getToolId()));
										}
										FileUtils.write(hwiFile, sb, Charset.forName("UTF-8"));
									}
								}
								project.cleanUp();
								out.println("FMU export complete for project '" + project.getName() + "'.\n");
							} catch (IOException e)
							{
								project.log(e);
							}
						} catch (IOException e1)
						{
							project.log(e1);
						}

					}
				});

				return fmuArchieveName;

			} catch (IOException e)
			{
				project.log(e);
			} catch (AnalysisException e)
			{
				project.log(e);
			}
		}
		else
		{
			err.println("FAILURE:  Model contains type errors.");
		}		
		
		return null;
	}

	protected String getExportType()
	{
		return "tool-wrapper";
	}

	protected ModelDescriptionConfig getModelDescriptionConfig(IProject project)
	{
		ModelDescriptionConfig config = new ModelDescriptionConfig();
		config.canBeInstantiatedOnlyOncePerProcess = false;
		config.needsExecutionTool = true;
		config.fmuGUID = java.util.UUID.randomUUID().toString();

		for (File source : project.getSpecFiles())
		{
			String path = source.getAbsolutePath().substring(project.getSourceRootPath().getAbsolutePath().length() + 1);
			config.sourceFiles.add(path);
		}

		return config;
	}


	protected void copyResourceFiles(IProject project, String resourcesFolder,
			String exts[]) throws IOException
	{
		InputStream is = null;
		LinkedList<File> resourceFiles;
		String resourceFileExtensions[] = exts;

		// Copy other resource files included with the model as resources.
		resourceFiles = (LinkedList<File>) FileUtils.listFiles(project.getSourceRootPath(), resourceFileExtensions, true);

		for (File resFile : resourceFiles)
		{
			is = new FileInputStream(resFile);
			project.createProjectTempRelativeFile(resourcesFolder + "/"
					+ resFile.getName(), is);
		}
	}

	protected void copyFmuResources(GeneratorInfo info, String name,
			IProject project, ModelDescriptionConfig modelDescriptionConfig,
			ASystemClassDefinition system, PrintStream out, PrintStream err)
			throws IOException, AnalysisException
	{
		final String resourcesFolder = "resources";

		InputStream is = null;
		final String interpreterJarName = "fmi-interpreter-jar-with-dependencies.jar";

		is = FmuExporter.class.getClassLoader().getResourceAsStream("jars/"
				+ interpreterJarName);

		project.createProjectTempRelativeFile(resourcesFolder + "/"
				+ interpreterJarName, is);

		StringBuffer sb = new StringBuffer();
		sb.append("false\n");
		sb.append("java\n");
		if (project.isOutputDebugEnabled())
		{
			// remote debug
			sb.append("-Xdebug\n");
			int port = 4000;
			String austouspend = "n";
			String[] configs = project.getToolDebugConfig().split("=");
			if (configs.length > 1)
			{
				port = Integer.parseInt(configs[0]);
				austouspend = "y".equals(("" + configs[1]).toLowerCase()) ? "y"
						: "n";
			}
			sb.append(String.format("-Xrunjdwp:server=y,transport=dt_socket,address=%d,suspend=%s\n", port, austouspend));
		}

		sb.append("-cp\n");
		sb.append("*\n");
		sb.append("org.crescendo.fmi.ShmServer\n");
		sb.append("-p");
		byte[] bytes = sb.toString().getBytes("UTF-8");
		InputStream source = new ByteArrayInputStream(bytes);
		project.createProjectTempRelativeFile(resourcesFolder + "/config.txt", source);
		bytes = info.modelDescriptionStringGenerator.getModelDescription().getBytes("UTF-8");
		source = new ByteArrayInputStream(bytes);
		project.createProjectTempRelativeFile(resourcesFolder
				+ "/modelDescription.xml", source);

		project.copyResourcesToTempFolder(resourcesFolder);

		for (File unit : project.getSpecFiles())
		{
			String path =null;
			if(unit.getAbsolutePath().startsWith(project.getSourceRootPath().getAbsolutePath())){
				path=	unit.getAbsolutePath().substring(project.getSourceRootPath().getAbsolutePath().length() + 1);
			}else
			{
				int index = unit.getAbsolutePath().indexOf(File.separatorChar);
				path = unit.getAbsolutePath().substring(index+1);
			}
			
			
			project.createProjectTempRelativeFile(resourcesFolder + "/model/"
					+ path, FileUtils.openInputStream(unit));
		}

		// native

		String binaries = "binaries";

		for (String folderName : new String[] { "darwin64", "linux64",
				"linux32", "win32", "win64" })
		{
			String extension = ".so";
			if (folderName.startsWith("darwin"))
			{
				extension = ".dylib";
			} else if (folderName.startsWith("linux"))
			{
				extension = ".so";
			}
			if (folderName.startsWith("win"))
			{
				extension = ".dll";
			}

			is = SharedMemory.class.getClassLoader().getResourceAsStream("lib/vdm-tool-wrapper/binaries/"
					+ folderName + "/vdm-tool-wrapper" + extension);

			if (is != null)
			{
				project.createProjectTempRelativeFile(binaries + "/"
						+ folderName + "/" + name + extension, is);
			}

		}

		is = this.getClass().getResourceAsStream("/lib/vdm-tool-wrapper/binaries/git-info.txt");

		if (is != null)
		{
			project.createProjectTempRelativeFile(binaries + "/git-info-txt", is);
		}

		copyResourceFiles(project,resourcesFolder,RESOURCE_EXTENSIONS);
	}
}
