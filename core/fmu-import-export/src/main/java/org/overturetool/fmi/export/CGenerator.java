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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.overture.ast.analysis.AnalysisException;
import org.overture.ast.node.INode;
import org.overture.codegen.utils.GeneratedData;
import org.overture.codegen.vdm2c.CGen;
import org.overture.config.Settings;
import org.overturetool.fmi.IProject;

public class CGenerator
{
	final IProject project;

	public CGenerator(IProject project)
	{
		this.project = project;
	}

	public List<File> generate(File outputDir, PrintStream out, PrintStream err)
			throws AnalysisException
	{

		final CGen vdm2c = new CGen();

		List<INode> nodes = new Vector<>();
		List<File> libFiles;
		nodes.addAll(project.getClasses());

		// Generate user specified classes
		vdm2c.getCGenSettings().setUseGarbageCollection(true);
				
		try {
			GeneratedData data = vdm2c.generate(nodes);
			vdm2c.genCSourceFiles(outputDir, data.getClasses());
			vdm2c.emitFeatureFile(outputDir,  CGen.FEATURE_FILE_NAME);
			vdm2c.emitClassAssocFile(outputDir, CGen.CLASS_ASSOC_FILE_NAME);
		}  catch (Exception e) {
			err.println("FAILURE:  the code generator encountered an error.");
			e.printStackTrace(err);
		}
		out.println("Project dialect: " + Settings.dialect);

		out.println("Code generation completed successfully.");
		out.println("Copying native library files.");
		libFiles = new LinkedList<>(copyNativeLibFiles(new File(outputDir, "vdmlib")));
		libFiles.addAll((LinkedList<File>)vdm2c.getEmittedFiles());
		
		return libFiles;
	}

	private List<File> copyNativeLibFiles(File outfolder)
	{
		List<File> libFiles;
		File outputFile = null;
		InputStream jarfile = null;
		FileOutputStream fos = null;
		JarInputStream jarstream = null;
		JarEntry filejarentry = null;

		if (!outfolder.exists())
		{
			outfolder.mkdir();
		}

		libFiles = new LinkedList<>();
		
//		File tmp = new File("src/main/resources/jars/vdmclib.jar");
//		boolean isThere = tmp.exists();
		
		try
		{
			jarfile = this.getClass().getClassLoader().getResourceAsStream("jars/vdmclib.jar");
			jarstream = new JarInputStream(jarfile);
			filejarentry = jarstream.getNextJarEntry();

			// Simply step through the JAR containing the library files and extract only the code files.
			// These are copied to the source output folder.
			while (filejarentry != null)
			{
				if (!filejarentry.getName().contains("src/main")
						|| filejarentry.getName().contains("META"))
				{
					filejarentry = jarstream.getNextJarEntry();
					continue;
				}

				String tmpFileName = filejarentry.getName().replace("src/main/", "");
				
				outputFile = new File(outfolder.toString()
						+ File.separator
						+ tmpFileName);
				
				if (filejarentry.isDirectory())
				{
					filejarentry = jarstream.getNextJarEntry();
					continue;
				}
				if (filejarentry.getName().contains("SampleMakefile"))
				{
					filejarentry = jarstream.getNextJarEntry();
					continue;
				}

				libFiles.add(new File(outfolder.getName() + File.separator + tmpFileName));
				
				outputFile.getParentFile().mkdirs();
				fos = new FileOutputStream(outputFile);

				while (jarstream.available() > 0)
				{
					int b = jarstream.read();
					if (b >= 0)
					{
						fos.write(b);
					}
				}
				fos.flush();
				fos.close();
				jarstream.closeEntry();
				filejarentry = jarstream.getNextJarEntry();
			}
			jarstream.close();
			jarfile.close();
		} catch (IOException e)
		{
			e.printStackTrace();
		}
		
		return libFiles;
	}

}
